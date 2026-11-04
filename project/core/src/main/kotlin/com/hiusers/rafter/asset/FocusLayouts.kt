package com.hiusers.rafter.asset

import com.hiusers.rafter.model.CanvasGeometry

/** 编译后的画布几何，不含玩家、任务或可变文本。 */
data class CanvasFocusLayout(
    val menu: String,
    val page: String,
    val bodyWidth: Int,
    val bodyHeight: Int,
    val footerHeight: Int = CanvasGeometry.EMPTY_FOOTER_HEIGHT,
    val trailingHeight: Int = CanvasGeometry.BODY_ACTION_TAIL,
) {
    fun vector() = listOf(bodyWidth, bodyHeight, footerHeight, trailingHeight)

    fun manifest() =
        mapOf(
            "menu" to menu,
            "page" to page,
            "body-width" to bodyWidth,
            "body-height" to bodyHeight,
            "footer-height" to footerHeight,
            "trailing-height" to trailingHeight,
        )
}

enum class NativeFocusKind(val code: Int) {
    TEXT(0),
    ITEM(1),
    CARRIER_ROW(2),
}

/** 文字高度由客户端按 9*n+8 计算；偏移倍增以保留半像素。 */
data class NativeFocusLayout(
    val menu: String,
    val page: String,
    val component: String,
    val kind: NativeFocusKind,
    val width: Int,
    val height: Int,
    val centerOffsetTwice: Int,
) {
    fun vector() = listOf(width, height, centerOffsetTwice, kind.code)

    fun manifest() =
        mapOf(
            "menu" to menu,
            "page" to page,
            "component" to component,
            "kind" to kind.name.lowercase(),
            "width" to width,
            "height" to if (kind == NativeFocusKind.TEXT) "9*n+8" else height,
            "center-offset-twice" to centerOffsetTwice,
        )
}
