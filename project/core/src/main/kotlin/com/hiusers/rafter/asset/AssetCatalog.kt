package com.hiusers.rafter.asset

import java.nio.file.Path

/** 不可变图片源信息，坐标使用倍率换算前的逻辑像素。 */
data class ImageSpec(
    val id: String,
    val width: Int,
    val height: Int,
    val color: String?,
    val source: String?,
    val scale: Int = 1,
    val contentWidth: Int = width,
    val contentHeight: Int = height,
    val transform: ImageSizing.Plan? = null,
)

data class ImageTile(
    val character: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val advance: Int,
)

data class Glyph(
    val character: String,
    val width: Int,
    val height: Int,
    val advance: Int,
    val tiles: List<ImageTile> = listOf(ImageTile(character, 0, 0, width, height, advance)),
)

/** 只读度量与来源标识，不读取文件或平台状态。 */
data class AssetCatalog(
    val images: Map<String, Glyph>,
    val widths: Map<Int, Double>,
    val allocation: Map<String, Int>,
    val fontSource: Path,
    val imageSpecs: List<ImageSpec>,
    val fingerprint: String,
    val sourceHashes: Map<Path, String>,
    val faces: Map<String, Map<Int, Double>>,
    val theme: UiTheme,
    val hideWarning: Boolean,
    val hideFocusOutline: Boolean,
    val focusLayouts: List<CanvasFocusLayout> = emptyList(),
    val sourceFingerprint: String = fingerprint,
    val hideNativeFocusOutline: Boolean,
    val nativeFocusLayouts: List<NativeFocusLayout> = emptyList(),
    val fontFaces: Map<String, FontFace>,
    val fontFamilies: Map<String, Path>,
    val fontSizes: Map<String, List<Int>> = emptyMap(),
    val dialogFrames: Map<String, DialogFrameGlyph> = emptyMap(),
    val buttonSkins: Map<String, Int> = emptyMap(),
    val modalLayouts: List<NativeModalLayout> = emptyList(),
    val modalBackdrops: List<ModalBackdropLayout> = emptyList(),
    val modalFrames: Map<String, DialogFrameGlyph> = emptyMap(),
    val textOffsets: Set<Int> = TextOffsets.OFFSETS.toSet(),
    val canvasItems: List<CanvasItemRoute> = emptyList(),
    val canvasFlows: Map<String, CanvasFlowPlan> = emptyMap(),
    val canvasModalFlows: Map<String, CanvasModalFlow> = emptyMap(),
    val canvasItemBaseFingerprint: String? = null,
    val inputAppearances: List<InputAppearanceLayout> = emptyList(),
) {
    fun dimensions() = images.mapValues { (_, glyph) -> glyph.width to glyph.height }

    /** 原图比例不包含传输留白和派生资源。 */
    fun sourceDimensions() =
        imageSpecs
            .filter { it.transform == null }
            .associate { it.id to (it.contentWidth to it.contentHeight) }

    private fun metricFont(font: String): String =
        TextOffsets.baseFont(
            if (font.startsWith(ModalEncoding.FONT_PREFIX))
                "rafter:" + font.removePrefix(ModalEncoding.FONT_PREFIX)
            else font
        )

    fun fontFace(font: String): FontFace? = fontFaces[metricFont(font)]

    fun textWidth(text: String, bold: Boolean = false, font: String = theme.defaultFont): Double {
        val metricFont = metricFont(font)
        return text.codePoints().toArray().sumOf { code ->
            val base =
                when (metricFont) {
                    ModalEncoding.BOUNDS_FONT -> {
                        require(code == ModalEncoding.BOUNDS_CHARACTER.codePointAt(0)) {
                            "Unknown Canvas ordering glyph"
                        }
                        ModalEncoding.BOUNDS_ADVANCE.toDouble()
                    }
                    "rafter:images" ->
                        requireNotNull(
                                images.values
                                    .asSequence()
                                    .flatMap { it.tiles.asSequence() }
                                    .firstOrNull { it.character.codePointAt(0) == code }
                            ) {
                                "Unknown image glyph U+${code.toString(16)}"
                            }
                            .advance
                            .toDouble()
                    "rafter:fill" ->
                        when (code) {
                            0xe000 -> 10.0
                            0xe001 -> 9.0
                            else -> error("Unknown fill glyph")
                        }
                    "rafter:modal_fill" ->
                        when (code) {
                            0xe000,
                            0xe004 -> 21.0
                            0xe001,
                            0xe005 -> 2.0
                            else -> error("Unknown modal fill glyph")
                        }
                    "rafter:space" ->
                        when (code) {
                            in 0xe100..0xe112 -> (1 shl (code - 0xe100)) / 64.0
                            in 0xe140..0xe152 -> -(1 shl (code - 0xe140)) / 64.0
                            else -> error("Unknown space glyph")
                        }
                    else ->
                        if (metricFont.startsWith("rafter:button_skins/"))
                            requireNotNull(
                                    buttonSkins
                                        .asSequence()
                                        .filter { (_, inset) ->
                                            metricFont == "rafter:button_skins/$inset"
                                        }
                                        .flatMap { (id, _) ->
                                            images.getValue(id).tiles.asSequence()
                                        }
                                        .firstOrNull { it.character.codePointAt(0) == code }
                                ) {
                                    "Unknown button skin glyph U+${code.toString(16)} in $font"
                                }
                                .advance
                                .toDouble()
                        else
                            requireNotNull(faces[metricFont]?.get(code)) {
                                "Unmeasurable canvas glyph U+${code.toString(16)} in $font; native text may use fallback, canvas text must have known advances"
                            }
                }
            base +
                if (
                    bold &&
                        metricFont !in
                            setOf(
                                "rafter:space",
                                "rafter:fill",
                                "rafter:modal_fill",
                                ModalEncoding.BOUNDS_FONT,
                            ) &&
                        (code != 32 || metricFont in faces)
                )
                    1.0
                else 0.0
        }
    }
}
