package com.hiusers.rafter.asset

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** 位移别名共用原字体、字号、采样和回退。 */
object TextOffsetFonts {
    /** 使用 SFNT/head 单位和有符号 hhea 上升值，不当作字形墨迹范围。 */
    fun ascent(source: Path): Double {
        val bytes = Files.readAllBytes(source)
        require(bytes.size >= 12) { "Invalid TrueType header: $source" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = data.getShort(4).toInt() and 0xffff
        require(12L + count * 16L <= bytes.size) { "Invalid TrueType directory: $source" }
        val tables = linkedMapOf<String, Pair<Int, Int>>()
        repeat(count) { index ->
            val at = 12 + index * 16
            val tag = ByteArray(4) { data.get(at + it) }.toString(Charsets.US_ASCII)
            val offset = data.getInt(at + 8)
            val length = data.getInt(at + 12)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) {
                "Invalid font table $tag: $source"
            }
            tables[tag] = offset to length
        }
        fun table(tag: String, minimum: Int): Int {
            val (offset, length) =
                requireNotNull(tables[tag]) { "Missing font table $tag: $source" }
            require(length >= minimum) { "Incomplete font table $tag: $source" }
            return offset
        }
        val units = data.getShort(table("head", 20) + 18).toInt() and 0xffff
        val ascender = data.getShort(table("hhea", 6) + 4).toInt()
        require(units > 0 && ascender > 0) { "Invalid TrueType ascent or units per em: $source" }
        return ascender.toDouble() / units
    }

    fun aliases(catalog: AssetCatalog): List<String> =
        catalog.fontFaces.keys.flatMap { original ->
            catalog.textOffsets.filter { it != 0 }.map { TextOffsets.font(original, it) }
        }

    /** 保留两轴原始坐标，拒绝客户端不接受的位移。 */
    fun shift(provider: JsonObject, offset: Int) {
        val original = provider.getAsJsonArray("shift")
        val x = original?.get(0)?.asDouble ?: 0.0
        val y = (original?.get(1)?.asDouble ?: 0.0) + offset
        require(x in -512.0..512.0 && y in -512.0..512.0) {
            "TTF provider shift exceeds [-512,512]: [$x,$y]"
        }
        provider.add("shift", Json.gson.toJsonTree(listOf(x, y)))
    }

    fun write(stage: Path, catalog: AssetCatalog) {
        val fonts = stage.resolve("assets/rafter/font")
        catalog.fontFaces.keys.forEach { original ->
            val source =
                JsonParser.parseString(
                        Files.readString(fonts.resolve(original.substringAfter(':') + ".json"))
                    )
                    .asJsonObject
            catalog.textOffsets
                .filter { it != 0 }
                .forEach { offset ->
                    val font = source.deepCopy()
                    font.getAsJsonArray("providers").forEach { entry ->
                        val provider = entry.asJsonObject
                        if (provider.get("type").asString == "ttf") shift(provider, offset)
                    }
                    Json.write(
                        fonts.resolve(
                            TextOffsets.font(original, offset).substringAfter(':') + ".json"
                        ),
                        font,
                    )
                }
        }
    }
}
