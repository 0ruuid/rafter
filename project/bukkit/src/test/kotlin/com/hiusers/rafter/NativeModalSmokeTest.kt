package com.hiusers.rafter

import com.google.gson.JsonParser
import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.pack.PackBuild
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.DialogView
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.FrozenCanvas
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import taboolib.module.chat.Components

/** 离线验证默认搜索弹窗与真实构包，不启动服务。 */
class NativeModalSmokeTest {
    @TempDir lateinit var root: Path

    @Test
    fun canvasFeedbackClipsLongErrorsWithoutChangingControlsOrSharedText() {
        Components.useAdventure = true
        DefaultBundle.install(root)
        val skin = root.resolve("core/ui/skins/friends/search.yml")
        Files.writeString(
            skin,
            Files.readString(skin).replace("feedback: description", "feedback: title"),
        )
        val theme = root.resolve("core/ui/theme.yml")
        val longError = "请检查".repeat(100)
        Files.writeString(
            theme,
            Files.readString(theme).replace("form-error: 请检查填写的内容。", "form-error: $longError"),
        )
        val loaded = MenuRepository(root, {}, { _, _, _ -> }).load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        val session =
            UiSession(
                UUID.randomUUID(),
                candidate.snapshot.menus.getValue("rafter:friends"),
                emptyMap(),
            )
        val text = CompositeText(candidate.assets)
        fun show(errors: Map<String, Any?> = emptyMap()): DialogView {
            val frame = OfflineFrame.resolve(session)
            val targets =
                frame.resolvedNodes.values
                    .filter {
                        it.clickable && frame.visible[it.id] == true && frame.enabled[it.id] == true
                    }
                    .map { it.id }
            return CanvasRenderer.render(
                session,
                frame,
                text,
                session.ready(session.beginRender(), targets),
                errors,
            )
        }
        session.rememberCanvas(FrozenCanvas.capture(session, show(), text))
        session.navigate("search")
        val normal = show()
        val error = show(mapOf("input" to "INVALID_VALUE"))
        assertEquals(
            normal.inputs.map { it.node.id to it.initial },
            error.inputs.map { it.node.id to it.initial },
        )
        assertEquals(normal.bodies.size, error.bodies.size)
        assertEquals(normal.buttons.map { it.node.width }, error.buttons.map { it.node.width })
        val painted = error.messages.first().parts.joinToString("") { it.text.toPlainText() }
        assertTrue("请检查" in painted)
        assertFalse(longError in painted)
        assertTrue(
            error.messages.first().parts.any { longError in it.text.toRawMessage() },
            "Full error remains in hover",
        )
        assertTrue(
            "搜索好友" in show().messages.first().parts.joinToString("") { it.text.toPlainText() },
            "Error presentation must not mutate the shared node",
        )
    }

