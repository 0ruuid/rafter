package com.hiusers.rafter.example

import com.hiusers.rafter.api.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** 仅编译消费方，不执行 Bukkit 或业务回调。 */
object KotlinConsumer {
    fun service() = RafterService.get()

    fun defaults() = UiOpenOptions()

    fun stateOnly(state: Map<String, Any?>) = UiOpenOptions(initialState = state)

    fun pageOnly(page: String?) = UiOpenOptions(initialPage = page)

    fun full(state: Map<String, Any?>, page: String?) =
        UiOpenOptions(initialState = state, initialPage = page)

    fun emptyResult() = UiActionResult()

    fun result() = UiActionResult(statePatch = mapOf("status" to "ok"))

    fun copyResult() = result().copy()

    fun descriptor() = ProviderDescriptor("示例")

    fun parameter() = ParameterSpec(ParameterType.STRING)

    fun parameterFactory() = ParameterSpec.of(ParameterType.INTEGER, maximum = 10)

    fun namedParameter() =
        ParameterSpec(
            type = ParameterType.STRING,
            required = false,
            nullable = true,
            hasDefault = true,
            defaultValue = null,
            choices = listOf(null, "example"),
            description = "示例参数",
        )

    fun failure(cause: Throwable) =
        UiOperationException(
            UiFailureCode.INVALID_ARGUMENT,
            cause = cause,
        )

    fun success(session: UUID) = OpenResult(opened = true, sessionId = session)

    fun successFactory(session: UUID) = OpenResult.of(opened = true, sessionId = session)

    fun failureFactory(cause: Throwable) =
        UiOperationException.of(UiFailureCode.INVALID_ARGUMENT, cause = cause)

    fun context(player: Player, session: UUID) =
        UiContext(
            player,
            session,
            "example:menu",
            "home",
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )

    fun failureCodeName() = UiFailureCode.INVALID_ARGUMENT.name

    fun failureMessage() = UiOperationException(UiFailureCode.INVALID_ARGUMENT).message

    fun failureCause(cause: Throwable) =
        UiOperationException(
                UiFailureCode.INVALID_ARGUMENT,
                cause = cause,
            )
            .cause

    fun failureCodes() = UiFailureCode.entries

    fun parameterTypes() = ParameterType.entries

    fun open(service: RafterService, player: Player, menu: String) = service.open(player, menu)

    fun open(service: RafterService, player: Player, menu: String, options: UiOpenOptions) =
        service.open(player, menu, options)

    fun register(service: RafterService, owner: Plugin): List<AutoCloseable> =
        listOf(
            service.registerData(
                owner,
                "example:value",
                DataProvider { _, _ ->
                    CompletableFuture.completedFuture("example")
                },
            ),
            service.registerCondition(
                owner,
                "example:allowed",
                ConditionProvider { _, _ ->
                    CompletableFuture.completedFuture(true)
                },
            ),
            service.registerAction(
                owner,
                "example:action",
                ActionProvider { _, _ ->
                    CompletableFuture.completedFuture(UiActionResult())
                },
            ),
            service.registerItems(
                owner,
                "example:item",
                ItemProvider { _, _ ->
                    CompletableFuture.completedFuture(ItemStack(Material.PAPER))
                },
            ),
        )
}
