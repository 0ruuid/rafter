package com.hiusers.rafter

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import taboolib.module.chat.Components

/** 旧输入页仅作回归夹具，不定义当前默认界面。 */
class DialogFrameIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun theImageTravelsInTheTitleWithoutAddingBodyRowsOrChangingNativeInputs() {
        Components.useAdventure = true
        installLegacyDefaults(root)
        Files.move(root.resolve("menus"), root.resolve("core/menus"))
        Files.move(root.resolve("ui/theme.yml"), root.resolve("core/ui/theme.yml"))
        Files.move(root.resolve("ui/images"), root.resolve("core/ui/images"))
        Files.delete(root.resolve("ui"))
        val loaded =
            MenuRepository(
                    root,
                    {},
                    { _, _, _ -> },
                    { Yaml(SafeConstructor(LoaderOptions())).load(it) },
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.toString())
        val menu = candidate.snapshot.menus.getValue("rafter:controls")
        val session = UiSession(UUID.randomUUID(), menu, emptyMap()).apply { navigate("nickname") }
        val page = session.current().spec
        val text = CompositeText(candidate.assets)
        val active = page.nodes.associate { it.id to true }
        val context = Frame(mapOf(BindingSpec("state", "nickname") to "Traveller"), active, active)
        val framed = CanvasRenderer.render(session, context, text)
        val plainMenu =
            menu.copy(
                pages =
                    menu.pages + (page.id to session.current().copy(spec = page.copy(frame = null)))
            )
        val plainSession =
            UiSession(UUID.randomUUID(), plainMenu, emptyMap()).apply { navigate("nickname") }
        val ordinary = CanvasRenderer.render(plainSession, context, text)
        val frame = candidate.assets.dialogFrames.getValue("rafter:solid-dark")
        assertTrue("rafter:solid-dark" in candidate.assets.dialogFrames)
        assertTrue(framed.title.toRawMessage().contains(frame.font))
        assertFalse(ordinary.title.toRawMessage().contains(frame.font))
        assertNotNull(framed.fallbackTitle)
        assertEquals(ordinary.title.toRawMessage(), framed.fallbackTitle!!.toRawMessage())
        assertEquals(
            ordinary.messages.flatMap { it.parts }.map { it.text.toRawMessage() },
            framed.messages.flatMap { it.parts }.map { it.text.toRawMessage() },
            "Backdrop must not add a header or push inputs down",
        )
        assertEquals(
            ordinary.inputs.map { Triple(it.node.id, it.initial, it.label.toRawMessage()) },
            framed.inputs.map { Triple(it.node.id, it.initial, it.label.toRawMessage()) },
        )
        assertEquals("nickname-input", framed.inputs.single().node.id)
        assertEquals("旅行者", framed.inputs.single().initial)
        assertTrue(
            framed.messages.none { body -> body.parts.any { frame.font in it.text.toRawMessage() } }
        )
        val expectedWidth = CanvasGeometry.bodyWidth(page.width) + page.frame!!.padding * 2
        val rgb =
            "#%02x%02x%02x".format(expectedWidth and 255, expectedWidth ushr 8, page.frame!!.inset)
        assertTrue(framed.title.toRawMessage().contains(rgb, ignoreCase = true))
        val transport = DialogTransport()
        assertTrue(transport.supportsFrame(ClientVersion.V_26_2))
        assertTrue(transport.supportsFrame(ClientVersion.V_26_3))
        assertTrue(transport.supportsFrame(ClientVersion.V_1_21_6))
        assertTrue(transport.supportsFrame(ClientVersion.V_1_21_7))
        assertTrue(transport.supportsFrame(ClientVersion.V_1_21_9))
        assertTrue(transport.supportsFrame(ClientVersion.V_1_21_11))
        assertTrue(transport.supportsFrame(ClientVersion.V_26_1))
        assertFalse(transport.supportsFrame(ClientVersion.UNKNOWN))
        assertFalse(transport.supportsFrame(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS))
        val fallback =
            transport.create(framed, text, context.values, emptyMap(), frameSupported = false)
        assertEquals(ordinary.title.toAdventureObject(), fallback.common.title)
        assertTrue(fallback.common.inputs.single().key.matches(Regex("[a-zA-Z0-9_]+")))
        assertFalse(fallback.common.title.toString().contains(frame.font))
    }
}
