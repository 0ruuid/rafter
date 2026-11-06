package com.hiusers.rafter.asset

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 按已声明的客户端接口注入输入外观，保留候选包中的面板与物品 Shader 修改。 */
object InputAppearanceShaders {
    fun fingerprint(hash: MessageDigest) {
        hash.update(InputAppearances.REVISION.toByteArray(Charsets.UTF_8))
        ClientRenderProfiles.all.forEach { profile ->
            hash.update(profile.directory.toByteArray(Charsets.UTF_8))
            hash.update(
                ClientShaderSources
                    .read(profile, "include/globals.glsl")
                    .toByteArray(Charsets.UTF_8)
            )
        }
    }

    fun manifest(catalog: AssetCatalog): Map<String, Any?> =
        mapOf(
            "enabled" to catalog.inputAppearances.isNotEmpty(),
            "revision" to InputAppearances.REVISION,
            "capability" to
                mapOf(
                    "supported-clients" to ClientRenderProfiles.all.flatMap { it.clients },
                    "profiles" to ClientRenderProfiles.all.map { it.manifest() },
                    "compiled-layouts" to InputAppearances.MAX_LAYOUTS,
                    "scope" to "modal",
                    "selection" to "per-input-appearance",
                    "requires-loaded-resource-pack" to true,
                    "requires-client-gametime-marker" to true,
                ),
            "profiles" to ClientRenderProfiles.all.map { it.manifest() },
            "native-texture" to "not-replaced",
            "clock-marker" to
                mapOf(
                    "world-age" to "-${InputAppearances.MARKER_UNIT_TICKS}*layoutId",
                    "layout-id-range" to "1..${InputAppearances.MAX_LAYOUTS}",
                    "shader-age" to "-GameTime*24000",
                    "tolerance" to 12,
                ),
            "regions" to
                catalog.inputAppearances.flatMap { layout ->
                    layout.inputs.map { region ->
                        mapOf(
                            "layout" to layout.key,
                            "layout-id" to layout.id,
                            "body-width" to layout.bodyWidth,
                            "body-height" to layout.bodyHeight,
                            "bounds" to region.bounds,
                            "appearance" to region.appearance,
                        )
                    }
                },
        )

    fun write(stage: Path, catalog: AssetCatalog) {
        if (catalog.inputAppearances.isEmpty()) return
        val regionCode = regionCode(catalog.inputAppearances)
        ClientRenderProfiles.all.forEach { profile ->
            val folder = stage.resolve("${profile.directory}/assets/minecraft/shaders/core")
            Files.createDirectories(folder)

            profile.textPrograms.forEach { program ->
                val textVertex = readFinal(stage, profile, "$program.vsh")
                val textFragment = readFinal(stage, profile, "$program.fsh")
                Files.writeString(
                    folder.resolve("$program.vsh"),
                    injectVertex(textVertex, profile, textProgram = true),
                )
                Files.writeString(
                    folder.resolve("$program.fsh"),
                    injectTextFragment(textFragment, profile, regionCode),
                )
            }

            val positionVertex = readFinal(stage, profile, "position_tex_color.vsh")
            val positionFragment = readFinal(stage, profile, "position_tex_color.fsh")
            Files.writeString(
                folder.resolve("position_tex_color.vsh"),
                injectVertex(positionVertex, profile, textProgram = false),
            )
            Files.writeString(
                folder.resolve("position_tex_color.fsh"),
                injectPositionFragment(positionFragment, profile, regionCode),
            )
        }
    }

    private fun readFinal(stage: Path, profile: ClientRenderProfile, name: String): String {
        val path = stage.resolve("${profile.directory}/assets/minecraft/shaders/core/$name")
        return if (Files.isRegularFile(path)) Files.readString(path)
        else ClientShaderSources.read(profile, name)
    }

    private fun varying(
        profile: ClientRenderProfile,
        direction: String,
        location: Int,
        type: String,
        name: String,
        flat: Boolean = false,
    ): String =
        (if (profile.vulkanInterface) "layout(location = $location) " else "") +
            (if (flat) "flat " else "") +
            "$direction $type $name;"

