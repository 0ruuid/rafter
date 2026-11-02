package com.hiusers.rafter.asset

/**
 * 标题贴图以 ±2048 位移居中，逐块抵消 advance，保持净宽为零。
 * RGB 编码宽度和内缩，关闭阴影与粗体；贴图不占正文高度。
 */
data class DialogFrameGlyph(
    val font: String,
    val characters: List<String>,
    val advances: List<Int>,
)
