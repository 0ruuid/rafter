package com.hiusers.rafter

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.model.*
import com.hiusers.rafter.text.CompositeText
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** 编译场景的辅助预览，不是游戏截图。 */
object UiPreview {
    fun render(
        page: CompiledPage,
        catalog: AssetCatalog,
        root: Path,
        output: Path,
        values: Map<BindingSpec, Any?> = emptyMap(),
    ) {
        val scale = 4
        val picture =
            BufferedImage(page.spec.width * scale, page.height * scale, BufferedImage.TYPE_INT_ARGB)
        val g = picture.createGraphics()
        g.scale(scale.toDouble(), scale.toDouble())
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        )
        g.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
        )
        val fonts =
            catalog.fontFaces.mapValues { (_, face) ->
                Font.createFont(Font.TRUETYPE_FONT, face.source.toFile())
                    .deriveFont(face.request.size.toFloat())
            }
        val text = CompositeText(catalog)
        fun caption(component: taboolib.module.chat.ComponentText, x: Double, y: Int) {
            var current = x
            fun run(e: JsonElement, font: String, color: String, bold: Boolean) {
                if (e.isJsonArray) {
                    e.asJsonArray.forEach { run(it, font, color, bold) }
                    return
                }
                if (e.isJsonPrimitive) {
                    val chars = e.asString
                    g.font = fonts[font] ?: fonts.getValue(catalog.theme.defaultFont)
                    if (bold) g.font = g.font.deriveFont(Font.BOLD)
                    g.color = runCatching { Color.decode(color) }.getOrDefault(Color.WHITE)
                    g.drawString(chars, current.toFloat(), (y + 8).toFloat())
                    current += catalog.textWidth(chars, bold, font)
                    return
                }
                val obj = e.asJsonObject
                val face = obj.get("font")?.asString ?: font
                val ink = obj.get("color")?.asString ?: color
                val weight = obj.get("bold")?.asBoolean ?: bold
                obj.get("text")?.let { value ->
                    val chars = value.asString
                    g.font = fonts[face] ?: fonts.getValue(catalog.theme.defaultFont)
                    if (weight) g.font = g.font.deriveFont(Font.BOLD)
                    g.color = runCatching { Color.decode(ink) }.getOrDefault(Color.WHITE)
                    g.drawString(chars, current.toFloat(), (y + 8).toFloat())
                    current += catalog.textWidth(chars, weight, face)
                }
                obj.getAsJsonArray("extra")?.forEach { run(it, face, ink, weight) }
            }
            run(
                JsonParser.parseString(component.toRawMessage()),
                catalog.theme.defaultFont,
                "#ffffff",
                false,
            )
        }
        fun image(id: String, box: Rect) {
            val spec = catalog.imageSpecs.first { it.id == id }
            if (spec.source != null) {
                val bitmap = ImageIO.read(root.resolve(spec.source).toFile())
                g.drawImage(bitmap, box.x, box.y, box.width, box.height, null)
            } else {
                g.color = Color.decode(spec.color)
                g.fillRect(box.x, box.y, box.width, box.height)
            }
        }
        page.scene.forEach { (node, box) ->
            when (val content = node.content) {
                is NodeContent.Image -> image(content.asset, box)
                is NodeContent.Meter -> {
                    g.color = Color.decode(content.track)
                    g.fillRect(box.x, box.y, box.width, box.height)
                    g.color = Color.decode(content.fill)
                    g.fillRect(
                        box.x,
                        box.y,
                        MeterAssets.filled(
                            box.width,
                            values[content.value],
                            values[content.maximum],
                        ),
                        box.height,
                    )
                }
                is NodeContent.Text ->
                    caption(text.render(node.text, values), box.x.toDouble(), box.y)
                is ClickContent -> error("Native controls are not canvas visuals")
                NodeContent.HitArea -> Unit
                is NodeContent.Item -> {
                    g.color = Color.GRAY
                    g.drawRect(box.x, box.y, box.width - 1, box.height - 1)
                    caption(text.literal("item"), box.x.toDouble(), box.y)
                }
                is FormContent -> error("Not a canvas preview component: ${node.type}")
            }
        }
        g.dispose()
        Files.createDirectories(output.parent)
        ImageIO.write(picture, "png", output.toFile())
    }
}
