package com.hiusers.rafter.asset

import java.nio.file.Path

/** 原生标签复用缩放后的图片切片，仅调整垂直位置。 */
internal object ButtonSkinFonts {
    const val VERSION = "native-button-label-bitmaps-v1"
    // 26.2/26.3 的 20px 按钮将 9px 标签居中到 y+6。
    const val LABEL_BASELINE_OFFSET = 6

    fun manifest(skins: Map<String, Int>): Map<String, Any?> =
        mapOf(
            "version" to VERSION,
            "glyph-ascent" to AssetCompiler.GLYPH_ASCENT,
            "label-baseline-offset" to LABEL_BASELINE_OFFSET,
            "requests" to skins.toSortedMap(),
            "fonts" to skins.values.distinct().sorted().map { "rafter:button_skins/$it" },
        )

    fun write(stage: Path, catalog: AssetCatalog) {
        catalog.buttonSkins.entries
            .groupBy { it.value }
            .toSortedMap()
            .forEach { (inset, entries) ->
                val providers =
                    entries
                        .sortedBy { it.key }
                        .flatMap { (id, _) ->
                            catalog.images.getValue(id).tiles.map { tile ->
                                val path = id.substringAfter(':') + "_${tile.x}_${tile.y}"
                                mapOf(
                                    "type" to "bitmap",
                                    "file" to "rafter:font/images/$path.png",
                                    "height" to tile.height,
                                    "ascent" to
                                        AssetCompiler.GLYPH_ASCENT + LABEL_BASELINE_OFFSET -
                                            inset -
                                            tile.y,
                                    "chars" to listOf(tile.character),
                                )
                            }
                        }
                Json.write(
                    stage.resolve("assets/rafter/font/button_skins/$inset.json"),
                    mapOf("providers" to providers),
                )
            }
    }
}
