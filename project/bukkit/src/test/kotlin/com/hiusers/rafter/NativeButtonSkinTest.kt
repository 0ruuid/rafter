package com.hiusers.rafter

import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import taboolib.module.chat.Components

class NativeButtonSkinTest {
    @TempDir lateinit var root: Path

    @Test
    fun configuredArtworkKeepsTextInputsAndDynamicSubmissionIndependent() {
        Components.useAdventure = true
        installLegacyDefaults(root)
        val loaded =
            MenuRepository(
                    root,
                    {},
                    { _, _, _ -> },
                    { Yaml(SafeConstructor(LoaderOptions())).load(it) },
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        val menu = candidate.snapshot.menus.getValue("rafter:workbench")
        val session = UiSession(UUID.randomUUID(), menu, emptyMap()).apply { navigate("profile") }
        val page = session.current().spec
        val active = page.nodes.associate { it.id to true }
        val text = CompositeText(candidate.assets)
        val handles =
            mapOf("save" to "save_token", "return" to "return_token", "__close" to "unused_token")
        val view =
            CanvasRenderer.render(
                session,
                Frame(emptyMap(), active, active),
                text,
                handles = handles,
            )
        assertEquals(
            listOf("nickname-input", "notifications-input", "difficulty-input", "volume-input"),
            view.inputs.map { it.node.id },
        )
        val save = view.buttons.single()
        assertEquals("save", save.node.id)
        assertEquals(
            "return",
            view.close.node.id,
            "Authored exit must replace, not accompany, the gray generated button",
        )
        assertEquals(listOf("submit", "back"), save.node.actions.map { it.type })
        assertEquals(listOf("back"), view.close.node.actions.map { it.type })
        val label = text.render(save.node.text)
        val unchanged = label.toRawMessage()
        val skin = (save.node.content as NodeContent.Button).skin!!
        assertTrue(save.label.toRawMessage().contains("rafter:button_skins/1"))
        assertEquals(
            text.width(label),
            text.width(save.label),
            0.0001,
            "Background must have zero net text advance",
        )
        val second =
            text.buttonLabel(
                skin,
                306,
                text.render(TextSpec(value = "Another", hover = TextSpec(value = "Hint"))),
            )
        assertTrue(
            second.toRawMessage().contains("Hint"),
            "Hover text must remain an independent component",
        )
        assertEquals(
            unchanged,
            label.toRawMessage(),
            "Composing a second label must not mutate the first player or definition",
        )
        assertFailsWith<IllegalArgumentException> {
            text.validateButtonLabel(TextSpec(value = "Save", font = "minecraft:default"), 306)
        }
        assertFailsWith<IllegalArgumentException> {
            text.buttonLabel(skin, 306, text.literal("W".repeat(100)))
        }
        assertFailsWith<IllegalArgumentException> {
            text.buttonLabel(skin, 306, text.literal("First\nSecond"))
        }
        val packet = DialogTransport().create(view, text, emptyMap(), emptyMap())
        assertIs<DynamicCustomAction>(packet.actions.single().action)
        assertIs<DynamicCustomAction>(packet.exitAction!!.action)
        assertEquals(
            "rafter:ui/save_token",
            (packet.actions.single().action as DynamicCustomAction).id.toString(),
        )
        assertEquals(
            "rafter:ui/return_token",
            (packet.exitAction!!.action as DynamicCustomAction).id.toString(),
        )
        val wireLabel =
            GsonComponentSerializer.gson().serialize(packet.actions.single().button.label)
        assertTrue(wireLabel.contains("rafter:button_skins/1"), wireLabel)
        assertTrue(packet.common.inputs.all { it.key.matches(Regex("[a-zA-Z0-9_]+")) })
    }
}
