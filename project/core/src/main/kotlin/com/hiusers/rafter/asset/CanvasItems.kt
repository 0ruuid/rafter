package com.hiusers.rafter.asset

import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.Rect

/** 原生载体和目标画布分开计量；不包含玩家或 ItemStack。 */
data class CanvasItemRoute(
    val menu: String,
    val page: String,
    val node: String,
    val channel: Int,
    val row: Int,
    val count: Int,
    val layout: ModalBackdropLayout,
    val bounds: Rect,
    val sourceY: Int,
    val sourceHeight: Int,
    val sourceBodyWidth: Int,
    val sourceLayoutHeight: Int,
    val popup: String? = null,
    val origin: String? = null,
    val dim: Double = 1.0,
    val clip: CanvasItemClip? = null,
) {
    val itemWidth
        get() = 1 + channel * 2

    val sourceWidth
        get() = itemWidth

    val maximumSourceWidth
        get() = sourceBodyWidth

    val bodyOuterWidth
        get() = sourceBodyWidth + CanvasItems.BODY_MARGIN * 2

    val sourceInsetX
        get() = (sourceBodyWidth - itemWidth) / 2

    val canvasWidth
        get() = layout.width

    val canvasHeight
        get() = layout.height

    val canvasInsetX
        get() = layout.contentInsetX

    val sourceTop
        get() = CanvasGeometry.PREFERRED_BODY_TOP + sourceY

    val canvasTop
        get() = CanvasGeometry.PREFERRED_BODY_TOP + CanvasGeometry.TEXT_PADDING + layout.inset

    val minimumGuiWidth
        get() = maxOf(bodyOuterWidth, layout.containerWidth + CanvasItems.BODY_MARGIN * 2)

    val minimumGuiHeight
        get() =
            CanvasGeometry.HEADER_HEIGHT +
                maxOf(sourceLayoutHeight, layout.layoutHeight) +
                CanvasGeometry.EMPTY_FOOTER_HEIGHT

    fun sourceTop(guiHeight: Int) = CanvasItems.bodyTop(guiHeight, sourceLayoutHeight) + sourceY

    fun sourceLeft(guiWidth: Int) =
        (guiWidth - bodyOuterWidth) / 2 + CanvasItems.BODY_MARGIN + sourceInsetX

    fun canvasLeft(guiWidth: Int) = layout.contentLeft(guiWidth)

    fun manifest(): Map<String, Any?> =
        mapOf(
            "menu" to menu,
            "page" to page,
            "popup" to popup,
            "origin" to origin,
            "node" to node,
            "channel" to channel,
            "bounds" to bounds,
            "source-row" to row,
            "source-item-width" to itemWidth,
            "source-height" to sourceHeight,
            "source-y" to sourceY,
            "source-maximum-width" to maximumSourceWidth,
            "layout-height" to sourceLayoutHeight,
            "canvas-inset" to layout.inset,
            "minimum-gui-width" to minimumGuiWidth,
            "minimum-gui-height" to minimumGuiHeight,
            "dim" to dim,
            "clip" to clip,
        )
}

/** 裁剪使用实际原生面板的位置，不按示例界面尺寸猜测。 */
data class CanvasItemClip(val width: Int, val height: Int, val top: Int, val layoutHeight: Int)

data class CanvasModalFlow(
    val origin: String,
    val modal: String,
    val flow: CanvasFlowPlan,
    val layout: NativeModalLayout,
)

/** 原生宽度限 1..256；奇数宽度提供 128 个整数居中通道。 */
object CanvasItems {
    const val BODY_MARGIN = 10
    const val CARRIER_HEIGHT = 1
    const val MAX_CHANNELS = 128
    const val FRAME_SIZE = 18
    const val ICON_SIZE = 16
    const val HOVER_PREFIX = "rafter:item/"

    fun bodyTop(guiHeight: Int, layoutHeight: Int): Int =
        minOf(
            CanvasGeometry.PREFERRED_BODY_TOP,
            guiHeight -
                CanvasGeometry.EMPTY_FOOTER_HEIGHT -
                minOf(
                    layoutHeight,
                    maxOf(
                        guiHeight -
                            CanvasGeometry.HEADER_HEIGHT -
                            CanvasGeometry.EMPTY_FOOTER_HEIGHT,
                        0,
                    ),
                ),
        )

    fun key(menu: String, page: String, popup: String? = null) =
        "$menu/$page" + (popup?.let { "@popup/$it" } ?: "")

    fun modalKey(menu: String, page: String, origin: String) = "$menu/$page@from/$origin"
}
