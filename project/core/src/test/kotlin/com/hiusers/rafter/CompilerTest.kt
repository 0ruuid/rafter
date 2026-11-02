package com.hiusers.rafter

import com.hiusers.rafter.compile.*
import com.hiusers.rafter.model.*
import kotlin.test.*
import org.yaml.snakeyaml.Yaml

class CompilerTest {
    @Test
    fun documentRoundTripDoesNotDropFields() {
        val original = minimal()
        val yaml = Yaml()
        val decoded = yaml.load<Map<String, Any?>>(yaml.dump(original))
        val result = MenuCompiler().compile(mapOf("round-trip.yml" to decoded))
        assertNotNull(result.snapshot)
        assertEquals(
            "Hello",
            result.snapshot.menus
                .getValue("test:menu")
                .spec
                .pages
                .getValue("main")
                .nodes
                .single()
                .text
                .value,
        )
    }

    @Test
    fun rejectsUnknownFieldsTypesAndReferences() {
        fun rejected(doc: Map<String, Any?>, code: String) {
            val result = MenuCompiler().compile(mapOf("bad.yml" to doc))
            assertNull(result.snapshot)
            assertTrue(result.diagnostics.any { it.code == code }, result.diagnostics.toString())
        }
        rejected(minimal() + mapOf("mystery" to true), "UNKNOWN_FIELD")
        rejected(minimal() + mapOf("schema-version" to 1), "UNKNOWN_FIELD")
        rejected(minimal() + mapOf("entry" to "missing"), "REFERENCE")
        rejected(
            minimal(components = listOf(mapOf("id" to "one", "type" to "mystery"))),
            "UNKNOWN_TYPE",
        )
        rejected(
            minimal("canvas", listOf(mapOf("id" to "input", "type" to "input", "state" to "name"))),
            "CAPABILITY",
        )
        rejected(
            minimal(
                components =
                    listOf(
                        mapOf(
                            "id" to "b",
                            "type" to "button",
                            "actions" to listOf(mapOf("type" to "open-page", "page" to "missing")),
                        )
                    )
            ),
            "REFERENCE",
        )
    }

    @Test
    fun rejectsIgnoredNativeCoordinatesAndRetiredCanvasContainers() {
        val native = mapOf("id" to "text", "type" to "text", "x" to 9)
        assertTrue(
            MenuCompiler()
                .compile(mapOf("m.yml" to minimal(components = listOf(native))))
                .diagnostics
                .any { it.code == "UNKNOWN_FIELD" }
        )
        val flow =
            mapOf(
                "id" to "column",
                "type" to "column",
                "children" to listOf(mapOf("id" to "text", "type" to "text", "x" to 9)),
            )
        assertTrue(
            MenuCompiler()
                .compile(mapOf("m.yml" to minimal("canvas", listOf(flow))))
                .diagnostics
                .any { it.code == "UNKNOWN_TYPE" }
        )
    }

    @Test
    fun duplicatesInvalidateWholeCandidate() {
        val result = MenuCompiler().compile(mapOf("a.yml" to minimal(), "b.yml" to minimal()))
        assertNull(result.snapshot)
        assertTrue(result.diagnostics.any { it.code == "DUPLICATE_ID" })
        val duplicates =
            listOf(mapOf("id" to "same", "type" to "text"), mapOf("id" to "same", "type" to "text"))
        assertNull(
            MenuCompiler().compile(mapOf("a.yml" to minimal(components = duplicates))).snapshot
        )
    }

    @Test
    fun expandsTypedTemplateParametersAndDetectsCycles() {
        val template =
            mapOf(
                "id" to "test:label",
                "params" to mapOf("label" to "default"),
                "component" to
                    mapOf("id" to "inner", "type" to "text", "text" to mapOf("param" to "label")),
            )
        val call =
            mapOf(
                "id" to "instance",
                "type" to "template",
                "key" to "test:label",
                "params" to mapOf("label" to "literal [text]"),
            )
        val result =
            MenuCompiler()
                .compile(
                    mapOf("m.yml" to minimal(components = listOf(call))),
                    mapOf("t.yml" to template),
                )
        assertNotNull(result.snapshot)
        assertEquals(
            "literal [text]",
            result.snapshot.menus
                .getValue("test:menu")
                .spec
                .pages
                .getValue("main")
                .nodes
                .single()
                .text
                .value,
        )
        val loop =
            template +
                mapOf(
                    "component" to
                        mapOf("id" to "loop", "type" to "template", "key" to "test:label")
                )
        val failure =
            MenuCompiler()
                .compile(
                    mapOf("m.yml" to minimal(components = listOf(call))),
                    mapOf("t.yml" to loop),
                )
        assertNull(failure.snapshot)
        assertTrue(failure.diagnostics.any { it.code == "TEMPLATE_CYCLE" })
    }

