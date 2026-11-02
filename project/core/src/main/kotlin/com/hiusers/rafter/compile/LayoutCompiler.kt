package com.hiusers.rafter.compile

import com.hiusers.rafter.model.*
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 按独立元素计算画布，分组已按原绘制顺序展开。 */
object LayoutCompiler {
    const val MAX_HEIGHT = 4095
    const val X_STEP = 1
    const val Y_STEP = CanvasGeometry.ROW_HEIGHT

    fun rows(value: Int) = ((value + Y_STEP - 1) / Y_STEP * Y_STEP).coerceAtLeast(Y_STEP)

    fun layout(
        page: PageSpec,
        assets: Map<String, Pair<Int, Int>>,
        measure: ((TextSpec) -> Pair<Int, Int>)? = null,
    ): CompiledPage {
        fun fail(node: NodeSpec, message: String, code: String = "LAYOUT"): Nothing =
            LayoutCompiler.fail(page, node, message, code)
        fun bounds(node: NodeSpec): Rect {
            if (node.x < 0 || node.y < 0) fail(node, "Canvas coordinates must be non-negative")
            if (node.content == NodeContent.HitArea && node.y % Y_STEP != 0)
                fail(node, "Explicit hit areas must follow the exported $Y_STEP px transport step")
            val natural =
                when (val content = node.content) {
                    is NodeContent.Image ->
                        assets[content.asset]
                            ?: fail(node, "Unknown asset ${content.asset}", "ASSET")
                    is NodeContent.Meter ->
                        requireNotNull(node.width) to requireNotNull(node.height)
                    NodeContent.Text -> {
                        fun singleLine(spec: TextSpec) {
                            val authored =
                                if (spec.inlineParts.isEmpty()) listOf(spec.value)
                                else spec.inlineParts.filter { it.binding == null }.map { it.text }
                            if (authored.any { '\n' in it || '\r' in it })
                                fail(node, "Canvas text uses single-line elements")
                            spec.parts.forEach(::singleLine)
                        }
                        singleLine(node.text)
                        val measured =
                            try {
                                measure?.invoke(node.text)
                            } catch (failure: DocumentError) {
                                throw failure
                            } catch (failure: IllegalArgumentException) {
                                fail(node, failure.message ?: "Cannot measure Canvas text")
                            }
                        return textBounds(page, node, measured, clipWidth = node.overflow == "clip")
                    }
                    NodeContent.HitArea ->
                        (node.width ?: fail(node, "Click area requires width")) to
                            (node.height ?: fail(node, "Click area requires height"))
                    else -> fail(node, "${node.type} is not a canvas element", "CAPABILITY")
                }
            val width = node.width ?: natural.first.coerceAtLeast(1)
            val height = node.height ?: natural.second.coerceAtLeast(1)
            val x = node.x
            if (width <= 0 || x < 0 || x + width > page.width)
                fail(node, "Element exceeds canvas width")
            if (
                height <= 0 ||
                    (node.content == NodeContent.HitArea && height % Y_STEP != 0) ||
                    node.y.toLong() + height > MAX_HEIGHT
            )
                fail(node, "Element height exceeds canvas or transport alignment")
            return Rect(x, node.y, width, height)
        }
        fun plane(nodes: List<NodeSpec>): List<PositionedNode> {
            val fixed =
                nodes
                    .filter { it.hitTarget == null }
                    .associate { it.id to PositionedNode(it, bounds(it)) }
            return Values.list(
                nodes.map { node ->
                    val target = node.hitTarget ?: return@map fixed.getValue(node.id)
                    val placed =
                        fixed[target]
                            ?: fail(
                                node,
                                "Unknown or non-visual hit target $target in this plane",
                                "REFERENCE",
                            )
                    if (
                        placed.node.content !is NodeContent.Image &&
                            !(placed.node.content == NodeContent.Text &&
                                placed.node.width != null &&
                                placed.node.height != null)
                    )
                        fail(
                            node,
                            "Follow an image or a text element with fixed width and height",
                            "CAPABILITY",
                        )
                    val box = placed.bounds
                    val top = box.y / Y_STEP * Y_STEP
                    // 点击以整行传输，导出实际覆盖行，不伪造像素精度。
                    val covered = Rect(box.x, top, box.width, rows(box.y + box.height) - top)
                    if (covered.y + covered.height > MAX_HEIGHT)
                        fail(node, "Followed click area exceeds transport height")
                    PositionedNode(node, covered)
                }
            )
        }
        val scene = plane(page.elements)
        val popups = page.popups.mapValues { (_, popup) -> plane(popup.elements) }
        val placed = scene + popups.values.flatten()
        val bottom = placed.maxOfOrNull { it.bounds.y + it.bounds.height } ?: Y_STEP
        val height = page.height ?: rows(bottom)
        if (height < bottom || height !in Y_STEP..MAX_HEIGHT || height % Y_STEP != 0) {
            val origin = page.elements.firstOrNull()?.origin
            val path =
                origin?.path?.substringBefore(".elements")?.let { "$it.height" }
                    ?: "$.pages.${page.id}.height"
            throw DocumentError(
                Diagnostic(
                    origin?.file ?: "project",
                    if (page.modal != null) "$.pages.${page.id}.modal.content-top" else path,
                    "LAYOUT",
                    if (page.modal != null)
                        "Modal content-top must leave room for every header element"
                    else
                        "Canvas height must contain every element and follow the $Y_STEP px transport step",
                )
            )
        }
        return CompiledPage(page, scene, height, java.util.Collections.unmodifiableMap(popups))
    }

