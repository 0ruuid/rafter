package com.hiusers.rafter.asset

import com.hiusers.rafter.model.Values
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import javax.imageio.ImageIO

/** 专用位图传输，边缘元数据不作为美术采样。 */
internal object DialogFrames {
    const val EDGE = 256
    const val GUTTER = 4
    const val CONTENT = EDGE - GUTTER * 2
    // height=4096、ascent=7 使原生排序覆盖整个背景。
    // 保留传入 Position.z，不替换为固定深度。
    const val LOGICAL_HEIGHT = 4096
    const val ADVANCE = LOGICAL_HEIGHT + 1
    const val SIGNATURE = 0xffa7d35b.toInt()
    const val VERSION = 0xff0204f8.toInt() // 协议版本 2，边框留白 4，载荷最大边长 248。

    data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)

    fun tiles(spec: ImageSpec): List<Tile> {
        val width = spec.contentWidth * spec.scale
        val height = spec.contentHeight * spec.scale
        require(width in 1..65535 && height in 1..65535) {
            "Dialog frame source exceeds 16-bit dimensions"
        }
        return buildList {
            for (y in 0 until height step CONTENT) for (x in 0 until width step CONTENT) {
                add(Tile(x, y, minOf(CONTENT, width - x), minOf(CONTENT, height - y)))
            }
        }
    }

    private fun original(specs: List<ImageSpec>, id: String) =
        requireNotNull(specs.firstOrNull { it.id == id && it.transform == null }) {
            "Dialog frame needs an original image asset: $id"
        }

    private fun glyph(spec: ImageSpec, font: String): DialogFrameGlyph {
        val count = tiles(spec).size
        require(count <= 0x1900) { "Too many dialog frame tiles for ${spec.id}" }
        return DialogFrameGlyph(
            font,
            Values.list((0 until count).map { Character.toString(0xe000 + it) }),
            Values.list(List(count) { ADVANCE }),
        )
    }

    fun compile(specs: List<ImageSpec>, assets: Set<String>): Map<String, DialogFrameGlyph> =
        Collections.unmodifiableMap(
            assets.sorted().associateWith { id ->
                glyph(original(specs, id), "rafter:dialog_frames/${id.substringAfter(':')}")
            }
        )

    fun compileModal(
        specs: List<ImageSpec>,
        layouts: List<NativeModalLayout>,
    ): Map<String, DialogFrameGlyph> {
        require(layouts.size <= 65536 && layouts.map { it.key }.distinct().size == layouts.size) {
            "Modal frame layout keys must be unique and fit the 16-bit index"
        }
        return Collections.unmodifiableMap(
            layouts.associate { layout ->
                val spec = original(specs, layout.asset)
                require(
                    layout.key.isNotBlank() &&
                        layout.width == spec.contentWidth &&
                        layout.height == spec.contentHeight &&
                        layout.bodyWidth > 0 &&
                        layout.bodyHeight > 0
                ) {
                    "Invalid modal frame geometry: ${layout.key}"
                }
                require(layout.background.matches(Regex("#[0-9a-fA-F]{6}"))) {
                    "Invalid modal background"
                }
                require(
                    layout.clear.all {
                        it.x >= 0 &&
                            it.y >= 0 &&
                            it.width > 0 &&
                            it.height > 0 &&
                            it.x.toLong() + it.width <= layout.width &&
                            it.y.toLong() + it.height <= layout.height
                    }
                ) {
                    "Modal clear rectangle exceeds source: ${layout.key}"
                }
                val key =
                    MessageDigest.getInstance("SHA-256")
                        .digest(layout.key.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                layout.key to glyph(spec, "rafter:modal_frames/$key")
            }
        )
    }

    fun writeModal(
        stage: Path,
        glyph: DialogFrameGlyph,
        spec: ImageSpec,
        image: BufferedImage,
        layout: NativeModalLayout,
        index: Int,
    ) {
        // 不修改作者原 PNG 或普通图片提供器。
        val cleared = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        cleared.setRGB(
            0,
            0,
            image.width,
            image.height,
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
            0,
            image.width,
        )
        val graphics = cleared.createGraphics()
        try {
            graphics.color = Color.decode(layout.background)
            layout.clear.forEach {
                graphics.fillRect(
                    it.x * spec.scale,
                    it.y * spec.scale,
                    it.width * spec.scale,
                    it.height * spec.scale,
                )
            }
        } finally {
            graphics.dispose()
        }
        write(stage, glyph, spec, cleared, kind = 1, layoutIndex = index)
    }

    fun write(
        stage: Path,
        glyph: DialogFrameGlyph,
        spec: ImageSpec,
        image: BufferedImage,
        kind: Int = 0,
        layoutIndex: Int = 0,
    ) {
        require(kind in 0..1 && layoutIndex in 0..65535)
        val tiles = tiles(spec)
        require(
            glyph.characters.size == tiles.size && glyph.advances == List(tiles.size) { ADVANCE }
        )
        val width = spec.contentWidth * spec.scale
        val height = spec.contentHeight * spec.scale
        require(image.width >= width && image.height >= height)
        val id = glyph.font.substringAfter(':')
        val providers = tiles.mapIndexed { index, tile ->
            // 固定方格保证末块不足或 1px 原图仍有可复现的 4097px 步进，四角标记保留逻辑边界。
            val bitmap = BufferedImage(EDGE, EDGE, BufferedImage.TYPE_INT_ARGB)
            for (corner in 0..3) bitmap.setRGB(
                (corner and 1) * (EDGE - 1),
                (corner shr 1) * (EDGE - 1),
                0xff524600.toInt() or corner,
            )
            bitmap.setRGB(0, 2, SIGNATURE)
            bitmap.setRGB(1, 2, VERSION)
            listOf(width, height, tile.x, tile.y, tile.width, tile.height, kind, layoutIndex)
                .forEachIndexed { field, value ->
                    // 16 位字段以 R 存低字节、G 存高字节。
                    bitmap.setRGB(
                        field + 2,
                        2,
                        0xff000000.toInt() or
                            ((value and 255) shl 16) or
                            (((value ushr 8) and 255) shl 8),
                    )
                }
            bitmap.setRGB(
                GUTTER,
                GUTTER,
                tile.width,
                tile.height,
                image.getRGB(tile.x, tile.y, tile.width, tile.height, null, 0, tile.width),
                0,
                tile.width,
            )
            val file = "font/$id/$index.png"
            val target = stage.resolve("assets/rafter/textures/$file")
            Files.createDirectories(target.parent)
            check(ImageIO.write(bitmap, "png", target.toFile())) { "PNG writer unavailable" }
            mapOf(
                "type" to "bitmap",
                "file" to "rafter:$file",
                "height" to LOGICAL_HEIGHT,
                "ascent" to AssetCompiler.GLYPH_ASCENT,
                "chars" to listOf(glyph.characters[index]),
            )
        }
        Json.write(stage.resolve("assets/rafter/font/$id.json"), mapOf("providers" to providers))
    }
}
