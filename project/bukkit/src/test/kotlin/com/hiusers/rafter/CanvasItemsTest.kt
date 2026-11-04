package com.hiusers.rafter

import com.github.retrooper.packetevents.protocol.nbt.*
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogItemData
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CanvasItemsTest {
    @TempDir lateinit var root: Path

    @BeforeEach
    fun components() {
        taboolib.module.chat.Components.useAdventure = true
    }

    private fun catalog(): AssetCatalog {
        val request = FontRequest("test", 10.0)
        val widths = (32..127).associateWith { 5.0 }
        return AssetCatalog(
            images = emptyMap(),
            widths = widths,
            allocation = emptyMap(),
            fontSource = root.resolve("unused.ttf"),
            imageSpecs = emptyList(),
            fingerprint = "base",
            sourceHashes = emptyMap(),
            faces = mapOf(request.id to widths),
            theme =
                UiTheme(
                    "test",
                    10.0,
                    1.0,
                    emptyMap(),
                    UiTheme.Palette("#FF6666"),
                    TextSpec(value = "Close"),
                    TextSpec(value = "Error"),
                    false,
                    false,
                    false,
                ),
            hideWarning = false,
            hideFocusOutline = false,
            hideNativeFocusOutline = false,
            fontFaces = mapOf(request.id to FontFace(request, root.resolve("unused.ttf"))),
            fontFamilies = mapOf("test" to root.resolve("unused.ttf")),
        )
    }

    private fun item(id: String, x: Int) =
        mapOf(
            "id" to id,
            "type" to "item",
            "item" to "test:items#$id",
            "x" to x,
            "y" to 18,
            "actions" to listOf(mapOf("type" to "close")),
        )

    private fun snapshot(nodes: List<Map<String, Any?>>): MenuSnapshot {
        val result =
            MenuCompiler()
                .compile(
                    mapOf(
                        "items.yml" to
                            mapOf(
                                "id" to "test:items",
                                "entry" to "home",
                                "pages" to
                                    mapOf(
                                        "home" to
                                            mapOf(
                                                "mode" to "canvas",
                                                "width" to 144,
                                                "height" to 90,
                                                "elements" to nodes,
                                            )
                                    ),
                            )
                    )
                )
        return assertNotNull(result.snapshot, result.diagnostics.toString())
    }

    @Test
    fun hiddenRowsKeepTheirCarrierAndSeparateHoverIdentity() {
        val snapshot = snapshot(listOf(item("first", 9), item("second", 63)))
        val assets = CanvasItemShaders.bind(catalog(), snapshot)
        val session =
            UiSession(UUID.randomUUID(), snapshot.menus.getValue("test:items"), emptyMap())
        fun view(second: Boolean) =
            CanvasRenderer.render(
                session,
                Frame(
                    emptyMap(),
                    mapOf("first" to true, "second" to second),
                    mapOf("first" to true, "second" to true),
                    itemCounts = mapOf("first" to 32, "second" to 7),
                ),
                CompositeText(assets),
                mapOf("first" to "first-click", "second" to "second-click"),
            )
        val hidden = view(false)
        val full = view(true)
        assertEquals(0.0, CompositeText(assets).width(full.title), full.title.toRawMessage())
        assertEquals(listOf(0, 1), hidden.canvasItems.map { it.route.row })
        assertEquals(listOf(true, false), hidden.canvasItems.map { it.visible })
        assertEquals(hidden.canvasItems.map { it.route }, full.canvasItems.map { it.route })
        assertEquals(
            setOf("first"),
            hidden.messages.flatMap { it.parts }.mapNotNull { it.hoverItem }.toSet(),
        )
        assertEquals(
            setOf("first", "second"),
            full.messages.flatMap { it.parts }.mapNotNull { it.hoverItem }.toSet(),
        )
        assertTrue(full.messages.flatMap { it.parts }.any { it.text.toPlainText() == "32" })
        assertTrue(full.messages.flatMap { it.parts }.any { it.text.toPlainText() == "7" })
        assertTrue(
            full.messages
                .flatMap { it.parts }
                .any { it.handle == "second-click" && it.hoverItem == "second" }
        )
    }

    @Test
    fun routingIsDeterministicAndGeneratedFromCompiledGeometry() {
        val snapshot = snapshot(listOf(item("first", 9), item("second", 63)))
        val assets = CanvasItemShaders.bind(catalog(), snapshot)
        assertEquals(assets, CanvasItemShaders.bind(assets, snapshot))
        assertEquals(listOf(1, 1), assets.canvasItems.map { it.sourceWidth })
        assertEquals(listOf(63, 117), assets.canvasItems.map { it.sourceTop })
        assertEquals(listOf(639, 639), assets.canvasItems.map { it.sourceLeft(1280) })
        assertEquals(listOf(319, 319), assets.canvasItems.map { it.sourceLeft(640) })
        assertEquals(568, assets.canvasItems.first().canvasLeft(1280))
        assertEquals(248, assets.canvasItems.first().canvasLeft(640))
        assertEquals(166, assets.canvasItems.first().minimumGuiHeight)
        CanvasItemShaders.write(root, assets)
        val shader =
            Files.readString(
                root.resolve("rafter_v260200/assets/minecraft/shaders/core/position_tex_color.vsh")
            )
        assertTrue(shader.startsWith("#version 330\n"))
        assertFalse("gl_VertexID" in shader)
        assertTrue("174.0" in shader && "10.0 + 76.0" in shader)
        assertTrue("128.0) + 0.0" in shader && "128.0) + 54.0" in shader)
        assertFalse("1024" in shader || "dimensions.y <" in shader)
        val flow = assets.canvasFlows.getValue(CanvasItems.key("test:items", "home"))
        assertEquals(0, flow.inset)
        assertEquals(98, flow.bodyHeight)
        assertEquals(128, flow.layoutHeight)
        assertFalse("1280" in shader || "960" in shader || "/* ROUTES */" in shader)
        val exported = Json.map(root.resolve("item-layouts.json"))
        assertEquals(
            ClientRenderProfiles.all.map { it.directory },
            (exported["profiles"] as List<*>).map { (it as Map<*, *>)["directory"] },
        )
        for (profile in ClientRenderProfiles.all) {
            val generated =
                Files.readString(
                    root.resolve(
                        "${profile.directory}/assets/minecraft/shaders/core/position_tex_color.vsh"
                    )
                )
            assertTrue(generated.startsWith("#version ${profile.glsl}\n"))
            assertTrue("vec2 source0" in generated)
            assertFalse("gl_VertexID" in generated || "gl_VertexIndex" in generated)
        }
        assertTrue(Files.exists(root.resolve("assets/rafter/items/empty.json")))
    }

    @Test
    fun wireHoverUsesItsOwnCompleteSnapshotWithoutReinterpretingItemComponents() {
        fun stack(id: String, model: String) =
            NBTCompound().apply {
                setTag("id", NBTString(id))
                setTag("count", NBTInt(7))
                setTag(
                    "components",
                    NBTCompound().apply { setTag("minecraft:item_model", NBTString(model)) },
                )
            }
        val first = stack("minecraft:paper", "test:first")
        val second = stack("minecraft:diamond", "test:second")
        val body =
            NBTCompound().apply {
                setTag("type", NBTString("minecraft:plain_message"))
                setTag(
                    "contents",
                    NBTList.createCompoundList().apply {
                        for (key in listOf("first", "second")) addTag(
                            NBTCompound().apply {
                                setTag("text", NBTString(" "))
                                setTag("insertion", NBTString(CanvasItems.HOVER_PREFIX + key))
                            }
                        )
                    },
                )
            }
        val payload = NBTCompound().apply { setTag("body", body) }
        val result =
            DialogItemData.restore(payload, emptyMap(), mapOf("first" to first, "second" to second))
        val parts = result.getCompoundTagOrThrow("body").getCompoundListTagOrThrow("contents")
        assertEquals(
            "minecraft:paper",
            parts.getTag(0).getCompoundTagOrThrow("hover_event").getStringTagValueOrThrow("id"),
        )
        assertEquals(
            "minecraft:diamond",
            parts.getTag(1).getCompoundTagOrThrow("hover_event").getStringTagValueOrThrow("id"),
        )
        assertEquals(
            "show_item",
            parts.getTag(1).getCompoundTagOrThrow("hover_event").getStringTagValueOrThrow("action"),
        )
        assertNull(parts.getTag(0).getTagOrNull("insertion"))
        assertNotNull(
            body.getCompoundListTagOrThrow("contents").getTag(0).getTagOrNull("insertion")
        )
        first
            .getCompoundTagOrThrow("components")
            .setTag("minecraft:item_model", NBTString("changed"))
        assertEquals(
            "test:first",
            parts
                .getTag(0)
                .getCompoundTagOrThrow("hover_event")
                .getCompoundTagOrThrow("components")
                .getStringTagValueOrThrow("minecraft:item_model"),
        )
    }

    @Test
    fun differentPageRoutesNeverShareASourceCorner() {
        val original = snapshot((0 until 6).map { item("item-$it", it * 18) })
        val menu = original.menus.getValue("test:items")
        val page = menu.pages.getValue("home")
        val pages =
            (0 until 6).associate { index ->
                val id = "page-$index"
                val height = listOf(90, 144, 342, 378, 396, 414)[index]
                id to page.copy(spec = page.spec.copy(id = id, height = height), height = height)
            }
        val expanded = original.copy(menus = mapOf("test:items" to menu.copy(pages = pages)))
        val routes = CanvasItemShaders.bind(catalog(), expanded).canvasItems
        assertEquals(36, routes.size)
        assertTrue(routes.map { it.channel }.toSet().size < routes.size)
        for (width in listOf(320, 640, 641, 1280, 1281, 1920)) {
            for (height in 400..560) {
                val visible = routes.filter {
                    it.minimumGuiHeight <= height && it.minimumGuiWidth <= width
                }
                val corners = visible.flatMap { route ->
                    listOf(0, CanvasItems.ICON_SIZE).flatMap { dx ->
                        listOf(0, CanvasItems.ICON_SIZE).map { dy ->
                            route.sourceLeft(width) + dx to route.sourceTop(height) + dy
                        }
                    }
                }
                assertEquals(
                    corners.size,
                    corners.toSet().size,
                    "Ambiguous corners at GUI ${width}x$height",
                )
            }
        }
        val capabilities = Contract.manifest().getValue("canvas") as Map<*, *>
        val items = capabilities["items"] as Map<*, *>
        assertEquals(false, items["durability-bar"])
        assertEquals("native-tooltip-only-no-modal-background-hover", items["hover-appearance"])
    }
}
