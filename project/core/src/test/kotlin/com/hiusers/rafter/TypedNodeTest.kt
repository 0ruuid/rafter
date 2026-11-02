package com.hiusers.rafter

import com.hiusers.rafter.api.*
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.Registry
import kotlin.test.*
import org.yaml.snakeyaml.Yaml

class TypedNodeTest {
    @Test
    fun nativeControlsHaveOnlyTheirOwnPayloadAndTextBindingIsResolvedOnce() {
        val nodes =
            listOf(
                mapOf(
                    "id" to "text",
                    "type" to "text",
                    "binding" to mapOf("type" to "state", "key" to "name"),
                ),
                mapOf("id" to "item", "type" to "item", "item" to "minecraft:stone"),
                mapOf("id" to "input", "type" to "input", "state" to "name"),
                mapOf("id" to "check", "type" to "checkbox", "state" to "enabled"),
                mapOf(
                    "id" to "select",
                    "type" to "select",
                    "state" to "choice",
                    "options" to mapOf("a" to "A"),
                ),
                mapOf("id" to "range", "type" to "slider", "state" to "number", "step" to 5),
                mapOf("id" to "button", "type" to "button", "actions" to emptyList<Any>()),
            )
        assertEquals(NodeKind.entries.map { it.id }.toSet() + "template", Contract.nodes.keys)
        val menu = compiled(minimal(components = nodes))
        val actual = menu.pages.getValue("main").spec.nodes
        assertSame(NodeContent.Text, actual[0].content)
        assertEquals(BindingSpec("state", "name"), actual[0].text.binding)
        assertIs<NodeContent.Item>(actual[1].content)
        assertIs<NodeContent.TextInput>(actual[2].content)
        assertIs<NodeContent.Checkbox>(actual[3].content)
        assertIs<NodeContent.Select>(actual[4].content)
        assertIs<NodeContent.Slider>(actual[5].content)
        assertIs<NodeContent.Button>(actual[6].content)
        assertFalse(
            NodeSpec::class.java.declaredFields.any {
                it.name in setOf("min", "max", "step", "asset", "item", "maxLength")
            }
        )
        assertNull(
            MenuCompiler()
                .compile(
                    mapOf(
                        "bad.yml" to
                            minimal(
                                components =
                                    listOf(
                                        mapOf(
                                            "id" to "image",
                                            "type" to "image",
                                            "asset" to "rafter:icon",
                                            "min" to 0,
                                        )
                                    )
                            )
                    )
                )
                .snapshot
        )
    }

    @Test
    fun canvasGeometryAndAuthoredOriginUseFlatIndependentElements() {
        val elements =
            listOf(
                mapOf("id" to "label", "type" to "text", "x" to 9, "y" to 9, "text" to "L"),
                mapOf(
                    "id" to "image",
                    "type" to "image",
                    "x" to 0,
                    "y" to 36,
                    "asset" to "rafter:icon",
                ),
                mapOf(
                    "id" to "close",
                    "type" to "hit-area",
                    "x" to 0,
                    "y" to 63,
                    "width" to 180,
                    "height" to 9,
                    "actions" to listOf(mapOf("type" to "close")),
                ),
            )
        val result =
            MenuCompiler(mapOf("rafter:icon" to (18 to 18)), canvasMeasure = { 6 to 18 })
                .compile(mapOf("flat.yml" to minimal("canvas", elements)))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        val page = snapshot.menus.getValue("test:menu").pages.getValue("main")
        assertEquals(72, page.height)
        assertEquals(listOf("label", "image", "close"), page.scene.map { it.node.id })
        assertEquals(
            listOf(Rect(9, 9, 6, 18), Rect(0, 36, 18, 18), Rect(0, 63, 180, 9)),
            page.scene.map { it.bounds },
        )
        assertEquals("$.pages.main.elements[0]", page.scene[0].node.origin?.path)
        assertSame(NodeContent.Text, page.scene[0].node.content)
        assertIs<NodeContent.Image>(page.scene[1].node.content)
        assertSame(NodeContent.HitArea, page.scene[2].node.content)
        assertEquals(listOf(ActionSpec("close")), page.scene[2].node.actions)
        assertTrue(page.scene[2].node.clickable)
        val layout = AuthoringContract.layouts(snapshot)
        assertTrue(layout.toString().contains("rafter-compiler"))
        assertFalse(layout.toString().contains("Player"))
    }

