package com.hiusers.rafter.runtime

import com.hiusers.rafter.api.ActionProvider
import com.hiusers.rafter.api.ConditionProvider
import com.hiusers.rafter.api.DataProvider
import com.hiusers.rafter.api.ItemProvider
import com.hiusers.rafter.api.UiActionResult
import com.hiusers.rafter.api.UiContext
import com.hiusers.rafter.api.UiFailureCode
import com.hiusers.rafter.api.UiOperationException
import com.hiusers.rafter.model.ActionSpec
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.ConditionSpec
import com.hiusers.rafter.model.Values
import java.util.Locale
import java.util.concurrent.CompletableFuture
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import taboolib.library.xseries.XMaterial
import taboolib.module.kether.KetherShell
import taboolib.module.kether.ScriptOptions
import taboolib.platform.compat.replacePlaceholder
import taboolib.platform.util.buildItem

/** 主线程调用提供器，完成时复查会话与注册代数；丢弃结果不等于取消或回滚业务。 */
class Evaluation(
    val data: Registry<DataProvider>,
    val conditions: Registry<ConditionProvider>,
    val actions: Registry<ActionProvider>,
    val items: Registry<ItemProvider>,
    private val scriptEvaluator: ((String, UiContext) -> CompletableFuture<Any?>)? = null,
    private val localBinding: (BindingSpec, UiContext) -> Any? = { spec, _ ->
        error("Session data is unavailable for ${spec.type}")
    },
    private val scopeContext: (BindingSpec, UiContext) -> UiContext = { _, context -> context },
) {
    private fun <T, V> invoke(
        registry: Registry<T>,
        id: String,
        args: Map<String, Any?>,
        guard: () -> Unit,
        callback: (T, Map<String, Any?>) -> CompletableFuture<V>,
    ): CompletableFuture<V> {
        guard()
        val lease =
            try {
                registry.acquire(id, args)
            } catch (failure: NoSuchElementException) {
                throw UiOperationException(UiFailureCode.PROVIDER_UNAVAILABLE, cause = failure)
            } catch (failure: IllegalArgumentException) {
                throw UiOperationException(UiFailureCode.INVALID_ARGUMENT, cause = failure)
            }
        val isRegistered = lease.validity()
        return callback(lease.value, lease.args).thenCompose { value ->
            main<V> {
                guard()
                if (!isRegistered()) throw UiOperationException(UiFailureCode.PROVIDER_UNAVAILABLE)
                value
            }
        }
    }

    fun action(
        spec: ActionSpec,
        context: UiContext,
        guard: () -> Unit,
    ): CompletableFuture<UiActionResult> = mainFuture {
        invoke(actions, spec.key, spec.args, guard) { provider, args ->
            provider.execute(context, args).thenApply { result ->
                // 接收时复制，不将提供方的可变容器带入主线程队列。
                try {
                    UiActionResult(Values.map(requireNotNull(result).statePatch))
                } catch (failure: IllegalArgumentException) {
                    throw UiOperationException(UiFailureCode.INVALID_ARGUMENT, cause = failure)
                }
            }
        }
    }

    fun script(
        source: String,
        context: UiContext,
        guard: () -> Unit = {},
    ): CompletableFuture<Any?> = mainFuture {
        guard()
        scriptEvaluator?.let {
            return@mainFuture it(source, context)
        }
        val options =
            ScriptOptions.builder()
                .sender(context.player)
                .namespace(listOf("rafter"))
                .vars(
                    mapOf(
                        "args" to context.args,
                        "state" to context.state,
                        "form" to context.form,
                        "rafter-context" to context,
                    ) + context.item?.let { mapOf("item" to it) }.orEmpty()
                )
                .build()
        KetherShell.eval(source, options)
    }

    fun binding(
        spec: BindingSpec,
        context: UiContext,
        guard: () -> Unit = {},
    ): CompletableFuture<Any?> = mainFuture {
        guard()
        val scoped = scopeContext(spec, context)
        when (spec.type) {
            "document",
            "source",
            "record",
            "item",
            "list" -> CompletableFuture.completedFuture(localBinding(spec, scoped))
            "args" -> CompletableFuture.completedFuture(scoped.args[spec.key])
            "state" -> CompletableFuture.completedFuture(scoped.state[spec.key])
            "data" ->
                invoke(data, spec.key, spec.args, guard) { provider, args ->
                        provider.read(scoped, args)
                    }
                    .thenApply { Values.freeze(it) }
            "kether" -> script(spec.key, scoped, guard).thenApply { Values.freeze(it) }
            "papi" -> {
                require(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                    "PlaceholderAPI is not enabled"
                }
                CompletableFuture.completedFuture(spec.key.replacePlaceholder(scoped.player))
            }
            else -> error("Unknown binding ${spec.type}")
        }
    }

    fun condition(
        spec: ConditionSpec?,
        context: UiContext,
        guard: () -> Unit = {},
    ): CompletableFuture<Boolean> = mainFuture {
        guard()
        if (spec == null) return@mainFuture CompletableFuture.completedFuture(true)
        when (spec.type) {
            "equals",
            "at-least" ->
                binding(requireNotNull(spec.binding), context, guard).thenCompose { left ->
                    (spec.compare?.let { binding(it, context, guard) }
                            ?: CompletableFuture.completedFuture(spec.value))
                        .thenApply { right ->
                            if (spec.type == "at-least") Values.atLeast(left, right)
                            else Values.equal(left, right)
                        }
                }
            "constant" -> {
                require(spec.value is Boolean) { "Constant condition must be boolean" }
                CompletableFuture.completedFuture(spec.value as Boolean)
            }
            "permission" ->
                CompletableFuture.completedFuture(context.player.hasPermission(spec.key))
            "state-equals" ->
                CompletableFuture.completedFuture(Values.equal(context.state[spec.key], spec.value))
            "provider" ->
                invoke(conditions, spec.key, spec.args, guard) { provider, args ->
                    provider.test(context, args)
                }
            "kether" ->
                script(spec.key, context, guard).thenApply {
                    require(it is Boolean) { "Condition script must return boolean" }
                    it
                }
            "not" -> condition(spec.children.single(), context, guard).thenApply { !it }
            "all",
            "any" -> {
                var future = CompletableFuture.completedFuture(spec.type == "all")
                spec.children.forEach { child ->
                    future = future.thenCompose { last ->
                        if (spec.type == "all" && !last || spec.type == "any" && last) {
                            CompletableFuture.completedFuture(last)
                        } else {
                            condition(child, context, guard)
                        }
                    }
                }
                future
            }
            else -> error("Unknown condition ${spec.type}")
        }
    }

    fun item(
        reference: String,
        context: UiContext,
        guard: () -> Unit = {},
    ): CompletableFuture<ItemStack> = mainFuture {
        guard()
        if (reference.startsWith("minecraft:")) {
            val material =
                XMaterial.matchXMaterial(reference.substringAfter(':').uppercase(Locale.ROOT))
                    .orElseThrow { IllegalArgumentException("Unknown material $reference") }
            CompletableFuture.completedFuture(buildItem(material))
        } else {
            val provider = reference.substringBefore('#')
            val id = reference.substringAfter('#', "")
            require(id.isNotEmpty()) { "Use provider:namespace#item-id" }
            invoke(items, provider, emptyMap(), guard) { value, _ ->
                    value.read(context, id)
                }
                .thenCompose { item ->
                    main {
                        guard()
                        item.clone()
                    }
                }
        }
    }

    fun check(kind: String, key: String, args: Map<String, Any?>) {
        when (kind) {
            "papi" ->
                require(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                    "PlaceholderAPI is not enabled"
                }
            "data" -> data.check(key, args)
            "condition" -> conditions.check(key, args)
            "action" -> actions.check(key, args)
            "item" ->
                if (key.startsWith("minecraft:")) {
                    require(
                        XMaterial.matchXMaterial(key.substringAfter(':').uppercase(Locale.ROOT))
                            .isPresent
                    ) {
                        "Unknown item $key"
                    }
                } else {
                    require('#' in key && key.substringAfter('#').isNotEmpty()) {
                        "Invalid item reference"
                    }
                    require(items.contains(key.substringBefore('#'))) { "Unknown item provider" }
                }
        }
    }
}
