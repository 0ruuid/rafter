package com.hiusers.rafter

import com.hiusers.rafter.asset.ModalAssets
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import kotlin.test.*

class NativeModalCompilerTest {
    private val assets = mapOf("rafter:panel" to (225 to 108))
    private val sources = mapOf("rafter:panel" to (220 to 107))
    private val compiler = MenuCompiler(assets, imageSources = sources)
    private val modal =
        mapOf<String, Any?>(
            "asset" to "rafter:panel",
            "background" to "#101820",
            "feedback" to "feedback",
        )
    private val input =
        mapOf<String, Any?>(
            "id" to "query",
            "type" to "input",
            "state" to "query",
            "width" to 220,
            "label-visible" to false,
        )
    private val cancel =
        mapOf<String, Any?>(
            "id" to "cancel",
            "type" to "button",
            "width" to 98,
            "text" to "Cancel",
            "role" to "exit",
            "actions" to listOf(mapOf("type" to "back")),
        )
    private val submit =
        mapOf<String, Any?>(
            "id" to "submit",
            "type" to "button",
            "width" to 98,
            "text" to "Search",
            "actions" to listOf(mapOf("type" to "submit"), mapOf("type" to "back")),
        )
    private val controls = listOf(input, cancel, submit)
    private val open =
        mapOf<String, Any?>(
            "id" to "open",
            "type" to "hit-area",
            "width" to 18,
            "height" to 9,
            "actions" to listOf(mapOf("type" to "open-page", "page" to "search")),
        )
    private val origin =
        mapOf<String, Any?>(
            "mode" to "canvas",
            "title" to "",
            "width" to 180,
            "height" to 90,
            "elements" to listOf(open),
        )

    private fun page(components: List<Map<String, Any?>> = controls, panel: Any? = modal) =
        mapOf<String, Any?>(
            "mode" to "canvas",
            "title" to "",
            "width" to 180,
            "columns" to 2,
            "elements" to
                listOf(
                    mapOf(
                        "id" to "feedback",
                        "type" to "text",
                        "text" to "",
                        "y" to 3,
                        "width" to 180,
                        "height" to 18,
                    )
                ),
            "components" to components,
            "modal" to panel,
        )

    private fun document(
        search: Map<String, Any?> = page(),
        main: Map<String, Any?> = origin,
        entry: String = "main",
    ) =
        mapOf(
            "id" to "test:modal",
            "entry" to entry,
            "state" to mapOf("query" to ""),
            "pages" to mapOf("main" to main, "search" to search),
        )

    private fun compile(
        search: Map<String, Any?> = page(),
        main: Map<String, Any?> = origin,
        entry: String = "main",
        using: MenuCompiler = compiler,
    ) = using.compile(mapOf("modal.yml" to document(search, main, entry)))

    private fun compiledSnapshot(result: CompileResult): MenuSnapshot =
        assertNotNull(result.snapshot, result.diagnostics.toString())

    private fun rejected(result: CompileResult): Diagnostic {
        assertNull(result.snapshot)
        return result.diagnostics.single().also {
            assertEquals("modal.yml", it.file)
            assertFalse(it.code == "COMPILE", it.toString())
        }
    }

    private fun obj(value: Any?) = assertIs<Map<*, *>>(value)

    private fun layout(preview: Map<*, *>) = obj(obj(preview["modal"])["layout"])

    private fun firstInput(preview: Map<*, *>) =
        obj(assertIs<List<*>>(layout(preview)["inputs"]).first())

    private fun variant(override: Map<String, Any?>) =
        listOf(
            mapOf(
                "when" to mapOf("type" to "constant", "value" to true),
                "override" to override,
            )
        )