    @Test
    fun requiresExplicitSafeTextAndValidActionOrdering() {
        for (format in listOf("simple", "colored")) {
            val dynamic =
                mapOf(
                    "id" to "t",
                    "type" to "text",
                    "text" to
                        mapOf(
                            "format" to format,
                            "binding" to mapOf("type" to "state", "key" to "name"),
                        ),
                )
            assertTrue(
                MenuCompiler()
                    .compile(mapOf("m.yml" to minimal(components = listOf(dynamic))))
                    .diagnostics
                    .any { it.code == "TEXT_FORMAT" }
            )
        }
        val unsafe =
            mapOf(
                "id" to "b",
                "type" to "button",
                "actions" to
                    listOf(
                        mapOf("type" to "set-state", "state" to "name", "value" to "new"),
                        mapOf("type" to "submit"),
                    ),
            )
        assertTrue(
            MenuCompiler()
                .compile(mapOf("m.yml" to minimal(components = listOf(unsafe))))
                .diagnostics
                .any { it.code == "ACTION" }
        )
    }

    @Test
    fun authoringContractUsesDecoderFieldDescriptors() {
        val schema = Contract.schema()
        assertTrue(schema.containsKey("\$defs"))
        assertEquals(Contract.nodes.keys, (Contract.manifest()["components"] as Map<*, *>).keys)
        assertFalse("input" in Contract.modes.getValue("canvas"))
        assertTrue(Contract.text.containsKey("hover"))
        assertFalse(Contract.manifest().toString().contains("minimessage", ignoreCase = true))
    }

    @Test
    fun independentElementsAllowOverlapButRejectOverflowAndUnalignedRows() {
        val overlap =
            listOf("a", "b").map { id ->
                mapOf(
                    "id" to id,
                    "type" to "hit-area",
                    "x" to 1,
                    "width" to 90,
                    "height" to 18,
                    "actions" to listOf(mapOf("type" to "close")),
                )
            }
        val result = MenuCompiler().compile(mapOf("m.yml" to minimal("canvas", overlap)))
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:menu")
                .pages
                .getValue("main")
        assertEquals(listOf("a", "b"), page.scene.map { it.node.id })
        assertEquals(page.scene[0].bounds, page.scene[1].bounds)
        assertNotNull(
            MenuCompiler()
                .compile(
                    mapOf(
                        "m.yml" to
                            minimal(
                                "canvas",
                                listOf(
                                    mapOf(
                                        "id" to "label",
                                        "type" to "text",
                                        "width" to 101,
                                        "x" to 1,
                                    )
                                ),
                            )
                    )
                )
                .snapshot
        )
        assertNotNull(
            MenuCompiler()
                .compile(
                    mapOf(
                        "m.yml" to
                            minimal(
                                "canvas",
                                listOf(mapOf("id" to "label", "type" to "text", "y" to 1)),
                            )
                    )
                )
                .snapshot
        )
        for (invalid in
            listOf(
                mapOf("id" to "overflow", "type" to "text", "width" to 433),
                mapOf(
                    "id" to "row",
                    "type" to "hit-area",
                    "y" to 1,
                    "width" to 90,
                    "height" to 18,
                    "actions" to emptyList<Any>(),
                ),
                mapOf(
                    "id" to "hit",
                    "type" to "hit-area",
                    "width" to 90,
                    "height" to 10,
                    "actions" to emptyList<Any>(),
                ),
            )) {
            val failure =
                MenuCompiler().compile(mapOf("m.yml" to minimal("canvas", listOf(invalid))))
            assertNull(failure.snapshot)
            assertTrue(
                failure.diagnostics.any { it.code == "LAYOUT" },
                failure.diagnostics.toString(),
            )
        }
    }

    @Test
    fun documentValuesAreFrozenAndRejectNonDataObjects() {
        val map = mutableMapOf<String, Any?>("list" to mutableListOf("safe"))
        val frozen = Values.map(map)
        (map["list"] as MutableList<String>)[0] = "changed"
        assertEquals(listOf("safe"), frozen["list"])
        assertFails { Values.map(mapOf("object" to Any())) }
        assertFails { Values.freeze(Double.NaN) }
    }
}
