package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCatalog
import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.asset.manifest
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.NativeRenderer
import com.hiusers.rafter.runtime.FrozenCanvas
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PlayerProfilePreviewTest {
    @TempDir lateinit var root: Path
    private val output = Path.of("../../build/previews/player-profile").toAbsolutePath().normalize()

    private fun repository() =
        MenuRepository(
            root,
            { error("No offline Kether execution") },
            ::checkStaticItemReference,
        )

    @Test
    fun compilesStatesFormsAndResourcesAndRendersActualConfiguration() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val loaded = repository().load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        assertTrue(loaded.diagnostics.isEmpty(), loaded.diagnostics.toString())
        assertEquals(
            "rafter:friends",
            candidate.defaultMenu,
            "Do not change the user's default menu",
        )
        val menu = candidate.snapshot.menus.getValue("rafter:player-profile")
        assertEquals(setOf("profile", "chat", "memo", "achievements", "name"), menu.pages.keys)
        assertTrue(
            menu.spec.pages.values
                .flatMap { it.nodes }
                .flatMap { it.actions }
                .none { it.type in setOf("provider", "kether") }
        )
        val page = menu.pages.getValue("profile")
        assertEquals(560 to 342, page.spec.width to page.height)
        assertTrue(
            page.spec.nodes.all { it.overflow == "error" },
            "Do not hide overflow with clipping",
        )
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        val text = CompositeText(candidate.assets)
        var frame = OfflineFrame.resolve(session)
        fun refresh() {
            frame = OfflineFrame.resolve(session)
        }
        fun label(id: String) =
            text.render(frame.resolvedNodes.getValue(id).text, frame.values).toPlainText()
        fun show(): com.hiusers.rafter.render.DialogView {
            refresh()
            val targets =
                frame.resolvedNodes.values
                    .filter {
                        it.clickable &&
                            frame.visible[it.id] == true &&
                            frame.enabled[it.id] == true &&
                            session.acceptsTarget(it.id)
                    }
                    .map { it.id } +
                    if (session.popup != null) listOf("__popup-dismiss") else emptyList()
            val handles = session.ready(session.beginRender(), targets)
            return if (session.current().spec.mode == PageMode.CANVAS)
                CanvasRenderer.render(session, frame, text, handles)
            else NativeRenderer.render(session, frame, text, handles = handles)
        }
        fun preview(
            name: String,
            active: UiSession = session,
            catalog: AssetCatalog = candidate.assets,
        ) {
            val resolved =
                if (active === session) {
                    show()
                    frame
                } else
                    OfflineFrame.resolve(active).also {
                        CanvasRenderer.render(active, it, CompositeText(catalog))
                    }
            val measured = CompositeText(catalog)
            val current = active.current()
            val hidden = current.spec.popups[active.popup]?.hideUnderlay.orEmpty()
            val scene =
                (current.scene.filter { it.node.id !in hidden && it.node.hitTarget !in hidden } +
                        current.popups[active.popup].orEmpty())
                    .filter { resolved.visible[it.node.id] == true }
                    .map { placed ->
                        val node = resolved.resolvedNodes.getValue(placed.node.id)
                        val width =
                            if (node.content == NodeContent.Text)
                                measured
                                    .width(measured.render(node.text, resolved.values))
                                    .roundToInt()
                            else placed.bounds.width
                        val x =
                            if (node.content == NodeContent.Text)
                                when (node.align) {
                                    "center" -> (node.x - width / 2.0).roundToInt()
                                    "right" -> node.x - width
                                    else -> node.x
                                }
                            else placed.bounds.x
                        PositionedNode(node, placed.bounds.copy(x = x))
                    }
            UiPreview.render(
                current.copy(scene = scene),
                catalog,
                root.resolve("core/ui"),
                output.resolve("$name.png"),
                resolved.values,
            )
        }
        fun patch(field: String, value: Any?) {
            session.data.patch("profile", "player-1", field, value)
            refresh()
        }

        assertEquals("林间旅人", label("info.name"))
        assertEquals("3 / 6", label("info.count"))
        assertTrue(frame.enabled.getValue("relationship"))
        assertTrue(frame.enabled.getValue("chat"))
        preview("profile")
        patch("friend", true)
        assertFalse(frame.enabled.getValue("relationship"))
        assertEquals("已是好友", label("relationship-label"))
        preview("friend")
        val other = UiSession(UUID.randomUUID(), menu, emptyMap())
        assertTrue(
            OfflineFrame.resolve(other).enabled.getValue("relationship"),
            "Preview friendship is session-local",
        )

        patch("online", false)
        assertEquals("离线", label("info.status"))
        assertFalse(frame.enabled.getValue("chat"))
        assertFalse(frame.enabled.getValue("teleport"))
        preview("offline")
        session.openPopup("more")
        refresh()
        assertFalse(frame.enabled.getValue("popup-trade"))
        assertFalse(session.acceptsTarget("chat"))
        assertTrue(session.acceptsTarget("popup-name"))
        preview("more-offline")
        session.closePopup()
        patch("online", true)
        session.openPopup("more")
        preview("more")
        val popup = page.popups.getValue("more")
        val rows =
            listOf("home", "trade", "memo", "name").map { id ->
                popup.single { it.node.id == "popup-$id" }.bounds
            }
        assertEquals(listOf(189, 216, 243, 270), rows.map { it.y })
        assertTrue(rows.all { it.height == 27 && it.y % 9 == 0 })
        assertTrue(rows.zipWithNext().all { (a, b) -> a.y + a.height <= b.y })
        val outsideFooter = popup.single { it.node.id == "popup-outside-footer" }
        assertEquals(Rect(364, 297, 88, 27), outsideFooter.bounds)
        assertEquals(listOf("close-popup"), outsideFooter.node.actions.map { it.type })
        assertTrue(
            session.acceptsTarget(outsideFooter.node.id),
            "The panel/anchor bounding-box gap must also dismiss, not become a dead strip",
        )
        for (region in listOf("info.badge-builder", "info.badge-helper", "info.achievement-link")) {
            assertTrue(
                page.spec.popups.getValue("more").hideUnderlay.any { it.startsWith("$region.") }
            )
        }
        assertFalse(
            page.spec.elements.single { it.id == "info.badge-explorer.hover" }.clickable,
            "Badge tooltip must not consume a background click",
        )
        session.closePopup()

        // 按导出的字体 advance 量测，不按字数估宽。
        val longName = "在群山与海洋之间寻找全新冒险的方块世界旅行家"
        patch("name", longName)
        patch("guild", "一起探索方块世界的冒险者公会")
        assertTrue(
            text.width(text.render(frame.resolvedNodes.getValue("info.name").text, frame.values)) <=
                288
        )
        assertEquals(longName, label("info.name"))
        preview("long-name")
        patch("name", "方".repeat(80))
        val overflow = assertFailsWith<DocumentError> { show() }.diagnostic
        assertEquals("LAYOUT", overflow.code)
        assertEquals("info.name", overflow.component)
        patch("name", "不能放在单行画布的\n多行名称")
        assertTrue(
            assertFailsWith<IllegalArgumentException> { show() }.message!!.contains("single line")
        )
        patch("name", "&a{{ player name }}<red>")
        assertEquals("&a{{ player name }}<red>", label("info.name"))
        show() // 数据保持字面含义，不执行脚本或格式标记。
        patch("name", "林间旅人")
        patch("guild", "青苔工坊")
        for (badge in listOf("explorer", "builder", "helper")) patch("$badge-unlocked", false)
        patch("unlocked", 0)
        assertTrue(frame.visible.getValue("info.empty-badges"))
        preview("no-achievements")
        for (badge in listOf("explorer", "builder", "helper")) patch("$badge-unlocked", true)
        patch("unlocked", 3)

        // 无选择时禁用资料动作。
        session.setState("selected-player", "missing")
        refresh()
        assertTrue(frame.visible.getValue("empty.title"))
        assertFalse(frame.visible.getValue("info.name"))
        assertTrue(
            listOf("relationship", "chat", "teleport", "more").all { frame.enabled[it] == false }
        )
        preview("missing-player")
        session.setState("selected-player", "player-1")

        val multiline = "第一行备注\n&a{{ player name }}<red>\n第三行不会作为脚本执行"
        for ((target, state, limit) in
            listOf(Triple("chat", "chat-message", 256), Triple("memo", "memo", 128))) {
            session.rememberCanvas(FrozenCanvas.capture(session, show(), text))
            session.navigate(target)
            val form = session.current()
            val plan = assertNotNull(form.modalLayout)
            val panel =
                candidate.assets.modalLayouts.single { it.key == "rafter:player-profile/$target" }
            val input =
                form.spec.components.single { it.id == "input" }.content as NodeContent.TextInput
            assertEquals(limit, input.maxLength)
            assertTrue(input.multiline)
            assertEquals(64, input.multilineHeight)
            assertFalse(input.labelVisible)
            assertEquals(5, input.maxLines)
            val bounds = plan.inputs.map { it.bounds } + plan.buttons.map { it.bounds }
            assertTrue(
                bounds.all {
                    it.y >= panel.offsetY && it.y + it.height <= panel.offsetY + panel.height
                }
            )
            assertEquals(1, plan.inputs.size)
            assertEquals(
                listOf("submit", "set-state", "back"),
                form.spec.components.single { it.id == "confirm" }.actions.map { it.type },
            )
            assertEquals(
                listOf("back"),
                form.spec.components.single { it.id == "cancel" }.actions.map { it.type },
            )
            session.setState(state, multiline)
            assertEquals(multiline, show().inputs.single().initial)
            assertFailsWith<IllegalArgumentException> {
                session.setState(state, "字".repeat(limit + 1))
            }
            session.back()
            show()
        }
        session.navigate("achievements")
        val all = show()
        assertTrue(
            all.messages.flatMap { it.parts }.any { "\n" in it.text.toPlainText() },
            "Multiline content uses real native text flow",
        )
        session.back()
        session.navigate("name")
        assertTrue(show().messages.flatMap { it.parts }.any { it.text.toPlainText() == "林间旅人" })
        session.back()

        // 空数据源同样必须可渲染。
        Files.writeString(
            root.resolve("core/data/player-profile.yml"),
            "id: preview:player-profile\nitems: []\n",
        )
        val emptyResult = repository().load()
        val emptyCandidate =
            assertNotNull(emptyResult.candidate, emptyResult.diagnostics.toString())
        val emptySession =
            UiSession(
                UUID.randomUUID(),
                emptyCandidate.snapshot.menus.getValue("rafter:player-profile"),
                emptyMap(),
            )
        val emptyFrame = OfflineFrame.resolve(emptySession)
        assertTrue(emptyFrame.visible.getValue("empty.title"))
        preview("empty-data", emptySession, emptyCandidate.assets)
        // 构包前恢复源文件，保持来源摘要一致。
        Files.write(
            root.resolve("core/data/player-profile.yml"),
            javaClass.classLoader.getResourceAsStream("core/data/player-profile.yml")!!.use {
                it.readBytes()
            },
        )

        val pack =
            AssetCompiler.build(root.resolve("core/ui"), candidate.assets, output.resolve("pack"))
        assertTrue(Files.isRegularFile(pack.resolve("pack.mcmeta")))
        Json.writeContract(
            output.resolve("contract/authoring.json"),
            AuthoringContract.manifest("0.0.2", emptyMap()) +
                ("source-fingerprints" to
                    candidate.assets.sourceHashes.entries.associate {
                        root.relativize(it.key).toString() to it.value
                    }),
        )
        Json.writeContract(
            output.resolve("contract/layouts.json"),
            AuthoringContract.layouts(candidate.snapshot),
        )
        Json.writeContract(output.resolve("contract/assets.json"), candidate.assets.manifest())
        Json.writeContract(
            output.resolve("contract/preview-data.json"),
            AuthoringContract.previewData(candidate.snapshot),
        )
        Json.writeContract(
            output.resolve("verification.json"),
            mapOf(
                "contract-version" to AuthoringContract.VERSION,
                "source" to "actual compiled YAML and generated resources",
                "checks" to
                    listOf(
                        "strict compiler and references",
                        "real font metrics",
                        "friend and offline states",
                        "popup row geometry and occlusion",
                        "empty and missing records",
                        "long names without clipping; unbounded/newline canvas names explicitly rejected",
                        "literal dynamic text",
                        "native multiline flow and length limits",
                        "real resource-pack build",
                    ),
                "previews" to
                    listOf(
                        "profile",
                        "friend",
                        "offline",
                        "more",
                        "more-offline",
                        "long-name",
                        "no-achievements",
                        "missing-player",
                        "empty-data",
                    ),
                "not-verified" to
                    listOf(
                        "Minecraft client display and GUI scales",
                        "live player/model/equipment",
                        "social providers and persistence",
                    ),
                "fallbacks" to
                    mapOf(
                        "copy-name" to
                            "native full-name view; no clipboard action in the current contract"
                    ),
            ),
        )
    }

    @Test
    fun rejectsUnknownTypesFieldsDuplicateIdsMissingAssetsStatesAndActionTargets() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val skin = root.resolve("core/ui/skins/player-profile/home.yml")
        val menu = root.resolve("core/menus/player-profile.yml")
        val originalSkin = Files.readString(skin)
        val originalMenu = Files.readString(menu)
        val cases =
            listOf(
                Triple("unknown-field", menu, originalMenu + "\nunknown-profile-field: true\n"),
                Triple("wrong-type", skin, originalSkin.replace("width: 560", "width: \"560\"")),
                Triple(
                    "unknown-component",
                    skin,
                    originalSkin.replaceFirst("type: image", "type: unsupported-profile-widget"),
                ),
                Triple(
                    "duplicate-id",
                    skin,
                    originalSkin.replace("id: close-image", "id: back-image"),
                ),
                Triple(
                    "missing-asset",
                    skin,
                    originalSkin.replace(
                        "asset: rafter:player-profile/background",
                        "asset: rafter:player-profile/absent",
                    ),
                ),
                Triple(
                    "missing-state",
                    menu,
                    originalMenu.replace("state: chat-message", "state: undeclared-state"),
                ),
                Triple(
                    "missing-action-target",
                    skin,
                    originalSkin.replace("page: achievements", "page: absent-page"),
                ),
            )
        val diagnostics = cases.associate { (name, path, invalid) ->
            assertNotEquals(
                if (path == skin) originalSkin else originalMenu,
                invalid,
                "$name must change its fixture",
            )
            Files.writeString(path, invalid)
            val result =
                try {
                    repository().load()
                } finally {
                    Files.writeString(skin, originalSkin)
                    Files.writeString(menu, originalMenu)
                }
            assertTrue(result.candidate == null, "$name must be rejected, not compiled")
            assertTrue(result.diagnostics.isNotEmpty(), name)
            name to result.diagnostics.map { it.toString() }
        }
        Json.writeContract(output.resolve("compiler-diagnostics.json"), diagnostics)
    }
}
