package com.hiusers.rafter.asset

import com.google.gson.JsonParser
import com.hiusers.rafter.model.NativeGeometry
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** 相同背景尺寸共用位移字体，不按玩家或行复制 TTF。 */
internal object ModalFonts {
    const val VERSION = "native-modal-fonts-v8-ordering-bounds"

    fun manifest(catalog: AssetCatalog): Map<String, Any?> =
        mapOf(
            "version" to VERSION,
            "layouts" to catalog.modalLayouts,
            "backdrops" to catalog.modalBackdrops,
            "frames" to catalog.modalFrames,
            "title-group-width" to ModalEncoding.TITLE_GROUP_WIDTH,
            "title-y" to ModalEncoding.TITLE_Y,
        )

    fun write(stage: Path, catalog: AssetCatalog) {
        if (catalog.modalLayouts.isEmpty() && catalog.modalBackdrops.isEmpty()) return
        val fonts = stage.resolve("assets/rafter/font")
        // 保留 CPU 字形边界；彩色和灰度文字通道均在片元阶段丢弃。
        val bounds = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..1) for (x in 0..1) bounds.setRGB(x, y, 0x01000000)
        check(
            ImageIO.write(
                bounds,
                "png",
                stage.resolve("assets/rafter/textures/font/canvas_bounds.png").toFile(),
            )
        ) {
            "PNG writer unavailable"
        }
        Json.write(
            fonts.resolve("canvas_bounds.json"),
            mapOf(
                "providers" to
                    listOf(
                        mapOf(
                            "type" to "bitmap",
                            "file" to "rafter:font/canvas_bounds.png",
                            "height" to ModalEncoding.BOUNDS_HEIGHT,
                            "ascent" to ModalEncoding.BOUNDS_ASCENT,
                            "chars" to listOf(ModalEncoding.BOUNDS_CHARACTER),
                        )
                    )
            ),
        )
        val thin = BufferedImage(1, 20, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until thin.height) thin.setRGB(0, y, 0xffffffff.toInt())
        check(
            ImageIO.write(
                thin,
                "png",
                stage.resolve("assets/rafter/textures/font/modal_fill_thin.png").toFile(),
            )
        ) {
            "PNG writer unavailable"
        }
        val counterHeight = NativeGeometry.BUTTON_HEIGHT - NativeGeometry.COUNTER_CLEARANCE
        for ((width, name) in listOf(20 to "modal_fill_counter", 1 to "modal_fill_counter_thin")) {
            val image = BufferedImage(width, counterHeight, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, -1)
            check(
                ImageIO.write(
                    image,
                    "png",
                    stage.resolve("assets/rafter/textures/font/$name.png").toFile(),
                )
            )
        }
        // 18×9 纹理含两格，NUL 跳过仅供边界使用的第二格。
        Json.write(
            fonts.resolve("modal_fill.json"),
            mapOf(
                "providers" to
                    listOf(
                        mapOf(
                            "type" to "bitmap",
                            "file" to "rafter:font/fill.png",
                            "height" to 20,
                            "ascent" to 13,
                            "chars" to listOf("\ue000\u0000"),
                        ),
                        mapOf(
                            "type" to "bitmap",
                            "file" to "rafter:font/modal_fill_thin.png",
                            "height" to 20,
                            "ascent" to 13,
                            "chars" to listOf("\ue001"),
                        ),
                        mapOf(
                            "type" to "bitmap",
                            "file" to "rafter:font/modal_fill_counter.png",
                            "height" to counterHeight,
                            "ascent" to 13 - NativeGeometry.COUNTER_CLEARANCE,
                            "chars" to listOf("\ue004"),
                        ),
                        mapOf(
                            "type" to "bitmap",
                            "file" to "rafter:font/modal_fill_counter_thin.png",
                            "height" to counterHeight,
                            "ascent" to 13 - NativeGeometry.COUNTER_CLEARANCE,
                            "chars" to listOf("\ue005"),
                        ),
                    )
            ),
        )
        if (catalog.modalBackdrops.isNotEmpty()) {
            val shift = ModalEncoding.FONT_Y_SHIFT
            (catalog.fontFaces.keys +
                    TextOffsetFonts.aliases(catalog) +
                    listOf("rafter:images", "rafter:fill"))
                .forEach { original ->
                    val font =
                        JsonParser.parseString(
                                Files.readString(
                                    fonts.resolve(original.substringAfter(':') + ".json")
                                )
                            )
                            .asJsonObject
                    font.getAsJsonArray("providers").forEach { entry ->
                        val provider = entry.asJsonObject
                        when (provider.get("type").asString) {
                            "ttf" -> TextOffsetFonts.shift(provider, shift)
                            "bitmap" ->
                                provider.addProperty("ascent", provider.get("ascent").asInt - shift)
                        }
                    }
                    Json.write(
                        fonts.resolve(ModalEncoding.font(original).substringAfter(':') + ".json"),
                        font,
                    )
                }
        }
    }
}
