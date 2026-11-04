package com.hiusers.rafter.api

import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** 打开时的初始状态与目标页；引擎接收时深拷贝并冻结，DTO 本身不复制集合。 */
data class UiOpenOptions(
    val initialState: Map<String, Any?>,
    val initialPage: String?,
) {
    // 保持跨插件的 JVM 构造签名稳定。
    constructor() : this(emptyMap(), null)

    constructor(initialState: Map<String, Any?>) : this(initialState, null)

    constructor(initialPage: String?) : this(emptyMap(), initialPage)
}

/** Rafter 提供只读普通值快照，嵌套最多 32 层；Player 仍限主线程访问。 自行构造此对象不复制集合，也不创建会话。 */
data class UiContext(
    val player: Player,
    val sessionId: UUID,
    val menu: String,
    val page: String,
    val args: Map<String, Any?>,
    val state: Map<String, Any?>,
    val form: Map<String, Any?>,
    /** 当前列表行的只读数据，非列表上下文为 null；业务仍须复查权威数据。 */
    val item: Map<String, Any?>?,
) {
    constructor(
        player: Player,
        sessionId: UUID,
        menu: String,
        page: String,
        args: Map<String, Any?>,
        state: Map<String, Any?>,
        form: Map<String, Any?>,
    ) : this(player, sessionId, menu, page, args, state, form, null)
}

/** 主线程发起读取，可异步完成；结果仅允许普通值，后台不得访问 Player。 */
fun interface DataProvider {
    fun read(context: UiContext, args: Map<String, Any?>): CompletableFuture<Any?>
}

/** 主线程发起条件检查，可异步完成；后台不得访问 Player。 */
fun interface ConditionProvider {
    fun test(context: UiContext, args: Map<String, Any?>): CompletableFuture<Boolean>
}

/** 主线程发起动作，结果由引擎校验并写回原会话；丢弃迟到结果不取消、回滚或重试业务。 动作结束自动重绘，不在返回链中等待同会话 refresh，避免相互等待。 */
fun interface ActionProvider {
    fun execute(context: UiContext, args: Map<String, Any?>): CompletableFuture<UiActionResult>
}

/** 仅包含已声明状态的增量；接收时深拷贝并冻结，校验通过后原子写回。 */
data class UiActionResult(val statePatch: Map<String, Any?>) {
    constructor() : this(emptyMap())

    companion object {
        @JvmStatic fun empty(): UiActionResult = UiActionResult()
    }
}

/** 主线程按 ID 读取物品，item 为节点当前行，非列表节点为 null；返回后不再并发修改 ItemStack。 */
fun interface ItemProvider {
    fun read(context: UiContext, id: String): CompletableFuture<ItemStack>
}

/** 成功仅表示最终目标页的服务端发送阶段完成；失败读取 [failure]，不代表客户端已显示。 */
data class OpenResult(
    val opened: Boolean,
    val failure: UiFailureCode?,
    val sessionId: UUID?,
) {
    constructor(opened: Boolean) : this(opened, null, null)

    constructor(opened: Boolean, failure: UiFailureCode?) : this(opened, failure, null)

    companion object {
        operator fun invoke(
            opened: Boolean,
            failure: UiFailureCode? = null,
            sessionId: UUID? = null,
        ): OpenResult = of(opened, failure, sessionId)

        @JvmStatic
        @JvmOverloads
        fun of(
            opened: Boolean,
            failure: UiFailureCode? = null,
            sessionId: UUID? = null,
        ): OpenResult = OpenResult(opened, failure, sessionId)
    }
}

/**
 * 通过 ServicesManager 获取界面服务。 界面操作可跨线程发起，内部回到主线程；不得阻塞等待 Future 或假定延续线程。 注册限主线程，owner
 * 停用自动注销；句柄可跨线程关闭，但返回不代表清理已完成。 旧句柄不影响新注册；有效注销使现存会话失效。超时不保证取消或回滚外部业务。
 */
interface RafterService {
    companion object {
        /** 插件启用后在主线程取得服务。 */
        @JvmStatic
        fun get(): RafterService =
            checkNotNull(Bukkit.getServicesManager().load(RafterService::class.java)) {
                "Rafter service is unavailable"
            }
    }

    fun open(player: Player, menu: String): CompletableFuture<OpenResult> =
        open(player, menu, emptyMap())

    fun open(player: Player, menu: String, options: UiOpenOptions): CompletableFuture<OpenResult> =
        open(player, menu, emptyMap(), options)

    /** 打开菜单，失败读取 [OpenResult.failure]；成功不确认客户端显示。 */
    fun open(
        player: Player,
        menu: String,
        args: Map<String, Any?>,
    ): CompletableFuture<OpenResult>

    /** 首读前初始化状态并打开目标页；modal 目标先呈现 Canvas 入口。成功表示最终目标页已发送，沿用同一会话 ID。 */
    fun open(
        player: Player,
        menu: String,
        args: Map<String, Any?>,
        options: UiOpenOptions,
    ): CompletableFuture<OpenResult>

    /** 仅关闭预期会话；无会话时幂等完成，其他会话返回 SUPERSEDED。 */
    fun close(player: Player, expectedSessionId: UUID): CompletableFuture<Void>

    /** 仅刷新预期会话，不存在或不匹配返回 SUPERSEDED。 忙碌时合并为后续帧，Future 在该帧交给发送层或明确失败后完成。 */
    fun refresh(player: Player, expectedSessionId: UUID): CompletableFuture<Void>

    fun registerData(
        owner: Plugin,
        id: String,
        provider: DataProvider,
    ): AutoCloseable = registerData(owner, id, ProviderDescriptor(id), provider)

    fun registerData(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: DataProvider,
    ): AutoCloseable

    fun registerCondition(
        owner: Plugin,
        id: String,
        provider: ConditionProvider,
    ): AutoCloseable = registerCondition(owner, id, ProviderDescriptor(id), provider)

    fun registerCondition(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: ConditionProvider,
    ): AutoCloseable

    fun registerAction(
        owner: Plugin,
        id: String,
        provider: ActionProvider,
    ): AutoCloseable = registerAction(owner, id, ProviderDescriptor(id), provider)

    fun registerAction(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: ActionProvider,
    ): AutoCloseable

    fun registerItems(
        owner: Plugin,
        id: String,
        provider: ItemProvider,
    ): AutoCloseable = registerItems(owner, id, ProviderDescriptor(id), provider)

    /** 物品提供器仅接收上下文和 ID，descriptor.parameters 必须为空。 */
    fun registerItems(
        owner: Plugin,
        id: String,
        descriptor: ProviderDescriptor,
        provider: ItemProvider,
    ): AutoCloseable

    /** 返回契约与扩展描述的只读快照，不含回调实例。 */
    fun contract(): Map<String, Any?>
}
