package com.hiusers.rafter.asset

import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.Rect

/** 物品节点的编译路由，不包含玩家或 ItemStack。 */
data class CanvasItemRoute(
    val menu: String,
    val page: String,
    val node: String,
    val channel: Int,
    val row: Int,
    val count: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val bounds: Rect,
    val maximumChannel: Int = channel - row + count - 1,
) {
    val itemWidth: Int
        get() = 1 + channel * 2

    val sourceWidth: Int
        get() = itemWidth + NativeGeometry.ITEM_DESCRIPTION_GAP + CanvasItems.DESCRIPTION_WIDTH

    val maximumSourceWidth: Int
        get() =
            1 +
                maximumChannel * 2 +
                NativeGeometry.ITEM_DESCRIPTION_GAP +
                CanvasItems.DESCRIPTION_WIDTH

    val sourceTop: Int
        get() = CanvasGeometry.PREFERRED_BODY_TOP + row * CanvasItems.ROW_STEP

    val minimumGuiWidth: Int
        get() = CanvasGeometry.bodyWidth(canvasWidth) + CanvasItems.BODY_MARGIN * 2

    val minimumGuiHeight: Int
        get() =
            CanvasGeometry.PREFERRED_BODY_TOP +
                count * CanvasItems.ROW_STEP +
                CanvasGeometry.bodyHeight(canvasHeight) +
                CanvasGeometry.BODY_ACTION_TAIL +
                CanvasGeometry.EMPTY_FOOTER_HEIGHT

    /** 对齐原生父容器后再对齐子行；负数位置按客户端向零取整。 */
    fun sourceLeft(guiWidth: Int): Int = bodyLeft(guiWidth) + sourceInsetX

    fun canvasLeft(guiWidth: Int): Int = bodyLeft(guiWidth) + canvasInsetX

    val bodyOuterWidth: Int
        get() = maximumSourceWidth + CanvasItems.BODY_MARGIN * 2

    val sourceInsetX: Int
        get() = (maximumSourceWidth - sourceWidth) / 2

    val canvasInsetX: Int
        get() =
            (maximumSourceWidth - CanvasGeometry.bodyWidth(canvasWidth)) / 2 +
                CanvasItems.CONTENT_INSET

    val canvasTop: Int
        get() =
            CanvasGeometry.PREFERRED_BODY_TOP +
                count * CanvasItems.ROW_STEP +
                CanvasGeometry.TEXT_PADDING

    private fun bodyLeft(guiWidth: Int): Int =
        (guiWidth - bodyOuterWidth) / 2 + CanvasItems.BODY_MARGIN

    fun manifest(): Map<String, Any?> =
        mapOf(
            "menu" to menu,
            "page" to page,
            "node" to node,
            "channel" to channel,
            "bounds" to bounds,
            "source-row" to row,
            "source-item-width" to itemWidth,
            "source-width" to sourceWidth,
            "source-y" to sourceTop,
            "source-maximum-width" to maximumSourceWidth,
            "minimum-gui-width" to minimumGuiWidth,
            "minimum-gui-height" to minimumGuiHeight,
        )
}

/** 原生宽度限 1..256；载体使用 1..255 的奇数宽度编码通道。 */
object CanvasItems {
    const val DESCRIPTION_WIDTH = 1024
    const val BODY_MARGIN = 10
    const val CONTENT_INSET = CanvasGeometry.TEXT_PADDING + CanvasGeometry.EDGE_INSET
    const val CARRIER_HEIGHT = 10
    const val ROW_STEP = CARRIER_HEIGHT + NativeGeometry.BODY_GAP
    const val MAX_CHANNELS = 128
    const val FRAME_SIZE = 18
    const val ICON_SIZE = 16
    const val HOVER_PREFIX = "rafter:item/"
}
