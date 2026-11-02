package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.compile.YamlSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 使用生产 YAML 解码器。 */
class YamlDocumentTest {
    @TempDir lateinit var root: Path

    private fun read(text: String): Map<String, Any?> {
        val file = root.resolve("document.yml")
        Files.writeString(file, text)
        return YamlSource.decode(YamlSource.read(root, file).text)
    }

    @Test
    fun explicitNullsSurviveMappingsListsAndLiteralStringsRemainLiteral() {
        val document =
            read(
                """
                value: null
                empty:
                nested: {value: ~}
                rows:
                  - value: null
                  - {}
                  - null
                quoted: ['null', '~', '', 'false']
                """
                    .trimIndent()
            )
        assertTrue(document.containsKey("value"))
        assertNull(document["value"])
        assertTrue(document.containsKey("empty"))
        assertEquals(mapOf("value" to null), document["nested"])
        assertEquals(listOf(mapOf("value" to null), emptyMap(), null), document["rows"])
        assertEquals(listOf("null", "~", "", "false"), document["quoted"])
    }

    @Test
    fun nullComparisonCompilesButAbsentOrAmbiguousComparisonIsRejected() {
        fun compile(comparison: String) =
            MenuCompiler()
                .compile(
                    mapOf(
                        "core/menus/test.yml" to
                            read(
                                """
            id: test:null
            entry: home
            state: {selected: null}
            pages:
              home:
                mode: native
                title: Test
                components:
                  - id: details
                    type: text
                    text: Details
                    visible:
                      type: equals
                      binding: {type: state, key: selected}
                      $comparison
        """
                                    .trimIndent()
                            )
                    )
                )
        val valid = compile("value: null")
        assertNotNull(valid.snapshot, valid.diagnostics.joinToString("\n"))
        assertTrue(valid.snapshot!!.menus.getValue("test:null").spec.state.containsKey("selected"))
        val missing = compile("")
        assertNull(missing.snapshot)
        assertEquals("CONDITION", missing.diagnostics.single().code)
        assertEquals(
            "equals requires exactly one of value/compare",
            missing.diagnostics.single().message,
        )
        val both =
            compile("value: null\n                      compare: {type: state, key: selected}")
        assertNull(both.snapshot)
        assertEquals("CONDITION", both.diagnostics.single().code)
    }
}
