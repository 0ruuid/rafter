package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.model.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DialogFrameTest {
    @TempDir lateinit var temp: Path

    @Test
    fun requestedOriginalsOnlyUseContentPixelsAndStableReusableFontIds() {
        val root = fixture()
        val document = AutoAssets.document(root)
        val ordinary = AssetCompiler.compile(root, document)
        assertTrue(ordinary.dialogFrames.isEmpty())
        val frame = AssetCompiler.compile(root, document, frameAssets = setOf("rafter:pixel"))
        val descriptor = frame.dialogFrames.getValue("rafter:pixel")
        assertEquals(setOf("rafter:pixel"), frame.dialogFrames.keys)
        assertEquals("rafter:dialog_frames/pixel", descriptor.font)
        assertEquals(listOf(4097), descriptor.advances)
        assertNotEquals(ordinary.fingerprint, frame.fingerprint)
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(root, document, frameAssets = setOf("rafter:missing"))
        }
        val built = AssetCompiler.build(root, frame, temp.resolve("pack"))
        val image =
            ImageIO.read(
                built.resolve("assets/rafter/textures/font/dialog_frames/pixel/0.png").toFile()
            )
        assertEquals(1, u16(image, 0))
        assertEquals(1, u16(image, 1)) // 使用原图尺寸，不使用传输填充后的 9×9。
        assertEquals(0x80123456.toInt(), image.getRGB(4, 4))
        assertEquals(0, image.getRGB(5, 4))
        assertFalse(Files.exists(built.resolve("assets/rafter/font/dialog_frames/unused.json")))
        val overlay = Json.map(built.resolve("pack.mcmeta"))["overlays"] as Map<*, *>
        val entries = overlay["entries"] as List<*>
        assertEquals(
            ClientRenderProfiles.all.map { it.directory }.toSet(),
            entries.map { (it as Map<*, *>)["directory"] }.toSet(),
        )
        for (profile in ClientRenderProfiles.all) for (program in profile.textPrograms) {
            val shader =
                built.resolve("${profile.directory}/assets/minecraft/shaders/core/$program.vsh")
            assertTrue(Files.exists(shader))
        }
    }

    @Test
    fun framedMixedCanvasRegistersOnlyItsNativeTextBodyFocusWithoutFooterAssumptions() {
        val root = fixture()
        val catalog =
            AssetCompiler.compile(
                    root,
                    AutoAssets.document(root),
                    frameAssets = setOf("rafter:pixel"),
                )
                .copy(hideFocusOutline = true, hideNativeFocusOutline = true)
        val controls =
            listOf(
                NodeSpec("query", NodeContent.TextInput("query")),
                NodeSpec("volume", NodeContent.Slider("volume")),
                NodeSpec("submit", NodeContent.Button()),
            )
        val page =
            PageSpec(
                "main",
                PageMode.CANVAS,
                TextSpec(),
                864,
                1,
                controls,
                frame = DialogFrameSpec("rafter:pixel"),
            )
        fun bind(spec: PageSpec): AssetCatalog {
            val menu =
                MenuSpec(
                    "test:frame",
                    spec.id,
                    null,
                    emptyMap(),
                    mapOf(spec.id to spec),
                    "menu.yml",
                )
            return DialogFocusShaders.bind(
                catalog,
                MenuSnapshot(
                    mapOf(
                        menu.id to
                            CompiledMenu(
                                menu,
                                mapOf(spec.id to CompiledPage(spec, emptyList(), 9)),
                            )
                    ),
                    emptyMap(),
                ),
            )
        }
        val bound = bind(page)
        assertTrue(
            bound.focusLayouts.isEmpty(),
            "Mixed Canvas must not guess a footer/tail signature",
        )
        val body = bound.nativeFocusLayouts.single()
        assertEquals(NativeFocusKind.TEXT, body.kind)
        assertEquals(
            listOf(CanvasGeometry.bodyWidth(864), 0, 0, NativeFocusKind.TEXT.code),
            body.vector(),
        )
        assertTrue(
            controls.none { it.id == body.component },
            "Inputs/sliders/buttons must not be registered",
        )
        val stage = temp.resolve("body-focus")
        DialogFocusShaders.write(
            stage,
            bound,
        ) // 填充后的最大宽度 874px 仍须通过原生校验。
        assertTrue(
            Files.readString(stage.resolve("assets/rafter/shaders/include/dialog_focus.glsl"))
                .contains("vec4(874.0, 0.0, 0.0, 0.0)")
        )
        assertTrue(bind(page.copy(frame = null)).nativeFocusLayouts.isEmpty())
        assertTrue(bind(page.copy(mode = PageMode.NATIVE)).nativeFocusLayouts.isEmpty())
    }

    @Test
    fun metadataTilesRoundTripRealRgbaAndCornersWithoutVertexPhase() {
        val spec = ImageSpec("rafter:art", 315, 216, null, "images/art@2x.png", 2, 315, 210)
        val source = BufferedImage(630, 420, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setRGB(
                x,
                y,
                (((x + y) and 255) shl 24) or
                    ((x and 255) shl 16) or
                    ((y and 255) shl 8) or
                    ((x xor y) and 255),
            )
        }
        val glyph = DialogFrames.compile(listOf(spec), setOf(spec.id)).getValue(spec.id)
        DialogFrames.write(temp, glyph, spec, source)
        val tiles = DialogFrames.tiles(spec)
        assertEquals(6, tiles.size)
        val decoded = tiles.mapIndexed { index, tile ->
            val bitmap =
                ImageIO.read(
                    temp
                        .resolve("assets/rafter/textures/font/dialog_frames/art/$index.png")
                        .toFile()
                )
            assertEquals(256, bitmap.width)
            assertEquals(256, bitmap.height)
            assertEquals(DialogFrames.SIGNATURE, bitmap.getRGB(0, 2))
            assertEquals(DialogFrames.VERSION, bitmap.getRGB(1, 2))
            assertEquals(
                listOf(630, 420, tile.x, tile.y, tile.width, tile.height, 0, 0),
                (0..7).map { u16(bitmap, it) },
            )
            for (y in 0 until tile.height) assertContentEquals(
                source.getRGB(tile.x, tile.y + y, tile.width, 1, null, 0, tile.width),
                bitmap.getRGB(4, 4 + y, tile.width, 1, null, 0, tile.width),
            )
            // 两种已核对纹理实现均使用 ±0.01 纹素的 UV 边缘。
            for (phase in 0..3) for (vertex in 0..3) {
                val corner = (vertex + phase) % 4
                val px = floor(if (corner and 1 == 0) 0.01 else 255.99).toInt()
                val py = floor(if (corner and 2 == 0) 0.01 else 255.99).toInt()
                val recovered = bitmap.getRGB(px, py) and 255
                assertEquals(corner, recovered)
                assertEquals(0, px - (recovered and 1) * 255)
                assertEquals(0, py - (recovered shr 1) * 255)
            }
            bitmap
        }
        // 在 CPU 重放采样，覆盖接缝、奇数视口和宽度限制。
        for ((guiWidth, guiHeight, requested, inset) in
            listOf(
                listOf(853, 481, 711, 4),
                listOf(321, 241, 65535, 0),
                listOf(41, 31, 13, 4),
            )) {
            val width = minOf(requested, guiWidth - inset * 2).toDouble()
            val height = (guiHeight - inset * 2).toDouble()
            val left = (guiWidth - width) / 2
            for (y in 0 until guiHeight) for (x in 0 until guiWidth) {
                if (
                    x + 0.5 < left ||
                        x + 0.5 >= left + width ||
                        y + 0.5 < inset ||
                        y + 0.5 >= inset + height
                )
                    continue
                val sx = floor((x + 0.5 - left) / width * source.width).toInt()
                val sy = floor((y + 0.5 - inset) / height * source.height).toInt()
                val tileIndex = tiles.indexOfFirst {
                    sx in it.x until it.x + it.width && sy in it.y until it.y + it.height
                }
                assertTrue(tileIndex >= 0)
                val tile = tiles[tileIndex]
                assertEquals(
                    source.getRGB(sx, sy),
                    decoded[tileIndex].getRGB(4 + sx - tile.x, 4 + sy - tile.y),
                )
            }
        }
    }

    @Test
    fun shaderInjectionRetainsVerifiedVanillaPathsAndExactProfileBoundaries() {
        assertEquals(
            setOf(63 to 0, 64 to 0, 69 to 0, 75 to 0, 84 to 0, 88 to 0, 97 to 1),
            DialogFrameShaders.supportedPackFormats,
        )
        for ((major, minor) in
            listOf(62 to 0, 63 to 1, 69 to 1, 75 to 1, 84 to 1, 88 to 1, 97 to 0, 98 to 0)) {
            assertFalse(DialogFrameShaders.supportsPackFormat(major, minor))
        }
        val hashes =
            mapOf(
                "26_2/text.vsh" to
                    "4bae202567d0e3020ab948c24dcd8acffaa23460622dc01f8cbc929301e062e5",
                "26_2/text.fsh" to
                    "3ef2d2825459867537446ad0497e9b29356162c6fdcd138a0a258678f7c1e597",
                "26_3/text.vsh" to
                    "c5aab3569c07200a3d24c40f6ed2a5716b6694acd015b81e763398b7ff3ddc1f",
                "26_3/text.fsh" to
                    "08a3b585cf52e39b66e2689132b6a996d81446e9cc6ab1eab73bbcca38186d43",
            )
        for (profile in DialogFrameShaders.supportedProfiles) {
            val version = profile.baseline
            for ((name, shader) in DialogFrameShaders.sources(profile)) {
                val bytes =
                    requireNotNull(javaClass.getResourceAsStream("/shaders/client/$version/$name"))
                        .use { it.readAllBytes() }
                if (profile.unifiedText)
                    assertEquals(
                        hashes.getValue("$version/$name"),
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        },
                    )
                val vanilla = bytes.toString(Charsets.UTF_8)
                assertTrue(shader.startsWith("#version ${profile.glsl}\n"))
                assertFalse(shader.contains('\uFEFF'))
                assertFalse(
                    shader.contains("!defined(IS_GRAYSCALE)"),
                    "TTF underlays need the grayscale GUI pipeline",
                )
                assertEquals(1, Regex("uniform sampler2D Sampler0;").findAll(shader).count())
                assertTrue(shader.startsWith(vanilla.substringBefore("void main() {")))
                val ordinary =
                    if (name.endsWith("vsh"))
                        shader
                            .substringAfter("void main() {")
                            .replace(
                                "\n${if (profile.unifiedText) "#if defined(IS_GUI)" else "#if 1"}\n    rafter_frame_position();\n#endif",
                                "",
                            )
                    else shader.substringAfter("        return;\n    }\n#endif")
                assertEquals(vanilla.substringAfter("void main() {"), ordinary)
                assertFalse(shader.contains("gl_FragDepth"))
                if (profile.vulkanInterface) assertTrue(shader.contains("layout(location = 7)"))
            }
        }
        val vertex =
            requireNotNull(javaClass.getResourceAsStream("/shaders/dialog_frame/frame_vertex.glsl"))
                .bufferedReader()
                .use { it.readText() }
        assertFalse(vertex.contains("gl_VertexID"))
        assertFalse(vertex.contains("gl_VertexIndex"))
        assertTrue(vertex.contains("vec4(point, Position.z, 1.0)"))
        assertTrue(vertex.contains("rgb.r | (rgb.g << 8)"))
        assertTrue(
            vertex.indexOf("if (rafter_modal_backdrop_position()) return;") <
                vertex.indexOf("ivec2 atlas = textureSize(Sampler0, 0);"),
            "Decode TTF coordinates before bitmap marker probing",
        )
        val fragment =
            requireNotNull(
                    javaClass.getResourceAsStream("/shaders/dialog_frame/frame_fragment.glsl")
                )
                .bufferedReader()
                .use { it.readText() }
        assertTrue(
            fragment.contains("texelFetch(Sampler0, rafterFrameOrigin + ivec2(4) + pixel, 0)")
        )
        assertFalse(fragment.contains("vertexColor.rgb"))
    }

    private fun u16(image: BufferedImage, field: Int): Int {
        val value = image.getRGB(field + 2, 2)
        return ((value ushr 16) and 255) or (((value ushr 8) and 255) shl 8)
    }

    private fun fixture(): Path {
        val root = temp.resolve("ui")
        Files.createDirectories(root.resolve("fonts"))
        Files.createDirectories(root.resolve("images"))
        requireNotNull(javaClass.getResourceAsStream("/core/ui/fonts/rafter-mono.ttf")).use {
            Files.copy(it, root.resolve("fonts/rafter-mono.ttf"))
        }
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x80123456.toInt())
        ImageIO.write(image, "png", root.resolve("images/pixel.png").toFile())
        ImageIO.write(image, "png", root.resolve("images/unused.png").toFile())
        return root
    }
}
