package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.AutoAssets
import com.hiusers.rafter.asset.FontRequest
import com.hiusers.rafter.asset.UiThemes
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml

class ThemeConfigurationTest {
    @TempDir lateinit var temp: Path

    @Test
    fun themeYamlRejectsInvalidConfigurationAndRendersConfiguredFontSizes() {
        taboolib.module.chat.Components.useAdventure = true
        val root = temp.resolve("core/ui")
        copyLegacyUiTo(root)
        val themeFile = root.resolve("theme.yml")
        val document = UiThemes.document(root)
        val theme = UiThemes.compile(document)
        assertEquals("rafter-mono", theme.defaultFamily)
        assertEquals(10.0, theme.defaultSize)
        assertEquals(FontRequest("rafter-mono", 10.0).id, theme.defaultFont)
        assertEquals(20, (theme.styles.getValue("heading").getValue("font-size") as Number).toInt())
        assertEquals("返回游戏", theme.closeLabel.value)

        fun reject(candidate: Map<String, Any?>, code: String, path: String) {
            Files.writeString(themeFile, Yaml().dump(candidate))
            val error = assertFailsWith<DocumentError> { AutoAssets.document(root) }.diagnostic
            assertEquals("core/ui/theme.yml", error.file)
            assertEquals(code, error.code)
            assertEquals(path, error.path)
        }
        reject(document + ("unknown" to true), "UNKNOWN_FIELD", "$.unknown")
        @Suppress("UNCHECKED_CAST")
        val defaults = document.getValue("defaults") as Map<String, Any?>
        reject(
            document + ("defaults" to (defaults + ("unknown" to true))),
            "UNKNOWN_FIELD",
            "$.defaults.unknown",
        )
        for (field in listOf("style", "extends")) {
            reject(
                document + ("styles" to mapOf("nested" to mapOf(field to "heading"))),
                "UNKNOWN_FIELD",
                "$.styles.nested.$field",
            )
        }
        reject(
            document +
                ("styles" to mapOf("nested" to mapOf("font" to mapOf("family" to "rafter-mono")))),
            "FIELD_TYPE",
            "$.styles.nested.font",
        )
        @Suppress("UNCHECKED_CAST")
        val controls = document.getValue("controls") as Map<String, Any?>
        reject(
            document +
                ("controls" to
                    (controls +
                        ("close" to
                            mapOf(
                                "format" to "literal",
                                "binding" to mapOf("type" to "state", "key" to "name"),
                            )))),
            "TEXT_BINDING",
            "$.controls.close.binding",
        )
        Files.writeString(themeFile, "defaults: [unterminated")
        val malformed = assertFailsWith<DocumentError> { UiThemes.document(root) }.diagnostic
        assertEquals("core/ui/theme.yml", malformed.file)
        assertEquals("THEME_READ", malformed.code)

        Files.writeString(themeFile, Yaml().dump(document))
        assertEquals(theme, UiThemes.compile(UiThemes.document(root)))
        val heading = FontRequest("rafter-mono", 20.0)
        val assets =
            AssetCompiler.compile(root, AutoAssets.document(root), requests = setOf(heading))
        assertTrue(themeFile in assets.sourceHashes)
        assertEquals(20.0, assets.fontFaces.getValue(heading.id).request.size)
        val text = CompositeText(assets)
        val menuDocument =
            minimal(
                "canvas",
                listOf(
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "font" to "rafter-mono",
                        "font-size" to 20,
                        "height" to 27,
                        "text" to
                            mapOf("format" to "simple", "value" to "[Label](font=rafter-mono)"),
                    )
                ),
            )
        val result =
            MenuCompiler(
                    assets.dimensions(),
                    textCheck = text::validate,
                    canvasTextCheck = text::validateCanvas,
                    styles = theme.styles,
                )
                .compile(mapOf("menu.yml" to menuDocument))
        val menu =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:menu")
        val label = menu.pages.getValue("main").spec.nodes.single().text
        assertEquals("rafter-mono", label.font)
        assertEquals(20.0, label.fontSize)
        val rendered = text.render(label)
        assertEquals("Label", rendered.toPlainText())
        assertTrue(heading.id in rendered.toRawMessage())
        assertEquals(assets.textWidth("Label", font = heading.id), text.width(rendered))
        assertEquals(text.width(text.render(TextSpec(value = "Label"))) * 2, text.width(rendered))
        val view =
            CanvasRenderer.render(
                UiSession(UUID.randomUUID(), menu, emptyMap()),
                Frame(emptyMap(), mapOf("label" to true), mapOf("label" to true)),
                text,
            )
        assertTrue(
            view.messages
                .flatMap { it.parts }
                .any {
                    it.text.toPlainText() == "Label" && heading.id in it.text.toRawMessage()
                }
        )
        assertEquals("返回游戏", view.close.label.toPlainText())
    }
}
