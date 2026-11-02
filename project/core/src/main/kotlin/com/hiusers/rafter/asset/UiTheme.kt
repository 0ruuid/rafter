package com.hiusers.rafter.asset

import com.hiusers.rafter.model.TextSpec

/** 不可变外观数据，默认值来自主题配置。 */
data class UiTheme(
    val defaultFamily: String,
    val defaultSize: Double,
    val oversample: Double,
    val styles: Map<String, Map<String, Any?>>,
    val palette: Palette,
    val closeLabel: TextSpec,
    val formError: TextSpec,
    val hideWarning: Boolean,
    val hideCanvasFocus: Boolean,
    val hideNativeFocus: Boolean,
) {
    val defaultRequest: FontRequest
        get() = FontRequest(defaultFamily, defaultSize)

    val defaultFont: String
        get() = defaultRequest.id

    data class Palette(val error: String)
}
