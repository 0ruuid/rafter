package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.render.DialogView
import com.hiusers.rafter.render.RenderButton
import com.hiusers.rafter.render.RenderInput
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogInputNames
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import taboolib.module.chat.Components

class DialogInputProtocolTest {
    @TempDir lateinit var root: Path

    @Test
    fun emittedNativeInputNamesObeyTheClientCodecEvenWhenComponentIdsDoNot() {
        Components.useAdventure = true
        val ui = root.resolve("core/ui")
        copyLegacyUiTo(ui)
        val text = CompositeText(AssetCompiler.compile(ui, legacyUiDocument()))
        val label = Components.text("Input", color = false)
        val inputs =
            listOf(
                RenderInput(
                    NodeSpec("nickname-input", NodeContent.TextInput("nickname")),
                    label,
                    "Traveller",
                ),
                RenderInput(
                    NodeSpec("notifications.enabled", NodeContent.Checkbox("enabled")),
                    label,
                    true,
                ),
                RenderInput(
                    NodeSpec(
                        "template/choice",
                        NodeContent.Select("choice", mapOf("normal" to TextSpec(value = "Normal"))),
                    ),
                    label,
                    "normal",
                ),
                RenderInput(
                    NodeSpec("input_0", NodeContent.Slider("amount", 0.0, 10.0, 1.0)),
                    label,
                    5.0,
                ),
            )
        val close = RenderButton(NodeSpec("close", NodeContent.Button()), label, null, null)
        val view = DialogView(label, emptyList(), inputs, emptyList(), close, 1)
        val packet = DialogTransport().create(view, text, emptyMap(), emptyMap())
        val keys = packet.common.inputs.map { it.key }
        assertEquals(inputs.size, keys.distinct().size)
        keys.forEach { key ->
            assertTrue(
                key.matches(Regex("[a-zA-Z0-9_]+")),
                "$key is not a valid input name: must not disconnect the client",
            )
        }
    }

    @Test
    fun inputAliasesRoundTripWithoutCollisionsAndRejectUnknownNames() {
        val ids = listOf("nickname-input", "notifications.enabled", "template/choice", "input_0")
        val values: Map<String, Any?> =
            mapOf(
                "input_0" to "Traveller",
                "input_1" to true,
                "input_2" to "normal",
                "input_3" to 5.0,
            )
        assertEquals(
            mapOf<String, Any?>(
                "nickname-input" to "Traveller",
                "notifications.enabled" to true,
                "template/choice" to "normal",
                "input_0" to 5.0,
            ),
            DialogInputNames.decode(values, ids),
        )
        assertEquals(emptyMap<String, Any?>(), DialogInputNames.decode(emptyMap(), ids))
        for (invalid in listOf("nickname-input", "input_-1", "input_00", "input_4", "random")) {
            assertNull(DialogInputNames.decode(values + (invalid to "forged"), ids), invalid)
        }
        assertNull(
            DialogInputNames.decode(values, emptyList()),
            "Fields from a previous page are not accepted",
        )
    }
}
