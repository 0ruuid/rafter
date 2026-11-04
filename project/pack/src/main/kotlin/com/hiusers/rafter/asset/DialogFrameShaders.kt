package com.hiusers.rafter.asset

import com.hiusers.rafter.model.CanvasGeometry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 仅适配标题背景与原生模态页，其他格式保留普通文本和标题回退。 */
object DialogFrameShaders {
    val supportedPackFormats: Set<Pair<Int, Int>> = ClientRenderProfiles.packFormats

    fun supportsPackFormat(major: Int, minor: Int): Boolean =
        ClientRenderProfiles.supportsPackFormat(major, minor)

    val supportedProfiles: List<ClientRenderProfile> = ClientRenderProfiles.all

    private fun resource(name: String) =
        DialogFrameShaders::class
            .java
            .getResourceAsStream("/shaders/dialog_frame/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() } ?: error("Missing bundled Dialog frame shader $name")

    private val templates by lazy {
        listOf("frame_vertex.glsl", "frame_fragment.glsl").associateWith(::resource)
    }

    private fun active(catalog: AssetCatalog) =
        catalog.dialogFrames.isNotEmpty() ||
            catalog.modalFrames.isNotEmpty() ||
            catalog.modalBackdrops.isNotEmpty()

    private fun table(name: String, rows: List<List<Int>>): String {
        // 空集合使用不可命中的占位项，避免零长 GLSL 数组。
        val storage = rows.ifEmpty { listOf(listOf(0, 0, 0, 0)) }
        return "const int ${name}_COUNT = ${rows.size};\nconst vec4 ${name}S[${storage.size}] = vec4[${storage.size}](\n" +
            storage.joinToString(",\n") {
                "    vec4(" + it.joinToString(", ") { n -> "$n.0" } + ")"
            } +
            "\n);"
    }

    internal fun vertexInclude(
        layouts: List<NativeModalLayout>,
        backdrops: List<ModalBackdropLayout>,
    ): String {
        val constants =
            mapOf(
                    "X_BASE" to ModalEncoding.X_BASE,
                    "ROW_STRIDE" to ModalEncoding.ROW_STRIDE,
                    "FONT_Y_SHIFT" to ModalEncoding.FONT_Y_SHIFT,
                    "TITLE_GROUP_WIDTH" to ModalEncoding.TITLE_GROUP_WIDTH,
                    "TITLE_Y" to ModalEncoding.TITLE_Y,
                    "ROW_HEIGHT" to CanvasGeometry.ROW_HEIGHT,
                    "TEXT_PADDING" to CanvasGeometry.TEXT_PADDING,
                    "WRAP_SLACK" to CanvasGeometry.WRAP_SLACK,
                    "HEADER_HEIGHT" to CanvasGeometry.HEADER_HEIGHT,
                    "PREFERRED_TOP" to CanvasGeometry.PREFERRED_BODY_TOP,
                    "FOOTER_HEIGHT" to CanvasGeometry.EMPTY_FOOTER_HEIGHT,
                )
                .entries
                .joinToString("\n") { (name, value) ->
                    "const float RAFTER_MODAL_$name = $value.0;"
                }
        return table(
            "RAFTER_MODAL_LAYOUT",
            layouts.map { listOf(it.width, it.height, it.bodyHeight, it.offsetY) },
        ) +
            "\n" +
            table(
                "RAFTER_MODAL_BACKDROP",
                backdrops.mapIndexed { index, backdrop ->
                    listOf(
                        backdrop.bodyWidth,
                        backdrop.bodyHeight,
                        backdrop.layoutHeight,
                        ModalEncoding.xBase(index, backdrops),
                    )
                },
            ) +
            "\n" +
            table(
                "RAFTER_CANVAS_METRIC",
                backdrops.map { listOf(it.containerWidth, it.inset, 0, 0) },
            ) +
            "\n" +
            constants +
            "\n" +
            templates.getValue("frame_vertex.glsl")
    }

    internal fun fingerprint(
        hash: MessageDigest,
        layouts: List<NativeModalLayout> = emptyList(),
        backdrops: List<ModalBackdropLayout> = emptyList(),
    ) {
        hash.update(
            Json.gson.toJson(supportedProfiles.map { it.manifest() }).toByteArray(Charsets.UTF_8)
        )
        hash.update(vertexInclude(layouts, backdrops).toByteArray(Charsets.UTF_8))
        supportedProfiles.forEach { profile ->
            profile.textPrograms.forEach { program ->
                for (extension in listOf("vsh", "fsh")) {
                    hash.update(
                        ClientShaderSources.read(profile, "$program.$extension")
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }
        }
        templates.toSortedMap().forEach { (name, source) ->
            hash.update(name.toByteArray(Charsets.UTF_8))
            hash.update(source.toByteArray(Charsets.UTF_8))
        }
    }

    fun manifest(catalog: AssetCatalog): Map<String, Any?> =
        mapOf(
            "active" to active(catalog),
            "transport" to "title-bitmap-tiles-v2",
            "tile-metadata" to
                listOf(
                    "source-width",
                    "source-height",
                    "tile-x",
                    "tile-y",
                    "tile-width",
                    "tile-height",
                    "kind-0-viewport-1-modal",
                    "modal-layout-index",
                ),
            "runtime-rgb" to listOf("width-low", "width-high", "inset-0..4"),
            "sampling" to "texelFetch-nearest",
            "depth" to "title-Position.z-and-4096px-logical-bounds",
            "profiles" to
                if (!active(catalog)) emptyList<Any>() else supportedProfiles.map { it.manifest() },
            "unsupported-profile-policy" to "ordinary-title-without-marker-glyphs",
            "assets" to catalog.dialogFrames,
            "modal-assets" to catalog.modalFrames,
        )

    internal fun packMetadata(catalog: AssetCatalog): Map<String, Any?> =
        DialogFocusShaders.packMetadata(catalog)

    internal fun sources(profile: ClientRenderProfile): Map<String, String> {
        require(profile in supportedProfiles) {
            "Unverified Dialog frame text ABI: ${profile.directory}"
        }
        val version = profile.baseline
        val include = if (profile.vulkanInterface) "#include" else "#moj_import"
        fun fields(direction: String) =
            listOf(
                    Triple("int", "rafterFrame", true),
                    Triple("ivec2", "rafterFrameOrigin", true),
                    Triple("ivec2", "rafterFrameSize", true),
                    Triple("vec2", "rafterFrameUV", false),
                )
                .mapIndexed { i, (type, name, flat) ->
                    (if (profile.vulkanInterface) "layout(location = ${i + 4}) " else "") +
                        (if (flat) "flat " else "") +
                        "$direction $type $name;"
                }
                .joinToString("\n")
        val guard = if (profile.unifiedText) "#if defined(IS_GUI)" else "#if 1"
        fun insert(source: String, needle: String, replacement: String): String {
            require(source.split(needle).size == 2) {
                "Unexpected vanilla $version shader anchor: $needle"
            }
            return source.replace(needle, replacement)
        }
        val vertexDeclarations =
            "$guard\n${fields("out")}\nuniform sampler2D Sampler0;\n" +
                "$include <rafter:dialog_frame_vertex.glsl>\n#endif\n\n"
        val fragmentDeclarations =
            "$guard\n${fields("in")}\n" + "$include <rafter:dialog_frame_fragment.glsl>\n#endif\n\n"
        return profile.textPrograms
            .flatMap { program ->
                val vertex =
                    insert(
                        insert(
                            ClientShaderSources.read(profile, "$program.vsh"),
                            "void main() {",
                            vertexDeclarations + "void main() {",
                        ),
                        "    texCoord0 = UV0;",
                        "    texCoord0 = UV0;\n$guard\n    rafter_frame_position();\n#endif",
                    )
                val output =
                    if (profile.vulkanInterface)
                        """
                        #ifdef OIT_ALPHA_ONLY
                            executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
                        #else
                            fragColor = calculateFinalColor(color);
                        #endif
                        """
                            .trimIndent()
                    else "fragColor = color;"
                val fragment =
                    insert(
                        ClientShaderSources.read(profile, "$program.fsh"),
                        "void main() {",
                        fragmentDeclarations +
                            """
void main() {
$guard
    if (rafterFrame == 1) {
        vec4 color = rafter_frame_color();
        if (color.a <= 0.0) discard;
$output
        return;
    }
#endif
"""
                                .trimIndent(),
                    )
                listOf("$program.vsh" to vertex, "$program.fsh" to fragment)
            }
            .toMap()
    }

    fun write(stage: Path, catalog: AssetCatalog) {
        if (!active(catalog)) return
        val include = stage.resolve("assets/rafter/shaders/include")
        Files.createDirectories(include)
        Files.writeString(
            include.resolve("dialog_frame_vertex.glsl"),
            vertexInclude(catalog.modalLayouts, catalog.modalBackdrops),
        )
        Files.writeString(
            include.resolve("dialog_frame_fragment.glsl"),
            templates.getValue("frame_fragment.glsl"),
        )
        supportedProfiles.forEach { profile ->
            val core = stage.resolve("${profile.directory}/assets/minecraft/shaders/core")
            Files.createDirectories(core)
            sources(profile).forEach { (name, source) ->
                require(source.startsWith("#version ") && !source.startsWith('\uFEFF'))
                Files.writeString(core.resolve(name), source)
            }
        }
    }
}
