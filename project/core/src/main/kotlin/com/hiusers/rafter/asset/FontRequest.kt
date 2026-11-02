package com.hiusers.rafter.asset

import java.math.BigDecimal
import java.nio.file.Path

/** 字体族与字号决定稳定协议 ID，不依赖发现顺序。 */
data class FontRequest(val family: String, val size: Double) {
    init {
        require(
            family.matches(Regex("[a-z0-9_./-]+")) &&
                family.split('/').none { it.isEmpty() || it == "." || it == ".." }
        ) {
            "Invalid font family: $family"
        }
        require(isValidSize(size)) {
            "Font size must be an integer from $MIN_SIZE to $MAX_SIZE"
        }
    }

    companion object {
        const val MIN_SIZE = 1
        const val MAX_SIZE = 128

        fun isValidSize(size: Double): Boolean =
            size.isFinite() && size >= MIN_SIZE && size <= MAX_SIZE && size % 1.0 == 0.0

        /** SimpleComponent 的裸字体键会被协议适配器补为 minecraft:。 */
        fun authoredFamily(componentFont: String): String =
            if (componentFont == "minecraft:default") componentFont
            else componentFont.removePrefix("minecraft:")
    }

    val id: String
        get() =
            "rafter:ttf/$family/${BigDecimal.valueOf(size).stripTrailingZeros().toPlainString()}"
}

/** 同一字体族的各字号共用源文件。 */
data class FontFace(val request: FontRequest, val source: Path)
