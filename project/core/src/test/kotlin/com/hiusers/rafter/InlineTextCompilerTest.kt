package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.compile.TextSpecDecoder
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.InlineTextPart
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.text.InlineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InlineTextCompilerTest {
    private val file = "inline.yml"
    private val path = "$.text"

    @Test
    fun compilesIndependentOccurrencesAndPreservesAuthoredTextModes() {
        val checked = mutableListOf<String>()
        val authored = "&a{{ state name }} / {{state name}}"
        val result =
            MenuCompiler(scriptCheck = { checked += it })
                .compile(
                    mapOf(
                        file to
                            minimal(
                                components =
                                    listOf(
                                        mapOf("id" to "label", "type" to "text", "text" to authored)
                                    )
                            )
                    )
                )
        val text =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:menu")
                .spec
                .pages
                .getValue("main")
                .nodes
                .single()
                .text
        val textPath = "$.pages.main.components[0].text"
        assertEquals("colored", text.format)
        assertEquals(authored, text.value)
        assertEquals(listOf("state name", "state name"), checked)
        val bindings = text.inlineParts.mapNotNull { it.binding }
        assertEquals(listOf("kether", "kether"), bindings.map { it.type })
        assertEquals(listOf("state name", "state name"), bindings.map { it.key })
        assertEquals(
            listOf("$file:$textPath:1", "$file:$textPath:3"),
            bindings.map { it.occurrence },
        )
        assertEquals(2, bindings.toSet().size)
        assertEquals("&a${InlineText.marker(1)} / ${InlineText.marker(3)}", InlineText.source(text))
        assertEquals("\ufdd012\ufdd1", InlineText.marker(12))
        assertFailsWith<UnsupportedOperationException> {
            (text.inlineParts as MutableList<InlineTextPart>).clear()
        }

        val calls = mutableListOf<Pair<Map<String, Any?>, String>>()
        val decoder =
            TextSpecDecoder(
                file,
                binding = { fields, bindingPath ->
                    calls += fields to bindingPath
                    BindingSpec(fields["type"] as String, fields["script"] as String)
                },
            )
        val plain = "&a[ordinary](bold)"
        assertEquals(TextSpec(format = "colored", value = plain), decoder.decode(plain, path))
        assertEquals(plain, InlineText.source(TextSpec(value = plain)))
        for (format in listOf("colored", "simple")) {
            val value = "[HP {{ state name }}](bold)"
            val explicit = decoder.decode(mapOf("format" to format, "value" to value), path)
            assertEquals(format, explicit.format)
            assertEquals(value, explicit.value)
            assertEquals("$file:$path.value:1", explicit.inlineParts[1].binding?.occurrence)
            assertEquals("[HP ${InlineText.marker(1)}](bold)", InlineText.source(explicit))
            assertEquals(
                mapOf("type" to "kether", "script" to "state name") to "$path.value",
                calls.last(),
            )
            assertTrue(
                decoder
                    .decode(mapOf("format" to format, "value" to plain), path)
                    .inlineParts
                    .isEmpty()
            )
        }

        val beforeEscapes = calls.size
        for ((value, expected) in
            listOf(
                """shown \{{ state name \}}""" to "shown {{ state name }}",
                """\\{{ state name \}}""" to """\{{ state name }}""",
                """only \}} and \{{""" to "only }} and {{",
            )) {
            val escaped = decoder.decode(value, path)
            assertEquals(listOf(InlineTextPart(text = expected)), escaped.inlineParts)
            assertEquals(expected, InlineText.source(escaped))
        }
        assertEquals(beforeEscapes, calls.size)
        val mixed = decoder.decode("""literal \{{x\}} {{ echo \{{literal\}} }}""", path)
        assertEquals("literal {{x}} ${InlineText.marker(1)}", InlineText.source(mixed))
        assertEquals("echo {{literal}}", mixed.inlineParts[1].binding?.key)

        val literal = "&a[raw](bold) {{}} {{ nested {{x}} }} \\{{"
        val beforeLiteral = calls.size
        val raw = decoder.decode(mapOf("format" to "literal", "value" to literal), path)
        assertEquals(literal, InlineText.source(raw))
        assertTrue(raw.inlineParts.isEmpty())
        assertEquals(beforeLiteral, calls.size)
        val dataBinding = BindingSpec("data", "profile", mapOf("value" to "{{not a script}}"))
        val data =
            TextSpecDecoder(file, binding = { _, _ -> dataBinding })
                .decode(
                    mapOf(
                        "format" to "literal",
                        "binding" to
                            mapOf("type" to "data", "key" to "profile", "args" to dataBinding.args),
                    ),
                    path,
                )
        assertSame(dataBinding, data.binding)
        assertNull(data.binding?.occurrence)
        assertTrue(data.inlineParts.isEmpty())

        val nested =
            decoder.decode(
                mapOf(
                    "format" to "parts",
                    "parts" to
                        listOf("A {{same}}", mapOf("format" to "literal", "value" to literal)),
                    "hover" to mapOf("format" to "simple", "value" to "[{{same}}](bold)"),
                ),
                path,
            )
        assertTrue(nested.inlineParts.isEmpty())
        assertEquals("$file:$path.parts[0]:1", nested.parts[0].inlineParts[1].binding?.occurrence)
        assertEquals(literal, InlineText.source(nested.parts[1]))
        val hover = assertNotNull(nested.hover)
        assertEquals("$file:$path.hover.value:1", hover.inlineParts[1].binding?.occurrence)
        assertEquals("[${InlineText.marker(1)}](bold)", InlineText.source(hover))
    }

    @Test
    fun rejectsMalformedTemplatesAndBindingsInStaticThemeText() {
        var validations = 0
        val decoder =
            TextSpecDecoder(
                file,
                binding = { fields, _ ->
                    validations++
                    BindingSpec("kether", fields["script"] as String)
                },
            )
        for ((source, code) in
            listOf(
                "{{" to "TEXT_TEMPLATE",
                "orphan }}" to "TEXT_TEMPLATE",
                "{{}}" to "TEXT_TEMPLATE",
                "{{ \n\t }}" to "TEXT_TEMPLATE",
                "{{ outer {{inner}} }}" to "TEXT_DEPTH",
                "{{valid}} {{unfinished" to "TEXT_TEMPLATE",
                "{{valid}} }}" to "TEXT_TEMPLATE",
                "\ufdd0" to "TEXT_TEMPLATE",
                "\ufdd1" to "TEXT_TEMPLATE",
                "{{valid}}\ufdd0" to "TEXT_TEMPLATE",
                List(65) { "{{same}}" }.joinToString("") to "TEXT_DEPTH",
            )) {
            val error =
                assertFailsWith<DocumentError>(source) { decoder.decode(source, path) }.diagnostic
            assertEquals(file, error.file)
            assertEquals(path, error.path)
            assertEquals(code, error.code, source)
            assertTrue(error.message.contains("character"), error.message)
        }
        assertEquals(
            0,
            validations,
            "The whole template must pass preflight before validating any script",
        )
        val limit = decoder.decode(List(64) { "{{same}}" }.joinToString(""), path)
        assertEquals(64, validations)
        assertEquals(64, limit.inlineParts.mapNotNull { it.binding }.toSet().size)

        val theme = TextSpecDecoder("theme.yml")
        val blocked: List<Pair<Any, String>> =
            listOf(
                "{{ state name }}" to path,
                mapOf("format" to "colored", "value" to "{{same}}") to "$path.value",
                mapOf("format" to "simple", "value" to "[{{same}}](bold)") to "$path.value",
                mapOf("format" to "parts", "parts" to listOf("{{same}}")) to "$path.parts[0]",
                mapOf("format" to "literal", "value" to "static", "hover" to "{{same}}") to
                    "$path.hover",
            )
        for ((value, expectedPath) in blocked) {
            val error = assertFailsWith<DocumentError> { theme.decode(value, path) }.diagnostic
            assertEquals("TEXT_BINDING", error.code)
            assertEquals("theme.yml", error.file)
            assertEquals(expectedPath, error.path)
        }
        assertEquals(
            "literal {{same}}",
            InlineText.source(theme.decode("""literal \{{same\}}""", path)),
        )
        assertTrue(theme.decode("static", path).inlineParts.isEmpty())
        assertEquals(
            "{{same}}",
            InlineText.source(
                theme.decode(mapOf("format" to "literal", "value" to "{{same}}"), path)
            ),
        )

        val rejection =
            DocumentError(Diagnostic(file, path, "SCRIPT", "Rejected by script validator"))
        val rejecting =
            TextSpecDecoder(
                file,
                binding = { fields, bindingPath ->
                    assertEquals(mapOf("type" to "kether", "script" to "invalid script"), fields)
                    assertEquals(path, bindingPath)
                    throw rejection
                },
            )
        assertSame(
            rejection,
            assertFailsWith<DocumentError> { rejecting.decode("{{ invalid script }}", path) },
        )
    }
}
