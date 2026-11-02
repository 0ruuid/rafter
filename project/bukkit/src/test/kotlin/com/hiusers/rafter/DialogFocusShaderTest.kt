package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.*
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 仅验证 JVM 与资源，不调用客户端或 GPU。 */
class DialogFocusShaderTest {
    @TempDir lateinit var temp: Path

    @org.junit.jupiter.api.BeforeEach
    fun componentBackend() {
        taboolib.module.chat.Components.useAdventure = true
    }

    private fun document(
        width: Int = 621,
        height: Int = 360,
        native: Boolean = false,
    ): Map<String, Any?> {
        val mode = if (native) "native" else "canvas"
        val page =
            mapOf(
                "mode" to mode,
                "title" to "Title",
                "width" to width,
                (if (native) "components" else "elements") to
                    listOf(
                        mapOf(
                            "id" to "label",
                            "type" to "text",
                            "text" to if (native) "Native" else "geometry",
                        )
                    ),
            ) + if (native) emptyMap() else mapOf("height" to height)
        return minimal(mode) + ("pages" to mapOf("main" to page))
    }

    private fun snapshot(
        width: Int = 621,
        height: Int = 360,
        native: Boolean = false,
    ): MenuSnapshot {
        val result = MenuCompiler().compile(mapOf("menu.yml" to document(width, height, native)))
        return assertNotNull(result.snapshot, result.diagnostics.toString())
    }

