package com.hiusers.rafter.asset

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs
import kotlin.math.round

/** 按 TTF hmtx/em 换算已声明字号，不使用 AWT 估算。 */
object NativeFonts {
    private val cache = linkedMapOf<String, Map<String, Map<Int, Double>>>()

    @Synchronized
    fun widths(source: Path, sizes: Map<String, Double>): Map<String, Map<Int, Double>> {
        sizes.forEach { (id, size) ->
            require(FontRequest.isValidSize(size)) {
                "Invalid font size $size for $id in $source; expected an integer from ${FontRequest.MIN_SIZE} to ${FontRequest.MAX_SIZE}"
            }
        }
        val bytes = Files.readAllBytes(source)
        val digest =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        // 字号变化不能复用旧布局度量。
        val key = digest + sizes.toSortedMap().toString()
        cache[key]?.let {
            return it
        }
        val result =
            try {
                measure(bytes, sizes)
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException(
                    "Cannot measure TrueType source $source for $sizes: ${failure.message}",
                    failure,
                )
            }
        if (cache.size >= 4) cache.remove(cache.keys.first())
        cache[key] = result
        return result
    }

    private fun metrics(bytes: ByteArray): Pair<Int, Map<Int, Int>> {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(bytes.size >= 12) { "Invalid TrueType header" }
        val count = data.getShort(4).toInt() and 0xffff
        require(12L + count * 16L <= bytes.size) { "Invalid TrueType directory" }
        val tables = linkedMapOf<String, Int>()
        repeat(count) { index ->
            val at = 12 + index * 16
            val tag = ByteArray(4) { data.get(at + it) }.toString(Charsets.US_ASCII)
            val offset = data.getInt(at + 8)
            val length = data.getInt(at + 12)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) {
                "Invalid font table $tag"
            }
            tables[tag] = offset
        }
        fun unsignedShort(at: Int) = data.getShort(at).toInt() and 0xffff
        val units = unsignedShort(tables.getValue("head") + 18)
        require(units > 0) { "Invalid font units per em" }
        val metricCount = unsignedShort(tables.getValue("hhea") + 34)
        require(metricCount > 0) { "Missing horizontal font metrics" }
        val hmtx = tables.getValue("hmtx")
        val glyphCount = unsignedShort(tables.getValue("maxp") + 4)
        val advances = IntArray(metricCount) { unsignedShort(hmtx + it * 4) }
        val glyphs = linkedMapOf<Int, Int>()
        val cmap = tables.getValue("cmap")
        val unicodeTables =
            (0 until unsignedShort(cmap + 2))
                .mapNotNull { index ->
                    val at = cmap + 4 + index * 8
                    val platform = unsignedShort(at)
                    val encoding = unsignedShort(at + 2)
                    if (platform == 0 || platform == 3 && encoding in setOf(1, 10))
                        cmap + data.getInt(at + 4)
                    else null
                }
                .distinct()
                .sortedByDescending { unsignedShort(it) }

        unicodeTables.forEach { at ->
            when (unsignedShort(at)) {
                12 ->
                    repeat(data.getInt(at + 12)) { index ->
                        val group = at + 16 + index * 12
                        val start = data.getInt(group)
                        val end = data.getInt(group + 4)
                        val first = data.getInt(group + 8)
                        for (code in maxOf(32, start)..minOf(0xffff, end)) {
                            val glyph = first + code - start
                            if (code !in 0xd800..0xdfff && glyph in 1 until glyphCount)
                                glyphs.putIfAbsent(code, glyph)
                        }
                    }
                4 -> {
                    val segments = unsignedShort(at + 6) / 2
                    val ends = at + 14
                    val starts = ends + segments * 2 + 2
                    val deltas = starts + segments * 2
                    val ranges = deltas + segments * 2
                    repeat(segments) { index ->
                        val start = unsignedShort(starts + index * 2)
                        val end = unsignedShort(ends + index * 2)
                        val delta = data.getShort(deltas + index * 2).toInt()
                        val range = unsignedShort(ranges + index * 2)
                        for (code in maxOf(32, start)..minOf(0xfffe, end)) {
                            val address = ranges + index * 2 + range + (code - start) * 2
                            val raw = if (range == 0) code else unsignedShort(address)
                            val glyph = if (range == 0 || raw != 0) (raw + delta) and 0xffff else 0
                            if (code !in 0xd800..0xdfff && glyph in 1 until glyphCount)
                                glyphs.putIfAbsent(code, glyph)
                        }
                    }
                }
            }
        }
        require(glyphs.isNotEmpty()) { "Font has no supported Unicode cmap" }
        return units to glyphs.mapValues { (_, glyph) -> advances[minOf(glyph, metricCount - 1)] }
    }

    /** 与渲染共用 hmtx 映射，不栅格化或猜字号。 */
    fun supportedSizes(source: Path): List<Int> {
        val (units, widths) = metrics(Files.readAllBytes(source))
        val advances = widths.values.distinct()
        return (FontRequest.MIN_SIZE..FontRequest.MAX_SIZE).filter { size ->
            advances.all { it.toLong() * size % units == 0L }
        }
    }

    private fun measure(
        bytes: ByteArray,
        sizes: Map<String, Double>,
    ): Map<String, Map<Int, Double>> {
        val (units, emWidths) = metrics(bytes)
        return Collections.unmodifiableMap(
            sizes.mapValues { (id, size) ->
                Collections.unmodifiableMap(
                    emWidths.mapValues { (code, em) ->
                        val width = em * size / units
                        require(abs(width - round(width)) < 0.000001) {
                            "Font $id U+${code.toString(16)} has fractional advance $width at size $size; only integral advances are supported, not arbitrary font/size combinations"
                        }
                        round(width)
                    }
                )
            }
        )
    }
}
