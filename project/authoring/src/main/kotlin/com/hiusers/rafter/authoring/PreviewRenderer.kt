package com.hiusers.rafter.authoring

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.FormFeedback
import com.hiusers.rafter.render.Frame
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt
import taboolib.module.chat.ComponentText
import taboolib.module.chat.parseToHexColor

/** 基于编译几何的离线预览，原生控件与视口位置仅作近似。 */
object PreviewRenderer {
    fun write(
        root: Path,
        candidate: Candidate,
        menu: String,
        page: String,
        scenario: PreviewScenario = PreviewScenario(),
    ): Path {
        val context = PreviewContext(candidate, menu, page, scenario)
        return GeneratedFiles.publish(root, "preview", { AuthoringExport.verify(candidate) }) {
            directory ->
            val painter = Painter(root, candidate.assets, context)
            val image = painter.render(false)
            ImageIO.write(image, "png", directory.resolve("preview.png").toFile())
            ImageIO.write(painter.render(true), "png", directory.resolve("regions.png").toFile())
            Json.writeContract(
                directory.resolve("report.json"),
                mapOf(
                    "menu" to menu,
                    "page" to page,
                    "fidelity" to "offline-design-proof-not-minecraft-screenshot",
                    "actions" to "not-executed",
                    "used-stubs" to context.usedStubs.toList(),
                    "approximations" to painter.approximations.toList(),
                    "source-fingerprints" to AuthoringExport.fingerprints(root, candidate),
                ),
            )
            Json.writeContract(
                directory.resolve("layouts.json"),
                com.hiusers.rafter.compile.AuthoringContract.layouts(candidate.snapshot),
            )
        }
    }

    private class Painter(val root: Path, val assets: AssetCatalog, val context: PreviewContext) {
        val approximations =
            linkedSetOf("Client viewport, scrolling, hover and GUI scaling are not simulated")
        private val fonts =
            assets.fontFaces.mapValues { (_, face) ->
                Font.createFont(Font.TRUETYPE_FONT, face.source.toFile())
                    .deriveFont(face.request.size.toFloat())
            }
        private val ascents =
            assets.fontFaces.mapValues { (_, face) ->
                TextOffsetFonts.ascent(face.source) * face.request.size
            }
        private val bitmaps = linkedMapOf<String, BufferedImage>()
        private val text = context.text
        private val page = context.page
        private val nativeSlots = mutableListOf<Pair<NodeSpec, Rect>>()
        private val labels = mutableListOf<Triple<NodeSpec, Rect, Frame>>()
        private val width: Int
        private val height: Int
        private val contentX: Int
        private val contentY: Int

