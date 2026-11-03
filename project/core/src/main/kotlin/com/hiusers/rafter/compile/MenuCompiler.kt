package com.hiusers.rafter.compile

import com.hiusers.rafter.asset.ImageRequest
import com.hiusers.rafter.asset.ImageSizing
import com.hiusers.rafter.model.ActionSpec
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.ButtonRole
import com.hiusers.rafter.model.ButtonSkinSpec
import com.hiusers.rafter.model.CompileResult
import com.hiusers.rafter.model.CompiledMenu
import com.hiusers.rafter.model.CompiledPage
import com.hiusers.rafter.model.ConditionSpec
import com.hiusers.rafter.model.DataSourceSpec
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DialogFrameSpec
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.ElementInteraction
import com.hiusers.rafter.model.FormContent
import com.hiusers.rafter.model.ListFilter
import com.hiusers.rafter.model.ListPlacement
import com.hiusers.rafter.model.ListSpec
import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.MenuSpec
import com.hiusers.rafter.model.ModalLayoutPlan
import com.hiusers.rafter.model.ModalSpec
import com.hiusers.rafter.model.NativeBodyPosition
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.NodeVariant
import com.hiusers.rafter.model.PageMode
import com.hiusers.rafter.model.PageSpec
import com.hiusers.rafter.model.PopupSpec
import com.hiusers.rafter.model.Rect
import com.hiusers.rafter.model.RowScope
import com.hiusers.rafter.model.SourceLocation
import com.hiusers.rafter.model.SourcePath
import com.hiusers.rafter.model.StateContent
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.Values
import com.hiusers.rafter.model.accepts

