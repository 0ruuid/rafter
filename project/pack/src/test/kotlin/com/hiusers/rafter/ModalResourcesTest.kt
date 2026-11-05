package com.hiusers.rafter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.ModalLayoutPlan
import com.hiusers.rafter.model.Rect
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModalResourcesTest {
    @TempDir lateinit var temp: Path

    private val layout =
        NativeModalLayout(
            "test:modal/search",
            "rafter:dialogs/search",
            280,
            156,
            "#36393f",
            listOf(Rect(16, 68, 248, 24), Rect(16, 124, 112, 24), Rect(152, 124, 112, 24)),
            1,
            ModalLayoutPlan(
                266,
                144,
                emptyList(),
                emptyList(),
                emptyList(),
                3,
                248,
                Rect(9, 0, 248, 20),
            ),
        )
    private val backdrop = ModalBackdropLayout(256, 90)

    @Test
    fun canvasOrderingBoundsAreInvisibleAndShareTheDeclaredAdvance() {
        val root = fixture()
        val catalog =
            AssetCompiler.compile(
                root,
                AutoAssets.document(root),
                modal = ModalAssets.Requests(emptyList(), listOf(backdrop)),
            )
        val pack = AssetCompiler.build(root, catalog, temp.resolve("bounds"))
        val provider =
            font(pack, ModalEncoding.BOUNDS_FONT).getAsJsonArray("providers")[0].asJsonObject
        assertEquals(4096, provider.get("height").asInt)
        assertEquals(7, provider.get("ascent").asInt)
        assertEquals(
            4097.0,
            catalog.textWidth(ModalEncoding.BOUNDS_CHARACTER, font = ModalEncoding.BOUNDS_FONT),
        )
        val image =
            ImageIO.read(pack.resolve("assets/rafter/textures/font/canvas_bounds.png").toFile())
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        for (y in 0..1) for (x in 0..1) assertEquals(0x01000000, image.getRGB(x, y))
    }

    @Test
    fun privateModalCopiesClearOnlyRequestedSourcePixelsAndKeepViewportArtworkUntouched() {
        val root = fixture()
        val original = Files.readAllBytes(root.resolve("images/dialogs/search.png"))
        val document = AutoAssets.document(root)
        val ordinary = AssetCompiler.compile(root, document, frameAssets = setOf(layout.asset))
        val before = AssetCompiler.build(root, ordinary, temp.resolve("ordinary"))
        assertFalse(Files.exists(before.resolve("assets/rafter/font/modal_fill.json")))
        assertFalse(Files.exists(before.resolve("assets/rafter/font/modal_backdrop")))
        assertFalse(Files.exists(before.resolve("assets/rafter/textures/font/modal_fill_thin.png")))
        val second = layout.copy(key = "test:modal/confirm", clear = emptyList())
        val catalog =
            AssetCompiler.compile(
                root,
                document,
                frameAssets = setOf(layout.asset),
                modal = ModalAssets.Requests(listOf(layout, second), emptyList()),
            )
        val pack = AssetCompiler.build(root, catalog, temp.resolve("modals"))
        assertContentEquals(original, Files.readAllBytes(root.resolve("images/dialogs/search.png")))
        assertEquals(ordinary.images, catalog.images)
        assertEquals(ordinary.allocation, catalog.allocation)
        assertEquals(listOf(layout, second), catalog.modalLayouts)
        assertEquals(setOf(layout.key, second.key), catalog.modalFrames.keys)
        assertEquals(2, catalog.modalFrames.values.map { it.font }.distinct().size)
        val source = ImageIO.read(root.resolve("images/dialogs/search.png").toFile())
        val spec = catalog.imageSpecs.single { it.id == layout.asset }
        for ((index, plan) in catalog.modalLayouts.withIndex()) {
            val glyph = catalog.modalFrames.getValue(plan.key)
            assertTrue(glyph.font.matches(Regex("rafter:modal_frames/[0-9a-f]{64}")))
            assertEquals(List(glyph.characters.size) { 4097 }, glyph.advances)
            val providers = font(pack, glyph.font).getAsJsonArray("providers")
            for ((tileIndex, tile) in DialogFrames.tiles(spec).withIndex()) {
                val provider = providers[tileIndex].asJsonObject
                assertEquals(4096, provider.get("height").asInt)
                assertEquals(7, provider.get("ascent").asInt)
                val bitmap =
                    ImageIO.read(
                        pack
                            .resolve(
                                "assets/rafter/textures/" +
                                    provider.get("file").asString.substringAfter(':')
                            )
                            .toFile()
                    )
                assertEquals(0xff0204f8.toInt(), bitmap.getRGB(1, 2))
                assertEquals(
                    listOf(280, 156, tile.x, tile.y, tile.width, tile.height, 1, index),
                    (0..7).map { u16(bitmap, it) },
                )
                for (y in 0 until tile.height) for (x in 0 until tile.width) {
                    val sx = tile.x + x
                    val sy = tile.y + y
                    val clear =
                        plan.clear.any {
                            sx in it.x until it.x + it.width && sy in it.y until it.y + it.height
                        }
                    assertEquals(
                        if (clear) Color.decode(plan.background).rgb else source.getRGB(sx, sy),
                        bitmap.getRGB(4 + x, 4 + y),
                        "layout=$index source=($sx,$sy)",
                    )
                }
            }
        }
        // 模态擦除不能影响普通图片或视口背景文件。
        Files.walk(before.resolve("assets/rafter/textures/font")).use { files ->
            files
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    assertContentEquals(
                        Files.readAllBytes(file),
                        Files.readAllBytes(pack.resolve(before.relativize(file))),
                    )
                }
        }
    }

    @Test
    fun backdropAliasesShareOneShiftedProviderSetAcrossLayouts() {
        val root = fixture()
        val catalog =
            AssetCompiler.compile(
                root,
                AutoAssets.document(root),
                modal = ModalAssets.Requests(listOf(layout), listOf(backdrop)),
            )
        val pack = AssetCompiler.build(root, catalog, temp.resolve("aliases"))
        assertEquals(
            1,
            catalog.fontFaces.size,
            "Default modal needs only one additional TTF provider",
        )
        val originals =
            catalog.fontFaces.keys +
                TextOffsetFonts.aliases(catalog) +
                listOf("rafter:images", "rafter:fill")
        for (original in originals) {
            val expected = font(pack, original).deepCopy()
            for (entry in expected.getAsJsonArray("providers")) {
                val provider = entry.asJsonObject
                when (provider.get("type").asString) {
                    "ttf" -> {
                        val shift = provider.getAsJsonArray("shift")
                        provider.add(
                            "shift",
                            Json.gson.toJsonTree(
                                listOf(
                                    shift[0].asDouble,
                                    shift[1].asDouble + ModalEncoding.FONT_Y_SHIFT,
                                )
                            ),
                        )
                    }
                    "bitmap" ->
                        provider.addProperty(
                            "ascent",
                            provider.get("ascent").asInt - ModalEncoding.FONT_Y_SHIFT,
                        )
                }
            }
            assertEquals(
                expected,
                font(pack, ModalEncoding.font(original)),
                "Only shift/ascent may change: $original",
            )
        }
        val fonts = pack.resolve("assets/rafter/font")
        assertEquals(
            originals.size.toLong(),
            Files.walk(fonts.resolve("modal_backdrop")).use { paths ->
                paths.filter { Files.isRegularFile(it) }.count()
            },
            "No per-row fonts",
        )
        assertEquals(
            1L,
            Files.walk(fonts).use { paths ->
                paths.filter { it.toString().endsWith(".ttf") }.count()
            },
            "No rasterization or copied per-layout/per-row TTF sources",
        )
        val ttf =
            font(pack, ModalEncoding.font(catalog.theme.defaultFont))
                .getAsJsonArray("providers")[0]
                .asJsonObject
        assertEquals(catalog.theme.oversample, ttf.get("oversample").asDouble)
        assertContentEquals(
            Files.readAllBytes(catalog.fontSource),
            Files.readAllBytes(fonts.resolve("sources/rafter-mono.ttf")),
        )
        for ((original, text) in
            listOf(
                catalog.theme.defaultFont to "Title",
                "rafter:fill" to "\ue000\ue001",
                "rafter:images" to catalog.images.getValue(layout.asset).character,
            )) {
            for (bold in listOf(false, true)) assertEquals(
                catalog.textWidth(text, bold, original),
                catalog.textWidth(text, bold, ModalEncoding.font(original)),
            )
        }
        val fills = font(pack, "rafter:modal_fill").getAsJsonArray("providers")
        assertEquals(4, fills.size())
        assertEquals("\ue000\u0000", fills[0].asJsonObject.getAsJsonArray("chars")[0].asString)
        assertEquals("rafter:font/fill.png", fills[0].asJsonObject.get("file").asString)
        assertEquals("\ue001", fills[1].asJsonObject.getAsJsonArray("chars")[0].asString)
        fills.forEachIndexed { index, fill ->
            val upperGap =
                when {
                    index < 2 -> 0
                    else -> -com.hiusers.rafter.model.NativeGeometry.COUNTER_CLEARANCE
                }
            assertEquals(20 + upperGap, fill.asJsonObject.get("height").asInt)
            assertEquals(13 + upperGap, fill.asJsonObject.get("ascent").asInt)
        }
        assertEquals("\ue004", fills[2].asJsonObject.getAsJsonArray("chars")[0].asString)
        assertEquals("\ue005", fills[3].asJsonObject.getAsJsonArray("chars")[0].asString)
        val counterFill =
            ImageIO.read(
                pack.resolve("assets/rafter/textures/font/modal_fill_counter.png").toFile()
            )
        assertEquals(17, counterFill.height)
        assertEquals(21.0, catalog.textWidth("\ue004", font = "rafter:modal_fill"))
        assertEquals(2.0, catalog.textWidth("\ue005", font = "rafter:modal_fill"))
        val thin =
            ImageIO.read(pack.resolve("assets/rafter/textures/font/modal_fill_thin.png").toFile())
        assertEquals(1, thin.width)
        assertEquals(20, thin.height)
        assertTrue((0 until 20).all { thin.getRGB(0, it) == 0xffffffff.toInt() })
        assertEquals(21.0, catalog.textWidth("\ue000", font = "rafter:modal_fill"))
        assertEquals(2.0, catalog.textWidth("\ue001", font = "rafter:modal_fill"))
    }

    @Test
    fun modalOnlyPacksWriteBothVerifiedAbisTablesAndFingerprintEveryRequest() {
        val root = fixture()
        val document = AutoAssets.document(root)
        val second = layout.copy(key = "test:modal/second", offsetY = -2)
        val requests =
            ModalAssets.Requests(
                listOf(layout, second),
                listOf(backdrop, ModalBackdropLayout(360, 162)),
            )
        val catalog = AssetCompiler.compile(root, document, modal = requests)
        assertTrue(catalog.dialogFrames.isEmpty())
        val pack = AssetCompiler.build(root, catalog, temp.resolve("shaders"))
        val entries =
            (Json.map(pack.resolve("pack.mcmeta"))["overlays"] as Map<*, *>)["entries"] as List<*>
        assertEquals(
            ClientRenderProfiles.all.map { it.directory }.toSet(),
            entries.map { (it as Map<*, *>)["directory"] }.toSet(),
        )
        for (profile in ClientRenderProfiles.all) for (name in
            profile.textPrograms.flatMap { listOf("$it.vsh", "$it.fsh") }) {
            val file = pack.resolve("${profile.directory}/assets/minecraft/shaders/core/$name")
            assertEquals(profile in DialogFrameShaders.supportedProfiles, Files.exists(file))
            if (Files.exists(file))
                assertTrue(Files.readString(file).startsWith("#version ${profile.glsl}\n"))
        }
        val include =
            Files.readString(pack.resolve("assets/rafter/shaders/include/dialog_frame_vertex.glsl"))
        for (row in
            listOf(
                "vec4(280.0, 156.0, 144.0, 1.0)",
                "vec4(280.0, 156.0, 144.0, -2.0)",
                "vec4(266.0, 98.0, 128.0, 32768.0)",
                "vec4(370.0, 170.0, 200.0, 57344.0)",
            )) assertTrue(row in include, row)
        assertTrue("const float RAFTER_MODAL_TITLE_Y = ${ModalEncoding.TITLE_Y}.0;" in include)
        assertTrue(
            "const float RAFTER_MODAL_TITLE_GROUP_WIDTH = ${ModalEncoding.TITLE_GROUP_WIDTH}.0;" in
                include
        )
        for (guard in
            listOf(
                "extent = panelLayout.xy;",
                "if (row == -1)",
                "abs(glyphLocalY) > 256.0",
                "localX < -512.0 || localX > 1024.0",
                "localX < -128.0 || localX > sourceWidth + 128.0",
            )) {
            assertTrue(guard in include, guard)
        }
        val shifted =
            font(pack, ModalEncoding.font(catalog.theme.defaultFont))
                .getAsJsonArray("providers")[0]
                .asJsonObject
        val originalShift =
            font(pack, catalog.theme.defaultFont)
                .getAsJsonArray("providers")[0]
                .asJsonObject
                .getAsJsonArray("shift")[1]
                .asDouble
        assertEquals(
            originalShift + ModalEncoding.FONT_Y_SHIFT,
            shifted.getAsJsonArray("shift")[1].asDouble,
        )
        val sentinel = DialogFrameShaders.vertexInclude(emptyList(), emptyList())
        for (name in listOf("LAYOUT", "BACKDROP")) {
            assertTrue("RAFTER_MODAL_${name}_COUNT = 0;" in sentinel)
            assertTrue("RAFTER_MODAL_${name}S[1]" in sentinel)
        }
        val variants =
            listOf(
                layout.copy(background = "#101010"),
                layout.copy(clear = emptyList()),
                layout.copy(plan = layout.plan.copy(bodyWidth = 267)),
                layout.copy(plan = layout.plan.copy(bodyHeight = 145)),
                layout.copy(offsetY = 2),
                layout.copy(plan = layout.plan.copy(buttonRowWidth = 249)),
            )
        for (variant in variants) assertNotEquals(
            catalog.fingerprint,
            AssetCompiler.compile(
                    root,
                    document,
                    modal = requests.copy(layouts = listOf(variant, second)),
                )
                .fingerprint,
        )
        assertNotEquals(
            catalog.fingerprint,
            AssetCompiler.compile(
                    root,
                    document,
                    modal = requests.copy(backdrops = listOf(backdrop)),
                )
                .fingerprint,
        )
        assertEquals(
            catalog.modalFrames,
            AssetCompiler.compile(root, document, modal = requests).modalFrames,
        )
        assertEquals(
            catalog.fingerprint,
            AssetCompiler.compile(root, document, modal = requests).fingerprint,
        )
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(
                root,
                document,
                modal = requests.copy(layouts = listOf(layout, layout)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(
                root,
                document,
                modal =
                    requests.copy(
                        layouts = listOf(layout.copy(clear = listOf(Rect(279, 0, 2, 1))))
                    ),
            )
        }
    }

    @Test
    fun reservedCoordinatesRoundTripBodyRowsAndTheOriginalTitleWithoutShrinkingNativePanels() {
        val backdrops = listOf(backdrop, ModalBackdropLayout(864, 306))
        fun bodyTop(guiH: Int, height: Int) =
            minOf(
                CanvasGeometry.PREFERRED_BODY_TOP,
                guiH - 5 - minOf(height, maxOf(guiH - 38, 0)),
            )
        // 在 CPU 重放协议，并覆盖普通字体的拒绝范围。
        fun position(x: Double, y: Double, guiW: Int, guiH: Int): Pair<Double, Double>? {
            if (x > -ModalEncoding.X_BASE * 0.5) return null
            val localY = y - ModalEncoding.TITLE_Y - ModalEncoding.FONT_Y_SHIFT
            if (abs(localY) > 256) return null
            val anchor = floor((guiW - ModalEncoding.TITLE_GROUP_WIDTH) * 0.5)
            for ((index, source) in backdrops.withIndex()) {
                val base = ModalEncoding.xBase(index, backdrops)
                val row =
                    floor(
                            (anchor - base - x + ModalEncoding.ROW_STRIDE * 0.5) /
                                ModalEncoding.ROW_STRIDE
                        )
                        .toInt()
                if (row !in -1 until source.height / 9) continue
                val localX = x - anchor + base + row * ModalEncoding.ROW_STRIDE
                if (row == -1) {
                    if (localX in -512.0..1024.0)
                        return anchor + localX to ModalEncoding.TITLE_Y + localY
                    continue
                }
                if (localX < -128 || localX > source.width + 128) continue
                return floor((guiW - source.bodyWidth) * 0.5) + 4 + localX to
                    bodyTop(guiH, source.layoutHeight) + 4 + row * 9 + localY
            }
            return null
        }
        for ((guiW, guiH) in listOf(853 to 481, 321 to 241, 201 to 51, 41 to 29)) {
            val anchor = floor((guiW - ModalEncoding.TITLE_GROUP_WIDTH) * 0.5)
            for ((index, source) in backdrops.withIndex()) for (row in
                listOf(-1, 0, source.height / 9 - 1)) {
                for (x in
                    if (row == -1) listOf(-512, -40, 0, 1024)
                    else listOf(-128, 0, source.width + 128)) {
                    for (y in listOf(-256.0, -1.5, 256.0)) {
                        val rawX =
                            anchor -
                                ModalEncoding.xBase(index, backdrops) -
                                row * ModalEncoding.ROW_STRIDE + x
                        val rawY = ModalEncoding.TITLE_Y + ModalEncoding.FONT_Y_SHIFT + y
                        val expected =
                            if (row == -1) anchor + x to ModalEncoding.TITLE_Y + y
                            else
                                floor((guiW - source.bodyWidth) * 0.5) + 4 + x to
                                    bodyTop(guiH, source.layoutHeight) + 4 + row * 9 + y
                        assertEquals(expected, position(rawX, rawY, guiW, guiH))
                    }
                }
            }
            // 即使面板溢出视口，源像素仍映射到实际面板范围。
            val left = (guiW - layout.width) * 0.5
            val top = bodyTop(guiH, layout.bodyHeight) + layout.offsetY
            assertEquals(280.0, (left + layout.width) - left)
            assertEquals(156, (top + layout.height) - top)
        }
        val anchor = floor((853 - ModalEncoding.TITLE_GROUP_WIDTH) * 0.5)
        fun encoded(row: Int, x: Int, y: Int = 0) =
            position(
                anchor - ModalEncoding.X_BASE - row * ModalEncoding.ROW_STRIDE + x,
                (ModalEncoding.TITLE_Y + ModalEncoding.FONT_Y_SHIFT + y).toDouble(),
                853,
                481,
            )
        assertNull(position(0.0, 11.0, 853, 481))
        assertNull(position(anchor - ModalEncoding.X_BASE, 11.0, 853, 481))
        for ((row, x) in
            listOf(
                -2 to 0,
                -1 to -513,
                -1 to 1025,
                0 to -129,
                0 to backdrop.width + 129,
                backdrop.height / 9 to 0,
            )) assertNull(encoded(row, x))
        assertNull(encoded(0, 0, 257))
        assertNull(encoded(0, 0, -257))
    }

    private fun font(pack: Path, id: String): JsonObject =
        JsonParser.parseString(
                Files.readString(pack.resolve("assets/rafter/font/${id.substringAfter(':')}.json"))
            )
            .asJsonObject

    private fun u16(image: BufferedImage, field: Int): Int {
        val value = image.getRGB(field + 2, 2)
        return ((value ushr 16) and 255) or (((value ushr 8) and 255) shl 8)
    }

    private fun fixture(): Path {
        val root = temp.resolve("ui")
        Files.createDirectories(root.resolve("fonts"))
        Files.createDirectories(root.resolve("images/dialogs"))
        requireNotNull(javaClass.getResourceAsStream("/core/ui/fonts/rafter-mono.ttf")).use {
            Files.copy(it, root.resolve("fonts/rafter-mono.ttf"))
        }
        val image = BufferedImage(280, 156, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            image.setRGB(
                x,
                y,
                (0x80000000.toInt() or
                    ((x and 255) shl 16) or
                    ((y and 255) shl 8) or
                    ((x xor y) and 255)),
            )
        }
        check(ImageIO.write(image, "png", root.resolve("images/dialogs/search.png").toFile()))
        return root
    }
}
