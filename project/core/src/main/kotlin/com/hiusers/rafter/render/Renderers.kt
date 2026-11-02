package com.hiusers.rafter.render

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.TextOffsets
import com.hiusers.rafter.compile.LayoutCompiler
import com.hiusers.rafter.model.ActionSpec
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.ButtonRole
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.FormContent
import com.hiusers.rafter.model.NativeBodyPosition
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.PositionedNode
import com.hiusers.rafter.model.Rect
import com.hiusers.rafter.model.SourceLocation
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import kotlin.math.roundToInt
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components

/** 与协议无关的文本片段，CUSTOM 元数据仅在发送边界绑定。 */
data class Fragment(val text: ComponentText, val handle: String? = null)

/** 正文文字、图片和物品共用顺序；输入与页脚独立。 */
sealed interface RenderBody

data class Message(val parts: List<Fragment>, val width: Int) : RenderBody

data class RenderButton(
    val node: NodeSpec,
    val label: ComponentText,
    val tooltip: ComponentText?,
    val handle: String?,
)

data class RenderInput(val node: NodeSpec, val label: ComponentText, val initial: Any?)

data class ItemBody(val node: NodeSpec, val label: ComponentText) : RenderBody

data class DialogView(
    val title: ComponentText,
    val bodies: List<RenderBody>,
    val inputs: List<RenderInput>,
    val buttons: List<RenderButton>,
    val close: RenderButton,
    val columns: Int,
    val fallbackTitle: ComponentText? = null,
    val modal: Boolean = false,
) {
    /** 类型化只读投影，正文顺序仅由 [bodies] 决定。 */
    val messages: List<Message>
        get() = bodies.filterIsInstance<Message>()

    val items: List<ItemBody>
        get() = bodies.filterIsInstance<ItemBody>()
}

data class Frame(
    val values: Map<BindingSpec, Any?>,
    val visible: Map<String, Boolean>,
    val enabled: Map<String, Boolean>,
    val resolvedNodes: Map<String, NodeSpec> = emptyMap(),
)

/** 读取编译页面与已签发能力，不修改会话或授权目标。 */
object NativeRenderer {
    fun render(
        session: UiSession,
        frame: Frame,
        text: CompositeText,
        errors: Map<String, Any?> = emptyMap(),
        handles: Map<String, String> = emptyMap(),
    ): DialogView {
        val page = session.current().spec
        val bodies = mutableListOf<RenderBody>()
        val inputs = mutableListOf<RenderInput>()
        val buttons = mutableListOf<RenderButton>()
        var close = exit(text, handles)
        page.components
            .filter { frame.visible[it.id] == true }
            .forEach { original ->
                val node = frame.resolvedNodes[original.id] ?: original
                val label = FormFeedback.label(node, page, frame, text, errors)
                when (val content = node.content) {
                    is NodeContent.Text ->
                        bodies += Message(listOf(Fragment(label)), node.width ?: page.width)
                    is NodeContent.Image ->
                        bodies +=
                            Message(
                                listOf(
                                    Fragment(text.image(content.asset)),
                                    Fragment(
                                        text.literal(
                                            "\n".repeat((text.imageHeight(content.asset) + 8) / 9)
                                        )
                                    ),
                                ),
                                node.width ?: page.width,
                            )
                    is NodeContent.Item -> bodies += ItemBody(node, label)
                    is FormContent ->
                        if (frame.enabled[node.id] == true) {
                            val inputLabel =
                                if (page.modal != null && content is NodeContent.Checkbox) {
                                    // BooleanInput 无宽度字段，使用字面步进匹配编译宽度。
                                    val width =
                                        (node.width ?: NativeGeometry.INPUT_WIDTH) -
                                            NativeGeometry.CHECKBOX_SIZE -
                                            NativeGeometry.LABEL_GAP
                                    val fitted = text.fitLine(label, width)
                                    Components.empty()
                                        .append(fitted)
                                        .append(text.spaces(width - text.width(fitted)))
                                } else if (
                                    page.modal != null &&
                                        content is NodeContent.TextInput &&
                                        content.labelVisible
                                ) {
                                    text.fitLine(label, node.width ?: NativeGeometry.INPUT_WIDTH)
                                } else label
                            inputs +=
                                RenderInput(
                                    node,
                                    inputLabel,
                                    session.form[node.id] ?: session.state[content.state],
                                )
                        }
                    is NodeContent.Button -> {
                        val button =
                            RenderButton(
                                node,
                                content.skin?.let {
                                    text.buttonLabel(
                                        it,
                                        node.width ?: NativeGeometry.BUTTON_WIDTH,
                                        label,
                                    )
                                } ?: label,
                                node.tooltip?.let { text.render(it, frame.values) },
                                if (frame.enabled[node.id] == true) handles[node.id] else null,
                            )
                        if (content.role == ButtonRole.EXIT) close = button else buttons += button
                    }
                    is NodeContent.Meter,
                    NodeContent.HitArea -> error("Unsupported native component ${node.type}")
                }
            }
        if (errors.isNotEmpty() && page.modal == null) {
            val parts = mutableListOf(text.theme.formError)
            errors.keys.forEach { id ->
                parts += TextSpec(value = "\n")
                parts +=
                    (frame.resolvedNodes[id] ?: page.components.firstOrNull { it.id == id })?.text
                        ?: TextSpec(value = id)
            }
            val label =
                text.render(
                    TextSpec(format = "parts", parts = parts, color = text.theme.palette.error),
                    frame.values,
                )
            bodies += Message(listOf(Fragment(label)), page.width)
        }
        return DialogFrameRenderer.render(
            DialogView(
                text.render(page.title, frame.values),
                bodies,
                inputs,
                buttons,
                close,
                page.columns,
            ),
            page,
            text,
        )
    }
}

