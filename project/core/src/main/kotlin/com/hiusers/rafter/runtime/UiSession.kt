package com.hiusers.rafter.runtime

import com.hiusers.rafter.model.CompiledMenu
import com.hiusers.rafter.model.CompiledPage
import com.hiusers.rafter.model.FormContent
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.PageMode
import com.hiusers.rafter.model.StateContent
import com.hiusers.rafter.model.Values
import com.hiusers.rafter.model.accepts
import java.util.Collections
import java.util.UUID

enum class SessionPhase {
    CREATED,
    RENDERING,
    READY,
    EXECUTING,
    CLOSED,
}

/** 主线程持有会话状态、表单和句柄；参数已冻结，编译菜单只读共享。 网络监听器只提交不可变输入快照。 */
class UiSession(
    val playerId: UUID,
    val menu: CompiledMenu,
    args: Map<String, Any?>,
    val timeoutMillis: Long = 600_000,
) {
    val id: UUID = UUID.randomUUID()
    val args = Values.map(args)
    val state = menu.spec.state.toMutableMap()
    val form = linkedMapOf<String, Any?>()
    val data = SessionData(menu.spec, state)
    var page = menu.spec.entry
        private set

    var popup: String? = null
        private set

    var generation = 0L
        private set

    var phase = SessionPhase.CREATED
        private set

    val closed: Boolean
        get() = phase == SessionPhase.CLOSED

    val busy: Boolean
        get() = phase == SessionPhase.EXECUTING

    var lastInteraction = System.currentTimeMillis()

    private data class Origin(val page: String, val popup: String?)

    private val history = mutableListOf<Origin>()
    private val handles = mutableMapOf<String, String>()
    private var lastCanvas: FrozenCanvas? = null
    var modalBackdrop: FrozenCanvas? = null
        private set

    /** 发送成功后才保存，迟到完成不能覆盖新帧。 */
    fun rememberCanvas(canvas: FrozenCanvas) {
        if (phase == SessionPhase.READY && canvas.page == page && canvas.generation == generation)
            lastCanvas = canvas
    }

    fun current(): CompiledPage = menu.pages.getValue(page)

    fun activeLists() = current().spec.lists.filter { it.popup == null || it.popup == popup }

    /** 关闭的弹层不读取提供器；背景仍显示但无点击能力。 */
    fun displayedNodes(): List<NodeSpec> =
        current().spec.let { spec ->
            spec.elements + spec.components + spec.popups[popup]?.elements.orEmpty()
        }

    fun acceptsTarget(id: String): Boolean =
        when (id) {
            "__close" -> true
            "__popup-dismiss" -> popup != null
            else ->
                if (popup == null)
                    id in (current().spec.elements + current().spec.components).map { it.id }
                else id in current().spec.popups.getValue(popup!!).elements.map { it.id }
        }

    fun openPopup(key: String) {
        check(!closed)
        require(key in current().spec.popups) { "Unknown popup $key" }
        popup = key
        phase = SessionPhase.CREATED
        invalidate()
    }

    fun closePopup() {
        check(!closed && popup != null)
        popup = null
        phase = SessionPhase.CREATED
        invalidate()
    }

    fun invalidate(): Long {
        generation++
        handles.clear()
        return generation
    }

    fun isCurrent(generation: Long) = !closed && this.generation == generation

    fun beginRender(): Long {
        check(!closed && !busy) { "Session cannot render in $phase" }
        phase = SessionPhase.RENDERING
        return invalidate()
    }

    fun ready(generation: Long, targets: Collection<String>): Map<String, String> {
        check(phase == SessionPhase.RENDERING && isCurrent(generation)) { "Stale render" }
        val issued = targets.associateWith { UUID.randomUUID().toString() }
        issued.forEach { (target, token) -> handles[token] = target }
        phase = SessionPhase.READY
        return Collections.unmodifiableMap(issued)
    }

    fun finishAction(generation: Long): Boolean {
        if (phase != SessionPhase.EXECUTING || !isCurrent(generation)) return false
        phase = SessionPhase.CREATED
        return true
    }

    fun navigate(target: String) {
        check(!closed)
        require(target in menu.pages)
        val currentPage = current().spec
        val replacingModal = currentPage.modal != null
        val backdrop =
            if (menu.pages.getValue(target).spec.modal != null) {
                if (replacingModal) {
                    requireNotNull(modalBackdrop) {
                        "A modal replacement requires a captured Canvas backdrop"
                    }
                } else {
                    require(
                        currentPage.mode == PageMode.CANVAS &&
                            currentPage.components.isEmpty() &&
                            currentPage.frame == null
                    ) {
                        "A modal requires a visible Canvas origin"
                    }
                    requireNotNull(lastCanvas?.takeIf { it.page == page }) {
                        "A modal requires a successfully presented Canvas backdrop"
                    }
                }
            } else null
        if (!replacingModal) history += Origin(page, popup.takeIf { backdrop != null })
        modalBackdrop = backdrop
        popup = null
        page = target
        form.clear()
        phase = SessionPhase.CREATED
        invalidate()
    }

    fun back() {
        check(!closed)
        modalBackdrop = null
        val origin =
            if (history.isEmpty()) Origin(menu.spec.entry, null)
            else history.removeAt(history.lastIndex)
        page = origin.page
        popup = origin.popup
        form.clear()
        phase = SessionPhase.CREATED
        invalidate()
    }

    fun close() {
        lastCanvas = null
        modalBackdrop = null
        phase = SessionPhase.CLOSED
        popup = null
        form.clear()
        history.clear()
        invalidate()
    }

    private fun stateValid(key: String, value: Any?): Boolean {
        if (key !in menu.spec.state) return false
        val old = menu.spec.state[key]
        val sameKind =
            if (menu.spec.lists.any { it.selectionState == key }) value == null || value is String
            else
                when (old) {
                    null -> true
                    is String -> value is String
                    is Boolean -> value is Boolean
                    is Number -> value is Number
                    is Map<*, *> -> value is Map<*, *>
                    is List<*> -> value is List<*>
                    else -> false
                }
        if (!sameKind) return false
        if (
            menu.spec.lists.any { it.pageState == key } &&
                !(value is Number &&
                    Values.isOrdinaryNumber(value) &&
                    value.toDouble().isFinite() &&
                    value.toDouble() >= 0 &&
                    value.toDouble() % 1.0 == 0.0)
        )
            return false
        // 同一状态可绑定列表和控件，列表规则不能跳过后续约束。
        return menu.spec.pages.values
            .flatMap { it.nodes }
            .mapNotNull { it.content as? StateContent }
            .filter { it.state == key }
            .all { it.accepts(value) }
    }

    fun setState(key: String, value: Any?) = patchState(mapOf(key to value))

    /** 全部校验成功后一次写入，不保留调用方的可变集合。 */
    fun patchState(patch: Map<String, Any?>) {
        val frozen = Values.map(patch)
        frozen.forEach { (key, value) ->
            require(stateValid(key, value)) { "Invalid state value for $key" }
        }
        state.putAll(frozen)
    }

    fun consume(token: String, now: Long = System.currentTimeMillis()): String? {
        if (phase != SessionPhase.READY || expired(now)) return null
        val id = handles[token] ?: return null
        lastInteraction = now
        phase = SessionPhase.EXECUTING
        invalidate()
        return id
    }

    fun expired(now: Long) = now - lastInteraction >= timeoutMillis

    /** 全部字段校验成功后才提交状态。 */
    fun submit(values: Map<String, Any?>, inputs: List<NodeSpec>): Map<String, Any?> {
        // 仅接受不可变标量，不保留提交对象。
        val submitted = values.toMap()
        val errors = linkedMapOf<String, Any?>()
        val candidate = linkedMapOf<String, Any?>()
        form.clear()
        val inputIds = inputs.map { it.id }.toSet()
        submitted.keys.filter { it !in inputIds }.forEach { errors[it] = "Unknown form field" }
        inputs.forEach { node ->
            val input = node.content as? FormContent ?: error("Not a form input: ${node.id}")
            if (node.id !in submitted) {
                errors[node.id] = "Missing ${node.type} value"
                return@forEach
            }
            val raw = submitted[node.id]
            val value: Any? =
                when (input) {
                    is NodeContent.TextInput,
                    is NodeContent.Select -> raw as? String
                    is NodeContent.Checkbox ->
                        when (raw) {
                            is Boolean -> raw
                            is String ->
                                when (raw) {
                                    "true" -> true
                                    "false" -> false
                                    else -> null
                                }
                            is Byte ->
                                when (raw.toInt()) {
                                    1 -> true
                                    0 -> false
                                    else -> null
                                }
                            else -> null
                        }
                    is NodeContent.Slider ->
                        when (raw) {
                            is Number -> if (Values.isOrdinaryNumber(raw)) raw.toDouble() else null
                            is String -> raw.takeIf { it.length <= 64 }?.toDoubleOrNull()
                            else -> null
                        }
                }
            if (input.accepts(value)) {
                val frozen = Values.freeze(value)
                form[node.id] = frozen
                if (stateValid(input.state, frozen)) candidate[input.state] = frozen
                else errors[node.id] = "Invalid ${node.type} state"
            } else errors[node.id] = "Invalid ${node.type} value"
        }
        if (errors.isEmpty()) state.putAll(candidate)
        return errors
    }
}
