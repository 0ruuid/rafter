package com.hiusers.rafter.runtime

import com.hiusers.rafter.api.ActionProvider
import com.hiusers.rafter.api.ConditionProvider
import com.hiusers.rafter.api.DataProvider
import com.hiusers.rafter.api.ItemProvider
import com.hiusers.rafter.api.OpenResult
import com.hiusers.rafter.api.ProviderDescriptor
import com.hiusers.rafter.api.RafterService
import com.hiusers.rafter.api.UiContext
import com.hiusers.rafter.api.UiFailureCode
import com.hiusers.rafter.api.UiOperationException
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.compile.LoadResult
import com.hiusers.rafter.model.ActionSpec
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.PageMode
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.Values
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.render.NativeRenderer
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogInputNames
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import taboolib.common.platform.function.submit

/** 主线程持有会话、动作和注册。资源由 ResourceCoordinator 发布；完成只表示发送阶段，不确认客户端或回滚业务。 */
internal class RafterEngine(
    private val plugin: Plugin,
    private val root: Path,
    private val transport: DialogTransport = DialogTransport(),
    private val post: (() -> Unit) -> Unit = { block ->
        submit(delay = 1) { block() }
        Unit
    },
    // 测试可替换脚本边界；生产使用 KetherShell。
    scriptEvaluator: ((String, UiContext) -> CompletableFuture<Any?>)? = null,
    private val clock: () -> Long = System::nanoTime,
) : RafterService, AutoCloseable {
    private val data = Registry<DataProvider>()
    private val conditions = Registry<ConditionProvider>()
    private val actions = Registry<ActionProvider>()
    private val items = Registry<ItemProvider>()
    private val sessions = mutableMapOf<UUID, UiSession>()
    private val evaluate =
        Evaluation(
            data,
            conditions,
            actions,
            items,
            scriptEvaluator,
            localBinding = { binding, context ->
                val session =
                    sessions[context.player.uniqueId]?.takeIf { it.id == context.sessionId }
                        ?: throw UiOperationException(UiFailureCode.SUPERSEDED)
                session.data.resolve(binding)
            },
            scopeContext = { binding, context ->
                val session =
                    sessions[context.player.uniqueId]?.takeIf { it.id == context.sessionId }
                        ?: throw UiOperationException(UiFailureCode.SUPERSEDED)
                binding.scope?.let { context.copy(item = session.data.item(it)) } ?: context
            },
        )
    private val frames = mutableMapOf<UUID, Work>()
    private val executions = mutableMapOf<UUID, Work>()
    private val refreshes = mutableMapOf<UUID, CompletableFuture<Void>>()
    private val fields = mutableMapOf<UUID, List<String>>()
    // 选择结果属于上次成功发送的视图，不写入共享菜单。
    private val shownVariants = mutableMapOf<UUID, Map<String, Int>>()
    private var snapshotRevision = 0L

    @Volatile private var stopped = false

    private val resources =
        ResourceCoordinator(root, plugin.logger, evaluate::check) {
            snapshotRevision++
            closeAll()
        }

    private class Work(
        val session: UiSession,
        val generation: Long,
        val revision: Long,
        val started: Long,
        val timeoutMillis: Long,
        val page: String = session.page,
        val result: CompletableFuture<Void> = CompletableFuture(),
    ) {
        fun expired(now: Long): Boolean = now - started >= timeoutMillis * 1_000_000
    }

    fun defaultMenu(): String =
        resources.candidate?.defaultMenu
            ?: throw UiOperationException(UiFailureCode.NO_VALID_SNAPSHOT)

    /** 补全只导出当前 ID，不暴露可变定义。 */
    internal fun menuIds(player: Player?): List<String> {
        check(Bukkit.isPrimaryThread())
        return resources.candidate
            ?.snapshot
            ?.menus
            ?.values
            .orEmpty()
            .filter { player == null || it.spec.permission?.let(player::hasPermission) != false }
            .map { it.spec.id }
            .sorted()
    }

    fun reload(): CompletableFuture<LoadResult> = resources.reload()

    internal fun install(next: Candidate) {
        resources.install(next)
    }

    fun pack(startup: Boolean = false): CompletableFuture<Path> = resources.pack(startup)

    fun startHostedPack() {
        resources.startHostedPack()
    }

    fun sendPack(player: Player): Boolean = resources.sendPack(player)

    fun packJoin(player: Player) {
        resources.packJoin(player)
    }

    fun packStatus(event: PlayerResourcePackStatusEvent) {
        resources.packStatus(event)
    }

    /** 捕获候选和已注册扩展后整组导出。 */
    fun exportContract(): Path {
        check(Bukkit.isPrimaryThread())
        return com.hiusers.rafter.authoring.AuthoringExport.write(
            root,
            contract(),
            resources.candidate,
        )
    }

    private fun context(
        player: Player,
        session: UiSession,
        scope: com.hiusers.rafter.model.RowScope? = null,
    ) =
        UiContext(
            player,
            session.id,
            session.menu.spec.id,
            session.page,
            session.args,
            Values.map(session.state),
            Values.map(session.form),
            scope?.let(session.data::item),
        )

    private fun current(session: UiSession, generation: Long): Boolean =
        !stopped && sessions[session.playerId] === session && session.isCurrent(generation)

    private fun live(session: UiSession, generation: Long) {
        if (!current(session, generation)) throw UiOperationException(UiFailureCode.SUPERSEDED)
        val work =
            frames[session.playerId]?.takeIf { it.generation == generation }
                ?: executions[session.playerId]?.takeIf { it.generation == generation }
        if (work != null) {
            if (work.revision != snapshotRevision || work.page != session.page) {
                throw UiOperationException(UiFailureCode.SUPERSEDED)
            }
            if (work.expired(clock())) throw UiOperationException(UiFailureCode.TIMED_OUT)
        }
        val player =
            Bukkit.getPlayer(session.playerId)
                ?: throw UiOperationException(UiFailureCode.PLAYER_UNAVAILABLE)
        if (!player.isOnline) throw UiOperationException(UiFailureCode.PLAYER_UNAVAILABLE)
        if (session.menu.spec.permission?.let { !player.hasPermission(it) } == true) {
            throw UiOperationException(UiFailureCode.NO_PERMISSION)
        }
    }

    override fun open(
        player: Player,
        menu: String,
        args: Map<String, Any?>,
    ): CompletableFuture<OpenResult> =
        mainFuture {
                fun rejected(code: UiFailureCode) =
                    CompletableFuture.completedFuture(OpenResult(false, code))
                if (stopped) return@mainFuture rejected(UiFailureCode.ENGINE_STOPPED)
                if (!player.isOnline) return@mainFuture rejected(UiFailureCode.PLAYER_UNAVAILABLE)
                if (!transport.supported(player))
                    return@mainFuture rejected(UiFailureCode.UNSUPPORTED_CLIENT)
                val active =
                    resources.candidate
                        ?: return@mainFuture rejected(UiFailureCode.NO_VALID_SNAPSHOT)
                val definition =
                    active.snapshot.menus[menu]
                        ?: return@mainFuture rejected(UiFailureCode.UNKNOWN_MENU)
                if (definition.spec.permission?.let { !player.hasPermission(it) } == true) {
                    return@mainFuture rejected(UiFailureCode.NO_PERMISSION)
                }
                if (!resources.ensurePackReady(player))
                    return@mainFuture rejected(UiFailureCode.RESOURCE_PACK_LOADING)
                val session =
                    try {
                        UiSession(player.uniqueId, definition, args, active.timeoutMillis)
                    } catch (failure: IllegalArgumentException) {
                        return@mainFuture rejected(UiFailureCode.INVALID_ARGUMENT)
                    }
                val previous = detach(player.uniqueId)
                sessions[player.uniqueId] = session
                completeDetached(previous, UiFailureCode.SUPERSEDED)
                // 完成 Future 可能立即触发业务延续。
                if (sessions[player.uniqueId] !== session)
                    return@mainFuture rejected(UiFailureCode.SUPERSEDED)
                render(player, session).handle { _, failure ->
                    if (failure == null) OpenResult(true, sessionId = session.id)
                    else OpenResult(false, failureCode(failure))
                }
            }
            .exceptionally { OpenResult(false, failureCode(it)) }

    override fun close(player: Player, expectedSessionId: UUID): CompletableFuture<Void> =
        main<Void> {
                val session = sessions[player.uniqueId]
                if (session != null) {
                    if (session.id != expectedSessionId)
                        throw UiOperationException(UiFailureCode.SUPERSEDED)
                    val pending = detach(player.uniqueId)
                    clear(player)
                    completeDetached(pending, UiFailureCode.SUPERSEDED)
                }
                null
            }
            .exceptionally { throw operationFailure(it) }

    /** 管理命令清理当前会话，不向业务服务暴露无身份操作。 */
    internal fun closeCurrent(player: Player): CompletableFuture<Void> = mainFuture {
        sessions[player.uniqueId]?.let { close(player, it.id) }
            ?: CompletableFuture.completedFuture<Void>(null)
    }

    override fun refresh(player: Player, expectedSessionId: UUID): CompletableFuture<Void> =
        mainFuture {
                val session =
                    sessions[player.uniqueId]?.takeIf { it.id == expectedSessionId }
                        ?: return@mainFuture CompletableFuture.failedFuture<Void>(
                            UiOperationException(UiFailureCode.SUPERSEDED)
                        )
                if (stopped) {
                    return@mainFuture CompletableFuture.failedFuture(
                        UiOperationException(UiFailureCode.ENGINE_STOPPED)
                    )
                }
                if (session.busy || frames.containsKey(player.uniqueId)) {
                    refreshes.getOrPut(player.uniqueId) { CompletableFuture() }
                } else {
                    render(player, session)
                }
            }
            .exceptionally { throw operationFailure(it) }

    /** 先分离旧状态再完成 Future，延续可能同步打开新界面。 */
    private fun detach(id: UUID): List<CompletableFuture<Void>> {
        sessions.remove(id)?.close()
        fields.remove(id)
        shownVariants.remove(id)
        return listOfNotNull(
            frames.remove(id)?.result,
            executions.remove(id)?.result,
            refreshes.remove(id),
        )
    }

    private fun completeDetached(
        pending: List<CompletableFuture<Void>>,
        code: UiFailureCode,
        cause: Throwable? = null,
    ) {
        pending.forEach { it.completeExceptionally(UiOperationException(code, cause = cause)) }
    }

    fun forgetIfOffline(id: UUID) {
        check(Bukkit.isPrimaryThread())
        // 旧连接的退出回调不能清理重新登录后的会话。
        if (Bukkit.getPlayer(id)?.isOnline != true) forget(id)
    }

    fun forget(id: UUID) {
        check(Bukkit.isPrimaryThread())
        resources.forget(id)
        completeDetached(detach(id), UiFailureCode.SUPERSEDED)
    }

    private fun clear(player: Player) {
        if (player.isOnline) {
            runCatching { transport.clear(player) }
                .onFailure {
                    plugin.logger.warning("Unable to clear ${player.uniqueId}: ${it.message}")
                }
        }
    }

    private fun closeAll(code: UiFailureCode = UiFailureCode.SUPERSEDED) {
        val ids = sessions.keys.toList()
        val pending = ids.flatMap(::detach)
        ids.mapNotNull(Bukkit::getPlayer).forEach(::clear)
        completeDetached(pending, code)
    }

    fun tick() {
        check(Bukkit.isPrimaryThread())
        val now = clock()
        // 复用现有每秒任务检查超时与闲置，不增加高频循环。
        val expired =
            (frames.values + executions.values)
                .filter { it.expired(now) }
                .distinctBy { it.session.id }
        expired.forEach { work ->
            if (current(work.session, work.generation)) {
                val player = Bukkit.getPlayer(work.session.playerId)
                if (player == null) {
                    completeDetached(
                        detach(work.session.playerId),
                        UiFailureCode.PLAYER_UNAVAILABLE,
                    )
                } else {
                    fail(player, work.session, UiOperationException(UiFailureCode.TIMED_OUT))
                }
            }
        }
        // ESC 无可靠关闭事件；闲置清理不能误关其他插件界面。
        val wall = System.currentTimeMillis()
        sessions.values.filter { it.expired(wall) }.map { it.playerId }.forEach(::forget)
    }

    private fun nodes(session: UiSession) = session.displayedNodes()

    private data class SelectedNode(val index: Int, val node: NodeSpec)

    /** 仅选择首个匹配变体，不读取未激活分支的数据。 */
    private fun selectVariant(
        node: NodeSpec,
        context: UiContext,
        guard: () -> Unit,
    ): CompletableFuture<SelectedNode> {
        var choice = CompletableFuture.completedFuture(-1)
        node.variants.forEachIndexed { index, variant ->
            choice = choice.thenCompose { selected ->
                if (selected >= 0) CompletableFuture.completedFuture(selected)
                else
                    evaluate.condition(variant.condition, context, guard).thenApply {
                        if (it) index else -1
                    }
            }
        }
        return choice.thenCompose { index ->
            main {
                guard()
                SelectedNode(index, if (index < 0) node else node.variants[index].node)
            }
        }
    }

    private fun render(
        player: Player,
        session: UiSession,
        errors: Map<String, Any?> = emptyMap(),
    ): CompletableFuture<Void> {
        if (
            !player.isOnline ||
                session.menu.spec.permission?.let { !player.hasPermission(it) } == true
        ) {
            val code =
                if (player.isOnline) UiFailureCode.NO_PERMISSION
                else UiFailureCode.PLAYER_UNAVAILABLE
            val pending = detach(player.uniqueId)
            clear(player)
            completeDetached(pending, code)
            return CompletableFuture.failedFuture(UiOperationException(code))
        }
        val policy = resources.candidate!!.runtime
        val generation = session.beginRender()
        val work = Work(session, generation, snapshotRevision, clock(), policy.renderTimeoutMillis)
        check(player.uniqueId !in frames) { "Concurrent render for one session" }
        frames[player.uniqueId] = work
        var context = context(player, session)
        val assets = resources.candidate!!.assets
        val all = nodes(session)
        val bindings = linkedSetOf<BindingSpec>()
        fun collectText(text: TextSpec) {
            text.binding?.let(bindings::add)
            text.inlineParts.forEach { it.binding?.let(bindings::add) }
            text.parts.forEach(::collectText)
            text.hover?.let(::collectText)
        }
        val values = linkedMapOf<BindingSpec, Any?>()
        val resolved = linkedMapOf<String, NodeSpec>()
        val choices = linkedMapOf<String, Int>()
        val visible = linkedMapOf<String, Boolean>()
        val enabled = linkedMapOf<String, Boolean>()
        val itemValues = linkedMapOf<String, ItemStack>()
        val guard = { live(session, generation) }
        var future =
            prepareSessionData(
                    session,
                    context,
                    evaluate,
                    policy.readConcurrency,
                    guard,
                    click = false,
                )
                .thenCompose {
                    main<Void> {
                        guard()
                        context = context(player, session)
                        null
                    }
                }
        // 条件顺序短路，隐藏节点不发起读取。
        all.forEach { node ->
            future = future.thenCompose {
                mainFuture<Void> {
                    guard()
                    val nodeContext = context(player, session, node.rowScope)
                    val parents = all.filter { node.id.startsWith(it.id + "/") }
                    if (parents.any { visible[it.id] != true }) {
                        visible[node.id] = false
                        enabled[node.id] = false
                        CompletableFuture.completedFuture<Void>(null)
                    } else {
                        evaluate.condition(node.visible, nodeContext, guard).thenCompose { shown ->
                            mainFuture<Void> {
                                guard()
                                visible[node.id] = shown
                                if (!shown) {
                                    enabled[node.id] = false
                                    CompletableFuture.completedFuture<Void>(null)
                                } else
                                    selectVariant(node, nodeContext, guard).thenCompose { selected
                                        ->
                                        mainFuture<Void> {
                                            guard()
                                            resolved[node.id] = selected.node
                                            choices[node.id] = selected.index
                                            if (parents.any { enabled[it.id] != true }) {
                                                enabled[node.id] = false
                                                CompletableFuture.completedFuture<Void>(null)
                                            } else
                                                evaluate
                                                    .condition(
                                                        selected.node.enabled,
                                                        nodeContext,
                                                        guard,
                                                    )
                                                    .thenCompose { usable ->
                                                        main<Void> {
                                                            guard()
                                                            enabled[node.id] = usable
                                                            null
                                                        }
                                                    }
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        future = future.thenCompose {
            mainFuture<Void> {
                guard()
                collectText(session.current().spec.title)
                resolved.values
                    .filter { visible[it.id] == true }
                    .forEach { node ->
                        collectText(node.text)
                        node.tooltip?.let(::collectText)
                        (node.content as? NodeContent.Meter)?.let {
                            bindings += it.value
                            bindings += it.maximum
                        }
                        (node.content as? NodeContent.Select)
                            ?.options
                            ?.values
                            ?.forEach(::collectText)
                    }
                var reads = CompletableFuture.completedFuture<Void>(null)
                val batch = mutableListOf<BindingSpec>()
                fun flush() {
                    if (batch.isEmpty()) return
                    val keys = batch.toList()
                    batch.clear()
                    reads = reads.thenCompose {
                        mainFuture {
                            readBatch(
                                keys,
                                policy.readConcurrency,
                                guard,
                                { binding -> evaluate.binding(binding, context, guard) },
                                { binding, value -> values[binding] = Values.freeze(value) },
                            )
                        }
                    }
                }
                bindings.forEach { binding ->
                    if (binding.type == "kether") {
                        flush() // 脚本是有序屏障，不能假定为独立纯读取。
                        reads =
                            reads
                                .thenCompose { evaluate.binding(binding, context, guard) }
                                .thenCompose { value ->
                                    main<Void> {
                                        guard()
                                        values[binding] = Values.freeze(value)
                                        null
                                    }
                                }
                    } else {
                        batch += binding
                    }
                }
                flush()
                reads
            }
        }
        future = future.thenCompose {
            mainFuture {
                val itemNodes =
                    resolved.values.filter {
                        visible[it.id] == true && it.content is NodeContent.Item
                    }
                readBatch(
                    itemNodes,
                    policy.readConcurrency,
                    guard,
                    { node ->
                        evaluate.item(
                            (node.content as NodeContent.Item).reference,
                            context(player, session, node.rowScope),
                            guard,
                        )
                    },
                    { node, item -> itemValues[node.id] = item },
                )
            }
        }
        future.whenComplete { _, failure ->
            main {
                if (!current(session, generation) || frames[player.uniqueId] !== work) return@main
                if (failure != null) {
                    fail(player, session, failure)
                    return@main
                }
                try {
                    guard()
                    if (!player.isOnline)
                        throw UiOperationException(UiFailureCode.PLAYER_UNAVAILABLE)
                    if (player.uniqueId in refreshes) {
                        // 延至下一 tick，避免提供器触发刷新时递归。
                        post {
                            main followUp@{
                                if (
                                    !current(session, generation) ||
                                        frames[player.uniqueId] !== work
                                )
                                    return@followUp
                                val queued = refreshes.remove(player.uniqueId) ?: return@followUp
                                try {
                                    frames.remove(player.uniqueId)
                                    val next = render(player, session, errors)
                                    relay(next, queued)
                                    work.result.completeExceptionally(
                                        UiOperationException(UiFailureCode.SUPERSEDED)
                                    )
                                } catch (error: Throwable) {
                                    fail(player, session, error)
                                    queued.completeExceptionally(operationFailure(error))
                                    work.result.completeExceptionally(operationFailure(error))
                                }
                            }
                            Unit
                        }
                        return@main
                    }
                    val targets =
                        resolved.values
                            .filter {
                                visible[it.id] == true &&
                                    enabled[it.id] == true &&
                                    it.clickable &&
                                    session.acceptsTarget(it.id)
                            }
                            .map { it.id } +
                            listOfNotNull(
                                "__close",
                                "__popup-dismiss".takeIf { session.popup != null },
                            )
                    val handles = session.ready(generation, targets)
                    val frame = Frame(values, visible, enabled, resolved.toMap())
                    val text = CompositeText(assets)
                    val view =
                        if (session.current().spec.mode == PageMode.NATIVE) {
                            NativeRenderer.render(session, frame, text, errors, handles)
                        } else {
                            CanvasRenderer.render(session, frame, text, handles, errors)
                        }
                    shownVariants[player.uniqueId] = choices.toMap()
                    fields[player.uniqueId] = view.inputs.map { it.node.id }
                    transport.show(player, view, text, values, itemValues)
                    session.data.presented()
                    val page = session.current()
                    if (
                        page.spec.mode == PageMode.CANVAS &&
                            page.spec.components.isEmpty() &&
                            page.spec.frame == null &&
                            page.spec.modal == null
                    ) {
                        session.rememberCanvas(FrozenCanvas.capture(session, view, text))
                    }
                    frames.remove(player.uniqueId)
                    work.result.complete(null)
                } catch (error: Throwable) {
                    fail(player, session, error)
                }
            }
        }
        return work.result
    }

    private fun relay(source: CompletableFuture<Void>, target: CompletableFuture<Void>) {
        source.whenComplete { _, failure ->
            if (failure == null) target.complete(null)
            else target.completeExceptionally(operationFailure(failure))
        }
    }

    private class FormRejected(val errors: Map<String, Any?>) :
        RuntimeException("Form not committed")

    /** 仅接受当前帧的协议字段，配置 ID 不直接作为输入键。 */
    fun clickFromPacket(player: Player, handle: String, values: Map<String, Any?>) {
        check(Bukkit.isPrimaryThread())
        val decoded = DialogInputNames.decode(values, fields[player.uniqueId].orEmpty()) ?: return
        click(player, handle, decoded)
    }

    fun click(player: Player, handle: String, values: Map<String, Any?>) {
        check(Bukkit.isPrimaryThread())
        val session = sessions[player.uniqueId] ?: return
        val publishedChoices = shownVariants[player.uniqueId].orEmpty()
        val id = session.consume(handle) ?: return
        if (!session.acceptsTarget(id)) return
        val node =
            if (id == "__close" || id == "__popup-dismiss") {
                NodeSpec(
                    id,
                    NodeContent.Button(
                        actions =
                            listOf(ActionSpec(if (id == "__close") "close" else "close-popup"))
                    ),
                )
            } else {
                nodes(session).firstOrNull { it.id == id } ?: return
            }
        val generation = session.generation
        val work =
            Work(
                session,
                generation,
                snapshotRevision,
                clock(),
                resources.candidate!!.runtime.actionTimeoutMillis,
            )
        executions[player.uniqueId] = work
        val context = context(player, session)
        val guard = { live(session, generation) }
        val parents = nodes(session).filter { node.id.startsWith(it.id + "/") }
        val submitted =
            try {
                Values.map(values)
            } catch (error: IllegalArgumentException) {
                finishAction(
                    player,
                    session,
                    work,
                    UiOperationException(UiFailureCode.INVALID_FORM, cause = error),
                )
                return
            }
        var actionNode = node
        var allowed =
            if (id in setOf("__close", "__popup-dismiss")) CompletableFuture.completedFuture(true)
            else
                prepareSessionData(
                        session,
                        context,
                        evaluate,
                        resources.candidate!!.runtime.readConcurrency,
                        guard,
                        click = true,
                    )
                    .thenApply {
                        node.rowScope?.let(session.data::accepts) ?: true
                    }
        (parents + node).forEach { target ->
            allowed = allowed.thenCompose { permitted ->
                mainFuture {
                    guard()
                    val targetContext = context(player, session, target.rowScope)
                    if (!permitted) {
                        CompletableFuture.completedFuture(false)
                    } else {
                        evaluate.condition(target.visible, targetContext, guard).thenCompose { shown
                            ->
                            if (!shown) CompletableFuture.completedFuture(false)
                            else if (
                                target.id == node.id && id !in setOf("__close", "__popup-dismiss")
                            ) {
                                selectVariant(target, targetContext, guard).thenCompose { selected
                                    ->
                                    mainFuture {
                                        guard()
                                        // 不能用新变体动作重新解释旧点击。
                                        if (publishedChoices[id] != selected.index) {
                                            throw UiOperationException(UiFailureCode.ACTION_DENIED)
                                        }
                                        actionNode = selected.node
                                        evaluate.condition(
                                            selected.node.enabled,
                                            targetContext,
                                            guard,
                                        )
                                    }
                                }
                            } else evaluate.condition(target.enabled, targetContext, guard)
                        }
                    }
                }
            }
        }
        allowed
            .thenCompose { permitted ->
                mainFuture {
                    guard()
                    if (!permitted) throw UiOperationException(UiFailureCode.ACTION_DENIED)
                    if (
                        actionNode.actions.none { it.type == "submit" } &&
                            submitted.isNotEmpty() &&
                            fields[player.uniqueId].isNullOrEmpty()
                    ) {
                        throw UiOperationException(UiFailureCode.INVALID_FORM)
                    }
                    execute(player, session, actionNode, submitted, generation)
                }
            }
            .whenComplete { _, failure ->
                main { finishAction(player, session, work, failure) }
            }
    }

    private fun finishAction(player: Player, session: UiSession, work: Work, error: Throwable?) {
        val failure =
            if (work.expired(clock())) UiOperationException(UiFailureCode.TIMED_OUT, cause = error)
            else error
        if (executions[player.uniqueId] === work) executions.remove(player.uniqueId)
        // 导航或关闭已接管下一帧，此处不重复发送。
        if (!current(session, work.generation)) {
            work.result.complete(null)
            return
        }
        if (failure != null && failureCode(failure) == UiFailureCode.TIMED_OUT) {
            // 外部任务可能继续完成，不自动重试不确定的副作用。
            fail(player, session, failure)
            work.result.completeExceptionally(operationFailure(failure))
            return
        }
        session.finishAction(work.generation)
        val problem = failure?.let(::unwrap)
        if (problem != null && problem !is FormRejected) notify(player, session, problem)
        val queued = refreshes.remove(player.uniqueId)
        val next = render(player, session, (problem as? FormRejected)?.errors.orEmpty())
        if (queued != null) relay(next, queued)
        if (failure == null) work.result.complete(null)
        else work.result.completeExceptionally(operationFailure(failure))
    }

    private fun execute(
        player: Player,
        session: UiSession,
        node: NodeSpec,
        submitted: Map<String, Any?>,
        generation: Long,
    ): CompletableFuture<Void> {
        val configured = node.actions
        val guard = { live(session, generation) }
        var future = CompletableFuture.completedFuture<Void>(null)
        configured.forEach { action ->
            future = future.thenCompose {
                mainFuture<Void> {
                    guard()
                    val context = context(player, session, node.rowScope)
                    when (action.type) {
                        "submit" -> {
                            val inputs =
                                nodes(session).filter { it.id in fields[player.uniqueId].orEmpty() }
                            if (submitted.keys != inputs.map { it.id }.toSet()) {
                                throw FormRejected(mapOf("form" to "INVALID_FIELDS"))
                            }
                            val resolvedInputs = mutableListOf<NodeSpec>()
                            var checks = CompletableFuture.completedFuture(true)
                            inputs.forEach { input ->
                                checks = checks.thenCompose { permitted ->
                                    mainFuture {
                                        guard()
                                        if (!permitted) {
                                            CompletableFuture.completedFuture(false)
                                        } else {
                                            evaluate
                                                .condition(input.visible, context, guard)
                                                .thenCompose { shown ->
                                                    if (!shown)
                                                        CompletableFuture.completedFuture(false)
                                                    else
                                                        selectVariant(input, context, guard)
                                                            .thenCompose { selected ->
                                                                mainFuture {
                                                                    guard()
                                                                    if (
                                                                        shownVariants[
                                                                                player.uniqueId]
                                                                            ?.get(input.id) !=
                                                                            selected.index
                                                                    )
                                                                        throw UiOperationException(
                                                                            UiFailureCode
                                                                                .ACTION_DENIED
                                                                        )
                                                                    resolvedInputs += selected.node
                                                                    evaluate.condition(
                                                                        selected.node.enabled,
                                                                        context,
                                                                        guard,
                                                                    )
                                                                }
                                                            }
                                                }
                                        }
                                    }
                                }
                            }
                            checks.thenCompose { permitted ->
                                main<Void> {
                                    guard()
                                    if (!permitted)
                                        throw UiOperationException(UiFailureCode.ACTION_DENIED)
                                    val errors = session.submit(submitted, resolvedInputs)
                                    if (errors.isNotEmpty()) throw FormRejected(errors)
                                    null
                                }
                            }
                        }
                        "set-state" -> {
                            val value =
                                action.binding?.let { evaluate.binding(it, context, guard) }
                                    ?: CompletableFuture.completedFuture(action.value)
                            value.thenCompose { resolved ->
                                main<Void> {
                                    guard()
                                    session.setState(action.key, resolved)
                                    null
                                }
                            }
                        }
                        "set-record",
                        "toggle-record" -> {
                            val selected = action.args["state"] as? String
                            val id =
                                if (selected != null) session.state[selected] as? String
                                else
                                    session.data
                                        .item(requireNotNull(node.rowScope))
                                        ?.get(session.menu.spec.data.getValue(action.key).idField)
                                        as? String
                            session.data.patch(
                                action.key,
                                requireNotNull(id) { "No selected record" },
                                action.args.getValue("field") as String,
                                action.value,
                                action.type == "toggle-record",
                            )
                            CompletableFuture.completedFuture<Void>(null)
                        }
                        "select-item" -> {
                            val selected = session.data.select(requireNotNull(node.rowScope))
                            session.setState(selected.first, selected.second)
                            CompletableFuture.completedFuture<Void>(null)
                        }
                        "page-list" -> {
                            val next =
                                session.data.page(action.key, (action.value as Number).toInt())
                            session.setState(next.first, next.second)
                            CompletableFuture.completedFuture<Void>(null)
                        }
                        "toggle-state" -> {
                            val value = session.state[action.key]
                            require(value is Boolean) { "Not a boolean state" }
                            session.setState(action.key, !value)
                            CompletableFuture.completedFuture<Void>(null)
                        }
                        "provider" ->
                            evaluate.action(action, context, guard).thenCompose { result ->
                                main<Void> {
                                    guard()
                                    try {
                                        session.patchState(result.statePatch)
                                    } catch (failure: IllegalArgumentException) {
                                        throw UiOperationException(
                                            UiFailureCode.INVALID_ARGUMENT,
                                            cause = failure,
                                        )
                                    }
                                    null
                                }
                            }
                        "kether" ->
                            evaluate.script(action.key, context, guard).thenApply<Void> { null }
                        "open-popup" -> {
                            session.finishAction(generation)
                            session.openPopup(action.key)
                            render(player, session)
                        }
                        "close-popup" -> {
                            session.finishAction(generation)
                            session.closePopup()
                            render(player, session)
                        }
                        "open-page" -> {
                            session.finishAction(generation)
                            session.navigate(action.key)
                            render(player, session)
                        }
                        "back" -> {
                            session.finishAction(generation)
                            session.back()
                            render(player, session)
                        }
                        "close" -> close(player, session.id)
                        else -> error("Unknown action ${action.type}")
                    }
                }
            }
        }
        return future
    }

    private fun failureCode(error: Throwable): UiFailureCode =
        when (val failure = unwrap(error)) {
            is UiOperationException -> failure.code
            is FormRejected -> UiFailureCode.INVALID_FORM
            is CancellationException -> UiFailureCode.SUPERSEDED
            else -> UiFailureCode.INTERNAL_ERROR
        }

    private fun operationFailure(error: Throwable): UiOperationException =
        (unwrap(error) as? UiOperationException)
            ?: UiOperationException(failureCode(error), cause = unwrap(error))

    private fun notify(player: Player, session: UiSession, failure: Throwable) {
        val code = failureCode(failure)
        if (code == UiFailureCode.INTERNAL_ERROR) {
            plugin.logger.log(
                Level.WARNING,
                "Menu ${session.menu.spec.id}/${session.page}: $code",
                unwrap(failure),
            )
        }
        if (player.isOnline) {
            runCatching { player.sendUiFailure(code) }
                .onFailure {
                    plugin.logger.fine("Unable to send UI failure message: ${it.message}")
                }
        }
    }

    private fun fail(player: Player, session: UiSession, failure: Throwable) {
        if (sessions[player.uniqueId] !== session) return
        val pending = detach(player.uniqueId)
        clear(player)
        notify(player, session, failure)
        completeDetached(pending, failureCode(failure), unwrap(failure))
    }

    private fun <T> register(
        registry: Registry<T>,
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: T,
    ): AutoCloseable {
        check(Bukkit.isPrimaryThread()) { "Provider registration requires the main thread" }
        check(!stopped) { "Engine stopped" }
        require(owner.isEnabled) { "Provider owner must be enabled" }
        val handle = registry.register(owner, id, descriptor, provider)
        val requested = AtomicBoolean()
        return AutoCloseable {
            if (requested.compareAndSet(false, true)) {
                main {
                    // 先在同一主线程任务内移除注册并使界面失效，再触发延续。
                    if (handle.remove() && !stopped) {
                        resources.cancelPack()
                        closeAll(UiFailureCode.PROVIDER_UNAVAILABLE)
                    }
                }
            }
        }
    }

    override fun registerData(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: DataProvider,
    ): AutoCloseable = register(data, owner, id, descriptor, provider)

    override fun registerCondition(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: ConditionProvider,
    ): AutoCloseable = register(conditions, owner, id, descriptor, provider)

    override fun registerAction(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: ActionProvider,
    ): AutoCloseable = register(actions, owner, id, descriptor, provider)

    override fun registerItems(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: ItemProvider,
    ): AutoCloseable {
        require(descriptor.parameters.isEmpty()) {
            "ItemProvider accepts an item ID, not parameter arguments"
        }
        return register(items, owner, id, descriptor, provider)
    }

    fun removeOwner(owner: Plugin) {
        check(Bukkit.isPrimaryThread())
        val removed =
            data.removeOwner(owner) +
                conditions.removeOwner(owner) +
                actions.removeOwner(owner) +
                items.removeOwner(owner)
        if (removed.isNotEmpty()) {
            resources.cancelPack()
            closeAll(UiFailureCode.PROVIDER_UNAVAILABLE)
        }
    }

    override fun contract(): Map<String, Any?> =
        AuthoringContract.manifest(
            plugin.description?.version ?: "development",
            mapOf(
                "data" to data.descriptors(),
                "conditions" to conditions.descriptors(),
                "actions" to actions.descriptors(),
                "items" to items.descriptors(),
            ),
        )

    /** 先标记关闭，再完成可能重入的 Future。 */
    override fun close() {
        stopped = true
        resources.close()
        closeAll(UiFailureCode.ENGINE_STOPPED)
        data.clear()
        conditions.clear()
        actions.clear()
        items.clear()
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) unwrap(error.cause!!) else error
}
