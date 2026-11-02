package com.hiusers.rafter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.model.ButtonSkinSpec
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ButtonSkinFontsTest {
    @TempDir lateinit var temp: Path
    private val full = ButtonSkinSpec("rafter:pixel", 0).request(80)
    private val inner = ButtonSkinSpec("rafter:pixel").request(80)
    private val solid = ButtonSkinSpec("rafter:solid").request(60)
    private val skins = linkedMapOf(full to 0, inner to 1, solid to 1)
    private val red = 0xff123456.toInt()
    private val blue = 0xffabcdef.toInt()

    @Test
    fun skinRequestsCompileSizedInteriorsWithNormalTileAdvances() {
        val (root, document) = fixture()
        val ordinary = AssetCompiler.compile(root, document)
        val extra = ImageRequest("rafter:pixel", 9, 9, "stretch")
        val catalog =
            AssetCompiler.compile(root, document, imageRequests = setOf(extra), buttonSkins = skins)
        assertEquals(
            ordinary.images.keys + skins.keys.map { it.id } + extra.id,
            catalog.images.keys,
        )
        assertEquals(skins.mapKeys { it.key.id }, catalog.buttonSkins)
        assertEquals(ordinary.sourceDimensions(), catalog.sourceDimensions())
        assertEquals(
            ordinary.images.getValue("rafter:pixel"),
            catalog.images.getValue("rafter:pixel"),
        )
        assertEquals(9, catalog.images.getValue(extra.id).tiles.single().height)
        for ((request, inset) in skins) {
            val spec = catalog.imageSpecs.single { it.id == request.id }
            val plan = assertNotNull(spec.transform)
            assertEquals(request.width, spec.contentWidth)
            assertEquals(20 - 2 * inset, spec.contentHeight)
            assertEquals((spec.contentWidth + 8) / 9 * 9, plan.layoutWidth)
            assertEquals(if (inset == 0) 27 else 18, plan.layoutHeight)
            assertEquals(0, plan.offsetX)
            assertEquals(0, plan.offsetY)
            val glyph = catalog.images.getValue(request.id)
            assertEquals(spec.width to spec.height, glyph.width to glyph.height)
            if (spec.scale == 8)
                assertTrue(glyph.tiles.size > 1, "Wide @8x skins still slice horizontally")
            for (tile in glyph.tiles) {
                assertEquals(0, tile.y)
                assertEquals(spec.height, tile.height)
                assertEquals(tile.width + 1, tile.advance)
                assertEquals(
                    tile.advance.toDouble(),
                    catalog.textWidth(tile.character, font = "rafter:images"),
                )
                assertEquals(
                    tile.advance.toDouble(),
                    catalog.textWidth(tile.character, font = "rafter:button_skins/$inset"),
                )
                assertEquals(
                    tile.advance + 1.0,
                    catalog.textWidth(
                        tile.character,
                        bold = true,
                        font = "rafter:button_skins/$inset",
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            catalog.textWidth(
                catalog.images.getValue(extra.id).character,
                font = "rafter:button_skins/1",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            catalog.textWidth(
                catalog.images.getValue(full.id).character,
                font = "rafter:button_skins/1",
            )
        }
    }

    @Test
    fun requestedFontsReuseImagePngsWithNativeLabelAscentAndNoWidgetOverrides() {
        val (root, document) = fixture()
        val source = Files.readAllBytes(root.resolve("images/pixel@8x.png"))
        val ordinary = AssetCompiler.compile(root, document)
        val before = AssetCompiler.build(root, ordinary, temp.resolve("ordinary"))
        assertFalse(Files.exists(before.resolve("assets/rafter/font/button_skins")))
        val catalog = AssetCompiler.compile(root, document, buttonSkins = skins)
        val pack = AssetCompiler.build(root, catalog, temp.resolve("skins"))
        assertContentEquals(source, Files.readAllBytes(root.resolve("images/pixel@8x.png")))
        for (id in ordinary.images.keys) {
            val file = "assets/rafter/textures/font/images/${id.substringAfter(':')}_0_0.png"
            assertContentEquals(
                Files.readAllBytes(before.resolve(file)),
                Files.readAllBytes(pack.resolve(file)),
            )
        }
        val names =
            Files.list(pack.resolve("assets/rafter/font/button_skins")).use { paths ->
                paths.map { it.fileName.toString() }.toList().toSet()
            }
        assertEquals(setOf("0.json", "1.json"), names)
        assertFalse(Files.exists(pack.resolve("assets/minecraft/textures/gui/sprites/widget")))
        val textureCount =
            Files.walk(pack.resolve("assets/rafter/textures/font/images")).use { paths ->
                paths.filter { Files.isRegularFile(it) }.count()
            }
        assertEquals(catalog.images.values.sumOf { it.tiles.size }.toLong(), textureCount)
        val images = providers(pack.resolve("assets/rafter/font/images.json"))
        for ((inset, requests) in skins.entries.groupBy { it.value }) {
            val font = providers(pack.resolve("assets/rafter/font/button_skins/$inset.json"))
            val expected =
                requests
                    .flatMap { catalog.images.getValue(it.key.id).tiles }
                    .map { it.character }
                    .toSet()
            assertEquals(expected, font.keys)
            for ((request, _) in requests) {
                val spec = catalog.imageSpecs.single { it.id == request.id }
                for (tile in catalog.images.getValue(request.id).tiles) {
                    val provider = font.getValue(tile.character)
                    val ascent = AssetCompiler.GLYPH_ASCENT + 6 - inset - tile.y
                    val reused =
                        images.getValue(tile.character).deepCopy().apply {
                            addProperty("ascent", ascent)
                        }
                    assertEquals(
                        reused,
                        provider,
                        "Only ascent changes; chars, file and height are reused",
                    )
                    assertEquals(tile.height, provider.get("height").asInt)
                    assertEquals(ascent, provider.get("ascent").asInt)
                    assertTrue(
                        ascent <= tile.height,
                        "Vanilla bitmap provider requires ascent <= height",
                    )
                    assertEquals(inset + tile.y, 6 + AssetCompiler.GLYPH_ASCENT - ascent)
                    val texture =
                        pack.resolve(
                            "assets/rafter/textures/" +
                                provider.get("file").asString.substringAfter(':')
                        )
                    val bitmap = ImageIO.read(texture.toFile())
                    assertEquals(tile.width * spec.scale, bitmap.width)
                    assertEquals(tile.height * spec.scale, bitmap.height)
                    assertTrue(bitmap.width <= 256 && bitmap.height <= 256)
                    assertTrue(
                        bitmap
                            .getRGB(0, 0, bitmap.width, bitmap.height, null, 0, bitmap.width)
                            .all { it == 0 || it == red || it == blue },
                        "Nearest sampling must not introduce blended colors",
                    )
                    val rightmost =
                        (bitmap.width - 1 downTo 0).first { x ->
                            (0 until bitmap.height).any { y -> bitmap.getRGB(x, y) ushr 24 != 0 }
                        }
                    val advance =
                        ((rightmost + 1).toDouble() * tile.height / bitmap.height).roundToInt() + 1
                    assertEquals(
                        tile.advance,
                        advance,
                        "Reused bitmap retains its normal client advance",
                    )
                }
            }
        }
        val manifest = Json.map(pack.resolve("manifest.json")).getValue("button-skins") as Map<*, *>
        assertEquals(ButtonSkinFonts.VERSION, manifest["version"])
        assertEquals(6.0, manifest["label-baseline-offset"])
        assertEquals(catalog.buttonSkins.mapValues { it.value.toDouble() }, manifest["requests"])
    }

    @Test
    fun skinRequestsAndFontVersionParticipateInStableFingerprintAndManifest() {
        val (root, document) = fixture()
        val requests = skins.keys.toSet()
        val ordinary = AssetCompiler.compile(root, document, imageRequests = requests)
        val catalog = AssetCompiler.compile(root, document, buttonSkins = skins)
        val reordered =
            AssetCompiler.compile(
                root,
                document,
                imageRequests = requests.reversed().toSet(),
                buttonSkins = skins.entries.reversed().associate { it.key to it.value },
            )
        val fewer =
            AssetCompiler.compile(
                root,
                document,
                imageRequests = requests,
                buttonSkins = skins - full,
            )
        assertEquals(ordinary.images.keys, catalog.images.keys)
        assertNotEquals(ordinary.fingerprint, catalog.fingerprint)
        assertNotEquals(fewer.fingerprint, catalog.fingerprint)
        assertEquals(catalog.fingerprint, reordered.fingerprint)
        assertEquals(catalog.allocation, reordered.allocation)
        assertEquals(catalog.fingerprint, catalog.sourceFingerprint)
        val manifest = catalog.manifest().getValue("button-skins") as Map<*, *>
        assertEquals(ButtonSkinFonts.VERSION, manifest["version"])
        assertEquals(AssetCompiler.GLYPH_ASCENT, manifest["glyph-ascent"])
        assertEquals(6, manifest["label-baseline-offset"])
        assertEquals(catalog.buttonSkins, manifest["requests"])
        assertEquals(listOf("rafter:button_skins/0", "rafter:button_skins/1"), manifest["fonts"])
    }

    @Test
    fun invalidSkinInsetsAndNonStretchInteriorsAreRejected() {
        val (root, document) = fixture()
        for ((request, inset) in
            listOf(
                full to -1,
                inner to 5,
                full to 1,
                inner.copy(width = null, fit = null) to 1,
                inner.copy(width = 0) to 1,
                inner.copy(fit = "contain") to 1,
                inner.copy(fit = null) to 1,
            )) {
            assertFailsWith<IllegalArgumentException>("$request inset=$inset") {
                AssetCompiler.compile(root, document, buttonSkins = mapOf(request to inset))
            }
        }
    }

    private fun providers(path: Path): Map<String, JsonObject> =
        JsonParser.parseString(Files.readString(path))
            .asJsonObject
            .getAsJsonArray("providers")
            .associate { entry ->
                val provider = entry.asJsonObject
                assertEquals("bitmap", provider.get("type").asString)
                provider.getAsJsonArray("chars").single().asString to provider
            }

    private fun fixture(): Pair<Path, Map<String, Any?>> {
        val root = temp.resolve("ui")
        Files.createDirectories(root.resolve("fonts"))
        Files.createDirectories(root.resolve("images"))
        requireNotNull(javaClass.getResourceAsStream("/core/ui/fonts/rafter-mono.ttf")).use {
            Files.copy(it, root.resolve("fonts/rafter-mono.ttf"))
        }
        val source = BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) source.setRGB(
            x,
            y,
            if (x < 8) red else blue,
        )
        ImageIO.write(source, "png", root.resolve("images/pixel@8x.png").toFile())
        val document = AutoAssets.document(root)
        @Suppress("UNCHECKED_CAST")
        val images = document.getValue("images") as Map<String, Map<String, Any?>>
        return root to
            (document +
                ("images" to
                    (images +
                        ("rafter:solid" to
                            mapOf(
                                "width" to 9,
                                "height" to 9,
                                "color" to "#123456",
                            )))))
    }
}
