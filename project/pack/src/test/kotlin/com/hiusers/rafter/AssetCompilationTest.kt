package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AssetCompilationTest {
    @TempDir lateinit var temp: Path

    /** 测试仅在资源类路径上复用宿主默认配置。 */
    @Test
    fun hostThemeAndFontsRemainAvailableToPackFixtures() {
        val theme = UiThemes.compile(UiThemes.document(temp))
        assertEquals(FontRequest("rafter-mono", 10.0), theme.defaultRequest)
        for (name in
            listOf("core/ui/theme.yml", "ui/fonts/${theme.defaultFamily}.ttf", "shaders/gui.vsh")) {
            val resources = java.util.Collections.list(javaClass.classLoader.getResources(name))
            assertEquals(1, resources.size, "Expected one resource owner for $name")
            resources.single().openStream().use { assertTrue(it.read() >= 0, name) }
        }
    }

    @Test
    fun imageLedgerStillRejectsInvalidAndDuplicateCodepointsAfterHeldCleanup() {
        for (invalid in
            listOf(
                mapOf("rafter:low" to 0xe2ff),
                mapOf("rafter:high" to 0xf800),
                mapOf("rafter:a" to 0xe300, "rafter:b" to 0xe300),
                mapOf("heldish:retained" to 0xf0000),
            )) {
            assertFailsWith<IllegalArgumentException> {
                GlyphAllocations(invalid + mapOf("held:ignored" to -1))
            }
        }
        val boundary = mapOf("rafter:first" to 0xe300, "rafter:last" to 0xf7ff)
        val ledger = GlyphAllocations(boundary + mapOf("held:ignored" to 0xf0000))
        assertEquals(boundary, ledger.entries)
        assertEquals(0xe300, ledger.image("rafter:first"))
        assertEquals(0xf7ff, ledger.image("rafter:last"))
        assertFailsWith<IllegalArgumentException> { ledger.image("rafter:overflow") }
    }

    @Test
    fun imageCellPaddingPreservesHighDensityPixelsWithoutResampling() {
        val file = temp.resolve("pixel@4x.png")
        val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xff123456.toInt())
        image.setRGB(63, 63, 0xffabcdef.toInt())
        javax.imageio.ImageIO.write(image, "png", file.toFile())
        val padded = AssetCompiler.imageFromFile(file, 72, 72, 4)
        assertEquals(72, padded.width)
        assertEquals(0xff123456.toInt(), padded.getRGB(0, 0))
        assertEquals(0xffabcdef.toInt(), padded.getRGB(63, 63))
        assertEquals(0, padded.getRGB(64, 64))
    }

    @Test
    fun oversizedImagesAreSlicedAndReassembledWithoutPixelResampling() {
        for ((scale, width, height) in listOf(Triple(1, 630, 648), Triple(8, 864, 3024))) {
            val root = temp.resolve("source-$scale")
            java.nio.file.Files.createDirectories(root.resolve("fonts"))
            for (name in listOf("fonts/rafter-mono.ttf")) {
                val input =
                    javaClass.getResourceAsStream("/core/ui/$name") ?: error("Missing font fixture")
                java.nio.file.Files.createDirectories(root.resolve(name).parent)
                input.use { java.nio.file.Files.copy(it, root.resolve(name)) }
            }
            val file = root.resolve("images/large@${scale}x.png")
            java.nio.file.Files.createDirectories(file.parent)
            val source =
                java.awt.image.BufferedImage(
                    width,
                    height,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB,
                )
            for (y in 0 until height) {
                val row =
                    IntArray(width) { x ->
                        0xff000000.toInt() or
                            ((x and 255) shl 16) or
                            ((y and 255) shl 8) or
                            ((x xor y) and 255)
                    }
                source.setRGB(0, y, width, 1, row, 0, width)
            }
            javax.imageio.ImageIO.write(source, "png", file.toFile())
            val assets = AssetCompiler.compile(root, AutoAssets.document(root))
            val glyph = assets.images.getValue("rafter:large")
            assertEquals(width / scale, glyph.width)
            assertEquals(height / scale, glyph.height)
            assertTrue(glyph.height > 360)
            assertTrue(glyph.tiles.size > 1)
            val pack = AssetCompiler.build(root, assets, temp.resolve("output-$scale"))
            val providers =
                Json.gson
                    .fromJson(
                        java.nio.file.Files.readString(
                            pack.resolve("assets/rafter/font/images.json")
                        ),
                        com.google.gson.JsonObject::class.java,
                    )
                    .getAsJsonArray("providers")
                    .associate { entry ->
                        val item = entry.asJsonObject
                        item.getAsJsonArray("chars")[0].asString to item
                    }
            val stitched =
                java.awt.image.BufferedImage(
                    width,
                    height,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB,
                )
            for (tile in glyph.tiles) {
                val provider = providers.getValue(tile.character)
                assertEquals(tile.height, provider.get("height").asInt)
                val texture =
                    pack.resolve(
                        "assets/rafter/textures/" +
                            provider.get("file").asString.substringAfter(':')
                    )
                val bitmap = javax.imageio.ImageIO.read(texture.toFile())
                assertTrue(bitmap.width <= 256 && bitmap.height <= 256)
                assertEquals(tile.width * scale, bitmap.width)
                assertEquals(tile.height * scale, bitmap.height)
                val graphics = stitched.createGraphics()
                try {
                    graphics.drawImage(bitmap, tile.x * scale, tile.y * scale, null)
                } finally {
                    graphics.dispose()
                }
            }
            for (y in 0 until height) assertContentEquals(
                source.getRGB(0, y, width, 1, null, 0, width),
                stitched.getRGB(0, y, width, 1, null, 0, width),
                "scale=$scale row=$y",
            )
            for (row in glyph.tiles.groupBy { it.y }.values) {
                assertEquals(0, row.first().x)
                row.zipWithNext().forEach { (left, right) ->
                    assertEquals(left.x + left.width, right.x)
                    assertEquals(
                        -1,
                        right.x - left.x - left.advance,
                    ) // 抵消原生字形额外的 1px 步进。
                }
                assertEquals(glyph.width, row.last().x + row.last().width)
            }
        }
    }
}
