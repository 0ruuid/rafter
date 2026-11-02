package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImageRasterTest {
    @TempDir lateinit var temp: Path
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()

    @Test
    fun singleSideSizingPreservesDensityNearestColorsAndTransparentPadding() {
        val source = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..1) for (x in 0..3) source.setRGB(x, y, if (x < 2) red else blue)
        val before = source.getRGB(0, 0, 4, 2, null, 0, 4)
        val plan = ImageSizing.resolve(ImageRequest("rafter:pixel", width = 6), 2, 1)
        assertEquals(6, plan.width)
        assertEquals(3, plan.height)
        val bitmap = ImageRaster.render(source, null, plan, 2)
        assertEquals(18, bitmap.width)
        assertEquals(18, bitmap.height)
        for (y in 0 until 18) for (x in 0 until 18) {
            val expected = if (y !in 0..5 || x !in 0..11) 0 else if (x < 6) red else blue
            assertEquals(expected, bitmap.getRGB(x, y), "$x,$y")
        }
        assertContentEquals(before, source.getRGB(0, 0, 4, 2, null, 0, 4))
    }

    @Test
    fun containCoverStretchAndGeneratedColorKeepBoxAndCellPaddingSeparate() {
        val source = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..1) for (x in 0..3) source.setRGB(x, y, if (x < 2) red else blue)
        for (fit in listOf("contain", "cover", "stretch")) {
            val plan = ImageSizing.resolve(ImageRequest("rafter:pixel", 4, 4, fit), 4, 2)
            for (solid in listOf(false, true)) {
                val bitmap =
                    ImageRaster.render(
                        if (solid) null else source,
                        if (solid) "#ff0000" else null,
                        plan,
                        1,
                    )
                for (y in 0 until 9) for (x in 0 until 9) {
                    val inside = x in 0..3 && y in (if (fit == "contain") 1..2 else 0..3)
                    assertEquals(
                        if (!inside) 0 else if (solid || x < 2) red else blue,
                        bitmap.getRGB(x, y),
                        "$fit/$solid/$x,$y",
                    )
                }
            }
        }
    }

    @Test
    fun extremeCoverSamplesFinalBoxWithoutAllocatingScaledIntermediate() {
        val source = BufferedImage(1, 1024, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 1024) source.setRGB(0, y, red)
        val plan = ImageSizing.resolve(ImageRequest("rafter:tall", 864, 1, "cover"), 1, 1024)
        assertEquals(
            884736,
            plan.scaledHeight,
        ) // 放大中间图将超过 7.64 亿像素。
        val bitmap = ImageRaster.render(source, null, plan, 1)
        assertEquals(864, bitmap.width)
        assertEquals(9, bitmap.height)
        assertEquals(red, bitmap.getRGB(432, 0))
        assertEquals(0, bitmap.getRGB(432, 1))
    }

    @Test
    fun requestsReuseIdsOnlyGenerateRequestedVariantsAndKeepOriginalPngUnchanged() {
        val root = fixture()
        val document = AutoAssets.document(root)
        val a = ImageRequest("rafter:pixel", width = 6)
        val same = ImageRequest("rafter:pixel", width = 6)
        val b = ImageRequest("rafter:pixel", height = 6)
        assertEquals(a.id, same.id)
        assertNotEquals(a.id, b.id)
        val original = AssetCompiler.compile(root, document)
        val sized = AssetCompiler.compile(root, document, imageRequests = setOf(a, same, b))
        val reordered = AssetCompiler.compile(root, document, imageRequests = linkedSetOf(b, a))
        assertEquals(original.images.keys + setOf(a.id, b.id), sized.images.keys)
        assertEquals(sized.fingerprint, reordered.fingerprint)
        assertNotEquals(original.fingerprint, sized.fingerprint)
        assertNotEquals(
            sized.fingerprint,
            AssetCompiler.compile(root, document, imageRequests = setOf(a)).fingerprint,
        )
        assertEquals(
            original.images.getValue("rafter:pixel"),
            sized.images.getValue("rafter:pixel"),
        )
        assertEquals(original.imageSpecs.single(), sized.imageSpecs.first())
        val spec = sized.imageSpecs.first { it.id == a.id }
        assertEquals(6, spec.contentWidth)
        assertEquals(3, spec.contentHeight)
        assertNotNull(spec.transform)
        val before = AssetCompiler.build(root, original, temp.resolve("original"))
        val after = AssetCompiler.build(root, sized, temp.resolve("sized"))
        val texture = "assets/rafter/textures/font/images/pixel_0_0.png"
        assertContentEquals(
            Files.readAllBytes(before.resolve(texture)),
            Files.readAllBytes(after.resolve(texture)),
        )
        for (image in sized.imageSpecs) for (tile in sized.images.getValue(image.id).tiles) {
            assertEquals(9, tile.height)
            val file =
                after.resolve(
                    "assets/rafter/textures/font/images/${image.id.substringAfter(':')}_${tile.x}_${tile.y}.png"
                )
            val bitmap = ImageIO.read(file.toFile())
            assertTrue(bitmap.width <= 256 && bitmap.height <= 256)
            assertEquals(tile.width * image.scale, bitmap.width)
            assertEquals(tile.height * image.scale, bitmap.height)
        }
        val manifest = Json.map(after.resolve("manifest.json"))
        val entry = (manifest.getValue("images") as Map<*, *>)[a.id] as Map<*, *>
        assertEquals("images/pixel@2x.png", entry["source"])
        assertEquals(6.0, entry["content-width"])
        assertNotNull(entry["transform"])
    }

    @Test
    fun staleMetadataSourceMutationAndRealIdCollisionAreRejected() {
        val root = fixture()
        val document = AutoAssets.document(root)
        val request = ImageRequest("rafter:pixel", width = 6)
        val candidate = AssetCompiler.compile(root, document, imageRequests = setOf(request))
        @Suppress("UNCHECKED_CAST")
        val images = document.getValue("images") as Map<String, Map<String, Any?>>
        val collision =
            document + ("images" to (images + (request.id to images.getValue("rafter:pixel"))))
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(root, collision, imageRequests = setOf(request))
        }
        val changed =
            BufferedImage(6, 2, BufferedImage.TYPE_INT_ARGB) // 仍容纳于同一 9px 单元。
        ImageIO.write(changed, "png", root.resolve("images/pixel@2x.png").toFile())
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(root, document, imageRequests = setOf(request))
        }
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.build(root, candidate, temp.resolve("stale"))
        }
        val plan = ImageSizing.resolve(request, 2, 1)
        assertFailsWith<IllegalArgumentException> { ImageRaster.render(changed, null, plan, 2) }
    }

    @Test
    fun everyTopPaddingPreservesAllPixelsAtEveryDensityAndFit() {
        for (scale in listOf(1, 2, 8)) {
            val source = BufferedImage(4 * scale, 2 * scale, BufferedImage.TYPE_INT_ARGB)
            // 保留作者透明区域，包括旧素材留白。
            for (y in 0 until source.height) for (x in 0 until source.width) {
                source.setRGB(x, y, if (y == 0) 0 else if (x < 2 * scale) red else blue)
            }
            val requests =
                listOf(
                    ImageRequest("rafter:pixel", width = 6),
                    ImageRequest("rafter:pixel", height = 4),
                ) +
                    listOf("contain", "cover", "stretch").map {
                        ImageRequest("rafter:pixel", 5, 5, it)
                    }
            for (request in requests) for (solid in listOf(false, true)) {
                val baselinePlan = ImageSizing.resolve(request, 4, 2)
                val baseline =
                    ImageRaster.render(
                        if (solid) null else source,
                        if (solid) "#ff0000" else null,
                        baselinePlan,
                        scale,
                    )
                for (padding in 0..8) {
                    val plan = ImageSizing.resolve(request.copy(topPadding = padding), 4, 2)
                    val bitmap =
                        ImageRaster.render(
                            if (solid) null else source,
                            if (solid) "#ff0000" else null,
                            plan,
                            scale,
                        )
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val visualY = y - padding * scale
                        val expected =
                            if (visualY in 0 until plan.height * scale) baseline.getRGB(x, visualY)
                            else 0
                        assertEquals(
                            expected,
                            bitmap.getRGB(x, y),
                            "${request.fit}/$scale/$padding/$solid/$x,$y",
                        )
                    }
                }
            }
            val unsized = ImageSizing.resolve(ImageRequest("rafter:pixel", topPadding = 8), 4, 2)
            val bitmap = ImageRaster.render(source, null, unsized, scale)
            assertContentEquals(
                source.getRGB(0, 0, source.width, source.height, null, 0, source.width),
                bitmap.getRGB(0, 8 * scale, source.width, source.height, null, 0, source.width),
            )
        }
    }

    @Test
    fun paddedAssetsTileAllRowsUnderGenerateWithoutChangingAuthoredFiles() {
        val root = fixture()
        val source = root.resolve("images/pixel@2x.png")
        val before = Files.readAllBytes(source)
        val requests = (1..8).map { ImageRequest("rafter:pixel", topPadding = it) }.toSet()
        val catalog =
            AssetCompiler.compile(root, AutoAssets.document(root), imageRequests = requests)
        val pack = AssetCompiler.build(root, catalog, temp.resolve("generate"))
        assertContentEquals(before, Files.readAllBytes(source))
        Files.list(root.resolve("images")).use { assertEquals(listOf(source), it.toList()) }
        val manifest = Json.map(pack.resolve("manifest.json"))
        for (request in requests) {
            val spec = catalog.imageSpecs.single { it.id == request.id }
            val plan = assertNotNull(spec.transform)
            assertEquals(request.topPadding, plan.topPadding)
            assertEquals(1 + request.topPadding, spec.contentHeight)
            val tiles = catalog.images.getValue(request.id).tiles
            assertEquals(plan.layoutHeight / 9, tiles.size)
            val entry = (manifest.getValue("images") as Map<*, *>)[request.id] as Map<*, *>
            assertEquals(
                request.topPadding.toDouble(),
                (entry["transform"] as Map<*, *>)["top-padding"],
            )
            for (tile in tiles) {
                val texture =
                    pack.resolve(
                        "assets/rafter/textures/font/images/${request.id.substringAfter(':')}_${tile.x}_${tile.y}.png"
                    )
                val bitmap = ImageIO.read(texture.toFile())
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val visualY = tile.y * spec.scale + y - request.topPadding * spec.scale
                    val expected = if (visualY !in 0..1) 0 else if (x < 2) red else blue
                    // alpha=1 的图集宽度标记属于传输元数据，不是可见像素。
                    val actual = bitmap.getRGB(x, y).let { if (it ushr 24 == 1) 0 else it }
                    assertEquals(expected, actual, "${request.topPadding}/$x/$y")
                }
            }
        }
    }

    private fun fixture(): Path {
        val root = temp.resolve("ui")
        Files.createDirectories(root.resolve("fonts"))
        for (name in listOf("fonts/rafter-mono.ttf")) {
            Files.createDirectories(root.resolve(name).parent)
            requireNotNull(javaClass.getResourceAsStream("/core/ui/$name")).use {
                Files.copy(it, root.resolve(name))
            }
        }
        Files.createDirectories(root.resolve("images"))
        val source = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..1) for (x in 0..3) source.setRGB(x, y, if (x < 2) red else blue)
        ImageIO.write(source, "png", root.resolve("images/pixel@2x.png").toFile())
        return root
    }
}
