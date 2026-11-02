package com.hiusers.rafter.model

/** 画布与资源编译共用的逻辑 GUI 像素。 */
object CanvasGeometry {
    const val ROW_HEIGHT = 9
    const val EDGE_INSET = 1
    const val WRAP_SLACK = 2
    const val TEXT_PADDING = 4
    // 画布将唯一操作放入正文，不发送 exit_action。
    const val HEADER_HEIGHT = 33
    const val PREFERRED_BODY_TOP = 63
    const val EMPTY_FOOTER_HEIGHT = 5
    const val BODY_ACTION_TAIL = 10 + 20

    fun lineWidth(canvasWidth: Int) = canvasWidth + WRAP_SLACK

    fun bodyWidth(canvasWidth: Int) = lineWidth(canvasWidth) + TEXT_PADDING * 2

    fun bodyHeight(canvasHeight: Int) = canvasHeight + TEXT_PADDING * 2
}