/** 只编译文档，不持有运行资源或发布快照。 默认钩子仅供结构发现；完整项目须由 MenuRepository 注入真实度量和平台检查。 */
class MenuCompiler(
    private val assets: Map<String, Pair<Int, Int>> = emptyMap(),
    private val scriptCheck: (String) -> Unit = {},
    private val textCheck: (TextSpec) -> Unit = {},
    private val extensionCheck: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
    private val canvasTextCheck: (TextSpec) -> Unit = {},
    private val styles: Map<String, Map<String, Any?>> = emptyMap(),
    private val imageSources: Map<String, Pair<Int, Int>> = assets,
    private val canvasMeasure: ((TextSpec) -> Pair<Int, Int>)? = null,
    private val buttonTextCheck: (TextSpec, Int) -> Unit = { _, _ -> },
) {
    fun compile(
        documents: Map<String, Map<String, Any?>>,
        templates: Map<String, Map<String, Any?>> = emptyMap(),
        dataDocuments: Map<String, Map<String, Any?>> = emptyMap(),
        skins: Map<String, Map<String, Any?>> = emptyMap(),
    ): CompileResult {
        val menus = linkedMapOf<String, CompiledMenu>()
        val diagnostics = mutableListOf<Diagnostic>()
        val datasets = linkedMapOf<String, List<Map<String, Any?>>>()
        dataDocuments.forEach { (file, doc) ->
            try {
                Contract.check(doc, Contract.dataDocument, file, "$")
                @Suppress("UNCHECKED_CAST")
                val items =
                    (doc.getValue("items") as List<*>).mapIndexed { index, item ->
                        if (item !is Map<*, *> || item.keys.any { it !is String })
                            throw DocumentError(
                                Diagnostic(
                                    file,
                                    "$.items[$index]",
                                    "FIELD_TYPE",
                                    "A data record must be an object",
                                )
                            )
                        Values.map(item as Map<String, Any?>)
                    }
                val id = doc.getValue("id") as String
                if (datasets.putIfAbsent(id, Values.list(items)) != null)
                    throw DocumentError(
                        Diagnostic(file, "$.id", "DUPLICATE_ID", "Duplicate data document $id")
                    )
            } catch (failure: DocumentError) {
                diagnostics += failure.diagnostic
            } catch (failure: Exception) {
                diagnostics +=
                    Diagnostic(file, "$", "DATA", failure.message ?: "Invalid data document")
            }
        }
        val skinCompiler =
            try {
                SkinCompiler(skins)
            } catch (failure: DocumentError) {
                diagnostics += failure.diagnostic
                return CompileResult(null, Values.list(diagnostics))
            }
        val templateIds = linkedMapOf<String, Map<String, Any?>>()
        val templateFiles = linkedMapOf<String, String>()
        templates.forEach { (file, doc) ->
            try {
                Contract.check(doc, Contract.template, file, "$")
                val id = doc["id"] as String
                require(templateIds.putIfAbsent(id, doc) == null) { "Duplicate template $id" }
                templateFiles[id] = file
            } catch (e: DocumentError) {
                diagnostics += e.diagnostic
            } catch (e: Exception) {
                diagnostics += Diagnostic(file, "$", "TEMPLATE", e.message ?: "Invalid template")
            }
        }
        documents.forEach { (file, original) ->
            val decoder =
                Decoder(
                    file,
                    templateIds,
                    templateFiles,
                    java.util.Collections.unmodifiableMap(datasets),
                    skinCompiler,
                )
            try {
                val spec = decoder.menu(original)
                if (menus.containsKey(spec.id))
                    decoder.fail("$.id", "DUPLICATE_ID", "Duplicate menu ${spec.id}")
                val plans =
                    spec.pages.mapValues { (_, page) ->
                        val ids = mutableSetOf<String>()
                        val editableStates = mutableSetOf<String>()
                        var exitButton: String? = null
                        fun inspect(node: NodeSpec, branch: Boolean = false) {
                            if (!branch && !ids.add(node.id))
                                decoder.failNode(
                                    node,
                                    "DUPLICATE_ID",
                                    "Duplicate component ${node.id}",
                                )
                            val content = node.content
                            if (
                                !branch &&
                                    content is NodeContent.Button &&
                                    content.role == ButtonRole.EXIT
                            ) {
                                if (exitButton != null)
                                    decoder.failNode(
                                        node,
                                        "DUPLICATE_EXIT",
                                        "A page can only have one exit button; already declared by $exitButton",
                                    )
                                exitButton = node.id
                            }
                            node.state?.let { key ->
                                if (!branch && content is FormContent && !editableStates.add(key))
                                    decoder.failNode(node, "STATE", "Duplicate form binding $key")
                                if (!spec.state.containsKey(key))
                                    decoder.failNode(node, "STATE", "Undeclared state $key")
                                val value = spec.state[key]
                                val valid = (content as? StateContent)?.accepts(value) ?: true
                                if (!valid)
                                    decoder.failNode(
                                        node,
                                        "STATE",
                                        "Invalid initial value for $key",
                                    )
                            }
                            node.actions.forEachIndexed { index, action ->
                                if (
                                    action.type == "select-item" &&
                                        (node.rowScope == null ||
                                            spec.lists
                                                .single { it.id == node.rowScope.list }
                                                .selectionState == null)
                                )
                                    decoder.failNode(
                                        node,
                                        "CAPABILITY",
                                        "select-item requires a selectable list row",
                                    )
                                if (
                                    action.type == "page-list" &&
                                        spec.lists.none { it.id == action.key }
                                )
                                    decoder.failNode(
                                        node,
                                        "REFERENCE",
                                        "Unknown list ${action.key}",
                                    )
                                if (action.type == "open-page" && action.key !in spec.pages)
                                    decoder.failNode(
                                        node,
                                        "REFERENCE",
                                        "Unknown page ${action.key}",
                                    )
                                if (
                                    action.type == "open-page" &&
                                        spec.pages[action.key]?.modal != null
                                )
                                    decoder.validateModalOpener(page, node)
                                if (action.type == "open-popup" && action.key !in page.popups)
                                    decoder.failNode(
                                        node,
                                        "REFERENCE",
                                        "Unknown popup ${action.key}",
                                    )
                                if (
                                    action.type == "close-popup" &&
                                        node.id !in
                                            page.popups.values.flatMap { it.elements }.map { it.id }
                                )
                                    decoder.failNode(
                                        node,
                                        "ACTION",
                                        "close-popup is only available inside a popup",
                                    )
                                if (
                                    action.type in setOf("set-state", "toggle-state") &&
                                        action.key !in spec.state
                                )
                                    decoder.failNode(
                                        node,
                                        "STATE",
                                        "Undeclared state ${action.key}",
                                    )
                                if (
                                    action.type == "submit" &&
                                        (index != 0 || node.id !in page.components.map { it.id })
                                )
                                    decoder.failNode(
                                        node,
                                        "ACTION",
                                        "submit must be the first action on a native component",
                                    )
                                if (
                                    action.type in
                                        setOf(
                                            "close",
                                            "back",
                                            "open-page",
                                            "open-popup",
                                            "close-popup",
                                        ) && index != node.actions.lastIndex
                                )
                                    decoder.failNode(
                                        node,
                                        "ACTION",
                                        "Navigation must be the last action",
                                    )
                            }
                            node.variants.forEach { inspect(it.node, branch = true) }
                        }
                        page.nodes.forEach { inspect(it) }
                        decoder.compilePage(page)
                    }
                menus[spec.id] = CompiledMenu(spec, java.util.Collections.unmodifiableMap(plans))
            } catch (e: DocumentError) {
                diagnostics += decoder.relocate(e.diagnostic)
            } catch (e: Exception) {
                diagnostics += Diagnostic(file, "$", "COMPILE", e.message ?: e.javaClass.simpleName)
            }
        }
        return CompileResult(
            if (diagnostics.isEmpty())
                MenuSnapshot(
                    java.util.Collections.unmodifiableMap(menus),
                    java.util.Collections.unmodifiableMap(
                        documents.mapValues { Values.map(it.value) }
                    ),
                )
            else null,
            Values.list(diagnostics),
        )
    }

    private inner class Decoder(
        val file: String,
        val templates: Map<String, Map<String, Any?>>,
        val templateFiles: Map<String, String>,
        val datasets: Map<String, List<Map<String, Any?>>>,
        val skins: SkinCompiler,
    ) {
        private val modalPlans = mutableMapOf<String, ModalLayoutPlan>()
        private val authoringIds = mutableSetOf<String>()
        private val pageLists = mutableListOf<ListSpec>()
        private val listReferences = mutableSetOf<String>()
        private val skinLocations = linkedMapOf<String, SkinLocation>()
        private val listLocations = linkedMapOf<String, String>()
        private var rowScope: RowScope? = null
        private var currentPopup: String? = null
        private var sourceDefinitions: Map<String, DataSourceSpec> = emptyMap()
        private var sourceNames: Set<String> = emptySet()
        private var initialState: Map<String, Any?> = emptyMap()

        fun relocate(value: Diagnostic): Diagnostic {
            if (value.file != file) return value
            val location =
                skinLocations.entries
                    .filter { value.path == it.key || value.path.startsWith(it.key + ".") }
                    .maxByOrNull { it.key.length }
                    ?.value
            if (location == null) {
                val list =
                    listLocations.entries
                        .filter { value.path.startsWith(it.key) }
                        .maxByOrNull { it.key.length }
                return if (list == null) value else value.copy(path = list.value)
            }
            return value.copy(
                path = location.authored,
                origins = Values.list((value.origins + location.origin).distinct()),
            )
        }

        private fun authored(node: NodeSpec): NodeSpec {
            val origin = node.origin ?: return node
            val location =
                skinLocations.entries
                    .filter { origin.path == it.key || origin.path.startsWith(it.key + ".") }
                    .maxByOrNull { it.key.length }
                    ?.value
            if (location == null) {
                val list =
                    listLocations.entries
                        .filter { origin.path.startsWith(it.key) }
                        .maxByOrNull { it.key.length }
                return if (list == null) node
                else node.copy(origin = origin.copy(path = list.value))
            }
            return node.copy(
                origin = origin.copy(path = location.authored),
                origins = Values.list((node.origins + location.origin).distinct()),
                variants = Values.list(node.variants.map { it.copy(node = authored(it.node)) }),
            )
        }

        val imageDimensions = assets.toMutableMap()
        val canvasImageDimensions = (assets + imageSources).toMutableMap()

        fun fail(path: String, code: String, message: String): Nothing =
            throw DocumentError(Diagnostic(file, path, code, message))

        fun failNode(node: NodeSpec, code: String, message: String): Nothing {
            val origin = node.origin ?: SourceLocation(file, "$", node.id)
            throw DocumentError(
                Diagnostic(origin.file, origin.path, code, message, node.id, origins = node.origins)
            )
        }

        private fun expandedOrigin(node: NodeSpec, origin: SourceLocation): NodeSpec =
            node.copy(
                origins = Values.list(listOf(origin) + node.origins),
                variants =
                    Values.list(
                        node.variants.map { it.copy(node = expandedOrigin(it.node, origin)) }
                    ),
            )

        /** 按原分组关系重算布局，保留行分配、隐式尺寸与样式。 */
        fun compilePage(page: PageSpec): CompiledPage {
            var compiled =
                if (page.mode == PageMode.NATIVE) CompiledPage(page, emptyList(), 0)
                else LayoutCompiler.layout(page, canvasImageDimensions, canvasMeasure)
            fun replace(node: NodeSpec, base: NodeSpec, branch: NodeSpec): NodeSpec =
                if (node.id == base.id) branch else node
            fun imageSize(node: NodeSpec, native: Boolean): Pair<Int, Int> {
                val content = node.content as NodeContent.Image
                val size =
                    (if (native) imageDimensions else canvasImageDimensions).getValue(content.asset)
                return (node.width ?: size.first) to (node.height ?: size.second)
            }
            fun inspect(base: NodeSpec, native: Boolean) {
                base.variants.forEach { variant ->
                    val branch = variant.node
                    if (
                        base.content is NodeContent.Image &&
                            imageSize(base, native) != imageSize(branch, native)
                    )
                        failNode(branch, "LAYOUT", "Variant image dimensions must match the base")
                    if (native) {
                        if (base.width != branch.width)
                            failNode(branch, "LAYOUT", "Variant native width must match the base")
                    } else {
                        val alternate =
                            page.copy(
                                elements =
                                    Values.list(page.elements.map { replace(it, base, branch) }),
                                popups =
                                    page.popups.mapValues { (_, popup) ->
                                        popup.copy(
                                            elements =
                                                Values.list(
                                                    popup.elements.map { replace(it, base, branch) }
                                                )
                                        )
                                    },
                            )
                        val plan =
                            LayoutCompiler.layout(alternate, canvasImageDimensions, canvasMeasure)
                        if (
                            base.content is NodeContent.Text &&
                                base.width == null &&
                                base.height == null
                        ) {
                            // 独立文字可改变自然范围，不挤动相邻元素。
                            compiled = compiled.copy(height = maxOf(compiled.height, plan.height))
                        } else if (
                            compiled.scene.map { it.bounds } != plan.scene.map { it.bounds } ||
                                compiled.popups.mapValues {
                                    it.value.map { placed -> placed.bounds }
                                } !=
                                    plan.popups.mapValues {
                                        it.value.map { placed -> placed.bounds }
                                    }
                        ) {
                            failNode(
                                branch,
                                "LAYOUT",
                                "Variant element geometry must match the base",
                            )
                        }
                    }
                }
            }
            (page.elements + page.popups.values.flatMap { it.elements }).forEach {
                inspect(it, native = false)
            }
            page.components.forEach { inspect(it, native = true) }
            return compiled.copy(modalLayout = modalPlans[page.id])
        }

        @Suppress("UNCHECKED_CAST")
        fun obj(v: Any?, path: String): Map<String, Any?> {
            if (v !is Map<*, *> || v.keys.any { it !is String })
                fail(path, "FIELD_TYPE", "Expected an object with string keys")
            return v as Map<String, Any?>
        }

        fun list(v: Any?, path: String): List<Any?> =
            v as? List<*> ?: fail(path, "FIELD_TYPE", "Expected array")

        fun id(value: String, path: String): String {
            if (!value.matches(Regex("[a-z0-9][a-z0-9_.:/-]*")))
                fail(path, "ID", "Invalid identifier $value")
            return value
        }

        fun script(value: String, path: String) {
            try {
                scriptCheck(value)
            } catch (e: Exception) {
                fail(path, "SCRIPT", e.message ?: "Invalid script")
            }
        }

        fun extension(kind: String, key: String, args: Map<String, Any?>, path: String) {
            try {
                extensionCheck(kind, key, args)
            } catch (e: Exception) {
                fail(path, "PROVIDER", e.message ?: "Unavailable provider $key")
            }
        }

        fun menu(m: Map<String, Any?>): MenuSpec {
            Contract.check(m, Contract.menu, file, "$")
            val state = Values.map(obj(m["state"] ?: emptyMap<String, Any?>(), "$.state"))
            initialState = state
            sourceNames = obj(m["data"] ?: emptyMap<String, Any?>(), "$.data").keys
            sourceDefinitions =
                java.util.Collections.unmodifiableMap(
                    obj(m["data"] ?: emptyMap<String, Any?>(), "$.data").mapValues { (alias, raw) ->
                        val path = SourcePath.field("$.data", alias)
                        val fields = obj(raw, path)
                        val type =
                            fields["type"] as? String
                                ?: fail(path, "REQUIRED_FIELD", "Missing data source type")
                        if (type !in setOf("document", "data", "args", "state", "record"))
                            fail(
                                path,
                                "CAPABILITY",
                                "A collection source must be document, data, args, state or record",
                            )
                        Contract.check(fields, Contract.dataSourceFields(type), file, path)
                        val source =
                            DataSourceSpec(
                                binding(fields - "id-field", path),
                                fields["id-field"] as? String ?: "id",
                            )
                        if (type == "document") {
                            val ids =
                                datasets.getValue(source.binding.key).mapIndexed { index, record ->
                                    record[source.idField] as? String
                                        ?: fail(
                                            path,
                                            "DATA_KEY",
                                            "Data record $index needs string ${source.idField}",
                                        )
                                }
                            if (ids.any { it.isEmpty() } || ids.distinct().size != ids.size)
                                fail(path, "DATA_KEY", "Record keys must be nonempty and unique")
                        }
                        source
                    }
                )
            fun dependency(alias: String, ancestry: Set<String>) {
                if (alias in ancestry)
                    fail("$.data.$alias", "DATA_CYCLE", "Collection source dependency cycle")
                val spec = sourceDefinitions.getValue(alias).binding
                if (spec.type == "record")
                    dependency(spec.args.getValue("source") as String, ancestry + alias)
            }
            sourceDefinitions.keys.forEach { dependency(it, emptySet()) }
            val pages =
                obj(m["pages"], "$.pages").mapValues { (key, value) ->
                    val path = SourcePath.field("$.pages", key)
                    authoringIds.clear()
                    pageLists.clear()
                    id(key, path)
                    val authoredPage = obj(value, path)
                    Contract.check(authoredPage, Contract.page, file, path)
                    val expandedSkin = skins.expand(authoredPage, file, path)
                    skinLocations.putAll(expandedSkin.locations)
                    val rawPage = expandedSkin.page
                    Contract.check(rawPage, Contract.page, file, path)
                    val modeName = rawPage["mode"] as String
                    if (modeName == "canvas" && "elements" !in rawPage)
                        fail("$path.elements", "REQUIRED_FIELD", "Missing Canvas elements")
                    if (modeName == "native" && "components" !in rawPage)
                        fail("$path.components", "REQUIRED_FIELD", "Missing Native components")
                    val pageFields = Contract.pageFields(modeName)
                    Contract.check(rawPage, pageFields, file, path)
                    val p = Contract.withDefaults(rawPage, pageFields)
                    val mode =
                        when (p["mode"]) {
                            "native" -> PageMode.NATIVE
                            "canvas" -> PageMode.CANVAS
                            else -> fail(path, "MODE", "Unknown mode")
                        }
                    val width =
                        (p["width"] as? Number)?.toInt()
                            ?: if (mode == PageMode.CANVAS) 432 else 360
                    if (width !in 90..864) fail(path, "LAYOUT", "Width must be 90..864")
                    val nativeBodyPosition =
                        if (mode == PageMode.CANVAS)
                            NativeBodyPosition.entries.first { it.id == p["native-body-position"] }
                        else NativeBodyPosition.AFTER_CANVAS
                    val columns = (p["columns"] as? Number)?.toInt() ?: 1
                    if (columns < 1) fail(path, "LAYOUT", "Invalid button columns")
                    fun decode(collection: String, nodeMode: PageMode): List<NodeSpec> =
                        followTargets(
                            list(p[collection] ?: emptyList<Any?>(), "$path.$collection")
                                .flatMapIndexed { i, n ->
                                    expand(
                                        obj(n, "$path.$collection[$i]"),
                                        "$path.$collection[$i]",
                                        nodeMode,
                                    )
                                }
                        )
                    val elements =
                        if (mode == PageMode.CANVAS) decode("elements", PageMode.CANVAS)
                        else emptyList()
                    val elementIds = elements.map { it.id }.toSet()
                    var page =
                        PageSpec(
                            key,
                            mode,
                            text(p["title"], "$path.title"),
                            width,
                            columns,
                            decode("components", PageMode.NATIVE),
                            (p["height"] as? Number)?.toInt(),
                            elements,
                            nativeBodyPosition,
                            if (mode == PageMode.CANVAS) {
                                val authored =
                                    obj(p["popups"] ?: emptyMap<String, Any?>(), "$path.popups")
                                if (
                                    authored.isNotEmpty() &&
                                        list(
                                                p["components"] ?: emptyList<Any?>(),
                                                "$path.components",
                                            )
                                            .isNotEmpty()
                                )
                                    fail(
                                        "$path.popups",
                                        "CAPABILITY",
                                        "Popups require a pure Canvas page; open a separate Native page for input",
                                    )
                                java.util.Collections.unmodifiableMap(
                                    authored.mapValues { (key, value) ->
                                        val popupPath = SourcePath.field("$path.popups", key)
                                        val rawPopup = obj(value, popupPath)
                                        Contract.check(rawPopup, Contract.popup, file, popupPath)
                                        val popup = Contract.withDefaults(rawPopup, Contract.popup)
                                        val hidden = linkedSetOf<String>()
                                        list(popup["hide-underlay"], "$popupPath.hide-underlay")
                                            .forEachIndexed { i, item ->
                                                val at = "$popupPath.hide-underlay[$i]"
                                                val id =
                                                    item as? String
                                                        ?: fail(
                                                            at,
                                                            "FIELD_TYPE",
                                                            "hide-underlay entries must be strings",
                                                        )
                                                val matches = elementIds.filter {
                                                    it == id || it.startsWith("$id.")
                                                }
                                                if (id !in authoringIds || matches.isEmpty())
                                                    fail(
                                                        at,
                                                        "REFERENCE",
                                                        "Unknown or empty page region $id",
                                                    )
                                                hidden += matches
                                            }
                                        val previousPopup = currentPopup
                                        currentPopup = key
                                        val popupElements =
                                            try {
                                                followTargets(
                                                    list(popup["elements"], "$popupPath.elements")
                                                        .flatMapIndexed { i, item ->
                                                            val at = "$popupPath.elements[$i]"
                                                            expand(
                                                                obj(item, at),
                                                                at,
                                                                PageMode.CANVAS,
                                                            )
                                                        }
                                                )
                                            } finally {
                                                currentPopup = previousPopup
                                            }
                                        PopupSpec(
                                            elements = popupElements,
                                            hideUnderlay =
                                                java.util.Collections.unmodifiableSet(hidden),
                                        )
                                    }
                                )
                            } else emptyMap(),
                            p["frame"]?.let { value ->
                                val at = "$path.frame"
                                val raw = obj(value, at)
                                Contract.check(raw, Contract.frame, file, at)
                                val frame = Contract.withDefaults(raw, Contract.frame)
                                val asset = frame.getValue("asset") as String
                                if (asset !in assets || ImageSizing.isGeneratedAsset(asset))
                                    fail("$at.asset", "ASSET", "Unknown frame source image $asset")
                                DialogFrameSpec(
                                    asset,
                                    (frame.getValue("padding") as Number).toInt(),
                                    (frame.getValue("inset") as Number).toInt(),
                                    (frame["width"] as? Number)?.toInt(),
                                )
                            },
                            p["modal"]?.let { modal(it, "$path.modal") },
                            Values.list(pageLists),
                            authoredPage["skin"] as? String,
                        )
                    if (page.modal != null) {
                        if (page.height != null)
                            fail(
                                "$path.height",
                                "CAPABILITY",
                                "Modal height is derived; remove page.height",
                            )
                        page =
                            try {
                                ModalLayoutCompiler.normalize(page)
                            } catch (failure: IllegalArgumentException) {
                                fail(
                                    "$path.modal",
                                    "LAYOUT",
                                    failure.message ?: "Invalid panel header layout",
                                )
                            }
                    }
                    validateModal(page, path, state)
                    page
                }
            val entry = m["entry"] as String
            if (entry !in pages) fail("$.entry", "REFERENCE", "Entry page does not exist")
            if (pages.getValue(entry).modal != null)
                fail("$.entry", "CAPABILITY", "A modal cannot be the menu entry page")
            val lists = pages.values.flatMap { it.lists }
            if (lists.map { it.id }.distinct().size != lists.size)
                fail("$.pages", "DUPLICATE_ID", "List IDs must be unique across a menu")
            val unknownLists = listReferences - lists.map { it.id }.toSet()
            if (unknownLists.isNotEmpty())
                fail("$.pages", "REFERENCE", "Unknown lists: $unknownLists")
            pages.values.forEach { page ->
                val base = page.lists.filter { it.popup == null }
                val scopes =
                    listOf(base) +
                        page.popups.keys.map { popup ->
                            base + page.lists.filter { it.popup == popup }
                        }
                scopes.forEach { active ->
                    try {
                        com.hiusers.rafter.model.ListProjectionOrder.resolve(
                            sourceDefinitions,
                            active,
                        )
                    } catch (failure: IllegalArgumentException) {
                        fail(
                            SourcePath.field("$.pages", page.id),
                            "LIST_CYCLE",
                            failure.message ?: "List selection dependency cycle",
                        )
                    }
                }
            }
            return MenuSpec(
                id(m["id"] as String, "$.id"),
                entry,
                m["permission"] as? String,
                state,
                java.util.Collections.unmodifiableMap(pages),
                file,
                sourceDefinitions,
                datasets,
            )
        }

        private fun modal(value: Any?, path: String): ModalSpec {
            val raw = obj(value, path)
            Contract.check(raw, Contract.modal, file, path)
            val fields = Contract.withDefaults(raw, Contract.modal)
            val asset = fields.getValue("asset") as String
            if (ImageSizing.isGeneratedAsset(asset) || asset !in assets)
                fail("$path.asset", "ASSET", "Unknown modal source image $asset")
            val source =
                imageSources[asset]
                    ?: fail("$path.asset", "ASSET", "Missing source dimensions for $asset")
            val clear =
                Values.list(
                    list(fields["clear"] ?: emptyList<Any?>(), "$path.clear").mapIndexed {
                        index,
                        item ->
                        val at = "$path.clear[$index]"
                        val rect = obj(item, at)
                        Contract.check(rect, Contract.rect, file, at)
                        val area =
                            Rect(
                                (rect.getValue("x") as Number).toInt(),
                                (rect.getValue("y") as Number).toInt(),
                                (rect.getValue("width") as Number).toInt(),
                                (rect.getValue("height") as Number).toInt(),
                            )
                        if (
                            area.x.toLong() + area.width > source.first ||
                                area.y.toLong() + area.height > source.second
                        )
                            fail(
                                at,
                                "LAYOUT",
                                "Modal clear rectangle must fit inside the source image",
                            )
                        area
                    }
                )
            return ModalSpec(
                asset,
                fields.getValue("background") as String,
                (fields.getValue("dim") as Number).toDouble(),
                (fields.getValue("top") as Number).toInt(),
                (fields.getValue("button-gap") as Number).toInt(),
                fields["feedback"] as? String,
                clear,
                contentTop = (fields.getValue("content-top") as Number).toInt(),
            )
        }

        private fun canvasText(spec: TextSpec, path: String) {
            fun singleLine(value: TextSpec) {
                val authored =
                    if (value.inlineParts.isEmpty()) listOf(value.value)
                    else value.inlineParts.filter { it.binding == null }.map { it.text }
                require(authored.none { '\n' in it || '\r' in it }) {
                    "Canvas text must be single line"
                }
                value.parts.forEach(::singleLine)
            }
            try {
                singleLine(spec)
                canvasTextCheck(spec)
            } catch (failure: Exception) {
                fail(path, "CANVAS_TEXT", failure.message ?: "Unsupported canvas text")
            }
        }

        fun validateModalOpener(page: PageSpec, node: NodeSpec) {
            if (
                page.mode != PageMode.CANVAS ||
                    page.frame != null ||
                    (page.modal == null && page.components.isNotEmpty())
            )
                failNode(
                    node,
                    "CAPABILITY",
                    "A modal requires a pure Canvas page without a viewport frame, or a current modal layer",
                )
            canvasText(page.title, SourcePath.field("$.pages", page.id) + ".title")
        }

        private fun validateModal(page: PageSpec, path: String, state: Map<String, Any?>) {
            val modal = page.modal ?: return
            if (page.mode != PageMode.CANVAS)
                fail("$path.modal", "CAPABILITY", "Modals require Canvas mode")
            val height =
                page.height
                    ?: fail(
                        "$path.height",
                        "REQUIRED_FIELD",
                        "A modal requires an explicit Canvas height",
                    )
            if (page.frame != null)
                fail("$path.frame", "CAPABILITY", "A modal cannot also have a viewport frame")
            if (page.popups.isNotEmpty())
                fail("$path.popups", "CAPABILITY", "A modal cannot declare popups")
            if (page.nativeBodyPosition != NativeBodyPosition.AFTER_CANVAS)
                fail(
                    "$path.native-body-position",
                    "CAPABILITY",
                    "A modal requires native body content after Canvas",
                )
            fun emptyTitle(value: TextSpec): Boolean =
                value.value.isEmpty() &&
                    value.binding == null &&
                    value.inlineParts.all { it.text.isEmpty() && it.binding == null } &&
                    value.parts.all(::emptyTitle) &&
                    (value.hover?.let(::emptyTitle) ?: true)
            if (!emptyTitle(page.title))
                fail("$path.title", "CAPABILITY", "A modal title must be empty and unbound")
            val forms = page.components.filter { it.content is FormContent }
            forms
                .dropLast(1)
                .firstOrNull { (it.content as? NodeContent.TextInput)?.multiline == true }
                ?.let {
                    failNode(
                        it,
                        "CAPABILITY",
                        "Multiline input must be the final modal input so its outside counter cannot overlap the next control",
                    )
                }
            val buttons = page.components.filter { it.content is NodeContent.Button }
            val descriptions = page.components.filter { it.content == NodeContent.Text }
            if (
                buttons.isEmpty() ||
                    forms.size + buttons.size + descriptions.size != page.components.size
            )
                fail(
                    "$path.components",
                    "CAPABILITY",
                    "A modal accepts text, native form controls and at least one action button",
                )
            val firstInput = page.components.indexOfFirst { it.content is FormContent }
            if (firstInput >= 0)
                descriptions.forEach { node ->
                    if (page.components.indexOf(node) > firstInput)
                        failNode(
                            node,
                            "CAPABILITY",
                            "Native body descriptions precede all form controls",
                        )
                }
            fun resolved(value: TextSpec, node: NodeSpec): TextSpec {
                if (value.inlineParts.any { it.binding != null })
                    failNode(
                        node,
                        "CAPABILITY",
                        "Modal measured labels cannot use inline script bindings",
                    )
                val bound =
                    value.binding?.let { binding ->
                        if (binding.type in setOf("record", "source", "list", "item")) return@let ""
                        if (
                            binding.type != "state" ||
                                binding.key !in state ||
                                state[binding.key] !is String
                        )
                            failNode(
                                node,
                                "STATE",
                                "Modal measured labels can only bind declared string state",
                            )
                        state[binding.key] as String
                    }
                return value.copy(
                    value = bound ?: value.value,
                    binding = null,
                    parts = value.parts.map { resolved(it, node) },
                )
            }
            fun control(node: NodeSpec) {
                if (node.visible != null)
                    failNode(
                        node,
                        "CAPABILITY",
                        "Modal native controls have fixed visibility; use separate pages for different layouts",
                    )
                if (node.content is FormContent && node.enabled != null)
                    failNode(node, "CAPABILITY", "Modal native form controls must remain enabled")
                if (
                    node.content == NodeContent.Text ||
                        node.content is NodeContent.Checkbox ||
                        (node.content as? NodeContent.TextInput)?.labelVisible == true
                ) {
                    val label = resolved(node.text, node)
                    val textPath = (node.origin?.path ?: "$path.components") + ".text"
                    canvasText(label, textPath)
                    val measured =
                        try {
                            canvasMeasure?.invoke(label)
                        } catch (failure: Exception) {
                            fail(
                                textPath,
                                "LAYOUT",
                                failure.message ?: "Cannot measure modal label",
                            )
                        }
                    if (node.content is NodeContent.Checkbox) {
                        val width = node.width ?: NativeGeometry.INPUT_WIDTH
                        val labelWidth =
                            width - NativeGeometry.CHECKBOX_SIZE - NativeGeometry.LABEL_GAP
                        if (labelWidth < 0 || measured != null && measured.first > labelWidth)
                            failNode(
                                node,
                                "LAYOUT",
                                "Checkbox label must fit inside its reserved native width",
                            )
                    } else if (
                        node.content == NodeContent.Text ||
                            (node.content as? NodeContent.TextInput)?.labelVisible == true
                    ) {
                        val width =
                            node.width
                                ?: if (node.content == NodeContent.Text) page.width
                                else NativeGeometry.INPUT_WIDTH
                        if (measured != null && measured.first > width)
                            fail(
                                textPath,
                                "LAYOUT",
                                "Modal label must fit on one line inside its native slot",
                            )
                    }
                }
                node.variants.forEach { control(it.node) }
            }
            page.components.forEach(::control)
            if (forms.isNotEmpty() && modal.feedback == null)
                fail(
                    "$path.modal.feedback",
                    "REQUIRED_FIELD",
                    "A modal form requires an explicit fixed feedback text target",
                )
            modal.feedback?.let { id ->
                val target =
                    (page.elements + page.components).singleOrNull { it.id == id }
                        ?: fail(
                            "$path.modal.feedback",
                            "REFERENCE",
                            "Unknown feedback text target: $id",
                        )
                if (
                    (target.content != NodeContent.Text &&
                        (target.content as? NodeContent.TextInput)?.labelVisible != true) ||
                        target.visible != null ||
                        target.enabled != null ||
                        target.variants.isNotEmpty() ||
                        target.interaction != null ||
                        target.rowScope != null
                )
                    fail(
                        "$path.modal.feedback",
                        "CAPABILITY",
                        "Feedback requires unconditional text or a visible input label with fixed geometry",
                    )
                if (target in page.elements && (target.width == null || target.height == null))
                    fail(
                        "$path.modal.feedback",
                        "LAYOUT",
                        "Canvas feedback requires explicit width and height",
                    )
            }
            val plan = ModalLayoutCompiler.layout(page)
            if (
                plan.buttons.any {
                    it.nativeWidth > it.bounds.width ||
                        it.nativeWidth < NativeGeometry.PAINTED_SPACER_WIDTH
                }
            ) {
                fail(
                    "$path.modal.button-gap",
                    "LAYOUT",
                    "Counter-safe spacer needs at least ${NativeGeometry.PAINTED_SPACER_WIDTH}px per action column and gap; increase button widths or button-gap",
                )
            }
            val source = imageSources.getValue(modal.asset)
            if (source.first < plan.bodyWidth)
                fail(
                    "$path.modal.asset",
                    "LAYOUT",
                    "Modal source panel must cover the complete native body width",
                )
            if (source.second.toLong() + modal.offsetY < plan.bodyHeight)
                fail(
                    "$path.modal.top",
                    "LAYOUT",
                    "Modal source panel must cover the complete native control flow",
                )
            plan.feedback?.bounds?.let { box ->
                val panelLeft = (plan.bodyWidth - source.first) / 2
                if (
                    box.x < panelLeft ||
                        box.x + box.width > panelLeft + source.first ||
                        box.y < modal.offsetY ||
                        box.y + box.height > modal.offsetY + source.second
                )
                    fail(
                        "$path.modal.feedback",
                        "LAYOUT",
                        "Feedback region must stay inside the modal panel",
                    )
            }
            val firstControlTop =
                plan.inputs.firstOrNull()?.bounds?.y ?: plan.buttons.first().bounds.y
            if (modal.offsetY > firstControlTop)
                fail(
                    "$path.modal.top",
                    "LAYOUT",
                    "Modal panel must start at or above its native controls",
                )
            modalPlans[page.id] = plan
        }

        fun substitute(v: Any?, params: Map<String, Any?>): Any? = substituteParameters(v, params)

        /** 视觉目标隐藏后，跟随区域不得保留点击能力。 */
        private fun followTargets(nodes: List<NodeSpec>): List<NodeSpec> {
            val byId = nodes.associateBy { it.id }
            return Values.list(
                nodes.map { node ->
                    val inherited = node.hitTarget?.let { byId[it]?.visible } ?: return@map node
                    fun apply(value: NodeSpec): NodeSpec =
                        value.copy(
                            visible =
                                value.visible?.let {
                                    ConditionSpec(
                                        "all",
                                        children = Values.list(listOf(it, inherited)),
                                    )
                                } ?: inherited,
                            variants =
                                Values.list(value.variants.map { it.copy(node = apply(it.node)) }),
                        )
                    apply(node)
                }
            )
        }

        private fun template(
            input: Map<String, Any?>,
            path: String,
            mode: PageMode,
            stack: List<String>,
        ): Pair<Map<String, Any?>, SourceLocation> {
            val m = input
            Contract.check(
                m,
                Contract.fields("template", mode.name.lowercase()),
                file,
                path,
            )
            val key = m["key"] as String
            if (key in stack || stack.size >= 16)
                fail(path, "TEMPLATE_CYCLE", "Template cycle/depth: $stack -> $key")
            val template = templates[key] ?: fail(path, "REFERENCE", "Unknown template $key")
            val defaults = obj(template["params"] ?: emptyMap<String, Any?>(), path)
            val passed = obj(m["params"] ?: emptyMap<String, Any?>(), path)
            if ((passed.keys - defaults.keys).isNotEmpty())
                fail(path, "TEMPLATE_PARAM", "Unknown template parameters")
            val expanded =
                obj(substitute(template["component"], defaults + passed), path).toMutableMap()
            expanded["id"] = m["id"]
            if (mode == PageMode.CANVAS) {
                for (axis in listOf("x", "y")) if (axis in m) {
                    val base = if (axis in expanded) expanded[axis] else 0
                    // 保留非法数值交给字段校验，不截断成合法整数。
                    if (base is Number)
                        expanded[axis] = base.toDouble() + (m[axis] as Number).toDouble()
                }
            }
            if ("style" in m) expanded["style"] = m["style"]
            for (predicate in listOf("visible", "enabled")) if (predicate in m) {
                expanded[predicate] =
                    if (predicate in expanded)
                        mapOf(
                            "type" to "all",
                            "conditions" to listOf(expanded[predicate], m[predicate]),
                        )
                    else m[predicate]
            }
            val origin =
                SourceLocation(
                    templateFiles.getValue(key),
                    "$.component",
                    (template["component"] as? Map<*, *>)?.get("id") as? String,
                )
            return expanded to origin
        }

        private fun inherit(
            nodes: List<NodeSpec>,
            visible: ConditionSpec?,
            enabled: ConditionSpec?,
        ): List<NodeSpec> {
            fun combined(own: ConditionSpec?, parent: ConditionSpec?) =
                if (parent == null) own
                else
                    own?.let { ConditionSpec("all", children = Values.list(listOf(parent, it))) }
                        ?: parent
            fun apply(node: NodeSpec): NodeSpec =
                node.copy(
                    visible = combined(node.visible, visible),
                    enabled = combined(node.enabled, enabled),
                    variants = Values.list(node.variants.map { it.copy(node = apply(it.node)) }),
                )
            return Values.list(nodes.map(::apply))
        }

        /** 分组只用于创作，校验前展开局部坐标与子 ID。 */
        private fun expand(
            input: Map<String, Any?>,
            path: String,
            mode: PageMode,
            stack: List<String> = emptyList(),
            prefix: String = "",
            offsetX: Int = 0,
            offsetY: Int = 0,
            depth: Int = 0,
        ): List<NodeSpec> {
            if (depth >= 16 && input["type"] in setOf("group", "template"))
                fail(path, "TEMPLATE_DEPTH", "Group/template nesting exceeds 16")
            if (input["type"] == "template") {
                val (expanded, origin) = template(input, path, mode, stack)
                return try {
                    expand(
                            expanded,
                            path,
                            mode,
                            stack + input.getValue("key").toString(),
                            prefix,
                            offsetX,
                            offsetY,
                            depth + 1,
                        )
                        .map { expandedOrigin(it, origin) }
                } catch (failure: DocumentError) {
                    throw DocumentError(
                        failure.diagnostic.copy(
                            origins = Values.list(listOf(origin) + failure.diagnostic.origins)
                        )
                    )
                }
            }
            if (input["type"] == "list") {
                if (rowScope != null)
                    fail(
                        path,
                        "CAPABILITY",
                        "Lists cannot be nested in a row",
                    )
                val listFields = Contract.fields("list", mode.name.lowercase())
                Contract.check(input, listFields, file, path)
                val fields = Contract.withDefaults(input, listFields)
                val localId = fields.getValue("id") as String
                val listId = prefix + localId
                if (!authoringIds.add(listId)) fail(path, "DUPLICATE_ID", "Duplicate list $listId")
                val source = fields.getValue("source") as String
                if (source !in sourceDefinitions)
                    fail("$path.source", "REFERENCE", "Unknown data source $source")
                val pageState = fields.getValue("page-state") as String
                val initialPage = initialState[pageState] as? Number
                if (
                    initialPage == null ||
                        !Values.isOrdinaryNumber(initialPage) ||
                        !initialPage.toDouble().isFinite() ||
                        initialPage.toDouble() < 0 ||
                        initialPage.toDouble() % 1.0 != 0.0
                )
                    fail(
                        "$path.page-state",
                        "STATE",
                        "A list needs a declared non-negative integer page state",
                    )
                val selection = fields["selection-state"] as? String
                if (
                    selection != null &&
                        (selection !in initialState ||
                            initialState[selection] != null && initialState[selection] !is String)
                )
                    fail(
                        "$path.selection-state",
                        "STATE",
                        "Selection state must be a declared string or null",
                    )
                val filter =
                    fields["filter"]?.let { raw ->
                        val at = "$path.filter"
                        val value = obj(raw, at)
                        Contract.check(value, Contract.listFilter, file, at)
                        val condition =
                            value["when"]?.let { condition(obj(it, "$at.when"), "$at.when", 0) }
                        fun cyclic(value: ConditionSpec): Boolean =
                            value.binding?.type in setOf("list", "item") ||
                                value.compare?.type in setOf("list", "item") ||
                                value.children.any(::cyclic)
                        if (condition != null && cyclic(condition))
                            fail(
                                at,
                                "CAPABILITY",
                                "A list filter cannot depend on list projections",
                            )
                        if (value.containsKey("value") == value.containsKey("state"))
                            fail(at, "FILTER", "Filter needs exactly one of value/state")
                        val filterState = value["state"] as? String
                        if (filterState != null && filterState !in initialState)
                            fail(at, "STATE", "Unknown filter state $filterState")
                        ListFilter(
                            value.getValue("field") as String,
                            Values.freeze(value["value"]),
                            condition,
                            filterState,
                        )
                    }
                val visible =
                    fields["visible"]?.let {
                        condition(obj(it, "$path.visible"), "$path.visible", 0)
                    }
                fun cyclic(value: ConditionSpec): Boolean =
                    value.binding?.type in setOf("list", "item") ||
                        value.compare?.type in setOf("list", "item") ||
                        value.children.any(::cyclic)
                if (visible != null && cyclic(visible))
                    fail(
                        "$path.visible",
                        "CAPABILITY",
                        "List visibility cannot depend on its own projection",
                    )
                val baseX = offsetX + ((fields["x"] as? Number)?.toInt() ?: 0)
                val baseY = offsetY + ((fields["y"] as? Number)?.toInt() ?: 0)
                val placements =
                    if (mode == PageMode.NATIVE) {
                        List((fields.getValue("page-size") as Number).toInt()) {
                            ListPlacement(0, 0)
                        }
                    } else if ("slots" in input) {
                        if ("row-height" in input || "page-size" in input)
                            fail(path, "LAYOUT", "Use explicit slots or uniform rows, not both")
                        list(fields["slots"], "$path.slots").mapIndexed { index, raw ->
                            val at = "$path.slots[$index]"
                            val slot = obj(raw, at)
                            Contract.check(slot, Contract.listSlot, file, at)
                            val values = Contract.withDefaults(slot, Contract.listSlot)
                            ListPlacement(
                                baseX + (values.getValue("x") as Number).toInt(),
                                baseY + (values.getValue("y") as Number).toInt(),
                                Values.map(
                                    obj(values["params"] ?: emptyMap<String, Any?>(), "$at.params")
                                ),
                            )
                        }
                    } else {
                        val step =
                            (fields["row-height"] as? Number)?.toInt()
                                ?: fail(
                                    "$path.row-height",
                                    "REQUIRED_FIELD",
                                    "Uniform rows need row-height",
                                )
                        List((fields.getValue("page-size") as Number).toInt()) { index ->
                            ListPlacement(baseX, baseY + index * step)
                        }
                    }
                val spec =
                    ListSpec(
                        listId,
                        source,
                        pageState,
                        selection,
                        Values.list(placements),
                        filter,
                        visible,
                        currentPopup,
                    )
                pageLists += spec
                val presenceField = sourceDefinitions.getValue(source).idField
                return (0 until spec.pageSize).flatMap { slot ->
                    val rowPath = "$path.rows[$slot]"
                    listLocations[rowPath] = path
                    rowScope = RowScope(listId, slot)
                    try {
                        val use =
                            mapOf(
                                "id" to "$localId.row-$slot",
                                "type" to "template",
                                "key" to fields.getValue("template"),
                                "params" to
                                    (obj(
                                        fields["params"] ?: emptyMap<String, Any?>(),
                                        "$path.params",
                                    ) + spec.placements[slot].params),
                            ) +
                                if (mode == PageMode.CANVAS)
                                    mapOf(
                                        "x" to spec.placements[slot].x,
                                        "y" to spec.placements[slot].y,
                                    )
                                else emptyMap()
                        val present =
                            ConditionSpec(
                                "not",
                                children =
                                    listOf(
                                        ConditionSpec(
                                            "equals",
                                            value = null,
                                            binding =
                                                BindingSpec(
                                                    "item",
                                                    presenceField,
                                                    mapOf("list" to listId, "slot" to slot),
                                                ),
                                        )
                                    ),
                            )
                        inherit(
                            expand(use, rowPath, mode, stack, prefix, 0, 0, depth + 1),
                            present,
                            null,
                        )
                    } finally {
                        rowScope = null
                    }
                }
            }
            if (input["type"] == "group") {
                if (mode != PageMode.CANVAS)
                    fail(path, "CAPABILITY", "Groups contain Canvas elements, not native widgets")
                Contract.check(input, Contract.fields("group", "canvas"), file, path)
                val fields = Contract.withDefaults(input, Contract.fields("group", "canvas"))
                val groupId = prefix + fields.getValue("id")
                if (!authoringIds.add(groupId))
                    throw DocumentError(
                        Diagnostic(
                            file,
                            "$path.id",
                            "DUPLICATE_ID",
                            "Duplicate component/group $groupId",
                            groupId,
                        )
                    )
                val childPrefix = "$groupId."
                val x = offsetX + (fields.getValue("x") as Number).toInt()
                val y = offsetY + (fields.getValue("y") as Number).toInt()
                val children =
                    list(fields["elements"], "$path.elements").flatMapIndexed { index, value ->
                        val at = "$path.elements[$index]"
                        expand(obj(value, at), at, mode, stack, childPrefix, x, y, depth + 1)
                    }
                return inherit(
                    children,
                    fields["visible"]?.let {
                        condition(obj(it, "$path.visible"), "$path.visible", 0)
                    },
                    fields["enabled"]?.let {
                        condition(obj(it, "$path.enabled"), "$path.enabled", 0)
                    },
                )
            }
            fun translated(node: NodeSpec): NodeSpec {
                val y = node.y + offsetY
                var content = node.content
                if (mode == PageMode.CANVAS && content is NodeContent.Image) {
                    // 累加父级偏移后再计算传输坐标，保留作者范围。
                    val padding = Math.floorMod(y, LayoutCompiler.Y_STEP)
                    if (padding != (content.request?.topPadding ?: 0)) {
                        val request =
                            (content.request ?: ImageRequest(content.asset)).copy(
                                topPadding = padding
                            )
                        if (padding == 0 && request.width == null && request.height == null) {
                            content = NodeContent.Image(request.asset)
                        } else {
                            val source =
                                imageSources[request.asset]
                                    ?: failNode(
                                        node,
                                        "ASSET",
                                        "Missing source dimensions for ${request.asset}",
                                    )
                            val plan =
                                try {
                                    ImageSizing.resolve(request, source.first, source.second)
                                } catch (failure: IllegalArgumentException) {
                                    failNode(
                                        node,
                                        "LAYOUT",
                                        failure.message ?: "Invalid image padding",
                                    )
                                }
                            imageDimensions[request.id] = plan.layoutWidth to plan.layoutHeight
                            canvasImageDimensions[request.id] = plan.width to plan.height
                            content = NodeContent.Image(request.id, request)
                        }
                    }
                }
                return node.copy(
                    x = node.x + offsetX,
                    y = y,
                    content = content,
                    variants =
                        Values.list(node.variants.map { it.copy(node = translated(it.node)) }),
                )
            }
            val leaf = authored(translated(node(input, path, mode, stack, prefix)))
            if (!authoringIds.add(leaf.id))
                failNode(leaf, "DUPLICATE_ID", "Duplicate component/group ${leaf.id}")
            return listOf(leaf)
        }

        fun node(
            input: Map<String, Any?>,
            path: String,
            mode: PageMode,
            stack: List<String>,
            prefix: String,
        ): NodeSpec {
            val type =
                input["type"] as? String
                    ?: fail(path, "REQUIRED_FIELD", "Missing explicit component type")
            if (type in setOf("template", "group")) {
                val expanded = expand(input, path, mode, stack, prefix)
                if (expanded.size != 1)
                    fail(path, "CAPABILITY", "A leaf replacement cannot contain multiple elements")
                return expanded.single()
            }
            if (type !in Contract.nodes) fail(path, "UNKNOWN_TYPE", "Unknown component $type")
            if (type !in Contract.modes[mode.name.lowercase()]!!)
                fail(path, "CAPABILITY", "$type is not available in $mode")
            val fields = Contract.fields(type, mode.name.lowercase())
            Contract.check(input, fields, file, path)
            // 仅作者显式填写的多行选项需要 multiline；单行也会注入默认值。
            if (type == "input" && input["multiline"] != true) {
                for (key in listOf("multiline-height", "max-lines")) if (key in input)
                    fail("$path.$key", "CAPABILITY", "$key requires multiline=true")
            }
            val m = Contract.withDefaults(styled(input, fields, mode, path), fields)
            if (type == "hit-area") {
                if ("target" in input && listOf("x", "y", "width", "height").any { it in input })
                    fail(
                        path,
                        "LAYOUT",
                        "A followed hit area cannot also declare coordinates or dimensions",
                    )
                if ("target" !in input && ("width" !in input || "height" !in input))
                    fail(
                        path,
                        "REQUIRED_FIELD",
                        "A hit area needs target or explicit width and height",
                    )
            }
            val localId = m["id"] as String
            if (!localId.matches(Regex("[a-z0-9][a-z0-9_.-]*")))
                fail(path, "ID", "Component IDs cannot contain paths or namespaces")
            val nodeId = prefix + localId
            fun integer(key: String, default: Int) = (m[key] as? Number)?.toInt() ?: default
            fun dimension(key: String): Int? =
                (m[key] as? Number)?.toInt()?.also {
                    if (
                        it !in
                            1..(if (key == "height" && (mode == PageMode.CANVAS || type == "image"))
                                    LayoutCompiler.MAX_HEIGHT
                                else 864) ||
                            mode == PageMode.CANVAS &&
                                key == "height" &&
                                type == "hit-area" &&
                                it % 9 != 0
                    )
                        fail(path, "LAYOUT", "$key must be positive; canvas uses 9px cells")
                }
            var width = dimension("width")
            var height = dimension("height")
            val x = integer("x", 0)
            val y = integer("y", 0)
            if (
                (x < 0 || x > 864) ||
                    y !in 0..(if (mode == PageMode.CANVAS) LayoutCompiler.MAX_HEIGHT else 864)
            )
                fail(
                    path,
                    "LAYOUT",
                    "Positions must be within bounds; Canvas explicit hit-area y must use 9px steps, images and text use pixel steps",
                )
            (m["item"] as? String)?.let { extension("item", it, emptyMap(), path) }
            val asset = m["asset"] as? String
            if (asset?.let(ImageSizing::isGeneratedAsset) == true)
                fail("$path.asset", "ASSET", "Generated image IDs cannot be authored")
            if (asset != null && asset !in assets) fail(path, "ASSET", "Unknown asset $asset")
            val skin =
                m["skin"]?.let { value ->
                    val skinPath = "$path.skin"
                    val raw = obj(value, skinPath)
                    Contract.check(raw, Contract.buttonSkin, file, skinPath)
                    val source = raw["asset"] as String
                    if (ImageSizing.isGeneratedAsset(source) || source !in imageSources)
                        fail("$skinPath.asset", "ASSET", "Unknown authored image asset $source")
                    ButtonSkinSpec(
                            source,
                            (raw["inset"] as? Number)?.toInt() ?: 1,
                            (raw["label-offset-x"] as? Number)?.toInt() ?: 0,
                        )
                        .also {
                            try {
                                it.request(width ?: NativeGeometry.BUTTON_WIDTH)
                            } catch (failure: IllegalArgumentException) {
                                fail(
                                    skinPath,
                                    "LAYOUT",
                                    failure.message ?: "Button skin must fit inside the button",
                                )
                            }
                        }
                }
            val imageRequest =
                if (type == "image" && ("width" in m || "height" in m)) {
                    ImageRequest(
                            requireNotNull(asset),
                            width,
                            height,
                            m["fit"] as? String,
                        )
                        .also { request ->
                            val source =
                                imageSources[request.asset]
                                    ?: fail(
                                        "$path.asset",
                                        "ASSET",
                                        "Missing source dimensions for $asset",
                                    )
                            val plan =
                                try {
                                    ImageSizing.resolve(request, source.first, source.second)
                                } catch (e: IllegalArgumentException) {
                                    fail(path, "LAYOUT", e.message ?: "Invalid image sizing")
                                }
                            width = if (mode == PageMode.CANVAS) plan.width else plan.layoutWidth
                            height = if (mode == PageMode.CANVAS) plan.height else plan.layoutHeight
                            imageDimensions[request.id] = plan.layoutWidth to plan.layoutHeight
                            canvasImageDimensions[request.id] = plan.width to plan.height
                        }
                } else {
                    if (type == "image" && "fit" in m)
                        fail("$path.fit", "LAYOUT", "fit requires both width and height")
                    null
                }
            val options =
                obj(m["options"] ?: emptyMap<String, Any?>(), "$path.options").mapValues { (k, v) ->
                    text(v, SourcePath.field("$path.options", k))
                }
            if (type == "select" && (options.isEmpty() || options.size > 64))
                fail(path, "OPTIONS", "select requires 1..64 options")
            val min = (m["min"] as? Number)?.toDouble() ?: 0.0
            val max = (m["max"] as? Number)?.toDouble() ?: 100.0
            val step = (m["step"] as? Number)?.toDouble() ?: 1.0
            if (
                min >= max ||
                    step <= 0 ||
                    step > max - min ||
                    !min.toFloat().isFinite() ||
                    !max.toFloat().isFinite() ||
                    !step.toFloat().isFinite() ||
                    step.toFloat() == 0f
            )
                fail(path, "RANGE", "Invalid slider range/step")
            val maxLength = integer("max-length", 64)
            if (maxLength !in 1..4096) fail(path, "RANGE", "max-length must be 1..4096")
            val actions =
                (m["actions"] as? List<*>)?.mapIndexed { i, value ->
                    action(obj(value, "$path.actions[$i]"), "$path.actions[$i]")
                } ?: emptyList()
            val rawLabel = m["text"] ?: ""
            // 组件绑定已替代文本，不再解析无用的回退脚本。
            val label =
                text(
                    if ("binding" in m && rawLabel is String)
                        mapOf("format" to "literal", "value" to rawLabel)
                    else rawLabel,
                    "$path.text",
                )
            if (mode == PageMode.CANVAS && type == "text")
                try {
                    canvasTextCheck(label)
                } catch (e: Exception) {
                    fail("$path.text", "CANVAS_TEXT", e.message ?: "Unsupported canvas text")
                }
            if (skin != null)
                try {
                    buttonTextCheck(label, width ?: NativeGeometry.BUTTON_WIDTH)
                } catch (failure: Exception) {
                    fail(
                        "$path.text",
                        "BUTTON_TEXT",
                        failure.message ?: "Unsupported skinned button text",
                    )
                }
            if ("binding" in m && (label.format != "literal" || label.binding != null))
                fail(path, "TEXT_FORMAT", "Node binding requires unbound literal text")
            val content: NodeContent =
                when (type) {
                    "text" -> NodeContent.Text
                    "hit-area" -> NodeContent.HitArea
                    "image" ->
                        NodeContent.Image(imageRequest?.id ?: requireNotNull(asset), imageRequest)
                    "meter" ->
                        NodeContent.Meter(
                            binding(obj(m["value"], "$path.value"), "$path.value"),
                            binding(obj(m["max"], "$path.max"), "$path.max"),
                            m["fill"] as String,
                            m["track"] as String,
                        )
                    "item" -> NodeContent.Item(m["item"] as String)
                    "button" ->
                        NodeContent.Button(
                            Values.list(actions),
                            skin,
                            ButtonRole.entries.single { it.id == m["role"] },
                        )
                    "input" ->
                        NodeContent.TextInput(
                            state = m["state"] as String,
                            maxLength = maxLength,
                            multiline = m["multiline"] as? Boolean ?: false,
                            labelVisible = m["label-visible"] as? Boolean ?: true,
                            multilineHeight = integer("multiline-height", 48),
                            maxLines = integer("max-lines", 3),
                        )
                    "checkbox" -> NodeContent.Checkbox(m["state"] as String)
                    "select" ->
                        NodeContent.Select(
                            m["state"] as String,
                            java.util.Collections.unmodifiableMap(options),
                        )
                    "slider" -> NodeContent.Slider(m["state"] as String, min, max, step)
                    else -> fail(path, "UNKNOWN_TYPE", "Unknown component $type")
                }
            val resolvedBinding =
                m["binding"]?.let { binding(obj(it, "$path.binding"), "$path.binding") }
            val base =
                NodeSpec(
                    id = nodeId,
                    content = content,
                    text =
                        if (resolvedBinding == null) label
                        else label.copy(binding = resolvedBinding),
                    tooltip = m["tooltip"]?.let { text(it, "$path.tooltip") },
                    visible =
                        m["visible"]?.let {
                            condition(obj(it, "$path.visible"), "$path.visible", 0)
                        },
                    enabled =
                        m["enabled"]?.let {
                            condition(obj(it, "$path.enabled"), "$path.enabled", 0)
                        },
                    width = width,
                    height = height,
                    x = x,
                    y = y,
                    origin = SourceLocation(file, path, nodeId),
                    interaction =
                        if (mode == PageMode.CANVAS && "actions" in m)
                            ElementInteraction(Values.list(actions))
                        else null,
                    align = m["align"] as? String ?: "left",
                    hitTarget = (m["target"] as? String)?.let { prefix + it },
                    rowScope = rowScope,
                    overflow = m["overflow"] as? String ?: "error",
                )
            val variants =
                (input["variants"] as? List<*>)?.mapIndexed { index, value ->
                    val variantPath = "$path.variants[$index]"
                    val variant = obj(value, variantPath)
                    Contract.check(variant, Contract.variant, file, variantPath)
                    val predicate =
                        condition(obj(variant["when"], "$variantPath.when"), "$variantPath.when", 0)
                    val override = obj(variant["override"], "$variantPath.override")
                    Contract.check(
                        override,
                        Contract.variantFields(type, mode.name.lowercase()),
                        file,
                        "$variantPath.override",
                    )
                    // 变体基于作者字段，不继承已解析默认值或前一分支。
                    // 动作列表整体替换。
                    val branch =
                        node(
                            (input - "variants") + override,
                            "$variantPath.override",
                            mode,
                            stack,
                            prefix,
                        )
                    NodeVariant(predicate, branch)
                } ?: emptyList()
            return base.copy(variants = Values.list(variants))
        }

        /** 先展开节点，再应用默认值；嵌套文本属性优先。 */
        private fun styled(
            input: Map<String, Any?>,
            fields: Map<String, Field>,
            mode: PageMode,
            path: String,
        ): Map<String, Any?> {
            val stylePath = "$path.style"
            val style =
                if ("style" in input) {
                    val name =
                        input["style"] as? String
                            ?: fail(stylePath, "FIELD_TYPE", "style must be a string")
                    styles[name] ?: fail(stylePath, "REFERENCE", "Unknown style $name")
                } else emptyMap()
            if (style.isNotEmpty()) {
                Contract.check(style, Contract.style, file, stylePath)
                style.keys.forEach { key ->
                    val supported =
                        when (key) {
                            "font",
                            "font-size" -> "text" in fields
                            "height" -> mode == PageMode.CANVAS
                            else -> false
                        }
                    if (!supported)
                        fail(
                            "$stylePath.$key",
                            "CAPABILITY",
                            "$key style is not available for ${input["type"]} in $mode",
                        )
                }
            }
            val merged = style + input
            val typography = merged.filterKeys { it == "font" || it == "font-size" }
            if (typography.isEmpty()) return merged
            val value = merged["text"] ?: ""
            val label =
                if (value is String) {
                    mapOf(
                        "format" to if ("binding" in merged) "literal" else "colored",
                        "value" to value,
                    )
                } else obj(value, "$path.text")
            return merged + ("text" to (typography + label))
        }

        fun binding(m: Map<String, Any?>, path: String): BindingSpec {
            val type = m["type"] as? String ?: fail(path, "REQUIRED_FIELD", "Missing binding type")
            Contract.check(
                m,
                Contract.bindings[type] ?: fail(path, "UNKNOWN_TYPE", "Unknown binding $type"),
                file,
                path,
            )
            val key = m[if (type == "kether") "script" else "key"] as String
            var args = Values.map(obj(m["args"] ?: emptyMap<String, Any?>(), path))
            when (type) {
                "document" ->
                    if (key !in datasets) fail(path, "REFERENCE", "Unknown data document $key")
                "item" -> {
                    val scope =
                        rowScope
                            ?: fail(
                                path,
                                "CAPABILITY",
                                "item binding is only available in list row components",
                            )
                    args = Values.map(mapOf("list" to scope.list, "slot" to scope.slot))
                }
                "source",
                "record" -> {
                    val source = m.getValue("source") as String
                    if (source !in sourceNames)
                        fail(path, "REFERENCE", "Unknown data source $source")
                    args = mapOf("source" to source)
                    if (type == "record") {
                        val state = m.getValue("state") as String
                        if (
                            state !in initialState ||
                                initialState[state] != null && initialState[state] !is String
                        )
                            fail(
                                path,
                                "STATE",
                                "Record lookup needs a declared string/null state key",
                            )
                        args = args + ("state" to state)
                    } else if (key == "count") {
                        if ("field" !in m || "value" !in m)
                            fail(path, "REQUIRED_FIELD", "count needs field and value")
                        args =
                            args +
                                mapOf(
                                    "field" to m.getValue("field"),
                                    "value" to Values.freeze(m["value"]),
                                )
                    } else if ("field" in m || "value" in m)
                        fail(path, "CAPABILITY", "size does not accept field/value")
                    args = Values.map(args)
                }
                "list" -> {
                    val list =
                        m["list"] as? String
                            ?: rowScope?.list
                            ?: fail(
                                path,
                                "REFERENCE",
                                "A list binding needs an explicit list outside a row",
                            )
                    listReferences += list
                    args = mapOf("list" to list)
                    if (key == "count") {
                        if ("field" !in m || "value" !in m)
                            fail(path, "REQUIRED_FIELD", "count needs field and value")
                        args =
                            args +
                                mapOf(
                                    "field" to m.getValue("field"),
                                    "value" to Values.freeze(m["value"]),
                                )
                    } else if ("field" in m || "value" in m)
                        fail(path, "CAPABILITY", "Only count accepts field/value")
                    if (key == "selected") {
                        val scope =
                            rowScope?.takeIf { it.list == list }
                                ?: fail(path, "CAPABILITY", "selected is a row statistic")
                        args = args + ("slot" to scope.slot)
                    }
                    args = Values.map(args)
                }
            }
            if (type == "kether") script(key, path)
            if (type == "papi") extension("papi", key, emptyMap(), path)
            if (type == "data") extension("data", key, args, path)
            return BindingSpec(type, key, args, scope = rowScope)
        }

        private val textDecoder = TextSpecDecoder(file, textCheck, ::binding)

        fun text(value: Any?, path: String, depth: Int = 0): TextSpec =
            textDecoder.decode(value, path, depth)

        fun action(m: Map<String, Any?>, path: String): ActionSpec {
            val type = m["type"] as? String ?: fail(path, "REQUIRED_FIELD", "Missing action type")
            Contract.check(
                m,
                Contract.actions[type] ?: fail(path, "UNKNOWN_TYPE", "Unknown action $type"),
                file,
                path,
            )
            if (type == "set-state" && (m.containsKey("value") == m.containsKey("binding")))
                fail(path, "ACTION", "set-state requires exactly one of value/binding")
            val key =
                when (type) {
                    "open-page" -> m["page"]
                    "open-popup" -> m["popup"]
                    "page-list" -> m["list"]
                    "set-record",
                    "toggle-record" -> m["source"]
                    "set-state",
                    "toggle-state" -> m["state"]
                    "provider" -> m["provider"]
                    "kether" -> m["script"]
                    else -> null
                }
                    as? String ?: ""
            val args =
                if (type in setOf("set-record", "toggle-record")) {
                    val source =
                        sourceDefinitions[key]
                            ?: fail(path, "REFERENCE", "Unknown data source $key")
                    if (source.binding.type != "document")
                        fail(
                            path,
                            "CAPABILITY",
                            "Local record actions require a document source; use provider actions for business data",
                        )
                    if (m["field"] == source.idField)
                        fail(path, "DATA_KEY", "Record identity cannot be edited")
                    val selected = m["state"] as? String
                    if (selected != null) {
                        if (
                            selected !in initialState ||
                                initialState[selected] != null && initialState[selected] !is String
                        )
                            fail(
                                path,
                                "STATE",
                                "Record action needs a declared string/null selection state",
                            )
                    } else if (
                        rowScope == null ||
                            pageLists.none { it.id == rowScope?.list && it.source == key }
                    )
                        fail(
                            path,
                            "CAPABILITY",
                            "Record action needs a matching list row or an explicit selection state",
                        )
                    Values.map(m.filterKeys { it in setOf("field", "state") })
                } else Values.map(obj(m["args"] ?: emptyMap<String, Any?>(), path))
            if (type == "kether") script(key, path)
            if (type == "provider") extension("action", key, args, path)
            return ActionSpec(
                type,
                key,
                Values.freeze(if (type == "page-list") m["step"] else m["value"]),
                m["binding"]?.let { binding(obj(it, path), path) },
                args,
            )
        }

        fun condition(m: Map<String, Any?>, path: String, depth: Int): ConditionSpec {
            if (depth > 16) fail(path, "CONDITION_DEPTH", "Condition nesting exceeds 16")
            val type =
                m["type"] as? String ?: fail(path, "REQUIRED_FIELD", "Missing condition type")
            Contract.check(
                m,
                Contract.conditions[type] ?: fail(path, "UNKNOWN_TYPE", "Unknown condition $type"),
                file,
                path,
            )
            val children =
                Values.list(
                    when (type) {
                        "all",
                        "any" ->
                            list(m["conditions"], "$path.conditions").mapIndexed { i, value ->
                                condition(
                                    obj(value, "$path.conditions[$i]"),
                                    "$path.conditions[$i]",
                                    depth + 1,
                                )
                            }
                        "not" ->
                            listOf(
                                condition(
                                    obj(m["condition"], "$path.condition"),
                                    "$path.condition",
                                    depth + 1,
                                )
                            )
                        else -> emptyList()
                    }
                )
            val key =
                when (type) {
                    "state-equals" -> m["state"]
                    "provider" -> m["provider"]
                    "kether" -> m["script"]
                    "permission" -> m["permission"]
                    else -> null
                }
                    as? String ?: ""
            val args = Values.map(obj(m["args"] ?: emptyMap<String, Any?>(), path))
            if (type == "kether") script(key, path)
            if (type == "provider") extension("condition", key, args, path)
            if (
                type in setOf("equals", "at-least") &&
                    (m.containsKey("value") == m.containsKey("compare"))
            )
                fail(path, "CONDITION", "$type requires exactly one of value/compare")
            return ConditionSpec(
                type,
                key,
                Values.freeze(m["value"]),
                args,
                children,
                m["binding"]?.let { binding(obj(it, "$path.binding"), "$path.binding") },
                m["compare"]?.let { binding(obj(it, "$path.compare"), "$path.compare") },
            )
        }
    }
}
