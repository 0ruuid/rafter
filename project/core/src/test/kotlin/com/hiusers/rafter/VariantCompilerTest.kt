package com.hiusers.rafter

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.ActionSpec
import com.hiusers.rafter.model.CompileResult
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariantCompilerTest {
    private val yes = mapOf("type" to "constant", "value" to true)
    private val no = mapOf("type" to "constant", "value" to false)
    private val element =
        mapOf<String, Any?>(
            "id" to "element",
            "type" to "text",
            "text" to "Base",
            "width" to 90,
            "actions" to listOf(mapOf("type" to "close")),
        )
    private val nativeButton =
        mapOf<String, Any?>(
            "id" to "native-button",
            "type" to "button",
            "text" to "Base",
            "width" to 90,
            "actions" to listOf(mapOf("type" to "close")),
        )
    private val assets =
        mapOf(
            "test:a" to (90 to 18),
            "test:b" to (90 to 18),
            "test:wide" to (99 to 18),
            "test:tall" to (90 to 27),
        )

    private fun variant(override: Map<String, Any?>, condition: Map<String, Any?> = yes) =
        mapOf("when" to condition, "override" to override)

    private fun compile(
        node: Map<String, Any?>,
        mode: String = "canvas",
        compiler: MenuCompiler =
            MenuCompiler(
                assets = assets,
                styles = mapOf("tall" to mapOf("height" to 27), "normal" to mapOf("height" to 18)),
            ),
    ) = compiler.compile(mapOf("menu.yml" to minimal(mode, listOf(node))))

    private fun node(result: CompileResult): NodeSpec =
        assertNotNull(result.snapshot, result.diagnostics.toString())
            .menus
            .getValue("test:menu")
            .spec
            .pages
            .getValue("main")
            .nodes
            .single()

    private fun rejected(result: CompileResult, code: String) {
        assertNull(result.snapshot)
        assertEquals(code, result.diagnostics.single().code, result.diagnostics.toString())
    }

    @Test
    fun compilesIndependentLeafBranchesRenamedFieldsAndTemplateOrigins() {
        val scripts = mutableSetOf<String>()
        val providers = mutableSetOf<String>()
        val compiler =
            MenuCompiler(
                assets = assets,
                scriptCheck = { scripts += it },
                extensionCheck = { kind, key, _ -> providers += "$kind:$key" },
            )
        val condition =
            mapOf(
                "type" to "all",
                "conditions" to
                    listOf(
                        mapOf("type" to "permission", "permission" to "test.use"),
                        mapOf("type" to "state-equals", "state" to "enabled", "value" to true),
                        mapOf("type" to "provider", "provider" to "test:condition"),
                        mapOf("type" to "kether", "script" to "condition-script"),
                        mapOf("type" to "any", "conditions" to listOf(yes)),
                        mapOf("type" to "not", "condition" to no),
                    ),
            )
        val actions =
            listOf(
                mapOf("type" to "set-state", "state" to "name", "value" to "new"),
                mapOf("type" to "toggle-state", "state" to "enabled"),
                mapOf("type" to "provider", "provider" to "test:action"),
                mapOf("type" to "kether", "script" to "action-script"),
                mapOf("type" to "open-page", "page" to "main"),
            )
        val authored =
            element +
                ("variants" to
                    listOf(
                        variant(
                            mapOf(
                                "text" to
                                    mapOf(
                                        "format" to "literal",
                                        "binding" to
                                            mapOf("type" to "kether", "script" to "binding-script"),
                                    ),
                                "actions" to actions,
                                "enabled" to no,
                            ),
                            condition,
                        ),
                        variant(mapOf("tooltip" to "Second")),
                    ))
        val base = node(compile(authored, compiler = compiler))
        val first = base.variants[0]
        val second = base.variants[1]
        assertEquals("all", first.condition.type)
        assertEquals("test.use", first.condition.children.first().key)
        assertEquals("not", first.condition.children.last().type)
        assertEquals(false, first.node.enabled?.value)
        assertEquals(base.id, first.node.id)
        assertEquals(base.type, first.node.type)
        assertEquals(base.width, first.node.width)
        assertTrue(first.node.variants.isEmpty())
        assertEquals("binding-script", first.node.text.binding?.key)
        assertEquals(
            listOf("name", "enabled", "test:action", "action-script", "main"),
            first.node.actions.map { it.key },
        )
        assertEquals(base.text, second.node.text)
        assertEquals(base.actions, second.node.actions)
        assertEquals(setOf("binding-script", "action-script", "condition-script"), scripts)
        assertEquals(setOf("action:test:action", "condition:test:condition"), providers)

        val hotspot =
            node(
                compile(
                    mapOf(
                        "id" to "mission",
                        "type" to "hit-area",
                        "width" to 90,
                        "height" to 18,
                        "actions" to listOf(mapOf("type" to "toggle-state", "state" to "enabled")),
                        "variants" to listOf(variant(mapOf("actions" to emptyList<Any>()))),
                    )
                )
            )
        assertEquals(listOf(ActionSpec("toggle-state", "enabled")), hotspot.actions)
        assertEquals(NodeContent.HitArea, hotspot.content)
        assertTrue(hotspot.clickable)
        assertTrue(hotspot.variants.single().node.actions.isEmpty())
        val image =
            mapOf(
                "id" to "image",
                "type" to "image",
                "asset" to "test:a",
                "variants" to listOf(variant(mapOf("asset" to "test:b"))),
            )
        for (mode in listOf("canvas", "native")) assertEquals(
            "test:b",
            (node(compile(image, mode)).variants.single().node.content as NodeContent.Image).asset,
        )
        val item =
            node(
                compile(
                    mapOf(
                        "id" to "item",
                        "type" to "item",
                        "item" to "test:first",
                        "variants" to listOf(variant(mapOf("item" to "test:second"))),
                    ),
                    "native",
                    compiler,
                )
            )
        assertEquals(
            "test:second",
            (item.variants.single().node.content as NodeContent.Item).reference,
        )
        assertTrue("item:test:second" in providers)
        val text =
            node(
                compile(
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "variants" to
                            listOf(variant(mapOf("text" to "Changed", "font-size" to 18))),
                    )
                )
            )
        assertEquals("Changed", text.variants.single().node.text.value)

        val template = mapOf("id" to "test:leaf", "component" to (authored + ("visible" to no)))
        val call = mapOf("id" to "instance", "type" to "template", "key" to "test:leaf")
        val expanded =
            node(
                compiler.compile(
                    mapOf("menu.yml" to minimal("canvas", listOf(call))),
                    mapOf("leaf.yml" to template),
                )
            )
        assertEquals(false, expanded.visible?.value)
        assertEquals("instance", expanded.variants.first().node.id)
        assertEquals(listOf("leaf.yml"), expanded.variants.first().node.origins.map { it.file })
        assertTrue(expanded.variants.first().node.origin!!.path.endsWith(".variants[0].override"))
        val invalidTemplate =
            template +
                ("component" to
                    (element +
                        ("variants" to
                            listOf(
                                variant(
                                    mapOf(
                                        "actions" to
                                            listOf(
                                                mapOf("type" to "open-page", "page" to "missing")
                                            )
                                    )
                                )
                            ))))
        val failure =
            compiler.compile(
                mapOf("menu.yml" to minimal("canvas", listOf(call))),
                mapOf("leaf.yml" to invalidTemplate),
            )
        rejected(failure, "REFERENCE")
        assertEquals(listOf("leaf.yml"), failure.diagnostics.single().origins.map { it.file })
    }

    @Test
    fun rejectsLegacyNestedUnsafeAndGeometryOverridesAndExportsClosedSchema() {
        fun changed(fields: Map<String, Any?>) = element + ("variants" to listOf(variant(fields)))
        for (field in
            listOf(
                "id",
                "type",
                "state",
                "x",
                "y",
                "width",
                "height",
                "children",
                "variants",
                "binding",
                "visible",
            )) rejected(compile(changed(mapOf(field to "forbidden"))), "UNKNOWN_FIELD")
        rejected(compile(changed(mapOf("enabled" to false))), "FIELD_TYPE")
        rejected(compile(element + ("variants" to List(33) { variant(emptyMap()) })), "RANGE")
        rejected(
            compile(element + ("variants" to listOf(variant(emptyMap()) + ("unknown" to true)))),
            "UNKNOWN_FIELD",
        )
        for (type in listOf("row", "column", "absolute", "panel", "toggle")) rejected(
            compile(mapOf("id" to "retired", "type" to type)),
            "UNKNOWN_TYPE",
        )
        rejected(compile(nativeButton), "CAPABILITY")
        for (type in
            listOf("open-page", "set-state", "toggle-state", "provider", "kether")) rejected(
            compile(changed(mapOf("actions" to listOf(mapOf("type" to type, "key" to "old"))))),
            "UNKNOWN_FIELD",
        )
        for (type in
            listOf(
                "permission",
                "state-equals",
                "provider",
                "kether",
                "all",
                "any",
                "not",
            )) rejected(
            compile(
                element +
                    ("variants" to
                        listOf(
                            variant(
                                emptyMap(),
                                mapOf(
                                    "type" to type,
                                    (if (type in listOf("all", "any", "not")) "children"
                                    else "key") to "old",
                                ),
                            )
                        ))
            ),
            "UNKNOWN_FIELD",
        )
        rejected(
            compile(
                changed(
                    mapOf(
                        "text" to
                            mapOf(
                                "format" to "literal",
                                "binding" to mapOf("type" to "kether", "key" to "old"),
                            )
                    )
                )
            ),
            "UNKNOWN_FIELD",
        )
        rejected(
            compile(
                changed(
                    mapOf("actions" to listOf(mapOf("type" to "open-page", "page" to "missing")))
                )
            ),
            "REFERENCE",
        )
        rejected(
            compile(
                changed(
                    mapOf(
                        "actions" to
                            listOf(
                                mapOf("type" to "set-state", "state" to "missing", "value" to true)
                            )
                    )
                )
            ),
            "STATE",
        )
        rejected(
            compile(
                changed(
                    mapOf("actions" to listOf(mapOf("type" to "close"), mapOf("type" to "back")))
                )
            ),
            "ACTION",
        )
        rejected(compile(changed(mapOf("actions" to listOf(mapOf("type" to "submit"))))), "ACTION")
        rejected(
            compile(
                nativeButton +
                    ("variants" to
                        listOf(
                            variant(
                                mapOf(
                                    "actions" to
                                        listOf(
                                            mapOf("type" to "toggle-state", "state" to "enabled"),
                                            mapOf("type" to "submit"),
                                        )
                                )
                            )
                        )),
                "native",
            ),
            "ACTION",
        )
        assertNotNull(
            compile(
                    nativeButton +
                        ("variants" to
                            listOf(
                                variant(
                                    mapOf(
                                        "actions" to
                                            listOf(
                                                mapOf("type" to "submit"),
                                                mapOf("type" to "close"),
                                            )
                                    )
                                )
                            )),
                    "native",
                )
                .snapshot
        )
        rejected(
            compile(
                changed(mapOf("actions" to listOf(mapOf("type" to "kether", "script" to "bad")))),
                compiler = MenuCompiler(scriptCheck = { error("invalid") }),
            ),
            "SCRIPT",
        )
        rejected(
            compile(
                changed(mapOf("enabled" to mapOf("type" to "provider", "provider" to "missing"))),
                compiler = MenuCompiler(extensionCheck = { _, _, _ -> error("missing") }),
            ),
            "PROVIDER",
        )
        rejected(compile(changed(mapOf("style" to "tall"))), "LAYOUT")
        assertNotNull(
            compile(
                    (element + ("height" to 18)) +
                        ("variants" to listOf(variant(mapOf("style" to "tall"))))
                )
                .snapshot
        )
        rejected(compile(changed(mapOf("asset" to "test:tall"))), "UNKNOWN_FIELD")
        for (mode in listOf("canvas", "native")) for (asset in
            listOf("test:wide", "test:tall")) rejected(
            compile(
                mapOf(
                    "id" to "image",
                    "type" to "image",
                    "asset" to "test:a",
                    "variants" to listOf(variant(mapOf("asset" to asset))),
                ),
                mode,
            ),
            "LAYOUT",
        )
        rejected(
            compile(
                mapOf(
                    "id" to "image",
                    "type" to "image",
                    "asset" to "test:a",
                    "width" to 90,
                    "variants" to listOf(variant(mapOf("asset" to "test:tall"))),
                )
            ),
            "LAYOUT",
        )
        val legacyRow = mapOf("id" to "row", "type" to "row", "children" to listOf(element))
        rejected(compile(legacyRow), "UNKNOWN_TYPE")

        val definitions = assertIs<Map<*, *>>(Contract.schema()["\$defs"])
        val variantSchema = assertIs<Map<*, *>>(definitions["variant"])
        assertEquals(false, variantSchema["additionalProperties"])
        assertEquals(listOf("when", "override"), variantSchema["required"])
        val properties = assertIs<Map<*, *>>(variantSchema["properties"])
        assertEquals(mapOf("\$ref" to "#/\$defs/condition"), properties["when"])
        val overrideSchema = assertIs<Map<*, *>>(properties["override"])
        assertEquals(false, overrideSchema["additionalProperties"])
        val overrideFields = assertIs<Map<*, *>>(overrideSchema["properties"])
        assertTrue("actions" in overrideFields && "asset" in overrideFields)
        assertFalse(
            "state" in overrideFields ||
                "id" in overrideFields ||
                "variants" in overrideFields ||
                "binding" in overrideFields
        )
        for ((mode, allowed) in Contract.modes) for (type in allowed) {
            val fields = Contract.fields(type, mode)
            assertEquals(type in Contract.variantTypes, "variants" in fields)
            if (type in Contract.variantTypes)
                assertEquals(32, fields.getValue("variants").maxItems)
        }
        assertFalse("asset" in Contract.variantFields("button", "native"))
        assertTrue("actions" in Contract.variantFields("text", "canvas"))
        assertEquals(setOf("type", "condition"), Contract.conditions.getValue("not").keys)
        assertEquals(setOf("type", "conditions"), Contract.conditions.getValue("all").keys)
        assertEquals("boolean", Contract.conditions.getValue("constant").getValue("value").type)
        assertEquals(setOf("type", "script"), Contract.bindings.getValue("kether").keys)
        assertTrue("variant-overrides" in Contract.manifest())
    }

    @Test
    fun nativeFormVariantsKeepContentAndRejectStateOverrides() {
        val controls =
            listOf(
                mapOf<String, Any?>("id" to "input", "type" to "input", "state" to "name"),
                mapOf<String, Any?>("id" to "checkbox", "type" to "checkbox", "state" to "enabled"),
                mapOf(
                    "id" to "select",
                    "type" to "select",
                    "state" to "choice",
                    "options" to mapOf("a" to "A", "b" to "B"),
                ),
                mapOf<String, Any?>("id" to "slider", "type" to "slider", "state" to "number"),
            )
        for (control in controls) {
            val empty = control + ("variants" to emptyList<Any>())
            val base = node(compile(empty, "native"))
            assertTrue(base.variants.isEmpty(), control.toString())

            val withText = control + ("variants" to listOf(variant(mapOf("text" to "Alternate"))))
            val variantNode = node(compile(withText, "native"))
            val branch = variantNode.variants.single().node
            assertEquals(variantNode.content, branch.content, control.toString())
            assertEquals("Alternate", branch.text.value, control.toString())

            rejected(
                compile(
                    control +
                        ("variants" to listOf(variant(mapOf("state" to "other")))),
                    "native",
                ),
                "UNKNOWN_FIELD",
            )
        }
    }
}