/** 绘制与命中独立，按同一作者顺序叠放。 */
object CanvasRenderer {
    const val EDGE_INSET = CanvasGeometry.EDGE_INSET
    const val WRAP_SLACK = CanvasGeometry.WRAP_SLACK
    const val BODY_PADDING = CanvasGeometry.TEXT_PADDING

    private data class Hit(
        val box: Rect,
        val handle: String?,
        val tooltip: ComponentText?,
        val capturesClick: Boolean,
    )

    /** 协议字体别名已合并位移，不重复叠加父级偏移。 */
    private fun textTop(element: JsonElement, inheritedFont: String): Int =
        when {
            element.isJsonPrimitive ->
                if (element.asString.isEmpty()) 0 else minOf(0, TextOffsets.offset(inheritedFont))
            element.isJsonArray ->
                element.asJsonArray.minOfOrNull { textTop(it, inheritedFont) } ?: 0
            else -> {
                val obj = element.asJsonObject
                val font = obj.get("font")?.asString ?: inheritedFont
                val own =
                    if (obj.get("text")?.asString.isNullOrEmpty()) 0
                    else minOf(0, TextOffsets.offset(font))
                minOf(own, obj.getAsJsonArray("extra")?.minOfOrNull { textTop(it, font) } ?: 0)
            }
        }

