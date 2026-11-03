package com.hiusers.rafter

import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.Field
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.DocumentError
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractDescriptorTest {
    @Test
    fun versionlessDocumentsCompileAndExportSchemasWhileAuthoringContractKeepsItsVersion() {
        val template =
            mapOf(
                "id" to "test:label",
                "component" to mapOf("id" to "inner", "type" to "text", "text" to "&aHello"),
            )
        val menu =
            minimal(
                components =
                    listOf(mapOf("id" to "instance", "type" to "template", "key" to "test:label"))
            )
        val compiler = MenuCompiler()
        val result = compiler.compile(mapOf("menu.yml" to menu), mapOf("template.yml" to template))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        val text =
            snapshot.menus.getValue("test:menu").spec.pages.getValue("main").nodes.single().text
        assertEquals("&aHello", text.value)
        assertEquals("colored", text.format)
        for ((menus, templates, file) in
            listOf(
                Triple(
                    mapOf("menu.yml" to (menu + ("schema-version" to 1))),
                    mapOf("template.yml" to template),
                    "menu.yml",
                ),
                Triple(
                    mapOf("menu.yml" to menu),
                    mapOf("template.yml" to (template + ("schema-version" to 1))),
                    "template.yml",
                ),
            )) {
            val failure = compiler.compile(menus, templates)
            assertNull(failure.snapshot)
            // 模板错误会连带产生未解析引用，此处断言原始来源错误。
            val diagnostic =
                failure.diagnostics.single { it.file == file && it.code == "UNKNOWN_FIELD" }
            assertEquals("UNKNOWN_FIELD", diagnostic.code)
            assertEquals(file, diagnostic.file)
            assertEquals("$.schema-version", diagnostic.path)
        }
        val documents =
            listOf(
                Contract.menu to menu,
                Contract.template to template,
                Contract.asset to mapOf("images" to emptyMap<String, Any?>()),
                Contract.settings to
                    mapOf("default-menu" to "test:menu", "idle-timeout-seconds" to 600),
                Contract.theme to
                    mapOf(
                        "defaults" to mapOf("font" to "rafter-mono", "font-size" to 10),
                        "palette" to emptyMap<String, Any?>(),
                        "controls" to emptyMap<String, Any?>(),
                        "hide-dialog-warning" to false,
                        "hide-canvas-focus-outline" to false,
                        "hide-native-focus-outline" to false,
                    ),
            )
        for ((fields, document) in documents) {
            check(document, fields)
            rejected(document + ("schema-version" to 1), fields, "schema-version", "UNKNOWN_FIELD")
        }
        val manifest = AuthoringContract.manifest("test", emptyMap())
        for (schema in
            listOf(
                Contract.schema(),
                Contract.templateSchema(),
                Contract.assetSchema(),
                Contract.themeSchema(),
            )) {
            assertFalse("schema-version" in objectValue(schema["properties"]))
            assertFalse("schema-version" in assertIs<List<*>>(schema["required"]))
            assertEquals(false, schema["additionalProperties"])
        }
        assertEquals(13, AuthoringContract.VERSION)
        val service = objectValue(manifest["service-api"])
        assertEquals("node-row-record-or-null", service["item-context"])
        assertEquals("client-flow-template-rows", service["native-lists"])
        assertEquals("expected-session-id-required", service["refresh"])
        assertEquals("expected-session-id-required", service["close"])
        assertEquals("declared-state-atomic", objectValue(service["action-result"])["state-patch"])
        val open = objectValue(service["open"])
        assertEquals("declared-state-atomic-before-first-read", open["initial-state"])
        assertEquals("entry-or-existing-page", open["initial-page"])
        assertEquals("present-entry-canvas-then-modal", open["initial-modal"])
        assertEquals("final-target-sent", open["completion"])
        val nativeModal = objectValue(manifest["native-modal"])
        assertEquals("canvas-only-without-frame-or-current-modal", nativeModal["opener"])
        assertEquals("forbidden", nativeModal["entry"])
        assertEquals("same-layer-reuse-backdrop-no-history-push", nativeModal["replacement"])
        for (export in
            listOf(
                manifest,
                AuthoringContract.layouts(snapshot),
                AuthoringContract.previewData(snapshot),
            )) {
            assertEquals(AuthoringContract.VERSION, export["contract-version"])
        }
    }

    @Test
    fun numericBoundsMultiplesAndEnumsDoNotRoundThroughDouble() {
        val field =
            Field(
                "number",
                minimum = BigDecimal("0.1"),
                maximum = BigDecimal("0.3"),
                multipleOf = BigDecimal("0.1"),
            )
        val fields = mapOf("value" to field)
        for (value in listOf(0.1, 0.2, 0.3, BigDecimal("0.3000"))) check(
            mapOf("value" to value),
            fields,
        )
        for (value in
            listOf(
                BigDecimal("0.09999999999999999999"),
                BigDecimal("0.30000000000000000001"),
                BigDecimal("0.20000000000000000001"),
            )) {
            rejected(mapOf("value" to value), fields, "value", "RANGE")
        }
        assertEquals(BigDecimal("0.1"), field.schema()["minimum"])
        assertEquals(BigDecimal("0.3"), field.schema()["maximum"])
        assertEquals(BigDecimal("0.1"), field.schema()["multipleOf"])
        val precise = BigDecimal("1.00000000000000000001")
        val enumField = Field("number", values = listOf(precise))
        check(mapOf("value" to precise), mapOf("value" to enumField))
        rejected(mapOf("value" to 1.0), mapOf("value" to enumField), "value", "RANGE")
        assertEquals(precise, enumField.schema()["const"])
    }

    @Test
    fun pageVariantsShareBoundsDefaultsAndExplicitModeSpecificListsWithSchema() {
        val schema = definition(Contract.schema(), "page")
        for (mode in Contract.modes.keys) {
            val fields = Contract.pageFields(mode)
            val variant = variant(schema, "mode", mode)
            val listField = if (mode == "canvas") "elements" else "components"
            assertDescriptors(fields, variant)
            assertEquals(if (mode == "canvas") 432 else 360, property(variant, "width")["default"])
            assertEquals(
                "#/\$defs/${mode}Node",
                objectValue(property(variant, listField)["items"])["\$ref"],
            )
            for (width in listOf(90, 91, 864)) check(page(mode) + ("width" to width), fields)
            for (width in listOf(89, 865)) rejected(
                page(mode) + ("width" to width),
                fields,
                "width",
                "LAYOUT",
            )
            check(page(mode) - listField, fields)
            rejected(page(mode) + (listField to true), fields, listField, "FIELD_TYPE")
            val otherList = if (mode == "canvas") "components" else "elements"
            if (mode == "canvas") check(page(mode) + (otherList to emptyList<Any>()), fields)
            else
                rejected(
                    page(mode) + (otherList to emptyList<Any>()),
                    fields,
                    otherList,
                    "UNKNOWN_FIELD",
                )
        }
        val native = variant(schema, "mode", "native")
        assertEquals(1, property(native, "columns")["default"])
        for (columns in listOf(0, -1)) rejected(
            page("native") + ("columns" to columns),
            Contract.pageFields("native"),
            "columns",
            "LAYOUT",
        )
        check(page("native") + mapOf("width" to 91, "columns" to 4), Contract.pageFields("native"))
        check(page("native") + ("columns" to 5), Contract.pageFields("native"))
        check(page("canvas") + ("columns" to 2), Contract.pageFields("canvas"))
        rejected(
            page("canvas") + ("mode" to "native"),
            Contract.pageFields("canvas"),
            "mode",
            "MODE",
        )
        rejected(page("Canvas"), Contract.page, "mode", "MODE")
        rejected(page("canvas") - "mode", Contract.page, "mode", "REQUIRED_FIELD")
        assertFalse("default" in Contract.page.getValue("width").schema())
    }

    @Test
    fun canvasGeometryKeepsRowAlignmentButAllowsIndependentPixelWidthsAndAnchors() {
        val fields = Contract.fields("text", "canvas")
        val node = node("text")
        for ((key, valid) in
            mapOf(
                "width" to listOf(1, 10, 864),
                "height" to listOf(1, 9, 10, 4095),
                "x" to listOf(0, 1, 864),
                "y" to listOf(0, 1, 4095),
            )) {
            valid.forEach { check(node + (key to it), fields) }
        }
        for ((key, invalid) in
            mapOf(
                "width" to listOf(0, 865),
                "height" to listOf(0, -1, 4096),
                "x" to listOf(-1, 865),
                "y" to listOf(-9, -1, 4104),
            )) {
            invalid.forEach { rejected(node + (key to it), fields, key, "LAYOUT") }
        }
        val schema = variant(definition(Contract.schema(), "canvasNode"), "type", "text")
        assertDescriptors(fields, schema)
        assertEquals(4095, property(schema, "height")["maximum"])
        assertEquals(4095, property(schema, "y")["maximum"])
        for (align in listOf("left", "center", "right")) check(node + ("align" to align), fields)
        rejected(node + ("align" to "middle"), fields, "align", "RANGE")
        val hit =
            node("hit-area") + mapOf("width" to 1, "height" to 18, "actions" to emptyList<Any>())
        check(hit, Contract.fields("hit-area", "canvas"))
        for (height in listOf(1, 10)) rejected(
            hit + ("height" to height),
            Contract.fields("hit-area", "canvas"),
            "height",
            "LAYOUT",
        )
        for (type in listOf("row", "column", "absolute", "panel", "toggle", "button")) assertFalse(
            type in Contract.modes.getValue("canvas")
        )
    }

    @Test
    fun nativeAndCanvasCapabilitiesDoNotAdvertiseIgnoredFields() {
        val schema = Contract.schema()
        val capabilities = objectValue(Contract.manifest()["capabilities"])
        for ((mode, allowed) in Contract.modes) {
            val variants = variants(definition(schema, "${mode}Node"))
            assertEquals(allowed, variants.map { property(it, "type")["const"] }.toSet())
            val described = objectValue(objectValue(capabilities[mode])["components"])
            assertEquals(allowed, described.keys)
            for (type in allowed) {
                val fields = Contract.fields(type, mode)
                assertDescriptors(fields, variant(definition(schema, "${mode}Node"), "type", type))
                assertEquals(fields.keys, objectValue(described[type]).keys)
            }
        }
        val nativeText = Contract.fields("text", "native")
        for (width in listOf(1, 91, 864)) check(node("text") + ("width" to width), nativeText)
        for (key in listOf("x", "y", "height")) rejected(
            node("text") + (key to 9),
            nativeText,
            key,
            "UNKNOWN_FIELD",
        )
        val button = node("button") + ("actions" to emptyList<Any>())
        for ((key, value) in
            mapOf("asset" to "rafter:skin", "color" to "#123456", "label-offset" to 9)) {
            rejected(
                button + (key to value),
                Contract.fields("button", "native"),
                key,
                "UNKNOWN_FIELD",
            )
        }
        assertFalse("input" in Contract.modes.getValue("canvas"))
        assertFalse("item" in Contract.modes.getValue("canvas"))
        assertFalse("row" in Contract.modes.getValue("native"))
        assertFalse("toggle" in Contract.modes.getValue("native"))
    }

    @Test
    fun assetImagesUseTheSameDimensionsDensityColorAndDictionaryLimits() {
        val image = mapOf("width" to 9, "height" to 9, "source" to "images/skin.jpg")
        val schema = Contract.assetSchema()
        val imageSchema = definition(schema, "assetImage")
        assertDescriptors(Contract.assetImage, imageSchema)
        assertEquals(
            "#/\$defs/assetImage",
            objectValue(property(schema, "images")["additionalProperties"])["\$ref"],
        )
        assertEquals(null, property(schema, "images")["maxProperties"])
        for ((key, values) in
            mapOf(
                "width" to listOf(9, 864),
                "height" to listOf(9, 4095),
                "scale" to listOf(1, 8),
            )) {
            values.forEach { check(image + (key to it), Contract.assetImage) }
        }
        for ((key, values) in
            mapOf(
                "width" to listOf(8, 10, 873),
                "height" to listOf(8, 10, 4104),
                "scale" to listOf(0, 9),
            )) {
            values.forEach { rejected(image + (key to it), Contract.assetImage, key, "RANGE") }
        }
        for (value in listOf("#000000", "#aBcD09")) check(
            (image - "source") + ("color" to value),
            Contract.assetImage,
        )
        for (value in listOf("red", "#fff", "#12345g", "#123456\n")) {
            rejected((image - "source") + ("color" to value), Contract.assetImage, "color", "COLOR")
        }
        assertEquals(mapOf("type" to "string"), property(imageSchema, "source"))
        assertEquals(
            listOf(listOf("source"), listOf("color")),
            variants(imageSchema).map { it["required"] },
        )
        val images = (0 until 128).associate { "rafter:icons/$it" to image }
        val document = mapOf("images" to images)
        check(document, Contract.asset)
        check(document + ("images" to (images + ("rafter:additional" to image))), Contract.asset)
        rejected(
            document + ("images" to listOf(image)),
            Contract.asset,
            "images",
            "FIELD_TYPE",
        )
        for (id in
            listOf(
                "other:icon",
                "rafter:.",
                "rafter:../icon",
                "rafter:icons/../icon",
                "rafter:icons/.",
            )) {
            val error =
                assertFailsWith<DocumentError> {
                        check(document + ("images" to mapOf(id to image)), Contract.asset)
                    }
                    .diagnostic
            assertEquals("ID", error.code)
            assertEquals(com.hiusers.rafter.model.SourcePath.field("$.images", id), error.path)
        }
        // 文件内容、路径与符号链接由资源编译器校验。
        check(image + ("source" to "images/no-invented-extension-rule"), Contract.assetImage)
        rejected(image + ("source" to 1), Contract.assetImage, "source", "FIELD_TYPE")
    }

    @Test
    fun formAndConditionCollectionBoundariesMatchSchemaWithoutInventedUniqueness() {
        val input = node("input") + ("state" to "name")
        for (value in listOf(1, 4096)) check(
            input + ("max-length" to value),
            Contract.fields("input", "native"),
        )
        for (value in listOf(0, 4097)) rejected(
            input + ("max-length" to value),
            Contract.fields("input", "native"),
            "max-length",
            "RANGE",
        )
        val select = node("select") + ("state" to "choice")
        for (size in listOf(1, 64)) check(
            select + ("options" to options(size)),
            Contract.fields("select", "native"),
        )
        for (size in listOf(0, 65)) rejected(
            select + ("options" to options(size)),
            Contract.fields("select", "native"),
            "options",
            "OPTIONS",
        )
        val child = mapOf("type" to "permission", "permission" to "rafter.use")
        for (type in listOf("all", "any")) {
            val fields = Contract.conditions.getValue(type)
            val condition = mapOf("type" to type, "conditions" to listOf(child))
            check(condition, fields)
            rejected(
                condition + ("conditions" to emptyList<Any>()),
                fields,
                "conditions",
                "CONDITION",
            )
            rejected(condition + ("children" to listOf(child)), fields, "children", "UNKNOWN_FIELD")
            val schema = variant(definition(Contract.schema(), "condition"), "type", type)
            assertDescriptors(fields, schema)
            assertFalse("uniqueItems" in property(schema, "conditions"))
            check(condition + ("conditions" to listOf(child, child)), fields)
        }
        val fields = Contract.conditions.getValue("not")
        val condition = mapOf("type" to "not", "condition" to child)
        check(condition, fields)
        rejected(condition + ("children" to listOf(child)), fields, "children", "UNKNOWN_FIELD")
        for (invalid in listOf(emptyList<Any>(), listOf(child), listOf(child, child))) {
            val error =
                assertFailsWith<DocumentError> {
                    check(condition + ("condition" to invalid), fields)
                }
            assertEquals("$.condition", error.diagnostic.path)
        }
        val schema = variant(definition(Contract.schema(), "condition"), "type", "not")
        assertDescriptors(fields, schema)
        assertEquals("#/\$defs/condition", property(schema, "condition")["\$ref"])
    }

    @Test
    fun builtinShapesRetainExplicitDiscriminatorsAndUnknownFieldRejection() {
        val payloads =
            mapOf(
                "text" to mapOf("text" to "Hello"),
                "image" to mapOf("asset" to "rafter:icon"),
                "item" to mapOf("item" to "minecraft:compass"),
                "button" to mapOf("actions" to emptyList<Any>()),
                "hit-area" to mapOf("width" to 90, "height" to 18, "actions" to emptyList<Any>()),
                "input" to mapOf("state" to "name"),
                "checkbox" to mapOf("state" to "enabled"),
                "select" to mapOf("state" to "choice", "options" to options(1)),
                "slider" to mapOf("state" to "number", "min" to 0, "max" to 100, "step" to 1),
                "template" to mapOf("key" to "test:label", "params" to mapOf("label" to "Hello")),
                "group" to mapOf("elements" to listOf(node("text"))),
                "list" to
                    mapOf("source" to "items", "template" to "test:row", "page-state" to "page"),
                "meter" to
                    mapOf(
                        "width" to 9,
                        "height" to 9,
                        "value" to mapOf("type" to "state", "key" to "value"),
                        "max" to mapOf("type" to "state", "key" to "maximum"),
                    ),
            )
        assertEquals(Contract.nodes.keys, payloads.keys)
        for ((mode, types) in Contract.modes) for (type in types) {
            val fields = Contract.fields(type, mode)
            val document = node(type) + payloads.getValue(type)
            check(document, fields)
            rejected(document - "type", fields, "type", "REQUIRED_FIELD")
            rejected(document + ("type" to "not-a-component"), fields, "type", "UNKNOWN_TYPE")
            rejected(
                document + ("typo" to true),
                fields,
                "typo",
                "UNKNOWN_FIELD",
                "$.components[2]",
            )
        }
        val binding =
            mapOf("type" to "data", "key" to "test:data", "args" to mapOf("name" to "literal"))
        check(binding, Contract.bindings.getValue("data"))
        rejected(
            binding + ("type" to "DATA"),
            Contract.bindings.getValue("data"),
            "type",
            "UNKNOWN_TYPE",
        )
        val text = mapOf("format" to "literal", "binding" to binding, "color" to "#123456")
        check(text, Contract.textFormats.getValue("literal"))
        rejected(text + ("format" to "Literal"), Contract.text, "format", "TEXT_FORMAT")
        rejected(
            text + ("parts" to emptyList<Any>()),
            Contract.textFormats.getValue("literal"),
            "parts",
            "UNKNOWN_FIELD",
        )
        val schema = Contract.schema()
        val literal = variant(variants(definition(schema, "text"))[1], "format", "literal")
        assertEquals(listOf("value", "binding"), objectValue(literal["not"])["required"])
        val setState = variant(definition(schema, "action"), "type", "set-state")
        assertEquals(
            listOf(listOf("value"), listOf("binding")),
            variants(setState).map { it["required"] },
        )
        check(
            mapOf("type" to "set-state", "state" to "name", "value" to null),
            Contract.actions.getValue("set-state"),
        )
        for ((schemaName, descriptors) in
            listOf(
                "action" to Contract.actions,
                "condition" to Contract.conditions,
                "binding" to Contract.bindings,
            )) {
            for ((type, fields) in descriptors) {
                val shape = variant(definition(schema, schemaName), "type", type)
                assertDescriptors(fields, shape)
                val renamed =
                    when {
                        type == "kether" -> "script"
                        schemaName == "binding" -> continue
                        type == "open-page" -> "page"
                        type in listOf("set-state", "toggle-state", "state-equals") -> "state"
                        type in listOf("provider", "permission") -> type
                        else -> continue
                    }
                assertTrue(renamed in objectValue(shape["properties"]))
                assertFalse("key" in objectValue(shape["properties"]))
                rejected(
                    mapOf("type" to type, renamed to "example", "key" to "legacy", "value" to null)
                        .filterKeys { it in fields || it == "key" },
                    fields,
                    "key",
                    "UNKNOWN_FIELD",
                )
            }
        }
    }

    @Test
    fun templatesDescribeOnlyPreSubstitutionStructureAndRealLiteralDefaults() {
        val document =
            mapOf(
                "id" to "test:label",
                "params" to mapOf("label" to "Default"),
                "component" to
                    mapOf("id" to "inner", "type" to "text", "text" to mapOf("param" to "label")),
            )
        check(document, Contract.template)
        assertDescriptors(Contract.template, Contract.templateSchema())
        assertEquals(mapOf("type" to "object"), property(Contract.templateSchema(), "component"))
        rejected(
            document + ("components" to emptyList<Any>()),
            Contract.template,
            "components",
            "UNKNOWN_FIELD",
        )
        rejected(document - "component", Contract.template, "component", "REQUIRED_FIELD")
        val manifest = Contract.manifest()
        assertEquals(Contract.nodes.keys, objectValue(manifest["components"]).keys)
        assertEquals(Contract.template.keys, objectValue(manifest["template-fields"]).keys)
        val canvas = objectValue(objectValue(manifest["capabilities"])["canvas"])
        val native = objectValue(objectValue(manifest["capabilities"])["native"])
        assertEquals(432, objectValue(objectValue(canvas["page"])["width"])["default"])
        assertEquals(360, objectValue(objectValue(native["page"])["width"])["default"])
        val textFields = objectValue(objectValue(canvas["components"])["text"])
        for (key in listOf("width", "height", "binding", "visible", "enabled")) assertFalse(
            "default" in objectValue(textFields[key])
        )
        assertEquals("", objectValue(textFields["text"])["default"])
        assertEquals(0, objectValue(textFields["x"])["default"])
        assertEquals(
            1,
            objectValue(objectValue(manifest["asset-image-fields"])["scale"])["default"],
        )
        assertFalse(
            "default" in
                objectValue(objectValue(manifest["asset-fields"])["hide-native-focus-outline"])
        )
    }

    @Test
    fun patternAndStringLengthSemanticsMatchJsonSchemaAndErrorsPointAtFields() {
        val field = Field("string", pattern = "x", minLength = 1, maxLength = 2)
        val fields = mapOf("label" to field)
        check(mapOf("label" to "😀x"), fields)
        rejected(mapOf("label" to "😀😀x"), fields, "label", "RANGE")
        rejected(mapOf("label" to "ab"), fields, "label", "RANGE")
        assertEquals("x", field.schema()["pattern"])
        assertEquals(1, field.schema()["minLength"])
        assertEquals(2, field.schema()["maxLength"])
        rejected(node("text") + ("id" to "label\n"), Contract.fields("text", "canvas"), "id", "ID")
        rejected(
            node("text") + ("id" to "namespace:label"),
            Contract.fields("text", "canvas"),
            "id",
            "ID",
        )
        rejected(
            node("text") + ("text" to mapOf(1 to "not a string key")),
            Contract.fields("text", "native"),
            "text",
            "FIELD_TYPE",
        )
        val original = node("text").toMutableMap()
        check(original, Contract.fields("text", "canvas"))
        assertEquals(
            setOf("id", "type"),
            original.keys,
        ) // 描述默认值，不修改作者输入。
    }

    private fun node(type: String): Map<String, Any?> = mapOf("id" to "example", "type" to type)

    private fun page(mode: String): Map<String, Any?> =
        mapOf(
            "mode" to mode,
            "title" to "Title",
            (if (mode == "canvas") "elements" else "components") to listOf(node("text")),
        )

    private fun options(size: Int) = (0 until size).associate { "option-$it" to "Option $it" }

    private fun check(document: Map<String, Any?>, fields: Map<String, Field>, path: String = "$") =
        Contract.check(document, fields, "descriptor.yml", path)

    private fun rejected(
        document: Map<String, Any?>,
        fields: Map<String, Field>,
        key: String,
        code: String,
        path: String = "$",
    ) {
        val diagnostic = assertFailsWith<DocumentError> { check(document, fields, path) }.diagnostic
        assertEquals("descriptor.yml", diagnostic.file)
        assertEquals(com.hiusers.rafter.model.SourcePath.field(path, key), diagnostic.path)
        assertEquals(code, diagnostic.code)
        assertEquals(document["id"] as? String, diagnostic.component)
    }

    private fun objectValue(value: Any?): Map<*, *> = assertIs<Map<*, *>>(value)

    private fun property(schema: Any?, key: String) =
        objectValue(objectValue(objectValue(schema)["properties"])[key])

    private fun definition(schema: Any?, name: String) =
        objectValue(objectValue(objectValue(schema)["\$defs"])[name])

    private fun variants(schema: Any?) =
        assertIs<List<*>>(objectValue(schema)["oneOf"]).map(::objectValue)

    private fun variant(schema: Any?, discriminator: String, value: String) =
        variants(schema).single { property(it, discriminator)["const"] == value }

    private fun assertDescriptors(fields: Map<String, Field>, schema: Any?) {
        val objectSchema = objectValue(schema)
        assertEquals(false, objectSchema["additionalProperties"])
        assertEquals(fields.filterValues { it.required }.keys.toList(), objectSchema["required"])
        assertEquals(fields.keys, objectValue(objectSchema["properties"]).keys)
        fields.forEach { (key, field) -> assertEquals(field.schema(), property(schema, key), key) }
        assertTrue(objectSchema["type"] == "object")
    }
}