    @Test
    fun legacyCanvasGoldenAndRetiredKindsAreRejectedWithoutCompatibilityNodes() {
        val legacy: Map<String, Any?> =
            Yaml().load(javaClass.getResourceAsStream("/fixtures/rejected-canvas.yml")!!)
        val failure = MenuCompiler().compile(mapOf("legacy.yml" to legacy))
        assertNull(failure.snapshot)
        assertTrue(
            failure.diagnostics.any {
                it.code == "REQUIRED_FIELD" && it.path == "$.pages.main.elements"
            }
        )
        for (type in listOf("panel", "toggle", "row", "column", "absolute", "button")) {
            val result =
                MenuCompiler()
                    .compile(
                        mapOf(
                            "legacy.yml" to
                                minimal(
                                    "canvas",
                                    listOf(mapOf("id" to "legacy", "type" to type)),
                                )
                        )
                    )
            assertNull(result.snapshot, type)
            assertTrue(
                result.diagnostics.any { it.code in setOf("UNKNOWN_TYPE", "CAPABILITY") },
                result.diagnostics.toString(),
            )
        }
    }

    @Test
    fun templateFailuresRetainExpansionOriginsAndDuplicateIdsTargetTheirNode() {
        val template =
            mapOf(
                "id" to "test:label",
                "component" to mapOf("id" to "inner", "type" to "text", "unknown" to true),
            )
        val call = mapOf("id" to "instance", "type" to "template", "key" to "test:label")
        val failure =
            MenuCompiler()
                .compile(
                    mapOf("menus/a.yml" to minimal(components = listOf(call))),
                    mapOf("templates/label.yml" to template),
                )
                .diagnostics
                .single()
        assertEquals("menus/a.yml", failure.file)
        assertTrue(
            failure.origins.any { it.file == "templates/label.yml" && it.path == "$.component" }
        )
        val duplicate = mapOf("id" to "same", "type" to "text")
        val error =
            MenuCompiler()
                .compile(mapOf("dupe.yml" to minimal(components = listOf(duplicate, duplicate))))
                .diagnostics
                .single()
        assertEquals("DUPLICATE_ID", error.code)
        assertEquals("$.pages.main.components[1]", error.path)
        assertEquals("same", error.component)
    }

    @Test
    fun initialStateAndFormStateUseTheSameTypedRulesAndPreviewNeverEvaluatesBindings() {
        val input = mapOf("id" to "input", "type" to "input", "state" to "name")
        assertNull(
            MenuCompiler()
                .compile(
                    mapOf(
                        "bad.yml" to
                            (minimal(components = listOf(input)) +
                                mapOf("state" to mapOf("name" to "line\nbreak")))
                    )
                )
                .snapshot
        )
        val dangerous =
            mapOf(
                "id" to "label",
                "type" to "text",
                "binding" to mapOf("type" to "kether", "script" to "trusted source"),
            )
        var validations = 0
        val snapshot =
            assertNotNull(
                MenuCompiler(scriptCheck = { validations++ })
                    .compile(mapOf("preview.yml" to minimal(components = listOf(dangerous))))
                    .snapshot
            )
        val before = validations
        val preview = AuthoringContract.previewData(snapshot)
        val manifest = AuthoringContract.manifest("0.0.2", emptyMap())
        assertEquals(before, validations)
        assertEquals(11, manifest["contract-version"])
        assertTrue(preview.toString().contains("explicit-stubs-required"))
        assertTrue(manifest.toString().contains("disabled"))
        val native = AuthoringContract.layouts(snapshot).toString()
        assertTrue(native.contains("native-client"))
    }

    @Test
    fun metadataWrappersDoNotConsumeTheParameterValueDepthBudget() {
        var nested: Any? = "leaf"
        repeat(31) { nested = listOf(nested) }
        val registry = Registry<String>()
        registry.register(
            Any(),
            "test:deep",
            ProviderDescriptor(
                "deep",
                mapOf(
                    "value" to
                        ParameterSpec(
                            ParameterType.ARRAY,
                            required = false,
                            hasDefault = true,
                            defaultValue = nested,
                        )
                ),
            ),
            "provider",
        )
        val exported = AuthoringContract.manifest("0.0.2", mapOf("data" to registry.descriptors()))
        assertTrue(exported.toString().contains("leaf"))
        assertFails { Values.freeze(listOf(listOf(nested))) }
    }
}
