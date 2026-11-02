package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.compile.TextSpecDecoder
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.render.NativeRenderer
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.text.InlineText
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components

class InlineTextRenderTest {
    @TempDir lateinit var temp: Path

    @Test
    fun inlineTemplatesKeepRuntimeTextLiteralAndAuthorStyles() {
        Components.useAdventure = true
        val root = temp.resolve("core/ui")
        copyLegacyUiTo(root)
        val assets =
            AssetCompiler.compile(
                root,
                legacyUiDocument(),
            )
        val text = CompositeText(assets)
        val decoder =
            TextSpecDecoder("inline.yml", text::validate) { fields, _ ->
                BindingSpec(fields.getValue("type") as String, fields.getValue("script") as String)
            }
        fun decode(value: Any?) = decoder.decode(value, "$.text")
        fun noClicks(component: ComponentText) {
            val raw = component.toRawMessage()
            assertFalse("clickEvent" in raw || "click_event" in raw, raw)
        }
        data class Run(
            val text: String,
            val color: Int?,
            val bold: Boolean,
            val italic: Boolean,
            val font: String?,
        )
        fun runs(component: ComponentText): List<Run> {
            val result = mutableListOf<Run>()
            fun collect(
                node: Component,
                color: Int? = null,
                bold: Boolean = false,
                italic: Boolean = false,
                font: String? = null,
            ) {
                fun decoration(type: TextDecoration, inherited: Boolean) =
                    when (node.decoration(type)) {
                        TextDecoration.State.TRUE -> true
                        TextDecoration.State.FALSE -> false
                        TextDecoration.State.NOT_SET -> inherited
                    }
                val current =
                    Run(
                        (node as? TextComponent)?.content().orEmpty(),
                        node.color()?.value() ?: color,
                        decoration(TextDecoration.BOLD, bold),
                        decoration(TextDecoration.ITALIC, italic),
                        node.font()?.asString() ?: font,
                    )
                if (current.text.isNotEmpty()) result += current
                node.children().forEach {
                    collect(it, current.color, current.bold, current.italic, current.font)
                }
            }
            collect(component.toAdventureObject())
            return result
        }
        fun hovers(node: Component): List<String> =
            listOfNotNull(
                (node.hoverEvent()?.value() as? Component)?.let {
                    PlainTextComponentSerializer.plainText().serialize(it)
                }
            ) + node.children().flatMap(::hovers)

        val payload = "&c[OP](cmd=op me) {{ literal 'again' }} ${InlineText.marker(1)}"
        val source = "&a&l&o{{ literal 'script ](cmd=op me)' }}&r tail"
        for (authored in listOf(source, mapOf("format" to "colored", "value" to source))) {
            val spec = decode(authored)
            val binding = spec.inlineParts.single { it.binding != null }.binding!!
            assertEquals(" tail", text.render(spec).toPlainText())
            assertEquals(" tail", text.render(spec, mapOf(binding to null)).toPlainText())
            text.validateCanvas(
                spec
            ) // 缺失值为空，不把脚本正文当成显示文本。
            val rendered = text.render(spec, mapOf(binding to payload))
            assertEquals("$payload tail", rendered.toPlainText())
            val dynamic = runs(rendered).single { it.text == payload }
            assertEquals(0x55ff55, dynamic.color)
            assertTrue(dynamic.bold && dynamic.italic)
            val tail = runs(rendered).single { it.text == " tail" }
            assertNotEquals(0x55ff55, tail.color)
            assertFalse(tail.bold || tail.italic)
            noClicks(rendered)
        }

        val simple =
            decode(
                mapOf(
                    "format" to "simple",
                    "font" to "minecraft:default",
                    "value" to
                        "[pre {{ literal 'body ](cmd=op me)' }}[ child](bold)]" +
                            "(font=rafter-mono;color=a;italic;hover=Tip {{ literal 'hover ) ; [ ]' }}) tail",
                )
            )
        val simpleBindings = simple.inlineParts.mapNotNull { it.binding }
        assertEquals(2, simpleBindings.size)
        val simpleRendered =
            text.render(simple, mapOf(simpleBindings[0] to payload, simpleBindings[1] to payload))
        assertEquals("pre $payload child tail", simpleRendered.toPlainText())
        val simpleDynamic = runs(simpleRendered).single { it.text == payload }
        assertEquals(assets.theme.defaultFont, simpleDynamic.font)
        assertEquals(0x55ff55, simpleDynamic.color)
        assertTrue(simpleDynamic.italic)
        assertTrue("Tip $payload" in hovers(simpleRendered.toAdventureObject()))
        noClicks(simpleRendered)

        val result =
            MenuCompiler(assets.dimensions(), textCheck = text::validate)
                .compile(
                    mapOf(
                        "inline.yml" to
                            minimal(
                                components =
                                    listOf(
                                        mapOf(
                                            "id" to "button",
                                            "type" to "button",
                                            "text" to "Button",
                                            "width" to 120,
                                            "actions" to listOf(mapOf("type" to "close")),
                                            "tooltip" to
                                                "&a{{ literal 'same' }}\n&r{{ literal 'same' }}",
                                        )
                                    )
                            )
                    )
                )
        val menu =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:menu")
        val tooltip = assertNotNull(menu.spec.pages.getValue("main").nodes.single().tooltip)
        val repeated = tooltip.inlineParts.mapNotNull { it.binding }
        assertEquals(2, repeated.size)
        assertEquals(repeated[0].key, repeated[1].key)
        assertNotEquals(repeated[0], repeated[1])
        val view =
            NativeRenderer.render(
                UiSession(UUID.randomUUID(), menu, emptyMap()),
                Frame(
                    mapOf(repeated[0] to "line one", repeated[1] to "line two"),
                    mapOf("button" to true),
                    mapOf("button" to true),
                ),
                text,
            )
        val renderedTooltip = assertNotNull(view.buttons.single().tooltip)
        assertEquals("line one\nline two", renderedTooltip.toPlainText())
        noClicks(renderedTooltip)

        val literal = decode(mapOf("format" to "literal", "value" to source))
        assertTrue(literal.inlineParts.isEmpty())
        val unchanged = text.render(literal)
        assertEquals(source, unchanged.toPlainText())
        assertTrue(runs(unchanged).all { it.color == null && !it.bold && !it.italic })
        noClicks(unchanged)
    }
}