        init {
            val plan = page.modalLayout
            if (plan != null) {
                width = maxOf(plan.bodyWidth, context.backdrop?.first?.spec?.width ?: 0)
                height = maxOf(plan.bodyHeight, context.backdrop?.first?.height ?: 0)
                contentX =
                    (width - CanvasGeometry.bodyWidth(page.spec.width)) / 2 +
                        CanvasGeometry.TEXT_PADDING +
                        CanvasGeometry.EDGE_INSET
                contentY = CanvasGeometry.TEXT_PADDING
                val left = (width - plan.bodyWidth) / 2
                plan.descriptions.forEach { slot ->
                    labels +=
                        Triple(
                            node(slot.id),
                            slot.bounds.copy(x = left + slot.bounds.x),
                            context.frame,
                        )
                }
                plan.inputs.forEach { slot ->
                    nativeSlots +=
                        node(slot.id) to slot.controlBounds.copy(x = left + slot.controlBounds.x)
                    slot.labelBounds?.let {
                        labels += Triple(node(slot.id), it.copy(x = left + it.x), context.frame)
                    }
                }
                plan.buttons
                    .filter { it.target != null }
                    .forEach { cell ->
                        nativeSlots +=
                            node(requireNotNull(cell.target)) to
                                cell.bounds.copy(x = left + cell.bounds.x)
                    }
                approximations +=
                    "Modal controls use the compiled native slots; frozen backdrop viewport centring is approximate"
            } else {
                width =
                    maxOf(
                        page.spec.width,
                        page.spec.components.maxOfOrNull { it.width ?: NativeGeometry.INPUT_WIDTH }
                            ?: 0,
                    )
                contentX = (width - page.spec.width) / 2
                contentY = 0
                var top =
                    if (page.spec.mode == PageMode.CANVAS) page.height + NativeGeometry.BODY_GAP
                    else 0
                // 非模态原生排版由客户端决定，此处仅绘制近似结果。
                if (page.spec.components.isNotEmpty())
                    approximations +=
                        "Non-modal native flow, wrapping and native widget appearance are approximate"
                page.spec.components
                    .filter { context.frame.visible[it.id] == true }
                    .forEach { original ->
                        val node = context.frame.resolvedNodes[original.id] ?: original
                        val itemHeight =
                            when (val content = node.content) {
                                is NodeContent.TextInput -> content.inputHeight
                                is NodeContent.Item -> NativeGeometry.ITEM_HEIGHT
                                NodeContent.Text ->
                                    maxOf(
                                        NativeGeometry.LINE_HEIGHT,
                                        text.bounds(label(node, context.frame)).second,
                                    )
                                is NodeContent.Image -> assets.images.getValue(content.asset).height
                                else -> NativeGeometry.BUTTON_HEIGHT
                            }
                        val box =
                            Rect(
                                (width - (node.width ?: NativeGeometry.INPUT_WIDTH)) / 2,
                                top,
                                node.width ?: NativeGeometry.INPUT_WIDTH,
                                itemHeight,
                            )
                        if (node.content == NodeContent.Text)
                            labels += Triple(node, box, context.frame)
                        else nativeSlots += node to box
                        top += itemHeight + NativeGeometry.BODY_GAP
                    }
                height = maxOf(page.height, top, 1)
            }
        }

        private fun node(id: String) =
            context.frame.resolvedNodes[id] ?: page.spec.nodes.single { it.id == id }

        private fun label(node: NodeSpec, frame: Frame): ComponentText =
            FormFeedback.label(node, page.spec, frame, text, context.scenario.errors)