    private fun injectVertex(
        source: String,
        profile: ClientRenderProfile,
        textProgram: Boolean,
    ): String {
        require(source.count { it == '\n' } > 0) {
            "Empty ${profile.baseline} shader source"
        }
        val guard = if (textProgram && profile.unifiedText) "#if defined(IS_GUI)" else "#if 1"
        val pointLocation = if (textProgram) 8 else 6
        val viewportLocation = if (textProgram) 9 else 7
        val declarations =
            """
            $guard
            ${varying(profile, "out", pointLocation, "vec2", "rafterInputPoint")}
            ${varying(profile, "out", viewportLocation, "vec2", "rafterInputViewport", flat = true)}
            #endif
            """.trimIndent()
        require(source.split("void main() {").size == 2) {
            "Unexpected ${profile.baseline} vertex shader main anchor"
        }
        val withDeclarations = source.replace("void main() {", "$declarations\nvoid main() {")
        val body =
            """
            $guard
                if (abs(ProjMat[3][3] - 1.0) > 0.0001 || abs(gl_Position.w) < 0.0001) {
                    rafterInputPoint = vec2(0.0);
                    rafterInputViewport = vec2(0.0);
                } else {
                    vec4 rafterInputPosition = ModelViewMat * vec4(Position, 1.0);
                    rafterInputPoint = rafterInputPosition.xy;
                    rafterInputViewport = round(abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1])));
                }
            #endif
            """.trimIndent()
        val projections =
            listOf(
                "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);",
                "    gl_Position = ProjMat * position;",
            )
        val projection = projections.singleOrNull { withDeclarations.split(it).size == 2 }
        requireNotNull(projection) {
            "Unexpected ${profile.baseline} vertex shader projection anchor"
        }
        return withDeclarations.replaceFirst(projection, "$projection\n$body")
    }

    private fun injectTextFragment(
        source: String,
        profile: ClientRenderProfile,
        regionCode: String,
    ): String {
        val declarations = fragmentDeclarations(profile, regionCode, textProgram = true)
        val withDeclarations =
            source.replaceFirst("void main() {", "$declarations\nvoid main() {")
        return if (!profile.unifiedText) {
            injectLegacyTextFragment(withDeclarations, profile)
        } else if (source.contains("calculateFinalColor(color)")) {
            injectUnifiedOitTextFragment(withDeclarations, profile)
        } else {
            injectUnifiedTextFragment(withDeclarations, profile)
        }
    }

    private fun injectLegacyTextFragment(source: String, profile: ClientRenderProfile): String {
        val fogOutput =
            "    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, " +
                "FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, " +
                "FogRenderDistanceEnd, FogColor);"
        val appearance = appearanceBlock("color")
        if (source.lastIndexOf(fogOutput) >= 0) {
            return replaceLast(source, fogOutput, "$appearance\n$fogOutput")
        }

        val colorOutput = "    fragColor = color * ColorModulator;"
        require(source.lastIndexOf(colorOutput) >= 0) {
            "Unexpected ${profile.baseline} legacy text fragment output anchor"
        }
        return replaceLast(source, colorOutput, "$appearance\n$colorOutput")
    }

    private fun injectUnifiedTextFragment(
        source: String,
        profile: ClientRenderProfile,
    ): String {
        val anchor = "#elif defined(IS_GUI)\n    fragColor = color;"
        require(source.split(anchor).size == 2) {
            "Unexpected ${profile.baseline} unified text GUI output anchor"
        }
        return source.replaceFirst(
            anchor,
            """
            #elif defined(IS_GUI)
                ${appearanceBlock("color")}
                fragColor = color;
            """.trimIndent(),
        )
    }

    private fun injectUnifiedOitTextFragment(
        source: String,
        profile: ClientRenderProfile,
    ): String {
        val anchor = "    vec4 color = texColor * vertexColor * ColorModulator;\n"
        require(source.split(anchor).size == 2) {
            "Unexpected ${profile.baseline} OIT text color anchor"
        }
        return source.replaceFirst(
            anchor,
            anchor +
                """
                #if defined(IS_GUI)
                    ${appearanceBlock("color")}
                #endif
                """.trimIndent() +
                "\n",
        )
    }

    private fun injectPositionFragment(
        source: String,
        profile: ClientRenderProfile,
        regionCode: String,
    ): String {
        val declarations = fragmentDeclarations(profile, regionCode, textProgram = false)
        val anchor = "    fragColor = color * ColorModulator;"
        require(source.split(anchor).size == 2) {
            "Unexpected ${profile.baseline} position fragment output anchor"
        }
        val replacement =
            """
            int appearanceRegion = rafterInputAppearanceRegion();
            if (appearanceRegion >= 0 && rafterInputAppearanceTransparent(appearanceRegion)) discard;
            $anchor
            """.trimIndent()
        return source
            .replaceFirst("void main() {", "$declarations\nvoid main() {")
            .replaceFirst(anchor, replacement)
    }

