package com.hiusers.rafter.asset

import com.hiusers.rafter.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 仅覆盖已适配格式的 GUI 管线，公用资源与版本覆盖分离。 */
object DialogFocusShaders {
    const val MAX_GEOMETRIES = 128

    val profiles: List<ClientRenderProfile> = ClientRenderProfiles.all

    private fun template(name: String) =
        DialogFocusShaders::class
            .java
            .getResourceAsStream("/shaders/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() } ?: error("Missing bundled GUI shader $name")

    private val templates by lazy {
        listOf("gui.vsh", "gui.fsh", "dialog_focus.glsl").associateWith(::template)
    }

    private fun sortedVectors(values: List<List<Int>>) =
        values
            .distinct()
            .sortedWith(
                compareBy<List<Int>> { it[0] }.thenBy { it[1] }.thenBy { it[2] }.thenBy { it[3] }
            )

    private fun vectors(catalog: AssetCatalog) =
        if (catalog.hideFocusOutline) sortedVectors(catalog.focusLayouts.map { it.vector() })
        else emptyList()

    private fun nativeVectors(catalog: AssetCatalog) =
        if (catalog.hideNativeFocusOutline)
            sortedVectors(catalog.nativeFocusLayouts.map { it.vector() })
        else emptyList()

    fun active(catalog: AssetCatalog) =
        catalog.hideFocusOutline && catalog.focusLayouts.isNotEmpty() ||
            catalog.hideNativeFocusOutline && catalog.nativeFocusLayouts.isNotEmpty()

    fun bind(catalog: AssetCatalog, snapshot: MenuSnapshot): AssetCatalog {
        val layouts =
            snapshot.menus.toSortedMap().flatMap { (menuId, menu) ->
                // 固定尾高只用于纯画布正文，不用于原生表单流。
                menu.pages
                    .toSortedMap()
                    .values
                    .filter {
                        it.spec.mode == PageMode.CANVAS &&
                            it.spec.components.isEmpty() &&
                            it.scene.none { placed -> placed.node.content is NodeContent.Item }
                    }
                    .map { page ->
                        CanvasFocusLayout(
                            menuId,
                            page.spec.id,
                            CanvasGeometry.bodyWidth(page.spec.width),
                            CanvasGeometry.bodyHeight(page.height),
                        )
                    }
            }
        val native =
            snapshot.menus.toSortedMap().flatMap { (menuId, menu) ->
                menu.pages.toSortedMap().values.flatMap { page ->
                    // 标题背景下的画布正文仍参与原生焦点流。
                    // 只匹配正文，不假定混合页尾部高度。
                    val canvasBody =
                        if (
                            (page.spec.frame != null || page.spec.modal != null) &&
                                page.spec.mode == PageMode.CANVAS
                        ) {
                            listOf(
                                NativeFocusLayout(
                                    menuId,
                                    page.spec.id,
                                    "@canvas-body",
                                    NativeFocusKind.TEXT,
                                    CanvasGeometry.bodyWidth(page.spec.width),
                                    0,
                                    0,
                                )
                            )
                        } else emptyList()
                    canvasBody +
                        page.spec.components.flatMap { node ->
                            fun layout(
                                kind: NativeFocusKind,
                                width: Int,
                                height: Int = 0,
                                offset: Int = 0,
                            ) =
                                NativeFocusLayout(
                                    menuId,
                                    page.spec.id,
                                    node.id,
                                    kind,
                                    width,
                                    height,
                                    offset,
                                )
                            when (node.type) {
                                "text",
                                "image" ->
                                    listOf(
                                        layout(
                                            NativeFocusKind.TEXT,
                                            (node.width ?: page.spec.width) +
                                                if (node.type == "text" && page.spec.modal != null)
                                                    NativeGeometry.TEXT_PADDING * 2
                                                else 0,
                                        )
                                    )
                                "item" -> {
                                    val description =
                                        node.width ?: NativeGeometry.ITEM_DESCRIPTION_WIDTH
                                    listOf(
                                        layout(
                                            NativeFocusKind.ITEM,
                                            NativeGeometry.ITEM_WIDTH,
                                            NativeGeometry.ITEM_HEIGHT,
                                            -(description + NativeGeometry.ITEM_DESCRIPTION_GAP),
                                        ),
                                        layout(
                                            NativeFocusKind.TEXT,
                                            description,
                                            offset =
                                                NativeGeometry.ITEM_WIDTH +
                                                    NativeGeometry.ITEM_DESCRIPTION_GAP,
                                        ),
                                    )
                                }
                                else -> emptyList() // 不登记输入、复选、滑条或按钮，保留原生交互反馈。
                            }
                        }
                }
            }
        val next =
            catalog.copy(
                focusLayouts = Values.list(layouts),
                nativeFocusLayouts = Values.list(native),
            )
        checkBudget(next)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(catalog.sourceFingerprint.toByteArray(Charsets.UTF_8))
        hash.update(
            Json.gson
                .toJson(
                    mapOf(
                        "canvas-enabled" to catalog.hideFocusOutline,
                        "native-enabled" to catalog.hideNativeFocusOutline,
                        "layouts" to layouts.map { it.manifest() },
                        "native-layouts" to native.map { it.manifest() },
                    )
                )
                .toByteArray(Charsets.UTF_8)
        )
        if (active(next)) {
            hash.update(
                Json.gson.toJson(profiles.map { it.manifest() }).toByteArray(Charsets.UTF_8)
            )
            templates.toSortedMap().values.forEach { hash.update(it.toByteArray(Charsets.UTF_8)) }
        }
        return next.copy(fingerprint = hash.digest().joinToString("") { "%02x".format(it) })
    }

    internal fun checkBudget(catalog: AssetCatalog) {
        if (active(catalog) && vectors(catalog).size + nativeVectors(catalog).size > MAX_GEOMETRIES)
            throw DocumentError(
                Diagnostic(
                    com.hiusers.rafter.model.ProjectPaths.UI + "/",
                    if (catalog.hideNativeFocusOutline && catalog.nativeFocusLayouts.isNotEmpty())
                        "$.hide-native-focus-outline"
                    else "$.hide-canvas-focus-outline",
                    "FOCUS_GEOMETRY_LIMIT",
                    "At most $MAX_GEOMETRIES distinct focus geometries; disable the relevant filter to omit its GUI matches",
                )
            )
    }

    internal fun fingerprint(hash: MessageDigest, catalog: AssetCatalog) {
        hash.update(Json.gson.toJson(manifest(catalog)).toByteArray(Charsets.UTF_8))
        if (active(catalog))
            templates.toSortedMap().values.forEach { hash.update(it.toByteArray(Charsets.UTF_8)) }
    }

    fun manifest(catalog: AssetCatalog): Map<String, Any?> =
        mapOf(
            "enabled" to (catalog.hideFocusOutline || catalog.hideNativeFocusOutline),
            "canvas-enabled" to catalog.hideFocusOutline,
            "native-enabled" to catalog.hideNativeFocusOutline,
            "active" to active(catalog),
            "matching" to "compiled-body-geometry",
            "max-geometries" to MAX_GEOMETRIES,
            "header-height" to CanvasGeometry.HEADER_HEIGHT,
            "preferred-body-top" to CanvasGeometry.PREFERRED_BODY_TOP,
            "quad-recovery" to "affine-jacobian",
            "centering" to "native-integer-truncation",
            "layouts" to catalog.focusLayouts.map { it.manifest() },
            "native-layouts" to catalog.nativeFocusLayouts.map { it.manifest() },
            "profiles" to if (active(catalog)) profiles.map { it.manifest() } else emptyList<Any>(),
        )

    fun packMetadata(catalog: AssetCatalog): Map<String, Any?> {
        val metadata =
            linkedMapOf<String, Any?>(
                "pack" to
                    mapOf(
                        "pack_format" to profiles.first().min[0],
                        "supported_formats" to
                            listOf(profiles.first().min[0], profiles.last().max[0]),
                        "min_format" to profiles.first().min,
                        "max_format" to profiles.last().max,
                        "description" to "Rafter Dialog menus",
                    )
            )
        val enabled = profiles.filter {
            active(catalog) ||
                catalog.canvasItems.isNotEmpty() ||
                ((catalog.dialogFrames.isNotEmpty() ||
                    catalog.modalFrames.isNotEmpty() ||
                    catalog.modalBackdrops.isNotEmpty()) &&
                    it in DialogFrameShaders.supportedProfiles)
        }
        if (enabled.isNotEmpty())
            metadata["overlays"] = mapOf("entries" to enabled.map { it.overlay() })
        return metadata
    }

    private fun expand(source: String, values: Map<String, String>): String {
        val result =
            values.entries.fold(source) { text, (key, value) -> text.replace("@$key@", value) }
        require(!Regex("@[A-Z_]+@").containsMatchIn(result)) { "Unexpanded GUI shader token" }
        return result
    }

    fun write(stage: Path, catalog: AssetCatalog) {
        if (!active(catalog)) return
        val geometry = vectors(catalog)
        val native = nativeVectors(catalog)
        // 画布宽度按像素计算，仅垂直传输行需对齐。
        require(
            geometry.size + native.size <= MAX_GEOMETRIES &&
                geometry.all { v ->
                    v[0] in CanvasGeometry.bodyWidth(90)..CanvasGeometry.bodyWidth(864) &&
                        v[1] in
                            CanvasGeometry.bodyHeight(CanvasGeometry.ROW_HEIGHT)..CanvasGeometry
                                    .bodyHeight(
                                        com.hiusers.rafter.compile.LayoutCompiler.MAX_HEIGHT
                                    ) &&
                        (v[1] - CanvasGeometry.bodyHeight(0)) % CanvasGeometry.ROW_HEIGHT == 0 &&
                        v[2] == CanvasGeometry.EMPTY_FOOTER_HEIGHT &&
                        v[3] == CanvasGeometry.BODY_ACTION_TAIL
                }
        ) {
            "Invalid compiled Canvas focus geometry"
        }
        require(
            native.all { v ->
                when (v[3]) {
                    // 正文范围包含换行余量与内边距，如 864 映射为 874。
                    NativeFocusKind.TEXT.code ->
                        v[0] in 1..CanvasGeometry.bodyWidth(864) &&
                            v[1] == 0 &&
                            v[2] in
                                setOf(
                                    0,
                                    NativeGeometry.ITEM_WIDTH + NativeGeometry.ITEM_DESCRIPTION_GAP,
                                )
                    NativeFocusKind.CARRIER_ROW.code ->
                        v[0] == CanvasItems.MAX_CHANNELS * 2 - 1 && v[1] == 1 && v[2] == 0
                    NativeFocusKind.ITEM.code ->
                        v[0] in 1..256 &&
                            v[1] in 1..256 &&
                            (v[2] == 0 ||
                                v[0] == NativeGeometry.ITEM_WIDTH &&
                                    v[1] == NativeGeometry.ITEM_HEIGHT &&
                                    v[2] in
                                        -(864 + NativeGeometry.ITEM_DESCRIPTION_GAP)..-(1 +
                                                NativeGeometry.ITEM_DESCRIPTION_GAP))
                    else -> false
                }
            }
        ) {
            "Invalid compiled Native focus geometry"
        }
        fun table(name: String, rows: List<List<Int>>): String {
            // GLSL 不允许空数组；数量为零时不匹配占位项。
            val storage = rows.ifEmpty { listOf(listOf(0, 0, 0, 0)) }
            return "const int ${name}_COUNT = ${rows.size};\nconst vec4 ${name}S[${storage.size}] = vec4[${storage.size}](\n" +
                storage.joinToString(",\n") {
                    "    vec4(" + it.joinToString(", ") { n -> "$n.0" } + ")"
                } +
                "\n);"
        }
        val common =
            expand(
                templates.getValue("dialog_focus.glsl"),
                mapOf(
                    "LAYOUTS" to table("RAFTER_FOCUS_LAYOUT", geometry),
                    "NATIVE_LAYOUTS" to table("RAFTER_NATIVE_FOCUS_LAYOUT", native),
                    "NATIVE_LINE_HEIGHT" to "${NativeGeometry.LINE_HEIGHT}.0",
                    "NATIVE_TEXT_PADDING" to "${NativeGeometry.TEXT_PADDING * 2}.0",
                    "HEADER_HEIGHT" to "${CanvasGeometry.HEADER_HEIGHT}.0",
                    "PREFERRED_TOP" to "${CanvasGeometry.PREFERRED_BODY_TOP}.0",
                ),
            )
        val include = stage.resolve("assets/rafter/shaders/include/dialog_focus.glsl")
        Files.createDirectories(include.parent)
        Files.writeString(include, common)
        profiles.forEach { profile ->
            val header =
                "#version ${profile.glsl}" +
                    if (profile.vulkanInterface)
                        "\n#extension GL_ARB_separate_shader_objects : require"
                    else ""
            val transforms =
                "layout(std140) uniform DynamicTransforms {\n    mat4 ModelViewMat;\n" +
                    (if (profile.vulkanInterface)
                        "    mat4 TextureMat;\n    vec4 ColorModulator;\n    vec3 ModelOffset;\n"
                    else
                        "    vec4 ColorModulator;\n    vec3 ModelOffset;\n    mat4 TextureMat;\n") +
                    (if (profile.lineWidth) "    float LineWidth;\n" else "") +
                    "};"
            fun field(
                type: String,
                name: String,
                direction: String,
                location: Int,
                flat: Boolean = false,
            ) =
                (if (profile.vulkanInterface) "layout(location = $location) " else "") +
                    (if (flat) "flat " else "") +
                    "$direction $type $name;"
            fun shared(direction: String) =
                listOf(
                        field("vec4", "vertexColor", direction, 0),
                        field("vec2", "rafterGuiPosition", direction, 1),
                        field("vec2", "rafterQuad", direction, 2),
                        field("vec2", "rafterViewport", direction, 3, true),
                    )
                    .joinToString("\n")
            val index =
                if (profile.vulkanInterface)
                    "#ifdef VULKAN\n    int corner = gl_VertexIndex % 4;\n#else\n    int corner = gl_VertexID % 4;\n#endif"
                else "    int corner = gl_VertexID % 4;"
            val base = mapOf("HEADER" to header, "TRANSFORMS" to transforms)
            val vertex =
                expand(
                    templates.getValue("gui.vsh"),
                    base +
                        mapOf(
                            "INPUTS" to
                                listOf(
                                        field("vec3", "Position", "in", 0),
                                        field("vec4", "Color", "in", 1),
                                    )
                                    .joinToString("\n"),
                            "OUTPUTS" to shared("out"),
                            "CORNER_INDEX" to index,
                        ),
                )
            val fragment =
                expand(
                    templates.getValue("gui.fsh"),
                    base +
                        mapOf(
                            "INPUTS" to shared("in"),
                            "OUTPUTS" to field("vec4", "fragColor", "out", 0),
                        ),
                )
            val core = stage.resolve("${profile.directory}/assets/minecraft/shaders/core")
            Files.createDirectories(core)
            Files.writeString(core.resolve("gui.vsh"), vertex)
            Files.writeString(core.resolve("gui.fsh"), fragment)
        }
    }
}