    @Test
    fun searchKeepsItsOriginAndUsesTheSuppliedPanelAroundRealControls() {
        Components.useAdventure = true
        val nativeTitleY = Math.round((33 - 20) / 2f) + Math.round((20 - 9) / 2f)
        assertEquals(
            nativeTitleY,
            com.hiusers.rafter.asset.ModalEncoding.TITLE_Y,
            "Header and title both round vertical centring; treating them as truncation shifts frozen backgrounds by 2px",
        )
        System.getProperty("rafter.modal.output")?.let {
            root = Files.createDirectories(Path.of(it))
        }
        DefaultBundle.install(root)
        javaClass.getResourceAsStream("/fixtures/dialog-composition.yml")!!.use {
            Files.copy(it, root.resolve("core/menus/composition.yml"))
        }
        val loaded =
            MenuRepository(
                    root,
                    { error("Unexpected script") },
                    ::checkStaticItemReference,
                    { Yaml(SafeConstructor(LoaderOptions())).load(it) },
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        val menu = candidate.snapshot.menus.getValue("rafter:friends")
        val text = CompositeText(candidate.assets)
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        fun frame() = OfflineFrame.resolve(session)
        fun show(errors: Map<String, Any?> = emptyMap()): DialogView {
            val current = frame()
            val targets =
                current.resolvedNodes.values
                    .filter {
                        it.clickable &&
                            current.visible[it.id] == true &&
                            current.enabled[it.id] == true
                    }
                    .map { it.id }
            val view =
                CanvasRenderer.render(
                    session,
                    current,
                    text,
                    errors = errors,
                    handles = session.ready(session.beginRender(), targets),
                )
            session.data.presented()
            return view
        }

        val origin = session.page
        val preview = show()
        var row = 0
        val titleRows =
            preview.messages.single().parts.mapNotNull { part ->
                if (part.text.toPlainText() == "\n") row++
                if (part.text.toPlainText() == "好友管理") row else null
            }
        assertEquals(1, titleRows.size)
        assertTrue(
            (titleRows.single() + 1) * 9 >= 12 + 10,
            "Header glyph must be emitted after the background row crossing its bottom; otherwise row 18 erases half the text",
        )
        session.rememberCanvas(FrozenCanvas.capture(session, preview, text))
        session.navigate("search")
        val view = show()
        assertTrue(view.modal)
        val description = view.messages[1]
        assertEquals(
            (description.width - 8).toDouble(),
            description.parts.sumOf { text.width(it.text) },
            "Native description must fit the client content width (outer width minus 8px padding) without a second line",
        )
        assertTrue(
            candidate.assets.modalLayouts.single { it.key.endsWith("/search") }.clear.isEmpty()
        )
        assertEquals(
            3,
            view.buttons.size,
            "Single-line form has only the actual action row and its horizontal spacer",
        )
        val rejectedForm = show(mapOf("input" to "INVALID_VALUE"))
        assertEquals(
            view.bodies.size,
            rejectedForm.bodies.size,
            "Errors must not append native bodies or reflow controls",
        )
        assertEquals(view.inputs.map { it.node.id }, rejectedForm.inputs.map { it.node.id })
        assertTrue(
            rejectedForm.messages.flatMap { it.parts }.any { "请检查" in it.text.toPlainText() }
        )
        assertFalse(rejectedForm.buttons.any { "请检查" in it.label.toPlainText() })
        assertEquals("input", view.inputs.single().node.id)
        val searchInput = view.inputs.single().node.content as NodeContent.TextInput
        assertFalse(searchInput.labelVisible)
        assertFalse(searchInput.multiline, "Search must use the actual native single-line EditBox")
        assertEquals(20, searchInput.inputHeight)
        assertEquals(3, view.buttons.size)
        assertEquals(2, view.buttons.count { it.handle != null })
        assertEquals(
            listOf("cancel", "confirm"),
            view.buttons.filter { it.handle != null }.map { it.node.id },
        )
        view.buttons
            .filter { it.handle != null }
            .forEach {
                assertEquals(0, (it.node.content as NodeContent.Button).skin!!.inset)
                assertTrue(
                    "modal_fill" in it.label.toRawMessage(),
                    "Cover the vanilla sprite, including transparent skin edges",
                )
            }
        val listPage = menu.spec.pages.getValue("friends")
        assertEquals(80, listPage.elements.single { it.id == "friends.row-0.name" }.y)
        assertEquals(94, listPage.elements.single { it.id == "friends.row-0.subtitle" }.y)
        assertEquals(274, listPage.elements.single { it.id == "friends.row-5.subtitle" }.y)
        assertTrue("rafter:modal_backdrop/" in view.title.toRawMessage())
        assertTrue("rafter:modal_frames/" in view.title.toRawMessage())
        assertFalse("clickEvent" in view.title.toRawMessage())
        val packet = DialogTransport().create(view, text, frame().values, emptyMap())
        assertNull(packet.exitAction)
        assertEquals(3, packet.columns)
        assertEquals(1, packet.common.inputs.size)
        assertFailsWith<IllegalArgumentException> {
            DialogTransport().create(view, text, frame().values, emptyMap(), frameSupported = false)
        }
        session.back()
        assertEquals(origin, session.page)
        assertEquals("", session.state.getValue("search-name"))

        session.rememberCanvas(FrozenCanvas.capture(session, show(), text))
        session.navigate("add")
        val add = show()
        assertTrue(add.modal)
        val messageControl =
            add.inputs.single { it.node.id == "message" }.node.content as NodeContent.TextInput
        assertTrue(messageControl.multiline)
        assertEquals(48, messageControl.multilineHeight)
        val filler = add.buttons.take(add.columns)
        filler.forEach { button ->
            val available =
                button.node.width!! - 2 * com.hiusers.rafter.model.NativeGeometry.BUTTON_TEXT_MARGIN
            assertTrue(
                text.width(button.label) <= available,
                "Native label is scissored: painted filler must fit inside the button's two 2px margins",
            )
        }
        assertTrue(
            filler.all {
                it.node.width == com.hiusers.rafter.model.NativeGeometry.PAINTED_SPACER_WIDTH &&
                    it.handle == null
            }
        )
        assertTrue(
            filler.all { "\ue004" in it.label.toPlainText() || "\ue005" in it.label.toPlainText() }
        )
        assertTrue(
            filler.none { "\ue002" in it.label.toPlainText() || "\ue003" in it.label.toPlainText() }
        )
        show(mapOf("message" to "INVALID_VALUE")).buttons.take(add.columns).forEach { button ->
            assertTrue(
                text.width(button.label) <=
                    button.node.width!! -
                        2 * com.hiusers.rafter.model.NativeGeometry.BUTTON_TEXT_MARGIN
            )
        }
        assertEquals(view.inputs.single().node.width, add.inputs.first().node.width)
        assertTrue("rafter:modal_backdrop/" in add.title.toRawMessage())
        assertEquals(
            listOf("cancel", "confirm"),
            add.buttons.filter { it.handle != null }.map { it.node.id },
        )
        assertTrue(
            add.buttons.single { it.node.id == "confirm" }.label.toPlainText().contains("发送申请")
        )
        assertEquals(
            2,
            DialogTransport().create(add, text, frame().values, emptyMap()).common.inputs.size,
        )
        session.back()
        assertEquals(origin, session.page)
        assertEquals("", session.state["target-name"], "Cancel must not write input state")
        assertEquals("你好, 一起探索新的地方吧!", session.state["request-message"])
        session.rememberCanvas(FrozenCanvas.capture(session, show(), text))
        session.navigate("add")
        val addInputs = show().inputs.map { it.node }
        val tooLong =
            session.submit(mapOf("input" to "Alex", "message" to "好".repeat(65)), addInputs)
        assertEquals(setOf("message"), tooLong.keys)
        assertEquals(
            "",
            session.state["target-name"],
            "An invalid message must not partially commit the name",
        )
        assertEquals("你好, 一起探索新的地方吧!", session.state["request-message"])
        assertTrue(
            session
                .submit(mapOf("input" to "Alex", "message" to "好".repeat(64)), addInputs)
                .isEmpty()
        )
        assertTrue(
            session
                .submit(mapOf("input" to "Alex", "message" to "你好\n一起探索新的地方吧!"), addInputs)
                .isEmpty()
        )
        session.back()
        assertEquals("Alex", session.state["target-name"])
        assertEquals("你好\n一起探索新的地方吧!", session.state["request-message"])
        assertEquals("", session.state["search-name"], "Adding must not overwrite search state")
        session.rememberCanvas(FrozenCanvas.capture(session, show(), text))
        session.navigate("chat")
        val chat = show()
        val chatInput = chat.inputs.single()
        val multiline = chatInput.node.content as NodeContent.TextInput
        assertTrue(chat.modal && multiline.multiline)
        val chatError = show(mapOf("input" to "INVALID_VALUE"))
        assertEquals(chat.buttons.map { it.node.width }, chatError.buttons.map { it.node.width })
        assertTrue(chatError.messages.flatMap { it.parts }.any { "请检查" in it.text.toPlainText() })
        assertTrue(
            chat.buttons.take(chat.columns).all {
                it.node.width == com.hiusers.rafter.model.NativeGeometry.PAINTED_SPACER_WIDTH
            }
        )
        assertEquals(64, multiline.multilineHeight)
        assertEquals(5, multiline.maxLines)
        assertTrue(
            session
                .submit(mapOf(chatInput.node.id to "你好\n第二行消息"), chat.inputs.map { it.node })
                .isEmpty()
        )
        session.back()
        assertEquals("你好\n第二行消息", session.state["chat-message"])
        session.openPopup("more")
        val opened = show()
        assertFalse(
            opened.messages.flatMap { it.parts }.any { "等级 36" in it.text.toPlainText() },
            "Covered underlay text must not leak through a differently batched popup bitmap",
        )
        session.rememberCanvas(FrozenCanvas.capture(session, opened, text))
        session.navigate("remove")
        val remove = show()
        assertTrue(remove.modal)
        assertEquals(3, remove.buttons.size, "Confirmation has no vertical spacer row")
        assertTrue(remove.inputs.isEmpty(), "Confirmation must not introduce a fake input")
        assertTrue("rafter:modal_backdrop/" in remove.title.toRawMessage())
        assertTrue(
            remove.buttons
                .filter { it.handle != null }
                .single { it.node.id == "confirm" }
                .node
                .actions
                .none { it.type == "submit" }
        )
        assertTrue(
            DialogTransport()
                .create(remove, text, frame().values, emptyMap())
                .common
                .inputs
                .isEmpty()
        )
        session.back()
        assertEquals(
            "more",
            session.popup,
            "Cancel restores the popup and selection that opened the modal",
        )
        val restored = show()
        session.rememberCanvas(FrozenCanvas.capture(session, restored, text))
        session.navigate("memo")
        val memo = show()
        assertTrue(memo.modal)
        val memoInput = memo.inputs.single()
        assertEquals("input", memoInput.node.id)
        assertFalse((memoInput.node.content as NodeContent.TextInput).multiline)
        assertTrue(
            session.submit(mapOf(memoInput.node.id to "一起冒险"), listOf(memoInput.node)).isEmpty()
        )
        session.back()
        assertEquals("一起冒险", session.state["memo"])
        assertEquals("more", session.popup)
        session.closePopup()
        val openArtwork =
            javax.imageio.ImageIO.read(
                root.resolve("core/ui/images/friends/panels/sidebar_open.png").toFile()
            )
        assertEquals(openArtwork.getRGB(20, 162), openArtwork.getRGB(20, 163))
        for (pageId in listOf("friends")) {
            val page = menu.spec.pages.getValue(pageId)
            assertEquals(setOf("more"), page.popups.keys)
            val profile = page.elements.single { it.id == "details.profile-hit" }
            assertTrue(profile.actions.isEmpty())
            assertEquals(false, profile.enabled!!.value)
        }
        val title = menu.spec.pages.getValue("friends").elements.single { it.id == "header.title" }
        assertEquals(12, title.y)
        val subtitle =
            menu.spec.pages.getValue("friends").elements.single {
                it.id == "friends.row-0.subtitle"
            }
        assertEquals(94, subtitle.y)
        val phase = text.offsetY(text.literal("像素对齐"), 4)
        assertEquals(text.width(text.literal("像素对齐")), text.width(phase))
        assertTrue("text_offset/4/" in phase.toRawMessage())

        // 混合原生控件使用独立测试夹具。
        val controls =
            UiSession(
                UUID.randomUUID(),
                candidate.snapshot.menus.getValue("test:dialog-controls"),
                emptyMap(),
            )
        fun controlFrame() =
            Frame(
                controls.state.mapKeys { BindingSpec("state", it.key) },
                controls.displayedNodes().associate { it.id to true },
                controls.displayedNodes().associate { it.id to true },
            )
        fun controlView() =
            CanvasRenderer.render(
                controls,
                controlFrame(),
                text,
                handles =
                    controls.ready(
                        controls.beginRender(),
                        controls.displayedNodes().filter { it.clickable }.map { it.id },
                    ),
            )
        controls.rememberCanvas(FrozenCanvas.capture(controls, controlView(), text))
        controls.navigate("settings")
        val mixed = controlView()
        assertEquals(5, mixed.inputs.size)
        assertEquals(3, mixed.buttons.count { it.handle != null })
        assertEquals(5, mixed.columns)
        val checkbox = mixed.inputs.single { it.node.id == "invitations" }
        assertEquals("", text.fitLine(checkbox.label, 0).toPlainText())
        assertEquals(
            248.0,
            text.width(checkbox.label) +
                com.hiusers.rafter.model.NativeGeometry.CHECKBOX_SIZE +
                com.hiusers.rafter.model.NativeGeometry.LABEL_GAP,
            "Literal advance sizes the actual checkbox label, not a fake hit box",
        )
        val mixedPacket = DialogTransport().create(mixed, text, controlFrame().values, emptyMap())
        assertEquals(5, mixedPacket.common.inputs.size)
        assertEquals(5, mixedPacket.columns)
        controls.close()

        PackBuild.prepare(root, candidate.assets, candidate.pack.copy(archive = true)).use { build
            ->
            assertTrue(
                Files.isRegularFile(build.folder.resolve("assets/rafter/font/modal_fill.json"))
            )
            val originalFont =
                build.folder.resolve(
                    "assets/rafter/font/" + text.theme.defaultFont.substringAfter(':') + ".json"
                )
            val baseline =
                JsonParser.parseString(Files.readString(originalFont))
                    .asJsonObject
                    .getAsJsonArray("providers")[0]
                    .asJsonObject
                    .getAsJsonArray("shift")[1]
                    .asDouble
            assertEquals(2.0, baseline, "Use hhea ascender, not a guessed font baseline")
            Files.walk(build.folder.resolve("assets/rafter/font/modal_backdrop")).use { paths ->
                paths
                    .filter { it.toString().endsWith(".json") }
                    .forEach { font ->
                        val providers =
                            JsonParser.parseString(Files.readString(font))
                                .asJsonObject
                                .getAsJsonArray("providers")
                        providers
                            .map { it.asJsonObject }
                            .filter { it.get("type").asString == "ttf" }
                            .forEach { provider ->
                                val shift = provider.getAsJsonArray("shift")
                                assertEquals(2, shift.size())
                                assertTrue(
                                    shift.all { it.asDouble in -512.0..512.0 },
                                    "Client rejects TTF shift: $font",
                                )
                            }
                    }
            }
            Files.walk(build.folder).use { paths ->
                paths
                    .filter { it.toString().endsWith(".vsh") || it.toString().endsWith(".fsh") }
                    .forEach {
                        assertTrue(Files.readString(it).startsWith("#version"), it.toString())
                    }
            }
            ZipFile(assertNotNull(build.archive).toFile()).use { zip ->
                assertNotNull(zip.getEntry("pack.mcmeta"))
            }
            assertEquals(root.resolve("generate/resource-pack"), build.commit(root))
        }
    }
}