    private fun catalog(enabled: Boolean = true): AssetCatalog {
        val ui = temp.resolve("core/ui")
        Files.createDirectories(ui)
        for (name in listOf("fonts/rafter-mono.ttf")) {
            val destination = ui.resolve(name)
            Files.createDirectories(destination.parent)
            javaClass.classLoader.getResourceAsStream("core/ui/$name")!!.use {
                Files.copy(it, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return AssetCompiler.compile(
            ui,
            mapOf(
                "hide-canvas-focus-outline" to enabled,
                "images" to
                    mapOf(
                        "rafter:test" to mapOf("width" to 9, "height" to 9, "color" to "#313233")
                    ),
            ),
        )
    }

    @Test
    fun compiledAndRenderedDimensionsAgreeForChangedWidthsAndLongPages() {
        val assets = catalog()
        for ((width, height) in listOf(621 to 360, 450 to 1953, 864 to 4095)) {
            val snapshot = snapshot(width, height)
            val bound = DialogFocusShaders.bind(assets, snapshot)
            val geometry = bound.focusLayouts.single()
            assertEquals(listOf(width + 10, height + 8, 5, 30), geometry.vector())
            val menu = snapshot.menus.values.single()
            val page = menu.pages.values.single()
            val session = UiSession(UUID.randomUUID(), menu, emptyMap())
            val visible = page.scene.associate { it.node.id to true }
            val view =
                CanvasRenderer.render(
                    session,
                    Frame(emptyMap(), visible, visible),
                    CompositeText(bound),
                )
            assertEquals(geometry.bodyWidth, view.messages.single().width)
            assertEquals(
                geometry.bodyHeight,
                (view.messages.single().parts.sumOf {
                    it.text.toPlainText().count { c -> c == '\n' }
                } + 1) * 9 + 8,
            )
            assertTrue(
                view.buttons.isEmpty()
            ) // 关闭操作实际位于正文。
            assertTrue(view.items.isEmpty() && view.inputs.isEmpty())
            assertEquals(1, view.columns)
        }
    }

    @Test
    fun shaderWriterAcceptsEveryPixelWidthWithoutWeakeningVerticalValidation() {
        val source = catalog()
        for (width in listOf(90, 91, 560, 863, 864)) {
            val bound = DialogFocusShaders.bind(source, snapshot(width, 342))
            val stage = temp.resolve("pixel-width-$width")
            DialogFocusShaders.write(stage, bound)
            val shader =
                Files.readString(stage.resolve("assets/rafter/shaders/include/dialog_focus.glsl"))
            assertTrue("vec4(${width + 10}.0, 350.0, 5.0, 30.0)" in shader)
        }
        val bound = DialogFocusShaders.bind(source, snapshot(560, 342))
        for (invalid in
            listOf(
                bound.focusLayouts.single().copy(bodyWidth = CanvasGeometry.bodyWidth(89)),
                bound.focusLayouts.single().copy(bodyWidth = CanvasGeometry.bodyWidth(865)),
                bound.focusLayouts.single().copy(bodyHeight = CanvasGeometry.bodyHeight(343)),
            )) {
            assertFailsWith<IllegalArgumentException> {
                DialogFocusShaders.write(
                    temp.resolve("invalid"),
                    bound.copy(focusLayouts = listOf(invalid)),
                )
            }
        }
    }

    @Test
    fun nativeBodiesHaveSeparateSignaturesAndCanOptOutIndependently() {
        val source = catalog()
        val snapshot = snapshot(native = true)
        val bound = DialogFocusShaders.bind(source, snapshot)
        assertTrue(bound.focusLayouts.isEmpty())
        assertTrue(DialogFocusShaders.active(bound))
        assertEquals(1, bound.nativeFocusLayouts.size)
        assertEquals(listOf(621, 0, 0, 0), bound.nativeFocusLayouts.single().vector())
        val pack = AssetCompiler.build(temp.resolve("core/ui"), bound, temp)
        val common =
            Files.readString(pack.resolve("assets/rafter/shaders/include/dialog_focus.glsl"))
        assertTrue("RAFTER_FOCUS_LAYOUT_COUNT = 0;" in common)
        assertTrue("RAFTER_NATIVE_FOCUS_LAYOUT_COUNT = 1;" in common)
        val disabled =
            DialogFocusShaders.bind(source.copy(hideNativeFocusOutline = false), snapshot)
        AssetCompiler.build(temp.resolve("core/ui"), disabled, temp)
        assertFalse(DialogFocusShaders.active(disabled))
        assertFalse(Json.map(pack.resolve("pack.mcmeta")).containsKey("overlays"))
        assertFalse(Files.exists(pack.resolve("assets/rafter/shaders")))
        assertNotEquals(bound.fingerprint, disabled.fingerprint)
    }

    @Test
    fun nativeItemRowsUseRealSpacingAndNeverRegisterEditableControls() {
        val nodes =
            listOf(
                mapOf(
                    "id" to "intro",
                    "type" to "text",
                    "text" to
                        mapOf(
                            "format" to "literal",
                            "binding" to mapOf("type" to "state", "key" to "name"),
                        ),
                ),
                mapOf(
                    "id" to "item",
                    "type" to "item",
                    "item" to "minecraft:spyglass",
                    "width" to 401,
                    "text" to "description",
                ),
                mapOf("id" to "input", "type" to "input", "state" to "name", "width" to 401),
                mapOf("id" to "check", "type" to "checkbox", "state" to "enabled"),
                mapOf(
                    "id" to "select",
                    "type" to "select",
                    "state" to "choice",
                    "options" to mapOf("a" to "A"),
                ),
                mapOf("id" to "range", "type" to "slider", "state" to "number"),
                mapOf(
                    "id" to "button",
                    "type" to "button",
                    "width" to 401,
                    "actions" to listOf(mapOf("type" to "close")),
                ),
            )
        val result = MenuCompiler().compile(mapOf("native.yml" to minimal(components = nodes)))
        val bound =
            DialogFocusShaders.bind(
                catalog(),
                assertNotNull(result.snapshot, result.diagnostics.toString()),
            )
        assertEquals(3, bound.nativeFocusLayouts.size)
        assertEquals(
            setOf(listOf(360, 0, 0, 0), listOf(32, 32, -403, 1), listOf(401, 0, 34, 0)),
            bound.nativeFocusLayouts.map { it.vector() }.toSet(),
        )
        assertEquals(setOf("intro", "item"), bound.nativeFocusLayouts.map { it.component }.toSet())
        val pack = AssetCompiler.build(temp.resolve("core/ui"), bound, temp)
        assertFalse(Files.exists(pack.resolve("assets/minecraft/textures/gui/sprites/widget")))
        assertFalse(
            Files.walk(pack).use { paths ->
                paths.anyMatch { it.fileName.toString().contains("textured") }
            }
        )
    }

    @Test
    fun mixedModesKeepOnlyEnabledSignaturesAndRemoveOverlaysWhenBothDisabled() {
        val assets = catalog()
        val result =
            MenuCompiler()
                .compile(
                    mapOf(
                        "canvas.yml" to document(),
                        "native.yml" to (document(native = true) + ("id" to "test:native")),
                    )
                )
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        val fingerprints = mutableSetOf<String>()
        for ((canvas, native) in
            listOf(true to true, false to true, true to false, false to false)) {
            val bound =
                DialogFocusShaders.bind(
                    assets.copy(hideFocusOutline = canvas, hideNativeFocusOutline = native),
                    snapshot,
                )
            fingerprints += bound.fingerprint
            assertEquals(canvas || native, DialogFocusShaders.active(bound))
            val pack = AssetCompiler.build(temp.resolve("core/ui"), bound, temp)
            assertEquals(
                canvas || native,
                Json.map(pack.resolve("pack.mcmeta")).containsKey("overlays"),
            )
            val include = pack.resolve("assets/rafter/shaders/include/dialog_focus.glsl")
            assertEquals(canvas || native, Files.exists(include))
            if (canvas || native) {
                val common = Files.readString(include)
                assertTrue("RAFTER_FOCUS_LAYOUT_COUNT = ${if(canvas) 1 else 0};" in common)
                assertTrue("RAFTER_NATIVE_FOCUS_LAYOUT_COUNT = ${if(native) 1 else 0};" in common)
            }
            assertEquals(assets.allocation, AssetCompiler.readAllocation(temp))
        }
        assertEquals(4, fingerprints.size)
    }

    @Test
    fun overlaysHaveClosedFormatsCorrectInterfacesAndNoRootOrTextShader() {
        val bound = DialogFocusShaders.bind(catalog(), snapshot(height = 1953))
        val pack = AssetCompiler.build(temp.resolve("core/ui"), bound, temp)
        val metadata = Json.map(pack.resolve("pack.mcmeta"))
        @Suppress("UNCHECKED_CAST")
        val entries =
            (metadata["overlays"] as Map<String, Any?>)["entries"] as List<Map<String, Any?>>
        assertEquals(
            DialogFocusShaders.profiles.map { it.directory },
            entries.map { it["directory"] },
        )
        val shaderFiles =
            Files.walk(pack).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith("sh") }.toList()
            }
        assertEquals(12, shaderFiles.size)
        assertFalse(Files.exists(pack.resolve("assets/minecraft/shaders/core")))
        val common =
            Files.readString(pack.resolve("assets/rafter/shaders/include/dialog_focus.glsl"))
        assertTrue("vec4(631.0, 1961.0, 5.0, 30.0)" in common)
        assertFalse("474.0" in common || "576.0" in common)
        assertTrue("dFdx(rafterGuiPosition)" in common)
        assertTrue("px * qy.y - py * qx.y" in common)
        assertFalse("fwidth(rafterGuiPosition)" in common)
        assertTrue("layoutHeight - visibleHeight" in common)
        for (profile in DialogFocusShaders.profiles) {
            val core = pack.resolve("${profile.directory}/assets/minecraft/shaders/core")
            val vertex = Files.readString(core.resolve("gui.vsh"))
            val fragment = Files.readString(core.resolve("gui.fsh"))
            assertTrue(vertex.startsWith("#version ${profile.glsl}"))
            assertTrue(fragment.startsWith("#version ${profile.glsl}"))
            assertEquals(profile.lineWidth, "float LineWidth;" in vertex)
            assertEquals(profile.vulkanInterface, "gl_VertexIndex" in vertex)
            assertEquals(profile.vulkanInterface, "layout(location = 3) flat" in vertex)
            assertTrue("rafter:dialog_focus.glsl" in fragment)
            assertTrue("rafterDialogFocus(color)" in fragment)
            assertFalse(Regex("@[A-Z_]+@").containsMatchIn(vertex + fragment + common))
            assertFalse(
                Regex("\\b(?:vec[234]|float|int)\\s+layout\\b")
                    .containsMatchIn(vertex + fragment + common)
            )
            if (profile.vulkanInterface)
                assertTrue(
                    vertex.indexOf("mat4 TextureMat;") < vertex.indexOf("vec4 ColorModulator;")
                )
            else
                assertTrue(
                    vertex.indexOf("vec4 ColorModulator;") < vertex.indexOf("mat4 TextureMat;")
                )
        }
        assertFalse(DialogFocusShaders.profiles.any { it.min[0] <= 70 && it.max[0] >= 70 })
    }

    @Test
    fun optOutRemovesPreviouslyGeneratedOverlaysWithoutRemappingGlyphs() {
        val assets = catalog()
        val snapshot = snapshot()
        val enabled = DialogFocusShaders.bind(assets, snapshot)
        val pack = AssetCompiler.build(temp.resolve("core/ui"), enabled, temp)
        assertTrue(
            Files.exists(pack.resolve("rafter_v260200/assets/minecraft/shaders/core/gui.fsh"))
        )
        val disabled = DialogFocusShaders.bind(assets.copy(hideFocusOutline = false), snapshot)
        AssetCompiler.build(temp.resolve("core/ui"), disabled, temp)
        assertFalse(Files.exists(pack.resolve("rafter_v260200")))
        assertFalse(Files.exists(pack.resolve("assets/rafter/shaders")))
        assertEquals(enabled.allocation, AssetCompiler.readAllocation(temp))
        assertNotEquals(enabled.fingerprint, disabled.fingerprint)
    }

    @Test
    fun layoutFingerprintIsStableAndChangesWithActualCompiledGeometry() {
        val assets = catalog()
        val first = DialogFocusShaders.bind(assets, snapshot())
        assertEquals(first.fingerprint, DialogFocusShaders.bind(first, snapshot()).fingerprint)
        assertNotEquals(
            first.fingerprint,
            DialogFocusShaders.bind(assets, snapshot(width = 630)).fingerprint,
        )
        assertNotEquals(
            first.fingerprint,
            DialogFocusShaders.bind(assets, snapshot(height = 1953)).fingerprint,
        )
        assertEquals(
            first.allocation,
            DialogFocusShaders.bind(assets, snapshot(height = 1953)).allocation,
        )
    }

    @Test
    fun duplicateBodiesAreDeduplicatedAndOversizedTablesHaveLocatedDiagnostics() {
        val assets = catalog()
        @Suppress("UNCHECKED_CAST")
        fun page(h: Int) = (document(height = h)["pages"] as Map<String, Any?>).getValue("main")
        val same =
            MenuCompiler()
                .compile(
                    mapOf(
                        "menu.yml" to
                            (document() +
                                mapOf("pages" to mapOf("main" to page(360), "other" to page(360))))
                    )
                )
                .snapshot!!
        val bound = DialogFocusShaders.bind(assets, same)
        val pack = AssetCompiler.build(temp.resolve("core/ui"), bound, temp)
        assertEquals(2, bound.focusLayouts.size)
        assertTrue(
            "RAFTER_FOCUS_LAYOUT_COUNT = 1;" in
                Files.readString(pack.resolve("assets/rafter/shaders/include/dialog_focus.glsl"))
        )
        val pages =
            (0..DialogFocusShaders.MAX_GEOMETRIES).associate { i ->
                (if (i == 0) "main" else "p$i") to page((i + 1) * 9)
            }
        val many =
            MenuCompiler()
                .compile(mapOf("menu.yml" to (document() + mapOf("pages" to pages))))
                .snapshot!!
        val error = assertFailsWith<DocumentError> { DialogFocusShaders.bind(assets, many) }
        assertEquals("core/ui/", error.diagnostic.file)
        assertEquals("FOCUS_GEOMETRY_LIMIT", error.diagnostic.code)
        assertFalse(
            DialogFocusShaders.active(
                DialogFocusShaders.bind(assets.copy(hideFocusOutline = false), many)
            )
        )
    }

    @Test
    fun invalidShaderCandidateKeepsPreviousPublishedPack() {
        val enabled = DialogFocusShaders.bind(catalog(), snapshot())
        val pack = AssetCompiler.build(temp.resolve("core/ui"), enabled, temp)
        val before = Files.readString(pack.resolve("manifest.json"))
        val invalid =
            enabled.copy(focusLayouts = listOf(enabled.focusLayouts.single().copy(bodyHeight = 19)))
        assertFails { AssetCompiler.build(temp.resolve("core/ui"), invalid, temp) }
        assertEquals(before, Files.readString(pack.resolve("manifest.json")))
        assertTrue(
            Files.exists(pack.resolve("rafter_v260300/assets/minecraft/shaders/core/gui.vsh"))
        )
        Files.list(temp).use { paths ->
            assertEquals(
                setOf("ui", "resource-pack"),
                paths.map { it.fileName.toString() }.toList().toSet(),
            )
        }
    }

    @Test
    fun invalidNativeShaderCandidateKeepsPreviousPublishedPack() {
        val enabled = DialogFocusShaders.bind(catalog(), snapshot(native = true))
        val pack = AssetCompiler.build(temp.resolve("core/ui"), enabled, temp)
        val manifest = Files.readString(pack.resolve("manifest.json"))
        val include =
            Files.readString(pack.resolve("assets/rafter/shaders/include/dialog_focus.glsl"))
        val invalid =
            enabled.copy(
                nativeFocusLayouts = listOf(enabled.nativeFocusLayouts.single().copy(width = 0))
            )
        assertFails { AssetCompiler.build(temp.resolve("core/ui"), invalid, temp) }
        assertEquals(manifest, Files.readString(pack.resolve("manifest.json")))
        assertEquals(
            include,
            Files.readString(pack.resolve("assets/rafter/shaders/include/dialog_focus.glsl")),
        )
        Files.list(temp).use { paths ->
            assertEquals(
                setOf("ui", "resource-pack"),
                paths.map { it.fileName.toString() }.toList().toSet(),
            )
        }
    }

    @Test
    fun assetFlagIsStrictAndExportedFromTheSameDescriptors() {
        val assets = catalog()
        val document = mapOf("images" to emptyMap<String, Any?>())
        assertFails {
            AssetCompiler.compile(
                temp.resolve("core/ui"),
                document + ("hide-canvas-focus-outline" to "true"),
            )
        }
        assertFails {
            AssetCompiler.compile(
                temp.resolve("core/ui"),
                document + ("hide-canvas-border" to true),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val properties = Contract.assetSchema()["properties"] as Map<String, Any?>
        assertEquals(Contract.asset.keys, properties.keys)
        assertTrue("hide-canvas-focus-outline" in properties)
        assertTrue(assets.hideFocusOutline && assets.hideNativeFocusOutline)
        assertTrue("hide-native-focus-outline" in properties)
        assertFails {
            AssetCompiler.compile(
                temp.resolve("core/ui"),
                document + ("hide-native-focus-outline" to "true"),
            )
        }
    }

    @Test
    fun actualNativePredicateKeepsInputCaretAndSolidFrames() {
        val nodes =
            listOf(
                mapOf("id" to "label", "type" to "text", "text" to "many rows"),
                mapOf(
                    "id" to "item",
                    "type" to "item",
                    "item" to "minecraft:compass",
                    "text" to "description",
                ),
            )
        val snapshot =
            MenuCompiler().compile(mapOf("native.yml" to minimal(components = nodes))).snapshot!!
        val pack =
            AssetCompiler.build(
                temp.resolve("core/ui"),
                DialogFocusShaders.bind(catalog(), snapshot),
                temp,
            )
        verifySourcePredicate(pack)
    }

    @Test
    fun actualGeneratedPredicateHandlesEveryCornerPhaseScrollAndViewportCrop() {
        val bound = DialogFocusShaders.bind(catalog(), snapshot(height = 1953))
        val pack = AssetCompiler.build(temp.resolve("core/ui"), bound, temp)
        verifySourcePredicate(pack)
    }

    private fun verifySourcePredicate(pack: Path) {
        val tools =
            runCatching {
                    val process =
                        ProcessBuilder(
                                "python3",
                                "-c",
                                "import shutil; raise SystemExit(0 if shutil.which('clang++') or shutil.which('g++') else 1)",
                            )
                            .start()
                    process.waitFor() == 0
                }
                .getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(
            tools,
            "Offline source-predicate check requires Python and an existing C++17 compiler; no auto-install",
        )
        val script =
            Path.of("..", "..", "scripts", "verify-dialog-focus.py").toAbsolutePath().normalize()
        val output = temp.resolve("source-predicate.log")
        val process =
            ProcessBuilder("python3", script.toString(), "--pack", pack.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
        val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "Source-only predicate regression exceeded its time limit")
        val log = Files.readString(output)
        assertEquals(0, process.exitValue(), log)
        assertTrue("retained-border-pixels=0" in log && "removed-nontarget-pixels=0" in log, log)
        println(log.trim())
    }
}
