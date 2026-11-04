package com.hiusers.rafter.text

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.DialogFrameGlyph
import com.hiusers.rafter.asset.ModalBackdropLayout
import com.hiusers.rafter.asset.ModalEncoding
import com.hiusers.rafter.asset.TextOffsets
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.render.Message
import com.hiusers.rafter.runtime.FrozenCanvas
import java.awt.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.module.chat.parseToHexColor

/** 文本保持 TabooLib 组件，仅专用字体解释保留坐标。 */
internal object ModalText {
    private fun spaces(text: CompositeText, output: ComponentText, amount: Double) {
        var left = amount
        while (abs(left) > 8191.0) {
            val part = if (left < 0) -8191 else 8191
            output.append(text.spaces(part))
            left -= part
        }
        if (left != 0.0) output.append(text.spaces(left))
    }

    fun backdrop(
        text: CompositeText,
        snapshot: FrozenCanvas,
        index: Int,
        dim: Double,
    ): ComponentText {
        return paint(
            text,
            snapshot.layout,
            snapshot.title,
            snapshot.titleWidth,
            snapshot.parts,
            index,
            dim,
        )
    }

    fun canvas(
        text: CompositeText,
        title: ComponentText,
        message: Message,
        layout: ModalBackdropLayout,
    ): ComponentText =
        paint(
            text,
            layout,
            title.toRawMessage(),
            text.width(title).roundToInt(),
            message.parts.map { it.text.toRawMessage() },
            text.assets.modalBackdrops.indexOf(layout),
            1.0,
        )

    private fun paint(
        text: CompositeText,
        layout: ModalBackdropLayout,
        title: String,
        titleWidth: Int,
        parts: List<String>,
        index: Int,
        dim: Double,
    ): ComponentText {
        require(index in text.assets.modalBackdrops.indices) {
            "Canvas title layout was not compiled into this pack"
        }
        require(dim in 0.0..1.0)
        fun dimColor(value: String): String {
            val color = Color(value.parseToHexColor())
            return "#%02x%02x%02x"
                .format(
                    (color.red * dim).roundToInt(),
                    (color.green * dim).roundToInt(),
                    (color.blue * dim).roundToInt(),
                )
        }
        fun sanitized(raw: String): ComponentText {
            val tree = JsonParser.parseString(raw)
            fun visit(element: JsonElement) {
                if (element.isJsonArray) element.asJsonArray.forEach(::visit)
                if (!element.isJsonObject) return
                val obj = element.asJsonObject
                listOf("clickEvent", "click_event", "hoverEvent", "hover_event", "insertion")
                    .forEach(obj::remove)
                obj.get("font")?.asString?.let { font ->
                    if (font != "rafter:space") {
                        require(
                            TextOffsets.baseFont(font) in text.assets.fontFaces ||
                                font in setOf("rafter:images", "rafter:fill")
                        ) {
                            "Unsupported font in a frozen Canvas: $font"
                        }
                        obj.addProperty("font", ModalEncoding.font(font))
                    }
                }
                obj.get("color")?.asString?.let { obj.addProperty("color", dimColor(it)) }
                obj.entrySet().forEach { (_, value) -> visit(value) }
            }
            visit(tree)
            val parent =
                JsonObject().apply {
                    addProperty("text", "")
                    addProperty("shadow_color", 0)
                    addProperty(
                        "color",
                        "#%02x%02x%02x"
                            .format(
                                (255 * dim).roundToInt(),
                                (255 * dim).roundToInt(),
                                (255 * dim).roundToInt(),
                            ),
                    )
                    add("extra", JsonArray().apply { add(tree) })
                }
            return Components.empty().append(Components.parseRaw(parent.toString()))
        }
        val output = Components.empty()
        val xBase = ModalEncoding.xBase(index, text.assets.modalBackdrops)
        if (titleWidth > 0) {
            val prefix = -xBase + ModalEncoding.ROW_STRIDE - titleWidth / 2
            spaces(text, output, prefix.toDouble())
            output.append(sanitized(title))
            spaces(text, output, (-prefix - titleWidth).toDouble())
        }
        spaces(text, output, -xBase.toDouble())
        var row = 0
        parts.forEach { raw ->
            if (Components.parseRaw(raw).toPlainText() == "\n") {
                spaces(
                    text,
                    output,
                    -(ModalEncoding.ROW_STRIDE + layout.width + 2).toDouble(),
                )
                row++
            } else output.append(sanitized(raw))
        }
        require(row + 1 == layout.height / 9) { "Backdrop row count changed" }
        spaces(
            text,
            output,
            (xBase + row * ModalEncoding.ROW_STRIDE - layout.width - 2).toDouble(),
        )
        return output
    }

    /** 大逻辑字形让 CPU 排序范围覆盖背景。 */
    fun panel(text: CompositeText, glyph: DialogFrameGlyph): ComponentText {
        val output = Components.empty()
        glyph.characters.forEachIndexed { index, character ->
            val half = glyph.advances[index] / 2
            output.append(text.spaces(-half))
            output.append(text.glyph(character, glyph.font).color(Color.WHITE))
            output.append(text.spaces(half - glyph.advances[index]))
        }
        return output
    }

    /** 无动作单元在原生纹理之后填色，不拥有业务点击。 */
    fun inertCell(
        text: CompositeText,
        width: Int,
        background: String,
        preserveInputCounter: Boolean = false,
    ): ComponentText {
        require(width > 0)
        val output = Components.empty()
        var cursor = 0
        output.append(text.spaces(-width / 2))
        val wide = if (preserveInputCounter) "\ue004" else "\ue000"
        val thin = if (preserveInputCounter) "\ue005" else "\ue001"
        val points =
            (0 until width / 20).map { it * 20 to wide } +
                (width / 20 * 20 until width).map { it to thin }
        points.forEach { (x, character) ->
            output.append(text.spaces(x - cursor))
            output.append(
                text.glyph(character, "rafter:modal_fill").color(Color.decode(background))
            )
            cursor = x + if (character == wide) 21 else 2
        }
        output.append(text.spaces(width / 2 - cursor))
        if (preserveInputCounter) {
            // 覆盖最小按钮，但保留列右侧的原生计数。
            val nativeWidth = NativeGeometry.PAINTED_SPACER_WIDTH
            val left = (width - nativeWidth) / 2 - width / 2
            output.append(text.spaces(left))
            repeat(nativeWidth) {
                output.append(
                    text.glyph("\ue001", "rafter:modal_fill").color(Color.decode(background))
                )
                output.append(text.spaces(-1))
            }
            output.append(text.spaces(-left - nativeWidth))
        }
        return output
    }
}