        fun render(regions: Boolean): BufferedImage {
            val scale = 2
            val image =
                BufferedImage(
                    Math.multiplyExact(width, scale),
                    Math.multiplyExact(height, scale),
                    BufferedImage.TYPE_INT_ARGB,
                )
            val g = image.createGraphics()
            try {
                g.scale(scale.toDouble(), scale.toDouble())
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                )
                g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
                )
                g.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
                )
                context.backdrop?.let { (origin, frame) ->
                    val hidden =
                        context.scenario.originPopup
                            ?.let { origin.spec.popups.getValue(it).hideUnderlay }
                            .orEmpty()
                    paintScene(
                        g,
                        origin.scene.filter {
                            it.node.id !in hidden && it.node.hitTarget !in hidden
                        },
                        frame,
                        (width - origin.spec.width) / 2,
                        0,
                        regions,
                    )
                    context.scenario.originPopup?.let { popup ->
                        paintScene(
                            g,
                            origin.popups.getValue(popup),
                            frame,
                            (width - origin.spec.width) / 2,
                            0,
                            regions,
                        )
                    }
                    g.color = Color(0, 0, 0, ((1 - page.spec.modal!!.dim) * 255).roundToInt())
                    g.fillRect(0, 0, width, height)
                }
                page.spec.modal?.let { modal ->
                    val spec = assets.imageSpecs.single { it.id == modal.asset }
                    image(g, modal.asset, (width - spec.contentWidth) / 2, modal.offsetY)
                }
                val hidden =
                    context.scenario.popup
                        ?.let { page.spec.popups.getValue(it).hideUnderlay }
                        .orEmpty()
                paintScene(
                    g,
                    page.scene.filter { it.node.id !in hidden && it.node.hitTarget !in hidden },
                    context.frame,
                    contentX,
                    contentY,
                    regions,
                )
                context.scenario.popup?.let {
                    paintScene(
                        g,
                        page.popups.getValue(it),
                        context.frame,
                        contentX,
                        contentY,
                        regions,
                    )
                }
                labels.forEach { (node, box, frame) ->
                    caption(g, label(node, frame), box.x.toDouble(), box.y, false)
                }
                nativeSlots.forEach { (node, box) -> widget(g, node, box, regions) }
            } finally {
                g.dispose()
            }
            return image
        }

        private fun paintScene(
            g: Graphics2D,
            scene: List<PositionedNode>,
            frame: Frame,
            dx: Int,
            dy: Int,
            regions: Boolean,
        ) {
            scene
                .filter { frame.visible[it.node.id] == true }
                .forEach { placed ->
                    val node = frame.resolvedNodes[placed.node.id] ?: placed.node
                    val box = placed.bounds.copy(x = placed.bounds.x + dx, y = placed.bounds.y + dy)
                    when (val content = node.content) {
                        is NodeContent.Image ->
                            image(
                                g,
                                content.asset,
                                box.x,
                                dy +
                                    placed.bounds.y / CanvasGeometry.ROW_HEIGHT *
                                        CanvasGeometry.ROW_HEIGHT,
                            )
                        NodeContent.Text -> {
                            val original = label(node, frame)
                            val value =
                                if (node.overflow == "clip" && node.width != null)
                                    text.fitLine(original, requireNotNull(node.width))
                                else original
                            val naturalWidth = text.width(value)
                            val x =
                                dx +
                                    com.hiusers.rafter.compile.LayoutCompiler.textLeft(
                                        node,
                                        ceil(naturalWidth).toInt(),
                                    )
                            caption(g, value, x.toDouble(), dy + node.y, true)
                        }
                        is NodeContent.Meter -> {
                            g.color = Color(content.track.parseToHexColor())
                            g.fillRect(box.x, box.y, box.width, box.height)
                            g.color = Color(content.fill.parseToHexColor())
                            g.fillRect(
                                box.x,
                                box.y,
                                MeterAssets.filled(
                                    box.width,
                                    frame.values[content.value],
                                    frame.values[content.maximum],
                                ),
                                box.height,
                            )
                        }
                        NodeContent.HitArea -> Unit
                        is NodeContent.Item -> widget(g, node, box, regions)
                        else -> error("Unexpected canvas component ${node.type}")
                    }
                    if (
                        regions &&
                            (node.content == NodeContent.HitArea ||
                                node.clickable ||
                                node.tooltip != null)
                    )
                        outline(g, box)
                }
        }

        private fun image(g: Graphics2D, id: String, x: Int, y: Int) {
            val spec = assets.imageSpecs.single { it.id == id }
            val bitmap =
                bitmaps.getOrPut(id) { AssetCompiler.raster(root.resolve(ProjectPaths.UI), spec) }
            g.drawImage(bitmap, x, y, spec.width, spec.height, null)
        }

        private fun outline(g: Graphics2D, box: Rect) {
            val stroke = g.stroke
            g.stroke =
                BasicStroke(
                    1f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    10f,
                    floatArrayOf(3f, 2f),
                    0f,
                )
            g.color = Color(255, 167, 69, 220)
            g.drawRect(box.x, box.y, box.width - 1, box.height - 1)
            g.stroke = stroke
        }

        private fun widget(g: Graphics2D, node: NodeSpec, box: Rect, regions: Boolean) {
            val content = node.content
            when (content) {
                is NodeContent.Image -> image(g, content.asset, box.x, box.y)
                is NodeContent.Item -> {
                    approximations +=
                        "Item ${node.id} (${content.reference}): provider not called; placeholder only"
                    outline(g, box)
                    caption(
                        g,
                        text.literal("[item: ${content.reference}]"),
                        box.x + 3.0,
                        box.y + 3,
                        false,
                    )
                }
                is NodeContent.Button -> {
                    content.skin?.let {
                        image(g, it.request(box.width).id, box.x + it.inset, box.y + it.inset)
                    } ?: outline(g, box)
                    val label = label(node, context.frame)
                    caption(g, label, box.x + (box.width - text.width(label)) / 2, box.y + 4, false)
                }
                is FormContent -> {
                    approximations +=
                        "Native ${node.type} ${node.id}: editable widget shown as wireframe"
                    outline(g, box)
                    val value =
                        context.session.form[node.id] ?: context.session.state[content.state]
                    val shown =
                        when (content) {
                            is NodeContent.Checkbox -> if (value == true) "[x]" else "[ ]"
                            is NodeContent.Select -> value?.toString().orEmpty()
                            is NodeContent.Slider -> "${value ?: content.min} / ${content.max}"
                            else -> value?.toString().orEmpty()
                        }
                    val before = g.clip
                    g.clipRect(box.x, box.y, box.width, box.height)
                    caption(g, text.literal(shown), box.x + 4.0, box.y + 4, false)
                    g.clip = before
                }
                else -> error("Unexpected native component ${node.type}")
            }
            if (regions) outline(g, box)
        }

        private fun caption(
            g: Graphics2D,
            component: ComponentText,
            x: Double,
            y: Int,
            canvas: Boolean,
        ) {
            var current = x
            var line = 0
            fun run(
                element: JsonElement,
                inheritedFont: String,
                ink: String,
                bold: Boolean,
                italic: Boolean,
            ) {
                if (element.isJsonArray) {
                    element.asJsonArray.forEach { run(it, inheritedFont, ink, bold, italic) }
                    return
                }
                if (element.isJsonPrimitive) {
                    run(
                        JsonParser.parseString(Json.gson.toJson(mapOf("text" to element.asString))),
                        inheritedFont,
                        ink,
                        bold,
                        italic,
                    )
                    return
                }
                val obj = element.asJsonObject
                val fontId = obj.get("font")?.asString ?: inheritedFont
                val base = TextOffsets.baseFont(fontId)
                val weight = obj.get("bold")?.asBoolean ?: bold
                val slant = obj.get("italic")?.asBoolean ?: italic
                val color = obj.get("color")?.asString ?: ink
                val font = fonts[base]
                require(!canvas || font != null || obj.get("text")?.asString.isNullOrEmpty()) {
                    "Canvas preview font unavailable: $fontId"
                }
                val actualFont =
                    (font ?: fonts.getValue(assets.theme.defaultFont)).deriveFont(
                        (if (weight) Font.BOLD else Font.PLAIN) or
                            (if (slant) Font.ITALIC else Font.PLAIN)
                    )
                g.font = actualFont
                g.color = Color(color.parseToHexColor())
                val ascent = ascents[base] ?: ascents.getValue(assets.theme.defaultFont)
                obj.get("text")?.asString?.codePoints()?.forEach { code ->
                    if (code == 10) {
                        current = x
                        line += ceil(actualFont.size2D.toDouble()).toInt()
                    } else {
                        val character = String(Character.toChars(code))
                        g.drawString(
                            character,
                            current.toFloat(),
                            (y + line + ascent + TextOffsets.offset(fontId)).toFloat(),
                        )
                        current +=
                            if (font != null) assets.textWidth(character, weight, fontId)
                            else g.fontMetrics.stringWidth(character).toDouble()
                    }
                }
                obj.getAsJsonArray("extra")?.forEach { run(it, fontId, color, weight, slant) }
            }
            run(
                JsonParser.parseString(component.toRawMessage()),
                assets.theme.defaultFont,
                "#ffffff",
                false,
                false,
            )
        }
    }
}