    fun render(
        session: UiSession,
        frame: Frame,
        text: CompositeText,
        handles: Map<String, String> = emptyMap(),
        errors: Map<String, Any?> = emptyMap(),
    ): DialogView {
        val page = session.current()
        val rows = List(page.height / CanvasGeometry.ROW_HEIGHT) { mutableListOf<Fragment>() }
        val advances = DoubleArray(rows.size)
        val hits = mutableListOf<Hit>()
        fun draw(x: Double, y: Int, component: ComponentText) {
            val row = y / CanvasGeometry.ROW_HEIGHT
            require(row in rows.indices) { "Element exceeds the compiled canvas height" }
            val at = x.roundToInt() + EDGE_INSET
            rows[row] += Fragment(text.spaces(at - advances[row]))
            rows[row] += Fragment(component)
            advances[row] = at + text.width(component)
        }
        rows.indices.forEach { row ->
            draw(0.0, row * CanvasGeometry.ROW_HEIGHT, text.glyph("\ue001", "rafter:fill"))
            draw(
                (page.spec.width - 9).toDouble(),
                row * CanvasGeometry.ROW_HEIGHT,
                text.glyph("\ue001", "rafter:fill"),
            )
        }
        fun paint(scene: List<PositionedNode>): List<Rect> =
            scene
                .filter { frame.visible[it.node.id] == true }
                .map { placed ->
                    val node = frame.resolvedNodes[placed.node.id] ?: placed.node
                    var box = placed.bounds
                    when (val content = node.content) {
                        is NodeContent.Image ->
                            text.imageTiles(content.asset).forEach { tile ->
                                draw((box.x + tile.x).toDouble(), box.y + tile.y, text.tile(tile))
                            }
                        is NodeContent.Meter -> {
                            fun bar(width: Int, color: String) {
                                var x = box.x
                                com.hiusers.rafter.asset.MeterAssets.segments(width).forEach {
                                    segment ->
                                    val request =
                                        com.hiusers.rafter.asset.MeterAssets.request(node, segment)
                                    text.imageTiles(request.id).forEach { tile ->
                                        draw(
                                            (x + tile.x).toDouble(),
                                            node.y / 9 * 9 + tile.y,
                                            text.tile(tile).color(java.awt.Color.decode(color)),
                                        )
                                    }
                                    x += segment
                                }
                            }
                            bar(box.width, content.track)
                            bar(
                                com.hiusers.rafter.asset.MeterAssets.filled(
                                    box.width,
                                    frame.values[content.value],
                                    frame.values[content.maximum],
                                ),
                                content.fill,
                            )
                        }
                        NodeContent.Text ->
                            try {
                                val original =
                                    FormFeedback.label(node, page.spec, frame, text, errors)
                                val label =
                                    if (node.overflow == "clip" && node.width != null)
                                        text.fitLine(original, node.width)
                                    else original
                                require(
                                    '\n' !in label.toPlainText() && '\r' !in label.toPlainText()
                                ) {
                                    "Canvas text must be single line"
                                }
                                val natural = text.bounds(label)
                                box =
                                    LayoutCompiler.textBounds(
                                        page.spec,
                                        node,
                                        natural,
                                        top =
                                            textTop(
                                                JsonParser.parseString(label.toRawMessage()),
                                                text.theme.defaultFont,
                                            ),
                                        heightLimit = page.height,
                                    )
                                // 与校验共用整像素行框，避免小数字宽取整后越界。
                                val inkX = LayoutCompiler.textLeft(node, natural.first)
                                // 在最后覆盖行提交文字，防止后续扫描行覆盖跨行字形。
                                val lastRow =
                                    (node.y + natural.second - 1) / CanvasGeometry.ROW_HEIGHT
                                val anchorY = lastRow * CanvasGeometry.ROW_HEIGHT
                                draw(
                                    inkX.toDouble(),
                                    anchorY,
                                    text.offsetY(label, node.y - anchorY),
                                )
                            } catch (failure: DocumentError) {
                                throw failure
                            } catch (failure: IllegalArgumentException) {
                                val at =
                                    node.origin
                                        ?: SourceLocation(
                                            "project",
                                            "$.pages.${page.spec.id}.elements",
                                            node.id,
                                        )
                                throw DocumentError(
                                    Diagnostic(
                                        at.file,
                                        at.path,
                                        "LAYOUT",
                                        failure.message ?: "Cannot render Canvas text",
                                        node.id,
                                        range = at.range,
                                        origins = node.origins,
                                    )
                                )
                            }
                        NodeContent.HitArea -> Unit
                        else -> error("Unsupported canvas element ${node.type}")
                    }
                    if (
                        node.content == NodeContent.HitArea ||
                            node.clickable ||
                            node.tooltip != null
                    ) {
                        // 禁用的上层区域仍遮挡底层点击；悬停和点击分别处理。
                        val handle = if (frame.enabled[node.id] == true) handles[node.id] else null
                        hits +=
                            Hit(
                                box,
                                handle,
                                node.tooltip?.let { text.render(it, frame.values) },
                                node.clickable,
                            )
                    }
                    box
                }
        val hiddenUnderlay =
            session.popup?.let { page.spec.popups.getValue(it).hideUnderlay }.orEmpty()
        paint(
            page.scene.filter {
                it.node.id !in hiddenUnderlay && it.node.hitTarget !in hiddenUnderlay
            }
        )
        session.popup?.let { key ->
            // 弹层同时拦截悬停和点击，不因上层元素禁用而穿透。
            hits +=
                Hit(
                    Rect(0, 0, page.spec.width, page.height),
                    handles["__popup-dismiss"],
                    null,
                    true,
                )
            val barrier = hits.size
            hits += Hit(Rect(0, 0, 0, 0), null, null, true)
            val boxes = paint(page.popups.getValue(key))
            if (boxes.isNotEmpty()) {
                val left = boxes.minOf { it.x }
                val top = boxes.minOf { it.y }
                hits[barrier] =
                    Hit(
                        Rect(
                            left,
                            top,
                            boxes.maxOf { it.x + it.width } - left,
                            boxes.maxOf { it.y + it.height } - top,
                        ),
                        null,
                        null,
                        true,
                    )
            }
        }
        val fragments = mutableListOf<Fragment>()
        val lineWidth = CanvasGeometry.lineWidth(page.spec.width)
        rows.forEachIndexed { index, painted ->
            fragments += Fragment(text.spaces(EDGE_INSET))
            var cursor = 0
            val active = hits.filter {
                index * CanvasGeometry.ROW_HEIGHT in it.box.y until it.box.y + it.box.height
            }
            val edges =
                (listOf(0, page.spec.width) +
                        active.flatMap { listOf(it.box.x, it.box.x + it.box.width) })
                    .distinct()
                    .sorted()
            edges.zipWithNext().forEach { (left, right) ->
                if (right <= left) return@forEach
                val covers: (Hit) -> Boolean = {
                    left >= it.box.x && right <= it.box.x + it.box.width
                }
                val hoverOwner = active.lastOrNull(covers)
                val clickOwner = active.lastOrNull { it.capturesClick && covers(it) }
                fragments += Fragment(text.spaces(left - cursor))
                val segment = text.spaces(right - left)
                hoverOwner?.tooltip?.let(segment::hoverText)
                fragments += Fragment(segment, clickOwner?.handle)
                cursor = right
            }
            fragments += Fragment(text.spaces(lineWidth - EDGE_INSET - cursor))
            fragments += Fragment(text.spaces(-lineWidth))
            fragments += painted
            fragments += Fragment(text.spaces(lineWidth - advances[index]))
            if (index < rows.lastIndex) fragments += Fragment(text.literal("\n"))
        }
        val native = NativeRenderer.render(session, frame, text, errors, handles)
        val canvas = Message(fragments, CanvasGeometry.bodyWidth(page.spec.width))
        val combined =
            native.copy(
                bodies =
                    when (page.spec.nativeBodyPosition) {
                        NativeBodyPosition.BEFORE_CANVAS -> native.bodies + canvas
                        NativeBodyPosition.AFTER_CANVAS -> listOf(canvas) + native.bodies
                    }
            )
        return NativeModalRenderer.render(session, combined, text)
    }
}

/** 关闭目标由引擎签发，外观来自主题。 */
private fun exit(text: CompositeText, handles: Map<String, String>): RenderButton {
    val label = text.theme.closeLabel
    val node = NodeSpec("__close", NodeContent.Button(actions = listOf(ActionSpec("close"))), label)
    return RenderButton(
        node,
        text.render(label),
        label.hover?.let { text.render(it) },
        handles[node.id],
    )
}