    private fun appearanceBlock(target: String): String =
        """
        int appearanceRegion = rafterInputAppearanceRegion();
        vec3 appearanceColor;
        if (appearanceRegion >= 0 &&
            rafterInputAppearanceTextColor(appearanceRegion, appearanceColor)) {
            bool inputInk = all(lessThan(abs(vertexColor.rgb - vec3(224.0 / 255.0)), vec3(0.001)));
            bool inputShadow = all(lessThan(abs(vertexColor.rgb - vec3(56.0 / 255.0)), vec3(0.001)));
            if (inputShadow) discard;
            if (inputInk) $target.rgb *= appearanceColor / vec3(224.0 / 255.0);
        }
        """.trimIndent()

    private fun fragmentDeclarations(
        profile: ClientRenderProfile,
        regionCode: String,
        textProgram: Boolean,
    ): String {
        val guard = if (textProgram && profile.unifiedText) "#if defined(IS_GUI)" else "#if 1"
        val pointLocation = if (textProgram) 8 else 6
        val viewportLocation = if (textProgram) 9 else 7
        val globals =
            ClientShaderSources
                .read(profile, "include/globals.glsl")
                .replaceFirst(Regex("^#version\\s+\\d+\\s*\\n"), "")
                .trim()
        return """
        $guard
        $globals
        ${varying(profile, "in", pointLocation, "vec2", "rafterInputPoint")}
        ${varying(profile, "in", viewportLocation, "vec2", "rafterInputViewport", flat = true)}
        $regionCode
        #endif
        """.trimIndent()
    }

    private fun replaceLast(source: String, anchor: String, replacement: String): String {
        val index = source.lastIndexOf(anchor)
        require(index >= 0) { "Missing shader output anchor: $anchor" }
        return source.substring(0, index) + replacement + source.substring(index + anchor.length)
    }

    private fun regionCode(layouts: List<InputAppearanceLayout>): String {
        val regions =
            layouts.flatMap { layout ->
                layout.inputs.map { layout.id to it }
            }
        val starts = buildMap {
            var start = 0
            layouts.forEach { layout ->
                put(layout.id, start)
                start += layout.inputs.size
            }
        }
        val matchers =
            layouts.joinToString("\n") { layout ->
                val cases =
                    layout.inputs.mapIndexed { index, region ->
                        val bounds = region.bounds
                        val regionIndex = starts.getValue(layout.id) + index
                        """
                        if (rafterInputPoint.x >= origin.x + ${bounds.x}.0 &&
                            rafterInputPoint.x < origin.x + ${bounds.x + bounds.width}.0 &&
                            rafterInputPoint.y >= origin.y + ${bounds.y}.0 &&
                            rafterInputPoint.y < origin.y + ${bounds.y + bounds.height}.0) return $regionIndex;
                        """.trimIndent()
                    }
                        .joinToString("\n")
                """
                if (layoutId == ${layout.id}) {
                    if (rafterInputViewport.x < ${layout.bodyWidth + 20}.0 ||
                        rafterInputViewport.y < ${layout.bodyHeight + 38}.0) return -1;
                    vec2 origin = vec2(
                        floor((rafterInputViewport.x - ${layout.bodyWidth}.0 - 20.0) * 0.5) + 10.0,
                        min(63.0, rafterInputViewport.y - 5.0 -
                            min(${layout.bodyHeight}.0, max(rafterInputViewport.y - 33.0 - 5.0, 0.0)))
                    );
                    $cases
                }
                """.trimIndent()
            }
        val transparent =
            regions.mapIndexedNotNull { index, (_, region) ->
                if (region.appearance.background == "transparent")
                    "if (region == $index) return true;"
                else null
            }
        val textColors =
            regions.mapIndexedNotNull { index, (_, region) ->
                region.appearance.textColor?.let {
                    val rgb = it.removePrefix("#").chunked(2).joinToString(", ") { byte ->
                        "${byte.toInt(16)}.0 / 255.0"
                    }
                    "if (region == $index) { color = vec3($rgb); return true; }"
                }
            }
        return """
            int rafterInputAppearanceRegion() {
                float age = -GameTime * 24000.0;
                int layoutId = int(floor(age / ${InputAppearances.MARKER_UNIT_TICKS}.0 + 0.5));
                if (layoutId < 1 || layoutId > ${InputAppearances.MAX_LAYOUTS} ||
                    abs(age - float(layoutId * ${InputAppearances.MARKER_UNIT_TICKS})) > 12.0) return -1;
                $matchers
                return -1;
            }
            bool rafterInputAppearanceTransparent(int region) {
                ${transparent.joinToString("\n")}
                return false;
            }
            bool rafterInputAppearanceTextColor(int region, out vec3 color) {
                color = vec3(0.0);
                ${textColors.joinToString("\n")}
                return false;
            }
        """.trimIndent()
    }
}
