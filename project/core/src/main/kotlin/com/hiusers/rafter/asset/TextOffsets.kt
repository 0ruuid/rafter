package com.hiusers.rafter.asset

/** 字体位移别名保留原字体和度量。 */
object TextOffsets {
    val OFFSETS = -16..16
    private val alias = Regex("rafter:text_offset/(-?[1-9][0-9]*)/(ttf/.+)")

    fun baseFont(font: String): String =
        alias.matchEntire(font)?.groupValues?.get(2)?.let { "rafter:$it" } ?: font

    fun offset(font: String): Int = alias.matchEntire(font)?.groupValues?.get(1)?.toInt() ?: 0

    /** 合并位移而不嵌套别名，仅收集实际所需偏移。 */
    fun font(original: String, offset: Int): String {
        require(offset in -512..512) {
            "Text Y offset must fit the font provider coordinate domain"
        }
        if (offset == 0) return original
        val match = alias.matchEntire(original)
        val base = baseFont(original)
        require(base.startsWith("rafter:ttf/")) {
            "Text Y offsets require a generated TTF face: $original"
        }
        val total = offset + (match?.groupValues?.get(1)?.toInt() ?: 0)
        require(total in -512..512) {
            "Combined text Y offset must fit the font provider coordinate domain"
        }
        return if (total == 0) base else "rafter:text_offset/$total/${base.substringAfter(':')}"
    }
}