    /** 自然文字范围必须容纳于声明框，字体抬升同样计入绘制和命中。 */
    internal fun textBounds(
        page: PageSpec,
        node: NodeSpec,
        measured: Pair<Int, Int>?,
        top: Int = textTop(node.text),
        heightLimit: Int = page.height ?: MAX_HEIGHT,
        clipWidth: Boolean = false,
    ): Rect {
        // 无测量器时仅作结构预估，渲染阶段仍须按真实字体检查。
        val natural = measured ?: (1 to ceil(node.text.fontSize ?: 10.0).toInt())
        val width = node.width ?: natural.first.coerceAtLeast(1)
        val height = node.height ?: natural.second.coerceAtLeast(1)
        if (node.x < 0 || node.y < 0) fail(page, node, "Canvas coordinates must be non-negative")
        if (width <= 0 || height <= 0) fail(page, node, "Text dimensions must be positive")
        if (measured != null && measured.first > width && !clipWidth)
            fail(
                page,
                node,
                "Text natural width ${measured.first}px exceeds declared width ${width}px",
            )
        if (measured != null && measured.second > height)
            fail(
                page,
                node,
                "Text natural height ${measured.second}px exceeds declared height ${height}px",
            )
        val left = textLeft(node, width)
        if (left < 0 || left.toLong() + width > page.width)
            fail(page, node, "Text bounds exceed canvas width")
        val lift = minOf(0, top)
        if (node.y.toLong() + lift < 0)
            fail(page, node, "Text font offset places the natural line box above the canvas")
        if (node.y.toLong() + height > heightLimit)
            fail(page, node, "Text bounds exceed canvas height")
        // 保留作者基线与分配下沿，不能漏掉抬升后的上沿。
        return Rect(left, node.y + lift, width, height - lift)
    }

    /** 运行时与预览共用整像素对齐。 */
    fun textLeft(node: NodeSpec, width: Int): Int =
        when (node.align) {
            "center" -> (node.x - width / 2.0).roundToInt()
            "right" -> node.x - width
            else -> node.x
        }

    /** 片段偏移可累加，悬停内容不参与正文行框。 */
    internal fun textTop(spec: TextSpec, inherited: Int = 0): Int {
        val offset = inherited + spec.offsetY
        return when {
            spec.format == "parts" -> spec.parts.minOfOrNull { textTop(it, offset) } ?: 0
            spec.value.isNotEmpty() ||
                spec.binding != null ||
                spec.inlineParts.any { it.binding != null || it.text.isNotEmpty() } ->
                minOf(0, offset)
            else -> 0
        }
    }

    private fun fail(
        page: PageSpec,
        node: NodeSpec,
        message: String,
        code: String = "LAYOUT",
    ): Nothing {
        val at = node.origin ?: SourceLocation("project", "$.pages.${page.id}.elements", node.id)
        throw DocumentError(
            Diagnostic(
                at.file,
                at.path,
                code,
                message,
                node.id,
                range = at.range,
                origins = node.origins,
            )
        )
    }
}
