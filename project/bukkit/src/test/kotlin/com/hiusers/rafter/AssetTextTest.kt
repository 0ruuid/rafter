package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.compile.TextSpecDecoder
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.SessionPhase
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class AssetTextTest {
    @TempDir lateinit var temp: Path

    @org.junit.jupiter.api.BeforeEach
    fun componentBackend() {
        taboolib.module.chat.Components.useAdventure = true
    }

    private fun root(): Path {
        val root = temp.resolve("core/ui")
        copyLegacyUiTo(root)
        return root
    }

    @Test
    fun resourcePackUsesOneStableDirectoryAcrossBuilds() {
        val root = root()
        val doc = legacyUiDocument()
        val first = AssetCompiler.compile(root, doc)
        val pack = AssetCompiler.build(root, first, temp)
        assertEquals(temp.resolve("generate/resource-pack"), pack)
        Files.writeString(pack.resolve("stale-output.txt"), "obsolete")
        val updated =
            AssetCompiler.compile(
                root,
                doc + mapOf("hide-dialog-warning" to false),
                first.allocation,
            )
        assertNotEquals(first.fingerprint, updated.fingerprint)
        assertEquals(pack, AssetCompiler.build(root, updated, temp))
        assertEquals(updated.fingerprint, Json.map(pack.resolve("manifest.json"))["fingerprint"])
        assertFalse(Files.exists(pack.resolve("stale-output.txt")))
        assertFalse(Files.exists(pack.resolve("assets/minecraft")))
        Files.list(temp).use { paths ->
            assertEquals(
                setOf("ui", "resource-pack"),
                paths.map { it.fileName.toString() }.toList().toSet(),
            )
        }
    }

    @Test
    fun buildsOnlyNamespacedDirectoryAndStableGlyphLedger() {
        val root = root()
        val doc = legacyUiDocument()
        val first = AssetCompiler.compile(root, doc)
        val pack = AssetCompiler.build(root, first, temp)
        assertTrue(Files.exists(pack.resolve("manifest.json")))
        assertFalse(Files.exists(pack.resolve("assets/minecraft/textures/gui/sprites/widget")))
        val second = AssetCompiler.compile(root, doc, first.allocation)
        assertEquals(first.images, second.images)
        val manifest = Files.readString(pack.resolve("manifest.json"))
        assertFails {
            AssetCompiler.compile(
                root,
                doc + mapOf("font" to "../../outside.ttf"),
                first.allocation,
            )
        }
        assertEquals(manifest, Files.readString(pack.resolve("manifest.json")))
        assertTrue(
            Files.walk(pack).use { paths ->
                paths.noneMatch { it.fileName.toString().endsWith(".zip") }
            }
        )
    }

    @Test
    fun exportedGlyphsAreActuallyProvidedByTheMonoFont() {
        val root = root()
        val catalog = AssetCompiler.compile(root, legacyUiDocument())
        val font =
            java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, catalog.fontSource.toFile())
        assertTrue(catalog.widths.keys.all { it == 32 || font.canDisplay(it) })
    }

    @Test
    fun sourceChangeCannotReplaceTheLastSuccessfulPack() {
        val root = root()
        val catalog = AssetCompiler.compile(root, legacyUiDocument())
        val pack = AssetCompiler.build(root, catalog, temp)
        val before =
            Files.walk(pack)
                .use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
                .associate {
                    pack.relativize(it).toString() to
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(it))
                            .toList()
                }
        Files.writeString(root.resolve("fonts/rafter-mono.ttf"), "changed during build")
        assertFails { AssetCompiler.build(root, catalog, temp) }
        val after =
            Files.walk(pack)
                .use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
                .associate {
                    pack.relativize(it).toString() to
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(it))
                            .toList()
                }
        assertEquals(before, after)
    }

    @Test
    fun failedCandidatePreservesPublishedOutputAndRemovesTemporaryFiles() {
        val root = root()
        val catalog = AssetCompiler.compile(root, legacyUiDocument())
        val pack = AssetCompiler.build(root, catalog, temp)
        val manifest = Files.readString(pack.resolve("manifest.json"))
        val invalid =
            catalog.copy(
                imageSpecs =
                    catalog.imageSpecs.map {
                        if (it.source != null) it.copy(width = it.width + 9) else it
                    }
            )
        assertFails { AssetCompiler.build(root, invalid, temp) }
        assertEquals(manifest, Files.readString(pack.resolve("manifest.json")))
        assertTrue(Files.exists(pack.resolve("pack.mcmeta")))
        Files.list(temp).use { paths ->
            assertEquals(
                setOf("ui", "resource-pack"),
                paths.map { it.fileName.toString() }.toList().toSet(),
            )
        }
    }

    @Test
    fun readsStableLedgerAndImportsLegacyAllocationWithoutPublishingHashDirectories() {
        assertEquals(emptyMap(), AssetCompiler.readAllocation(temp))
        val oldHash = "a".repeat(64)
        Json.write(temp.resolve("generated/current.json"), mapOf("directory" to oldHash))
        Json.write(
            temp.resolve("generated/$oldHash/manifest.json"),
            mapOf("allocation" to mapOf("rafter:mark" to 0xe300)),
        )
        assertEquals(mapOf("rafter:mark" to 0xe300), AssetCompiler.readAllocation(temp))
        val root = root()
        val catalog =
            AssetCompiler.compile(root, legacyUiDocument(), AssetCompiler.readAllocation(temp))
        val pack = AssetCompiler.build(root, catalog, temp)
        assertEquals(catalog.allocation, AssetCompiler.readAllocation(temp))
        Json.write(temp.resolve("generated/current.json"), mapOf("directory" to "invalid"))
        assertEquals(catalog.allocation, AssetCompiler.readAllocation(temp))
        Json.write(
            pack.resolve("manifest.json"),
            mapOf("allocation" to mapOf("rafter:mark" to 58112.5)),
        )
        assertFails { AssetCompiler.readAllocation(temp) }
    }

    @Test
    fun newlySortedImagesDoNotRemapExistingGlyphs() {
        val root = root()
        val doc = legacyUiDocument()
        val original = AssetCompiler.compile(root, doc)
        @Suppress("UNCHECKED_CAST") val images = doc["images"] as Map<String, Any?>
        val expanded =
            doc +
                mapOf(
                    "images" to
                        images +
                            mapOf(
                                "rafter:aaa" to
                                    mapOf("width" to 9, "height" to 9, "color" to "#ffffff")
                            )
                )
        val next = AssetCompiler.compile(root, expanded, original.allocation)
        assertEquals(original.images.getValue("rafter:mark"), next.images.getValue("rafter:mark"))
        assertNotEquals(
            next.images.getValue("rafter:aaa").character,
            next.images.getValue("rafter:mark").character,
        )
    }

    @Test
    fun oldHeldLedgerEntriesAreDroppedWithoutMovingImageGlyphs() {
        val root = root()
        val images =
            mapOf(
                "rafter:aaa" to mapOf("width" to 9, "height" to 9, "color" to "#ffffff"),
                "rafter:mark" to mapOf("width" to 9, "height" to 9, "color" to "#000000"),
            )
        val retained = mapOf("rafter:mark" to 0xe345, "rafter:unused#0,9" to 0xe34a)
        val previous =
            retained +
                mapOf(
                    "held:minecraft:diamond_sword" to 0xf0000,
                    "held:test:duplicate" to 0xe345,
                    "held:test:invalid" to -1,
                )
        Json.write(temp.resolve("resource-pack/allocation.json"), previous)
        val catalog =
            AssetCompiler.compile(
                root,
                legacyUiDocument() + mapOf("images" to images),
                AssetCompiler.readAllocation(temp),
            )
        val expected = retained + mapOf("rafter:aaa" to 0xe34b)
        assertEquals(expected, catalog.allocation)
        assertEquals(0xe345, catalog.images.getValue("rafter:mark").character.codePointAt(0))
        assertEquals(0xe34b, catalog.images.getValue("rafter:aaa").character.codePointAt(0))
        AssetCompiler.build(root, catalog, temp)
        assertEquals(expected, AssetCompiler.readAllocation(temp))
    }

    @Test
    fun nativeBitmapImagesReserveEnoughTextRows() {
        val catalog = AssetCompiler.compile(root(), legacyUiDocument())
        val text = CompositeText(catalog)
        val document =
            minimal(
                components =
                    listOf(mapOf("id" to "image", "type" to "image", "asset" to "rafter:mark"))
            )
        val menu =
            MenuCompiler(catalog.dimensions())
                .compile(mapOf("menu.yml" to document))
                .snapshot!!
                .menus
                .getValue("test:menu")
        val session =
            com.hiusers.rafter.runtime.UiSession(java.util.UUID.randomUUID(), menu, emptyMap())
        val frame =
            com.hiusers.rafter.render.Frame(
                emptyMap(),
                mapOf("image" to true),
                mapOf("image" to true),
            )
        val view = com.hiusers.rafter.render.NativeRenderer.render(session, frame, text)
        val body = view.messages.single().parts.joinToString("") { it.text.toPlainText() }
        assertTrue(body.count { it == '\n' } >= 2)
    }

    @Test
    fun renderersOnlyExposeSuppliedHandlesWithoutChangingSessionOrAuthorization() {
        val catalog = AssetCompiler.compile(root(), legacyUiDocument())
        val text = CompositeText(catalog)
        fun tokens(view: DialogView): Set<String> =
            (view.buttons.mapNotNull { it.handle } +
                    view.messages.flatMap { message -> message.parts.mapNotNull { it.handle } } +
                    listOfNotNull(view.close.handle))
                .toSet()
        for (mode in listOf("native", "canvas")) {
            val ids = listOf("allowed", "unissued", "disabled", "hidden")
            val components = ids.mapIndexed { index, id ->
                mapOf(
                    "id" to id,
                    "type" to if (mode == "canvas") "text" else "button",
                    "text" to "Go",
                    "width" to 90,
                    "actions" to listOf(mapOf("type" to "close")),
                ) + if (mode == "canvas") mapOf("height" to 18, "y" to index * 27) else emptyMap()
            }
            val result =
                MenuCompiler(catalog.dimensions())
                    .compile(mapOf("menu.yml" to minimal(mode, components)))
            val menu =
                assertNotNull(result.snapshot, result.diagnostics.toString())
                    .menus
                    .getValue("test:menu")
            val session = UiSession(java.util.UUID.randomUUID(), menu, emptyMap())
            val frame =
                Frame(
                    emptyMap(),
                    ids.associateWith { it != "hidden" },
                    ids.associateWith { it != "disabled" },
                )
            fun render(handles: Map<String, String> = emptyMap()): DialogView =
                if (mode == "native") NativeRenderer.render(session, frame, text, handles = handles)
                else CanvasRenderer.render(session, frame, text, handles)
            val initial = session.generation
            assertTrue(tokens(render()).isEmpty())
            assertEquals(initial, session.generation)
            assertEquals(SessionPhase.CREATED, session.phase)
            val generation = session.beginRender()
            val handles = session.ready(generation, listOf("allowed", "__close"))
            val state = session.state.toMap()
            session.form["draft"] = "safe"
            val lastInteraction = session.lastInteraction
            assertEquals(handles.values.toSet(), tokens(render(handles)))
            assertEquals(handles.values.toSet(), tokens(render(handles)))
            assertTrue(tokens(render()).isEmpty())
            val forged = java.util.UUID.randomUUID().toString()
            assertEquals(setOf(forged), tokens(render(mapOf("allowed" to forged))))
            assertNull(session.consume(forged, lastInteraction))
            assertNull(session.consume("unissued", lastInteraction))
            assertEquals(generation, session.generation)
            assertEquals(SessionPhase.READY, session.phase)
            assertEquals(state, session.state)
            assertEquals(mapOf<String, Any?>("draft" to "safe"), session.form)
            assertEquals(lastInteraction, session.lastInteraction)
            assertEquals("allowed", session.consume(handles.getValue("allowed"), lastInteraction))
            assertNull(session.consume(handles.getValue("__close"), lastInteraction))
        }
    }

    @Test
    fun authoredColorsRenderButLiteralAndBindingValuesStayUntouched() {
        val text =
            CompositeText(
                AssetCompiler.compile(root(), legacyUiDocument())
            ) // 只编译夹具，不生成资源包。
        val decoder = TextSpecDecoder("text.yml", text::validate)
        val source = "&aGreen &{#ffffff}White [OP](command=op me)"
        val plain = "Green White [OP](command=op me)"
        fun noClicks(component: taboolib.module.chat.ComponentText) {
            val json = component.toRawMessage()
            assertFalse("clickEvent" in json || "click_event" in json, json)
        }
        for (input in listOf(source, mapOf("format" to "colored", "value" to source))) {
            val spec = decoder.decode(input, "$.text")
            assertEquals("colored", spec.format)
            val component = text.render(spec)
            assertEquals(plain, component.toPlainText())
            val runs = mutableListOf<Pair<String, net.kyori.adventure.text.format.TextColor?>>()
            fun collect(
                node: net.kyori.adventure.text.Component,
                inherited: net.kyori.adventure.text.format.TextColor? = null,
            ) {
                val color = node.color() ?: inherited
                if (node is net.kyori.adventure.text.TextComponent && node.content().isNotEmpty())
                    runs += node.content() to color
                node.children().forEach { collect(it, color) }
            }
            collect(component.toAdventureObject())
            assertEquals(
                "Green ",
                runs.filter { it.second?.value() == 0x55ff55 }.joinToString("") { it.first },
            )
            assertEquals(
                "White [OP](command=op me)",
                runs.filter { it.second?.value() == 0xffffff }.joinToString("") { it.first },
            )
            noClicks(component)
        }
        val bound =
            compiled(
                    minimal(
                        components =
                            listOf(
                                mapOf(
                                    "id" to "bound",
                                    "type" to "text",
                                    "binding" to mapOf("type" to "state", "key" to "name"),
                                )
                            )
                    )
                )
                .spec
                .pages
                .getValue("main")
                .nodes
                .single()
                .text
        for (spec in
            listOf(
                TextSpec(value = source),
                decoder.decode(mapOf("format" to "literal", "value" to source), "$.text"),
                bound,
            )) {
            assertEquals("literal", spec.format)
            val component = text.render(spec, mapOf(BindingSpec("state", "name") to source))
            assertEquals(source, component.toPlainText())
            assertFalse("\"color\"" in component.toRawMessage())
            noClicks(component)
        }
    }

    @Test
    fun variableTextIsLiteralAndBuildersAreNotShared() {
        val assets = AssetCompiler.compile(root(), legacyUiDocument())
        val text = CompositeText(assets)
        val binding = BindingSpec("state", "name")
        val dangerous = "<red>[OP](command=op me)"
        val spec =
            TextSpec(
                format = "parts",
                parts =
                    listOf(
                        TextSpec(value = "Label: "),
                        TextSpec(
                            binding = binding,
                            color = "#59b887",
                            hover = TextSpec(value = "tip"),
                        ),
                    ),
            )
        val component = text.render(spec, mapOf(binding to dangerous))
        val json = component.toRawMessage()
        assertTrue(component.toPlainText().contains(dangerous))
        assertFalse(json.contains("run_command"))
        assertFalse(json.contains("clickEvent") || json.contains("click_event"))
        assertTrue(json.contains("hoverEvent") || json.contains("hover_event"), json)
        assertTrue(json.contains(FontRequest("rafter-mono", 10.0).id))
        val simple = text.render(TextSpec(format = "simple", value = "[X](font=minecraft:default)"))
        assertTrue(simple.toRawMessage().contains("minecraft:default"))
        val other = text.render(spec, mapOf(binding to "Other"))
        assertFalse(other.toPlainText().contains(dangerous))
        assertNotSame(component, other)
        assertFails { text.validate(TextSpec(format = "simple", value = "[OP](command=op me)")) }
        assertFails { text.validate(TextSpec(format = "simple", value = "[broken](hover=x")) }
    }

    @Test
    fun transportBatchAssemblyIsIdenticalToAppendingIndependentComponents() {
        val text =
            CompositeText(
                AssetCompiler.compile(
                    root(),
                    legacyUiDocument(),
                    requests = setOf(FontRequest("rafter-mono", 20.0)),
                )
            )
        val token = "00000000-0000-0000-0000-000000000001"
        val parts =
            (0 until 40).map { i ->
                com.hiusers.rafter.render.Fragment(
                    text.render(
                        TextSpec(
                            value = "字形$i",
                            font = "rafter-mono",
                            fontSize = 20.0,
                            color = "#3c8527",
                            hover = TextSpec(value = "悬停$i"),
                        )
                    ),
                    if (i % 3 == 0) token else null,
                )
            }
        val serializer = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
        val original =
            parts.fold(net.kyori.adventure.text.Component.empty()) { result, part ->
                val converted = part.text.toAdventureObject()
                result.append(
                    if (part.handle == null) converted
                    else
                        converted.clickEvent(
                            net.kyori.adventure.text.event.ClickEvent.custom(
                                net.kyori.adventure.key.Key.key("rafter:ui/${part.handle}"),
                                net.kyori.adventure.nbt.api.BinaryTagHolder.binaryTagHolder("{}"),
                            )
                        )
                )
            }
        assertEquals(
            serializer.serialize(original),
            serializer.serialize(com.hiusers.rafter.transport.DialogTransport().wire(parts)),
        )
    }

    @Test
    fun legacyRenderingFixtureCompilesAndTextSurvivesWireConversion() {
        val catalog = AssetCompiler.compile(root(), legacyUiDocument())
        val text = CompositeText(catalog)
        val result =
            MenuCompiler(
                    catalog.dimensions(),
                    textCheck = text::validate,
                    styles = catalog.theme.styles,
                )
                .compile(mapOf("core/menus/demo.yml" to legacyYaml("core/menus/demo.yml")))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        assertEquals(
            setOf("home", "form", "preview", "journal", "settings", "timeline"),
            snapshot.menus.getValue("rafter:demo").pages.keys,
        )
        val c =
            text.render(
                TextSpec(
                    value = "test",
                    font = "rafter-mono",
                    hover = TextSpec(value = "nested", hover = TextSpec(value = "inner")),
                )
            )
        val wire =
            com.hiusers.rafter.transport
                .DialogTransport()
                .wire(
                    listOf(
                        com.hiusers.rafter.render.Fragment(
                            c,
                            "00000000-0000-0000-0000-000000000001",
                        )
                    )
                )
        val serialized =
            net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(wire)
        assertTrue(serialized.contains(FontRequest("rafter-mono", 10.0).id))
        assertTrue(serialized.contains("nested"))
        assertTrue(serialized.contains("inner"))
        assertTrue(serialized.contains("rafter:ui/00000000-0000-0000-0000-000000000001"))
        assertTrue(serialized.contains("custom"))
    }
}
