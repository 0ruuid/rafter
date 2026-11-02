package com.hiusers.rafter.model

import java.util.Collections

/** 边界只接受 JSON 形状的普通值；冻结时复制嵌套集合，不共享调用方可变容器。 */
object Values {
    fun atLeast(left: Any?, right: Any?): Boolean {
        val a = (left as? Number)?.toDouble() ?: error("Numeric condition needs a number")
        val b = (right as? Number)?.toDouble() ?: error("Numeric condition needs a number")
        require(a.isFinite() && b.isFinite()) { "Numeric condition needs finite numbers" }
        return java.math
            .BigDecimal(left.toString())
            .compareTo(java.math.BigDecimal(right.toString())) >= 0
    }

    fun freeze(value: Any?, depth: Int = 0): Any? = freezeAt(value, depth, 32)

    /** 数值转换前拒绝可变或自定义 Number。 */
    fun isOrdinaryNumber(value: Number): Boolean =
        value is Byte ||
            value is Short ||
            value is Int ||
            value is Long ||
            value is Float ||
            value is Double ||
            value.javaClass == java.math.BigInteger::class.java ||
            value.javaClass == java.math.BigDecimal::class.java

    private fun freezeAt(value: Any?, depth: Int, limit: Int): Any? {
        require(depth <= limit) { "Value nesting exceeds $limit" }
        return when (value) {
            null,
            is String,
            is Boolean -> value
            is Number -> {
                require(isOrdinaryNumber(value)) { "Numbers must be ordinary immutable JVM values" }
                require(value.toDouble().isFinite()) { "Non-finite number" }
                value
            }
            is Map<*, *> ->
                Collections.unmodifiableMap(
                    value.entries.associate { (k, v) ->
                        require(k is String) { "Map key must be a string" }
                        k to freezeAt(v, depth + 1, limit)
                    }
                )
            is List<*> -> Collections.unmodifiableList(value.map { freezeAt(it, depth + 1, limit) })
            else -> throw IllegalArgumentException("Not a document value: ${value.javaClass.name}")
        }
    }

    fun equal(a: Any?, b: Any?): Boolean =
        when {
            a is Number && b is Number ->
                java.math.BigDecimal(a.toString()).compareTo(java.math.BigDecimal(b.toString())) ==
                    0
            a is Map<*, *> && b is Map<*, *> ->
                a.keys == b.keys && a.keys.all { equal(a[it], b[it]) }
            a is List<*> && b is List<*> ->
                a.size == b.size && a.indices.all { equal(a[it], b[it]) }
            else -> a == b
        }

    @Suppress("UNCHECKED_CAST")
    fun map(value: Map<String, Any?>): Map<String, Any?> = freeze(value) as Map<String, Any?>

    /** 描述包装有独立嵌套限制，不放宽普通数据约束。 */
    @Suppress("UNCHECKED_CAST")
    fun contract(value: Map<String, Any?>): Map<String, Any?> =
        freezeAt(value, 0, 64) as Map<String, Any?>

    /** 只复制列表容器，元素的不可变性由调用方保证。 */
    fun <T> list(value: List<T>): List<T> = Collections.unmodifiableList(value.toList())
}

data class BindingSpec(
    val type: String,
    val key: String = "",
    val args: Map<String, Any?> = emptyMap(),
    val occurrence: String? = null,
    val scope: RowScope? = null,
)

data class InlineTextPart(val text: String = "", val binding: BindingSpec? = null)

/** 代码构造文本默认按字面处理，仅配置解码启用颜色。 */
data class TextSpec(
    val format: String = "literal",
    val value: String = "",
    val parts: List<TextSpec> = emptyList(),
    val binding: BindingSpec? = null,
    val font: String? = null,
    val color: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val hover: TextSpec? = null,
    val fontSize: Double? = null,
    val inlineParts: List<InlineTextPart> = emptyList(),
    val offsetY: Int = 0,
)

data class ConditionSpec(
    val type: String,
    val key: String = "",
    val value: Any? = null,
    val args: Map<String, Any?> = emptyMap(),
    val children: List<ConditionSpec> = emptyList(),
    val binding: BindingSpec? = null,
    val compare: BindingSpec? = null,
)

data class ActionSpec(
    val type: String,
    val key: String = "",
    val value: Any? = null,
    val binding: BindingSpec? = null,
    val args: Map<String, Any?> = emptyMap(),
)

enum class PageMode {
    NATIVE,
    CANVAS,
}

/** 仅正文可置于画布前后，输入和操作区始终随后排列。 */
enum class NativeBodyPosition(val id: String) {
    BEFORE_CANVAS("before-canvas"),
    AFTER_CANVAS("after-canvas"),
}

/** 弹层坐标仍相对原页面画布。 */
data class PopupSpec(val elements: List<NodeSpec>, val hideUnderlay: Set<String> = emptySet())

/** 视口背景不占正文流高度。 */
data class DialogFrameSpec(
    val asset: String,
    val padding: Int = 12,
    val inset: Int = 4,
    val width: Int? = null,
)

/** 静态面板覆盖冻结画布，输入仍使用原生控件。 */
data class ModalSpec(
    val asset: String,
    val background: String,
    val dim: Double = 0.52,
    val offsetY: Int = 1,
    val buttonGap: Int = 24,
    val feedback: String? = null,
    val clear: List<Rect> = emptyList(),
    val requestedTop: Int = offsetY,
    val contentTop: Int = 35,
)

data class PageSpec(
    val id: String,
    val mode: PageMode,
    val title: TextSpec,
    val width: Int,
    val columns: Int,
    val components: List<NodeSpec>,
    val height: Int? = null,
    val elements: List<NodeSpec> = emptyList(),
    val nativeBodyPosition: NativeBodyPosition = NativeBodyPosition.AFTER_CANVAS,
    val popups: Map<String, PopupSpec> = emptyMap(),
    val frame: DialogFrameSpec? = null,
    val modal: ModalSpec? = null,
    val lists: List<ListSpec> = emptyList(),
    val skin: String? = null,
) {
    /** 两区域共享身份与会话，不共享布局。 */
    val nodes: List<NodeSpec>
        get() = elements + components + popups.values.flatMap { it.elements }
}

data class MenuSpec(
    val id: String,
    val entry: String,
    val permission: String?,
    val state: Map<String, Any?>,
    val pages: Map<String, PageSpec>,
    val source: String,
    val data: Map<String, DataSourceSpec> = emptyMap(),
    val documents: Map<String, List<Map<String, Any?>>> = emptyMap(),
) {
    val lists: List<ListSpec>
        get() = pages.values.flatMap { it.lists }
}

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun overlaps(other: Rect) =
        x < other.x + other.width &&
            other.x < x + width &&
            y < other.y + other.height &&
            other.y < y + height
}

data class PositionedNode(val node: NodeSpec, val bounds: Rect)

data class CompiledPage(
    val spec: PageSpec,
    val scene: List<PositionedNode>,
    val height: Int,
    val popups: Map<String, List<PositionedNode>> = emptyMap(),
    val modalLayout: ModalLayoutPlan? = null,
)

data class CompiledMenu(val spec: MenuSpec, val pages: Map<String, CompiledPage>)

data class MenuSnapshot(
    val menus: Map<String, CompiledMenu>,
    val documents: Map<String, Map<String, Any?>>,
)

data class CompileResult(val snapshot: MenuSnapshot?, val diagnostics: List<Diagnostic>)