    @Test
    fun counterSpacersRespectNativeLabelMarginsWithoutWideningColumns() {
        val multiline = input + mapOf("multiline" to true, "multiline-height" to 48)
        val using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to (220 to 200)))
        for (gap in 5..7) {
            val failure =
                rejected(
                    compile(
                        page(listOf(multiline, cancel, submit), modal + ("button-gap" to gap)),
                        using = using,
                    )
                )
            assertEquals("LAYOUT", failure.code)
            assertEquals("$.pages.search.modal.button-gap", failure.path)
        }
        val snapshot =
            compiledSnapshot(
                compile(
                    page(listOf(multiline, cancel, submit), modal + ("button-gap" to 8)),
                    using = using,
                )
            )
        val plan = snapshot.menus.getValue("test:modal").pages.getValue("search").modalLayout!!
        plan.buttons.take(plan.columns).forEach { cell ->
            assertEquals(2 * NativeGeometry.BUTTON_TEXT_MARGIN, cell.nativeWidth)
            assertTrue(cell.nativeWidth <= cell.bounds.width)
            val nativeLeft = cell.bounds.x + (cell.bounds.width - cell.nativeWidth) / 2
            assertEquals(
                cell.bounds.x + cell.bounds.width / 2,
                nativeLeft + cell.nativeWidth / 2 + cell.labelShift,
            )
        }
    }

    @Test
    fun authoringSeparatesFlowSlotsWidgetsLabelsCountersAndInternalCells() {
        val multiline =
            input +
                mapOf(
                    "multiline" to true,
                    "multiline-height" to 48,
                    "label-visible" to true,
                    "text" to "Message",
                )
        val using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to (220 to 200)))
        val snapshot =
            compiledSnapshot(compile(page(listOf(multiline, cancel, submit)), using = using))
        val plan = snapshot.menus.getValue("test:modal").pages.getValue("search").modalLayout!!
        val slot = plan.inputs.single()
        val label = assertNotNull(slot.labelBounds)
        val counter = assertNotNull(slot.counterRegion)
        assertEquals(9, label.height)
        assertEquals(label.y + label.height + NativeGeometry.LABEL_GAP, slot.controlBounds.y)
        assertEquals(48, slot.controlBounds.height)
        assertEquals(
            slot.controlBounds.y + slot.controlBounds.height + NativeGeometry.COUNTER_OFFSET,
            counter.y,
        )
        assertEquals(
            slot.bounds.width,
            counter.width,
            "Counter region reserves a line, not a fake measured count",
        )
        assertEquals(
            plan.counterClearance!!.y + NativeGeometry.COUNTER_CLEARANCE,
            counter.y + counter.height,
            "Removing the reserve row without another spacing solution would overlap the native counter",
        )
        assertEquals(2, plan.buttons.count { it.role == ModalCellRole.ACTION })
        assertEquals(3, plan.buttons.count { it.role == ModalCellRole.COUNTER_SPACER })
        assertEquals(1, plan.buttons.count { it.role == ModalCellRole.HORIZONTAL_SPACER })
        val exported =
            obj(obj(obj(AuthoringContract.layouts(snapshot)["menus"])["test:modal"])["search"])
        val native = firstInput(exported)
        assertEquals(48, obj(native["control-bounds"])["height"])
        assertEquals(9, obj(native["counter-region"])["height"])
        val cells = assertIs<List<*>>(layout(exported)["buttons"]).map(::obj)
        assertEquals(
            listOf("cancel", "submit"),
            cells.filter { it["author-editable"] == true }.map { it["target"] },
        )
        assertTrue(cells.filter { it["target"] == null }.all { it["author-editable"] == false })
        val policy = obj(Contract.manifest()["native-text-input"])
        assertEquals(false, policy["keystroke-events"])
        assertEquals("native-client-until-submit", policy["draft-owner"])
    }

    @Test
    fun nativeButtonColumnsAreNotLimitedToFour() {
        val buttons = (0..5).map { submit + mapOf("id" to "action$it", "width" to 30) }
        val using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to (400 to 240)))
        val snapshot =
            compiledSnapshot(
                compile(
                    page(
                        listOf(input) + buttons,
                        modal + ("button-gap" to 8),
                    ) + ("columns" to 6),
                    using = using,
                )
            )
        val plan = snapshot.menus.getValue("test:modal").pages.getValue("search").modalLayout!!
        assertEquals(6, plan.buttons.count { it.role == ModalCellRole.ACTION })
        assertEquals(11, plan.columns)
    }

    @Test
    fun defaultsSourceGeometryAndFrozenListsMatchAssetAndEditorConsumers() {
        val cleared =
            mutableListOf(mutableMapOf("x" to 0, "y" to 0, "width" to 220, "height" to 107))
        val snapshot = compiledSnapshot(compile(page(panel = modal + ("clear" to cleared))))
        val page = snapshot.menus.getValue("test:modal").spec.pages.getValue("search")
        assertEquals(
            ModalSpec(
                "rafter:panel",
                "#101820",
                feedback = "feedback",
                clear = listOf(Rect(0, 0, 220, 107)),
            ),
            page.modal,
        )
        assertFalse(assertIs<NodeContent.TextInput>(page.components.first().content).labelVisible)
        assertTrue(NodeContent.TextInput("query").labelVisible)
        assertNull(PageSpec("plain", PageMode.NATIVE, TextSpec(), 180, 1, emptyList()).modal)
        cleared.first()["width"] = 1
        cleared.clear()
        assertEquals(listOf(Rect(0, 0, 220, 107)), page.modal!!.clear)
        assertFailsWith<UnsupportedOperationException> {
            (page.modal!!.clear as MutableList<Rect>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (page.components as MutableList<NodeSpec>).clear()
        }
        val layout = ModalAssets.collect(snapshot, sources).layouts.single()
        assertEquals(220, layout.width)
        assertEquals(107, layout.height)
        assertEquals(220, layout.bodyWidth)
        assertEquals(86, layout.bodyHeight)
        val exported = AuthoringContract.layouts(snapshot)
        assertEquals(AuthoringContract.VERSION, exported["contract-version"])
        val preview = obj(obj(obj(exported["menus"])["test:modal"])["search"])
        assertEquals(86, layout(preview)["body-height"])
        assertEquals(36, firstInput(preview)["y"])
        assertEquals(
            listOf(98, 98),
            assertIs<List<*>>(layout(preview)["buttons"])
                .map(::obj)
                .filter { it["target"] != null }
                .map { it["width"] },
        )
        assertEquals(
            false,
            obj(obj(assertIs<List<*>>(preview["native-components"]).first())["input"])[
                "label-visible"],
        )
        val ordinary =
            compiledSnapshot(
                compiler.compile(
                    mapOf(
                        "modal.yml" to
                            minimal(
                                components = listOf((input - "label-visible") + ("state" to "name"))
                            )
                    )
                )
            )
        assertTrue(
            assertIs<NodeContent.TextInput>(
                    ordinary.menus
                        .getValue("test:menu")
                        .spec
                        .pages
                        .getValue("main")
                        .components
                        .single()
                        .content
                )
                .labelVisible
        )
    }

    @Test
    fun multilineInputsUseActualHeightInModalFlowAndEditorExports() {
        val configurations =
            listOf(
                emptyMap<String, Any?>(),
                mapOf("multiline-height" to 20, "max-lines" to 1),
                mapOf("multiline-height" to 72, "max-lines" to 6),
                mapOf("multiline-height" to 512, "max-lines" to 64),
            )
        for (options in configurations) {
            val height = options["multiline-height"] as? Int ?: 48
            val lines = options["max-lines"] as? Int ?: 3
            val flowHeight = 36 + height + 10 + 42
            val search = page(listOf(input + ("multiline" to true) + options, cancel, submit))
            val using =
                MenuCompiler(
                    assets,
                    imageSources = mapOf("rafter:panel" to (220 to (flowHeight - 1))),
                )
            val snapshot = compiledSnapshot(compile(search, using = using))
            val content =
                assertIs<NodeContent.TextInput>(
                    snapshot.menus
                        .getValue("test:modal")
                        .spec
                        .pages
                        .getValue("search")
                        .components
                        .first()
                        .content
                )
            assertTrue(content.multiline)
            assertFalse(content.labelVisible)
            assertEquals(height, content.multilineHeight)
            assertEquals(lines, content.maxLines)
            assertEquals(height, content.inputHeight)
            val preview =
                obj(obj(obj(AuthoringContract.layouts(snapshot)["menus"])["test:modal"])["search"])
            assertEquals(flowHeight, layout(preview)["body-height"])
            assertEquals(height, firstInput(preview)["height"])
            val exported =
                obj(obj(assertIs<List<*>>(preview["native-components"]).first())["input"])
            assertEquals(true, exported["multiline"])
            assertEquals(height, exported["multiline-height"])
            assertEquals(lines, exported["max-lines"])
            assertEquals(height, exported["input-height"])
            val tooShort =
                MenuCompiler(
                    assets,
                    imageSources = mapOf("rafter:panel" to (220 to (flowHeight - 2))),
                )
            assertEquals(
                "$.pages.search.modal.top",
                rejected(compile(search, using = tooShort)).path,
            )
        }
    }

    @Test
    fun multilineOptionsRequireExplicitMultilineAndStrictIntegerBounds() {
        for ((key, invalid) in
            mapOf(
                "multiline-height" to listOf<Any?>(19, 513, 48.5, "48", true, null),
                "max-lines" to listOf<Any?>(0, 65, 3.5, "3", false, null),
            )) {
            for (value in invalid) {
                val failure =
                    rejected(
                        compile(
                            page(
                                listOf(
                                    input + mapOf("multiline" to true, key to value),
                                    cancel,
                                    submit,
                                )
                            )
                        )
                    )
                assertTrue(failure.path.endsWith(".$key"), failure.toString())
                assertEquals(if (value is Int) "RANGE" else "FIELD_TYPE", failure.code)
            }
            for (singleLine in listOf(input, input + ("multiline" to false))) {
                val value = Contract.fields("input", "native").getValue(key).default
                val failure =
                    rejected(compile(page(listOf(singleLine + (key to value), cancel, submit))))
                assertTrue(failure.path.endsWith(".$key"), failure.toString())
                assertEquals("CAPABILITY", failure.code)
            }
        }
    }

    @Test
    fun singleLineDefaultsAndServerAcceptanceDoNotEstimateVisualLines() {
        val ordinary =
            compiledSnapshot(
                compiler.compile(
                    mapOf(
                        "modal.yml" to
                            minimal(components = listOf(input - "label-visible")) +
                                ("state" to mapOf("query" to ""))
                    )
                )
            )
        val content =
            assertIs<NodeContent.TextInput>(
                ordinary.menus
                    .getValue("test:menu")
                    .spec
                    .pages
                    .getValue("main")
                    .components
                    .single()
                    .content
            )
        assertEquals(NodeContent.TextInput("query"), content)
        assertEquals(NativeGeometry.BUTTON_HEIGHT, content.inputHeight)
        assertFalse(content.accepts("a\nb"))
        assertFalse(content.accepts("a\rb"))
        val multiline = content.copy(multiline = true, multilineHeight = 20, maxLines = 1)
        assertEquals(20, multiline.inputHeight)
        assertTrue(multiline.accepts("a\n".repeat(20)))
        assertTrue(multiline.accepts("x".repeat(64)))
        assertFalse(multiline.accepts("x".repeat(65)))
    }

    @Test
    fun optionalNativeDescriptionAddsSeventeenPixelsAndTenPixelGapBeforeInput() {
        val description =
            mapOf("id" to "description", "type" to "text", "width" to 220, "text" to "Description")
        for (inputHeight in listOf(20, 96)) {
            val field =
                if (inputHeight == 20) input
                else input + mapOf("multiline" to true, "multiline-height" to inputHeight)
            val search =
                page(
                    listOf(description, field, cancel, submit),
                    modal + mapOf("top" to 94, "content-top" to 41, "feedback" to "description"),
                )
            val inputTop = 117 + 8 + 10 + 17 + 10
            val flowHeight = inputTop + inputHeight + 10 + if (inputHeight == 20) 20 else 42
            val using =
                MenuCompiler(
                    assets,
                    imageSources = mapOf("rafter:panel" to (220 to (flowHeight - 94))),
                )
            val snapshot = compiledSnapshot(compile(search, using = using))
            val preview =
                obj(obj(obj(AuthoringContract.layouts(snapshot)["menus"])["test:modal"])["search"])
            val exported = layout(preview)
            assertEquals(162, firstInput(preview)["y"])
            assertEquals(flowHeight, exported["body-height"])
            val descriptionLayout = obj(assertIs<List<*>>(exported["descriptions"]).first())
            assertEquals("description", descriptionLayout["id"])
            assertEquals(135, descriptionLayout["y"])
            assertEquals(220, descriptionLayout["width"])
            assertEquals(17, descriptionLayout["height"])
            val tooShort =
                MenuCompiler(
                    assets,
                    imageSources = mapOf("rafter:panel" to (220 to (flowHeight - 95))),
                )
            assertEquals(
                "$.pages.search.modal.top",
                rejected(compile(search, using = tooShort)).path,
            )
        }
    }

    @Test
    fun modalDescriptionRejectsExtraBodiesReorderingConditionalGeometryAndWrapping() {
        val description =
            mapOf<String, Any?>(
                "id" to "description",
                "type" to "text",
                "width" to 220,
                "text" to "Description",
            )
        val using =
            MenuCompiler(
                assets,
                imageSources = mapOf("rafter:panel" to (220 to 600)),
                canvasMeasure = { it.value.length * 8 to 9 },
            )
        val condition = mapOf("type" to "constant", "value" to true)
        for (invalid in
            listOf(
                description + ("visible" to condition),
                description + ("text" to "first\nsecond"),
                description + ("text" to "first\rsecond"),
                description + ("text" to "x".repeat(28)),
                description + ("text" to "{{ description }}"),
                description +
                    ("text" to
                        mapOf(
                            "format" to "literal",
                            "binding" to mapOf("type" to "data", "key" to "description"),
                        )),
            )) rejected(compile(page(listOf(invalid) + controls), using = using))
        rejected(compile(page(listOf(input, description, cancel, submit)), using = using))
        for (body in
            listOf(
                mapOf("id" to "image", "type" to "image", "asset" to "rafter:panel"),
                mapOf("id" to "item", "type" to "item", "item" to "description"),
            )) rejected(compile(page(listOf(body) + controls), using = using))
    }

    @Test
    fun descriptionStateBindingsAreResolvedForSingleLineAndPixelWidthChecks() {
        val description =
            mapOf<String, Any?>(
                "id" to "description",
                "type" to "text",
                "width" to 220,
                "binding" to mapOf("type" to "state", "key" to "description"),
            )
        val measured = mutableListOf<String>()
        val using =
            MenuCompiler(
                assets,
                imageSources = mapOf("rafter:panel" to (220 to 600)),
                canvasMeasure = {
                    measured += it.value
                    it.value.length * 8 to 9
                },
            )
        fun withState(value: Any?) =
            using.compile(
                mapOf(
                    "modal.yml" to
                        (document(page(listOf(description) + controls)) +
                            ("state" to mapOf("query" to "", "description" to value)))
                )
            )
        compiledSnapshot(withState("Short description"))
        assertTrue("Short description" in measured)
        assertEquals("LAYOUT", rejected(withState("x".repeat(28))).code)
        assertEquals("CANVAS_TEXT", rejected(withState("first\nsecond")).code)
        assertEquals("STATE", rejected(withState(42)).code)
        assertEquals(
            "STATE",
            rejected(compile(page(listOf(description) + controls), using = using)).code,
        )
    }

    @Test
    fun textOffsetsDecodeNestedPartsAndExportWithoutChangingNativeFlow() {
        val description =
            mapOf(
                "id" to "description",
                "type" to "text",
                "width" to 220,
                "text" to mapOf("format" to "literal", "value" to "Description", "offset-y" to 3),
            )
        val cancelText =
            mapOf(
                "format" to "parts",
                "offset-y" to -1,
                "parts" to
                    listOf(mapOf("format" to "literal", "value" to "Cancel", "offset-y" to 2)),
            )
        val search =
            page(
                listOf(description, input, cancel + ("text" to cancelText), submit),
                modal + ("feedback" to "description"),
            )
        val using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to (220 to 134)))
        val snapshot = compiledSnapshot(compile(search, using = using))
        val modalPage = snapshot.menus.getValue("test:modal").spec.pages.getValue("search")
        assertEquals(3, modalPage.components[0].text.offsetY)
        assertEquals("description", modalPage.modal!!.feedback)
        assertEquals(-1, modalPage.components[2].text.offsetY)
        assertEquals(2, modalPage.components[2].text.parts.single().offsetY)
        assertEquals(0, modalPage.components[3].text.offsetY)
        val preview =
            obj(obj(obj(AuthoringContract.layouts(snapshot)["menus"])["test:modal"])["search"])
        assertEquals(63, firstInput(preview)["y"])
        assertEquals(113, layout(preview)["body-height"])
        assertEquals(
            3,
            obj(obj(assertIs<List<*>>(preview["native-components"]).first())["text"])["offset-y"],
        )
        assertEquals("description", obj(preview["modal"])["feedback"])
        val controls = assertIs<List<*>>(preview["native-components"])
        val label = obj(obj(controls[2])["text"])
        assertEquals(-1, label["offset-y"])
        assertEquals(2, obj(assertIs<List<*>>(label["parts"]).single())["offset-y"])
        for (offset in listOf<Any?>(-9, 9, 0.5, "3", true, null)) {
            val invalid =
                description +
                    ("text" to
                        mapOf(
                            "format" to "literal",
                            "value" to "Description",
                            "offset-y" to offset,
                        ))
            val failure = rejected(compile(page(listOf(invalid) + this.controls), using = using))
            assertTrue(failure.path.endsWith(".text.offset-y"))
            assertEquals(if (offset is Int) "RANGE" else "FIELD_TYPE", failure.code)
        }
        for (format in listOf("literal", "colored", "simple", "parts")) {
            val value =
                if (format == "parts") mapOf("parts" to listOf("Text"))
                else mapOf("value" to "Text")
            val component =
                mapOf(
                    "id" to "text",
                    "type" to "text",
                    "text" to (value + mapOf("format" to format, "offset-y" to 8)),
                )
            val ordinary =
                compiledSnapshot(
                    compiler.compile(mapOf("modal.yml" to minimal(components = listOf(component))))
                )
            assertEquals(
                8,
                ordinary.menus
                    .getValue("test:menu")
                    .spec
                    .pages
                    .getValue("main")
                    .components
                    .single()
                    .text
                    .offsetY,
            )
        }
    }

    @Test
    fun descriptorsSchemaAndManifestExposeOnlyTheStrictAuthoredFields() {
        val definitions = obj(Contract.schema()["\$defs"])
        val manifest = AuthoringContract.manifest("test", emptyMap())
        for ((name, fields) in listOf("modal" to Contract.modal, "rect" to Contract.rect)) {
            val schema = obj(definitions[name])
            assertEquals(false, schema["additionalProperties"])
            assertEquals(fields.filterValues { it.required }.keys.toList(), schema["required"])
            for ((key, field) in fields) {
                assertEquals(field.schema(), obj(schema["properties"])[key])
                assertEquals(field.type, obj(obj(manifest["$name-fields"])[key])["type"])
            }
        }
        assertEquals("modal", Contract.pageFields("canvas").getValue("modal").type)
        assertFalse("modal" in Contract.pageFields("native"))
        val label = Contract.fields("input", "native").getValue("label-visible")
        assertEquals("boolean", label.type)
        assertEquals(true, label.default)
        assertFalse("label-visible" in Contract.variantFields("input", "native"))
        val inputFields = Contract.fields("input", "native")
        for (name in listOf("node", "nativeNode")) {
            val schema =
                assertIs<List<*>>(obj(definitions[name])["oneOf"]).map(::obj).single {
                    obj(obj(it["properties"])["type"])["const"] == "input"
                }
            for (key in listOf("multiline-height", "max-lines")) {
                val field = inputFields.getValue(key)
                assertEquals(field.schema(), obj(schema["properties"])[key])
                val described = obj(obj(obj(manifest["components"])["input"])[key])
                for ((keyword, value) in field.keywords()) assertEquals(value, described[keyword])
                val dependency = obj(obj(schema["dependentSchemas"])[key])
                assertEquals(listOf("multiline"), dependency["required"])
                assertEquals(true, obj(obj(dependency["properties"])["multiline"])["const"])
                assertFalse(key in Contract.variantFields("input", "native"))
            }
        }
        val canvasPage =
            assertIs<List<*>>(obj(definitions["page"])["oneOf"]).map(::obj).single {
                obj(obj(it["properties"])["mode"])["const"] == "canvas"
            }
        val modalRule =
            assertIs<List<*>>(canvasPage["allOf"]).map(::obj).single {
                obj(it["if"])["required"] == listOf("modal")
            }
        val componentBounds = obj(obj(obj(modalRule["then"])["properties"])["components"])
        assertEquals(1, componentBounds["minItems"])
        assertNull(componentBounds["maxItems"])
        val description = obj(obj(manifest["native-modal"])["description"])
        assertEquals(17, description["body-height"])
        assertEquals(10, description["gap-after"])
        assertEquals("before-inputs", description["placement"])
        assertEquals(
            "independent-of-input-centered-native-grid",
            obj(manifest["native-modal"])["button-widths"],
        )
        val offset = Contract.text.getValue("offset-y")
        assertEquals(-8, offset.minimum)
        assertEquals(8, offset.maximum)
        assertEquals(0, offset.default)
        for ((format, fields) in Contract.textFormats) {
            assertEquals(offset, fields["offset-y"])
            val described = obj(obj(obj(manifest["text-fields"])[format])["offset-y"])
            for ((keyword, value) in offset.keywords()) assertEquals(value, described[keyword])
        }
        assertEquals(
            listOf("multiline-height", "max-lines"),
            obj(manifest["native-text-input"])["multiline-only-fields"],
        )
        assertEquals("input", assertIs<List<*>>(obj(manifest["native-modal"])["controls"]).first())
        for (fields in
            listOf(
                modal + ("dim" to 0),
                modal + ("dim" to 1),
                modal + ("top" to -64),
                modal + ("top" to 128),
                modal + ("button-gap" to 8),
                modal + ("button-gap" to 128),
            )) {
            Contract.check(fields, Contract.modal, "modal.yml", "$.pages.search.modal")
        }
        assertEquals(16, AuthoringContract.VERSION)
    }

    @Test
    fun modalFieldsAndClearRectanglesRejectUnknownTypesBoundsAndGeneratedSources() {
        for (panel in
            listOf<Any?>(
                null,
                "rafter:panel",
                modal - "asset",
                modal - "background",
                modal + ("asset" to "rafter:missing"),
                modal + ("asset" to "other:panel"),
                modal + ("asset" to "rafter:_sized/panel"),
                modal + ("background" to "#123"),
                modal + ("dim" to -0.01),
                modal + ("dim" to 1.01),
                modal + ("dim" to "0.5"),
                modal + ("top" to -4096),
                modal + ("top" to 4096),
                modal + ("top" to 1.5),
                modal + ("button-gap" to 4),
                modal + ("button-gap" to 129),
                modal + ("button-gap" to true),
                modal + ("feedback" to false),
                modal + ("clear" to emptyMap<String, Any?>()),
                modal + ("typo" to true),
            )) {
            assertTrue(
                rejected(compile(page(panel = panel))).path.contains("modal"),
                panel.toString(),
            )
        }
        val area = mapOf("x" to 0, "y" to 0, "width" to 1, "height" to 1)
        for (rect in
            listOf<Any?>(
                null,
                area - "x",
                area + ("typo" to 1),
                area + ("x" to -1),
                area + ("y" to -1),
                area + ("width" to 0),
                area + ("height" to 0),
                area + ("x" to 0.5),
                area + ("x" to 220),
                area + ("y" to 107),
                area + ("width" to 221),
                area + ("height" to 108),
                area + ("x" to Int.MAX_VALUE),
                area + ("width" to Int.MAX_VALUE),
            )) {
            assertTrue(
                rejected(compile(page(panel = modal + ("clear" to listOf(rect)))))
                    .path
                    .contains("clear[0]"),
                rect.toString(),
            )
        }
        val generated =
            MenuCompiler(
                assets + ("rafter:_sized/panel" to (225 to 108)),
                imageSources = sources + ("rafter:_sized/panel" to (220 to 107)),
            )
        assertEquals(
            "ASSET",
            rejected(
                    compile(
                        page(panel = modal + ("asset" to "rafter:_sized/panel")),
                        using = generated,
                    )
                )
                .code,
        )
        assertEquals(
            "ASSET",
            rejected(compile(using = MenuCompiler(assets, imageSources = emptyMap()))).code,
        )
    }

    @Test
    fun buttonSkinLabelOffsetsDecodeStrictlyWithoutMovingTheBitmapRequest() {
        val baseSkin = mapOf<String, Any?>("asset" to "rafter:panel")
        fun compileSkin(skin: Map<String, Any?>) =
            compiler.compile(
                mapOf("modal.yml" to minimal(components = listOf(cancel + ("skin" to skin))))
            )
        for (offset in listOf<Int?>(null, -432, 0, 432)) {
            val skin = if (offset == null) baseSkin else baseSkin + ("label-offset-x" to offset)
            val page =
                compiledSnapshot(compileSkin(skin))
                    .menus
                    .getValue("test:menu")
                    .spec
                    .pages
                    .getValue("main")
            val decoded =
                assertNotNull(assertIs<NodeContent.Button>(page.components.single().content).skin)
            assertEquals(offset ?: 0, decoded.labelOffsetX)
            assertEquals(ButtonSkinSpec("rafter:panel").request(98), decoded.request(98))
        }
        for (offset in listOf<Any?>("12", true, null, 1.5, -433, 433)) {
            val failure = rejected(compileSkin(baseSkin + ("label-offset-x" to offset)))
            assertTrue(failure.path.endsWith(".skin.label-offset-x"))
            assertEquals(if (offset is Int) "LAYOUT" else "FIELD_TYPE", failure.code)
        }
    }

    @Test
    fun modalNativeFlowIsComposableButNeverInventsConditionalWidgetGeometry() {
        val using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to (400 to 800)))
        for (columns in 1..6) compiledSnapshot(
            compile(page() + ("columns" to columns), using = using)
        )
        for (search in
            listOf(
                page() + ("height" to 18),
                page() + ("mode" to "native"),
                page() + ("frame" to mapOf("asset" to "rafter:panel")),
                page() + ("popups" to mapOf("popup" to mapOf("elements" to listOf(open)))),
                page() + ("native-body-position" to "before-canvas"),
                page() + ("title" to "Search"),
                page(listOf(input)),
            )) rejected(compile(search, using = using))
        val condition = mapOf("type" to "constant", "value" to true)
        for (index in controls.indices) {
            rejected(
                compile(
                    page(
                        controls.mapIndexed { i, node ->
                            if (i == index) node + ("visible" to condition) else node
                        }
                    ),
                    using = using,
                )
            )
            rejected(
                compile(
                    page(
                        controls.mapIndexed { i, node ->
                            if (i == index) node + ("width" to 0) else node
                        }
                    ),
                    using = using,
                )
            )
        }
        rejected(
            compile(page(listOf(input + ("enabled" to condition), cancel, submit)), using = using)
        )
        compiledSnapshot(
            compile(page(listOf(input + ("label-visible" to true), cancel, submit)), using = using)
        )
        compiledSnapshot(compile(page(listOf(cancel)), using = using))
        compiledSnapshot(
            compile(
                page(listOf(input - "width", cancel - "width", submit - "width")),
                using = using,
            )
        )
        compiledSnapshot(compile(page(listOf(submit, cancel)), using = using))
        val flexible =
            listOf(
                input,
                cancel + ("actions" to listOf(mapOf("type" to "close"))),
                submit + ("variants" to variant(mapOf("enabled" to condition))),
            )
        compiledSnapshot(compile(page(flexible), using = using))
    }

    @Test
    fun nativeModalInputAndButtonRowsHaveIndependentWidthsWithinTheSourcePanel() {
        for (inputWidth in listOf(244, 252)) {
            val description =
                mapOf(
                    "id" to "description",
                    "type" to "text",
                    "width" to inputWidth,
                    "text" to "Description",
                )
            val search =
                page(
                    listOf(
                        description,
                        input + ("width" to inputWidth),
                        cancel + ("width" to 112),
                        submit + ("width" to 112),
                    )
                )
            val requiredWidth = maxOf(inputWidth, 112 + 112 + 24)
            for (sourceWidth in listOf(requiredWidth, 280)) {
                val using =
                    MenuCompiler(
                        assets,
                        imageSources = mapOf("rafter:panel" to (sourceWidth to 200)),
                    )
                val snapshot = compiledSnapshot(compile(search, using = using))
                val preview =
                    obj(
                        obj(obj(AuthoringContract.layouts(snapshot)["menus"])["test:modal"])[
                            "search"]
                    )
                assertEquals(requiredWidth, layout(preview)["body-width"])
                assertEquals(248, layout(preview)["button-row-width"])
                assertEquals(inputWidth, firstInput(preview)["width"])
            }
            val tooNarrow =
                MenuCompiler(
                    assets,
                    imageSources = mapOf("rafter:panel" to ((requiredWidth - 1) to 200)),
                )
            assertEquals(
                "$.pages.search.modal.asset",
                rejected(compile(search, using = tooNarrow)).path,
            )
        }
    }

    @Test
    fun panelCoversTheActualNativeBodyAndStartsNoLowerThanTheInput() {
        compiledSnapshot(compile(page(panel = modal + ("button-gap" to 8))))
        assertEquals(
            "$.pages.search.modal.asset",
            rejected(compile(page(panel = modal + ("button-gap" to 25)))).path,
        )
        rejected(compile(page() + ("width" to 220))) // 正文含内边距，实际宽度为 230px。
        for (source in listOf(219 to 107, 220 to 84)) {
            rejected(
                compile(
                    using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to source))
                )
            )
        }
        val moved =
            compiledSnapshot(compile(page(panel = modal + ("top" to 10))))
                .menus
                .getValue("test:modal")
                .pages
                .getValue("search")
        assertEquals(27, moved.height)
        assertEquals(10, moved.spec.modal!!.offsetY)
        assertEquals(9, moved.spec.elements.single { it.id == "feedback" }.y)
        assertEquals(45, moved.modalLayout!!.inputs.single().bounds.y)
        assertEquals("CAPABILITY", rejected(compile(page() + ("height" to 18))).code)
        assertEquals(
            "UNKNOWN_FIELD",
            rejected(compile(page(panel = modal + ("offset-y" to 1)))).code,
        )
    }

    @Test
    fun feedbackIsAnExplicitFixedTextReferenceAndNeverAnImplicitButtonRow() {
        assertEquals("REQUIRED_FIELD", rejected(compile(page(panel = modal - "feedback"))).code)
        assertEquals(
            "REFERENCE",
            rejected(compile(page(panel = modal + ("feedback" to "missing")))).code,
        )
        assertEquals(
            "CAPABILITY",
            rejected(compile(page(panel = modal + ("feedback" to "query")))).code,
        )
        assertEquals(
            "UNKNOWN_FIELD",
            rejected(compile(page(panel = modal + ("hint" to "old")))).code,
        )
        for (override in
            listOf(
                mapOf("visible" to mapOf("type" to "constant", "value" to false)),
                mapOf("actions" to listOf(mapOf("type" to "close"))),
            )) {
            val node =
                mapOf(
                    "id" to "feedback",
                    "type" to "text",
                    "text" to "",
                    "y" to 3,
                    "width" to 180,
                    "height" to 18,
                ) + override
            assertNull(compile(page() + ("elements" to listOf(node))).snapshot)
        }
        val labelFeedback =
            compiledSnapshot(
                    compile(
                        page(
                            listOf(input + ("label-visible" to true), cancel, submit),
                            modal + ("feedback" to "query"),
                        )
                    )
                )
                .menus
                .getValue("test:modal")
                .pages
                .getValue("search")
                .modalLayout!!
        assertEquals("query", labelFeedback.feedback!!.id)
        assertEquals(13, labelFeedback.feedback!!.bounds.height)
        val noSize = mapOf("id" to "feedback", "type" to "text", "text" to "")
        assertEquals("LAYOUT", rejected(compile(page() + ("elements" to listOf(noSize)))).code)
        val single =
            compiledSnapshot(compile())
                .menus
                .getValue("test:modal")
                .pages
                .getValue("search")
                .modalLayout!!
        assertNull(single.counterClearance)
        assertEquals(3, single.buttons.size)
        assertNotNull(single.feedback)
        val multiline = input + mapOf("multiline" to true, "multiline-height" to 20)
        val using = MenuCompiler(assets, imageSources = mapOf("rafter:panel" to (220 to 200)))
        assertEquals(
            "CAPABILITY",
            rejected(
                    compile(
                        page(
                            listOf(
                                multiline,
                                input + mapOf("id" to "other"),
                                cancel,
                                submit,
                            )
                        ),
                        using = using,
                    )
                )
                .code,
        )
        val confirmation =
            compiledSnapshot(compile(page(listOf(cancel, submit), modal - "feedback")))
                .menus
                .getValue("test:modal")
                .pages
                .getValue("search")
                .modalLayout!!
        assertNull(confirmation.counterClearance)
        assertEquals(3, confirmation.buttons.size)
    }

    @Test
    fun entryAndOpenersRequireASnapshotCapableCanvasWithoutAFrameOrAnotherModal() {
        assertEquals("$.entry", rejected(compile(entry = "search")).path)
        rejected(
            compile(
                main = origin + ("components" to listOf(mapOf("id" to "native", "type" to "text")))
            )
        )
        rejected(compile(main = origin + ("frame" to mapOf("asset" to "rafter:panel"))))
        rejected(
            compile(
                main =
                    mapOf(
                        "mode" to "native",
                        "title" to "",
                        "components" to
                            listOf(cancel + mapOf("id" to "open", "actions" to open["actions"])),
                    )
            )
        )
        val replace =
            submit +
                ("actions" to
                    listOf(
                        mapOf("type" to "submit"),
                        mapOf("type" to "open-page", "page" to "search"),
                    ))
        compiledSnapshot(compile(page(listOf(input, cancel, replace))))
        val popupOrigin =
            origin +
                mapOf(
                    "elements" to emptyList<Any?>(),
                    "popups" to mapOf("search-menu" to mapOf("elements" to listOf(open))),
                )
        compiledSnapshot(compile(main = popupOrigin))
        val checked = mutableListOf<String>()
        val strict =
            MenuCompiler(
                assets,
                imageSources = sources,
                canvasTextCheck = {
                    checked += it.value
                    require(it.font != "minecraft:default") { "Fallback font is not measurable" }
                },
            )
        compiledSnapshot(compile(main = origin + ("title" to "Canvas title"), using = strict))
        assertTrue("Canvas title" in checked)
        val titleFailure =
            rejected(
                compile(
                    main =
                        origin +
                            ("title" to
                                mapOf(
                                    "format" to "literal",
                                    "value" to "Canvas title",
                                    "font" to "minecraft:default",
                                )),
                    using = strict,
                )
            )
        assertEquals("CANVAS_TEXT", titleFailure.code)
        assertEquals("$.pages.main.title", titleFailure.path)
    }
}
