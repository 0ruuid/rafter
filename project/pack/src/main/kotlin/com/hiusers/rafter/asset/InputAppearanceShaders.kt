package com.hiusers.rafter.asset

import java.nio.file.Files
import java.nio.file.Path

/** 只为已核对的 26.2 GUI overlay 注入原生输入外观。 */
object InputAppearanceShaders {
    private val profile = ClientRenderProfiles.all.single { it.directory == "rafter_v260200" }

    fun manifest(catalog: AssetCatalog): Map<String, Any?> =
        mapOf(
            "enabled" to catalog.inputAppearances.isNotEmpty(),
            "revision" to InputAppearances.REVISION,
            "profile" to "26.2",
            "native-texture" to "not-replaced",
            "clock-marker" to
                mapOf(
                    "world-age" to "-256*layoutId",
                    "layout-id-range" to "1..64",
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
        val folder = stage.resolve("${profile.directory}/assets/minecraft/shaders/core")
        Files.createDirectories(folder)
        val regionCode = regionCode(catalog.inputAppearances)
        val textVertex = readFinal(stage, "text.vsh")
        val textFragment = readFinal(stage, "text.fsh")
        val positionVertex = readFinal(stage, "position_tex_color.vsh")
        val positionFragment = readFinal(stage, "position_tex_color.fsh")
        Files.writeString(folder.resolve("text.vsh"), injectVertex(textVertex, true))
        Files.writeString(
            folder.resolve("text.fsh"),
            injectTextFragment(textFragment, regionCode),
        )
        Files.writeString(
            folder.resolve("position_tex_color.vsh"),
            injectVertex(positionVertex, false),
        )
        Files.writeString(
            folder.resolve("position_tex_color.fsh"),
            injectPositionFragment(positionFragment, regionCode),
        )
    }

    private fun readFinal(stage: Path, name: String): String {
        val path = stage.resolve("${profile.directory}/assets/minecraft/shaders/core/$name")
        return if (Files.isRegularFile(path)) Files.readString(path)
        else ClientShaderSources.read(profile, name)
    }

    private fun injectVertex(source: String, textProgram: Boolean): String {
        require(source.count { it == '\n' } > 0) { "Empty 26.2 shader source" }
        val guard = if (textProgram) "#if defined(IS_GUI)" else "#if 1"
        val declarations =
            """
            $guard
            out vec2 rafterInputPoint;
            flat out vec2 rafterInputViewport;
            #endif
            """.trimIndent()
        require(source.split("void main() {").size == 2) {
            "Unexpected 26.2 vertex shader main anchor"
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
        val projection =
            listOf(
                    "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);",
                    "    gl_Position = ProjMat * position;",
                )
                .single { withDeclarations.split(it).size == 2 }
        return withDeclarations.replaceFirst(projection, "$projection\n$body")
    }

    private fun injectTextFragment(source: String, regionCode: String): String {
        val declarations = fragmentDeclarations(regionCode, true)
        val anchor = "#elif defined(IS_GUI)\n    fragColor = color;"
        require(source.split(anchor).size == 2) {
            "Unexpected 26.2 text fragment GUI anchor"
        }
        val replacement =
            """
            #elif defined(IS_GUI)
                int appearanceRegion = rafterInputAppearanceRegion();
                vec3 appearanceColor;
                if (appearanceRegion >= 0 &&
                    rafterInputAppearanceTextColor(appearanceRegion, appearanceColor)) {
                    bool inputInk = all(lessThan(abs(vertexColor.rgb - vec3(224.0 / 255.0)), vec3(0.001)));
                    bool inputShadow = all(lessThan(abs(vertexColor.rgb - vec3(56.0 / 255.0)), vec3(0.001)));
                    if (inputShadow) discard;
                    if (inputInk) color.rgb = texColor.rgb * appearanceColor * ColorModulator.rgb;
                }
                fragColor = color;
            """.trimIndent()
        return source
            .replaceFirst("void main() {", "$declarations\nvoid main() {")
            .replaceFirst(anchor, replacement)
    }

    private fun injectPositionFragment(source: String, regionCode: String): String {
        val declarations = fragmentDeclarations(regionCode, false)
        val anchor = "    fragColor = color * ColorModulator;"
        require(source.split(anchor).size == 2) {
            "Unexpected 26.2 position fragment output anchor"
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

    private fun fragmentDeclarations(regionCode: String, textProgram: Boolean): String {
        val guard = if (textProgram) "#if defined(IS_GUI)" else "#if 1"
        return """
        $guard
        layout(std140) uniform Globals {
            ivec3 CameraBlockPos;
            vec3 CameraOffset;
            vec2 ScreenSize;
            float GlintAlpha;
            float GameTime;
            int MenuBlurRadius;
            int UseRgss;
        };
        in vec2 rafterInputPoint;
        flat in vec2 rafterInputViewport;
        $regionCode
        #endif
        """.trimIndent()
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
                int layoutId = int(floor(age / 256.0 + 0.5));
                if (layoutId < 1 || layoutId > 64 || abs(age - float(layoutId * 256)) > 12.0) return -1;
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
