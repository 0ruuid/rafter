package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.compile.TextSpecDecoder
import com.hiusers.rafter.model.CompileResult
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.TextSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleCompilerTest {
    private val typography = mapOf<String, Any?>("font" to "test:theme", "font-size" to 18)
    private val canvasStyle = typography + mapOf("height" to 36)
    private val path = "$.pages.main.elements[0]"
    private val nativePath = "$.pages.main.components[0]"

    private fun compile(
        node: Map<String, Any?>,
        mode: String = "canvas",
        style: Map<String, Any?> = typography,
    ): CompileResult =
        MenuCompiler(styles = mapOf("theme" to style))
            .compile(mapOf("menu.yml" to minimal(mode, listOf(node))))

    private fun node(result: CompileResult): NodeSpec =
        assertNotNull(
                result.snapshot,
                result.diagnostics.toString(),
            )
            .menus
            .getValue("test:menu")
            .spec
            .pages
            .getValue("main")
            .nodes
            .single()

    private fun text(fields: Map<String, Any?>): TextSpec =
        TextSpecDecoder("expected").decode(fields, "$.text")

    @Test
    fun explicitNodeFieldsAndNestedTextOverrideStyleDefaults() {
        val label =
            mapOf(
                "id" to "label",
                "type" to "text",
                "style" to "theme",
                "text" to "&a[unchanged](bold)",
                "font" to "test:outer",
                "font-size" to 27,
                "height" to 27,
            )
        val explicit = node(compile(label, style = canvasStyle))
        assertEquals(27, explicit.height)
        assertEquals(
            text(
                mapOf(
                    "format" to "colored",
                    "value" to "&a[unchanged](bold)",
                    "font" to "test:outer",
                    "font-size" to 27,
                )
            ),
            explicit.text,
        )

        val nestedText =
            mapOf(
                "format" to "simple",
                "value" to "[unchanged](bold)",
                "font" to "test:nested",
                "font-size" to 9,
            )
        val nested = node(compile(label + ("text" to nestedText), style = canvasStyle))
        assertEquals(text(nestedText), nested.text)
        assertEquals(9.0, nested.text.fontSize)
        val partial =
            node(compile(label + ("text" to (nestedText - "font-size")), style = canvasStyle))
        assertEquals("test:nested", partial.text.font)
        assertEquals(27.0, partial.text.fontSize)

        val inherited =
            node(compile(label - setOf("font", "font-size", "height"), style = canvasStyle))
        assertEquals(36, inherited.height)
        assertEquals(18.0, inherited.text.fontSize)
        assertEquals(
            text(typography + mapOf("format" to "colored", "value" to "&a[unchanged](bold)")),
            inherited.text,
        )
    }

    @Test
    fun typographyPreservesLiteralSimplePartsAndBindingText() {
        val binding = mapOf("type" to "state", "key" to "name")
        val labels =
            listOf(
                mapOf("format" to "literal", "value" to "&a[not markup](bold)"),
                mapOf("format" to "simple", "value" to "[authored](bold)"),
                mapOf("format" to "literal", "binding" to binding),
                mapOf(
                    "format" to "parts",
                    "parts" to listOf("&aPart", mapOf("format" to "literal", "value" to "[raw]")),
                ),
            )
        labels.forEach { label ->
            val result =
                node(
                    compile(
                        mapOf(
                            "id" to "label",
                            "type" to "text",
                            "style" to "theme",
                            "text" to label,
                        ),
                        "native",
                    )
                )
            assertEquals(label["format"], result.text.format)
            assertEquals("test:theme", result.text.font)
            if ("binding" in label) {
                assertEquals("state", result.text.binding?.type)
                assertEquals("name", result.text.binding?.key)
                assertEquals("", result.text.value)
            } else {
                assertEquals(text(typography + label), result.text)
            }
        }
        val bound =
            node(
                compile(
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "style" to "theme",
                        "text" to "&aPlaceholder",
                        "binding" to binding,
                    ),
                    "native",
                )
            )
        assertEquals("literal", bound.text.format)
        assertEquals("&aPlaceholder", bound.text.value)
        assertEquals("test:theme", bound.text.font)
        assertEquals("name", bound.text.binding?.key)
    }

    @Test
    fun outerTypographyAlsoWorksWithoutNamedStyle() {
        val result =
            node(
                compile(
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "text" to "Hello",
                        "font" to "test:outer",
                        "font-size" to 18,
                    ),
                    "native",
                )
            )
        assertEquals(
            text(
                mapOf(
                    "format" to "colored",
                    "value" to "Hello",
                    "font" to "test:outer",
                    "font-size" to 18,
                )
            ),
            result.text,
        )
    }

    @Test
    fun unknownStyleReportsItsReferencePath() {
        val result = compile(mapOf("id" to "label", "type" to "text", "style" to "missing"))
        assertNull(result.snapshot)
        val diagnostic = result.diagnostics.single()
        assertEquals("menu.yml", diagnostic.file)
        assertEquals("$path.style", diagnostic.path)
        assertEquals("REFERENCE", diagnostic.code)
        assertTrue("missing" in diagnostic.message)
    }

    @Test
    fun nativeRejectsHeightAndBothModesRejectBackgroundStyles() {
        val button =
            mapOf(
                "id" to "button",
                "type" to "button",
                "style" to "theme",
                "actions" to emptyList<Any>(),
            )
        val native = compile(button, "native", mapOf("height" to 18))
        assertNull(native.snapshot)
        assertEquals("CAPABILITY", native.diagnostics.single().code)
        assertEquals("$nativePath.style.height", native.diagnostics.single().path)
        for (mode in listOf("canvas", "native")) {
            val result =
                compile(
                    mapOf("id" to "label", "type" to "text", "style" to "theme"),
                    mode,
                    mapOf("background" to "#112233"),
                )
            assertNull(result.snapshot)
            assertEquals("UNKNOWN_FIELD", result.diagnostics.single().code)
            assertEquals(
                "${if (mode == "canvas") path else nativePath}.style.background",
                result.diagnostics.single().path,
            )
        }
    }

    @Test
    fun stylesRejectInheritanceNestedStylesAndUnsupportedElementFields() {
        for (key in listOf("extends", "style", "text", "background")) {
            val result =
                compile(
                    mapOf("id" to "label", "type" to "text", "style" to "theme"),
                    style = mapOf(key to "other"),
                )
            assertNull(result.snapshot)
            assertEquals("UNKNOWN_FIELD", result.diagnostics.single().code)
            assertEquals("$path.style.$key", result.diagnostics.single().path)
        }
        val image =
            compile(
                mapOf(
                    "id" to "image",
                    "type" to "image",
                    "asset" to "test:icon",
                    "style" to "theme",
                )
            )
        assertNull(image.snapshot)
        assertEquals("CAPABILITY", image.diagnostics.single().code)
        assertEquals("$path.style.font", image.diagnostics.single().path)
    }

    @Test
    fun canvasRetiredControlsAreRejectedInsteadOfReceivingBackgroundStyles() {
        for (type in listOf("panel", "toggle", "row", "column", "absolute", "button")) {
            val result = compile(mapOf("id" to "retired", "type" to type))
            assertNull(result.snapshot, type)
            assertTrue(
                result.diagnostics.any { it.code in setOf("UNKNOWN_TYPE", "CAPABILITY") },
                result.diagnostics.toString(),
            )
        }
    }

    @Test
    fun invalidStyleValuesRetainStylePathsEvenWhenExplicitlyOverridden() {
        val result =
            compile(
                mapOf(
                    "id" to "label",
                    "type" to "text",
                    "style" to "theme",
                    "height" to 27,
                ),
                style = mapOf("height" to 10),
            )
        assertNull(result.snapshot)
        assertEquals("RANGE", result.diagnostics.single().code)
        assertEquals("$path.style.height", result.diagnostics.single().path)
    }

    @Test
    fun nestedTemplatesResolveStylesAfterParameterSubstitutionWithoutMutatingSources() {
        val leaf =
            mapOf(
                "id" to "test:leaf",
                "params" to mapOf("look" to "theme", "label" to "default"),
                "component" to
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "style" to mapOf("param" to "look"),
                        "text" to mapOf("param" to "label"),
                        "font" to "test:explicit",
                    ),
            )
        val wrapper =
            mapOf(
                "id" to "test:wrapper",
                "params" to mapOf("label" to "&aTemplate"),
                "component" to
                    mapOf(
                        "id" to "inner",
                        "type" to "template",
                        "key" to "test:leaf",
                        "params" to mapOf("label" to mapOf("param" to "label")),
                    ),
            )
        val templates = mapOf("leaf.yml" to leaf, "wrapper.yml" to wrapper)
        val call = mapOf("id" to "instance", "type" to "template", "key" to "test:wrapper")
        val styles =
            mapOf(
                "theme" to typography,
                "alternate" to mapOf("font" to "test:alternate", "font-size" to 27),
            )
        val compiler = MenuCompiler(styles = styles)
        fun expand(invocation: Map<String, Any?>) =
            compiler.compile(
                mapOf("menu.yml" to minimal("native", listOf(invocation))),
                templates,
            )
        val inherited = node(expand(call))
        assertEquals("instance", inherited.id)
        assertEquals(
            text(
                mapOf(
                    "format" to "colored",
                    "value" to "&aTemplate",
                    "font" to "test:explicit",
                    "font-size" to 18,
                )
            ),
            inherited.text,
        )
        assertEquals(listOf("wrapper.yml", "leaf.yml"), inherited.origins.map { it.file })
        val overridden = node(expand(call + ("style" to "alternate")))
        assertEquals(
            text(
                mapOf(
                    "format" to "colored",
                    "value" to "&aTemplate",
                    "font" to "test:explicit",
                    "font-size" to 27,
                )
            ),
            overridden.text,
        )
        assertEquals(inherited.text, node(expand(call)).text)
        assertEquals(mapOf("param" to "look"), (leaf["component"] as Map<*, *>)["style"])
        assertEquals(typography, styles["theme"])

        val failure = expand(call + ("style" to "missing"))
        assertNull(failure.snapshot)
        assertEquals("$nativePath.style", failure.diagnostics.single().path)
        assertEquals(
            listOf("wrapper.yml", "leaf.yml"),
            failure.diagnostics.single().origins.map { it.file },
        )
    }
}
