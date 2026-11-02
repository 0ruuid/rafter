package com.hiusers.rafter.asset

import java.awt.Color
import java.awt.image.BufferedImage

/** 直接采样到最终留白位图，cover 不分配放大中间图。 */
internal object ImageRaster {
    fun render(
        source: BufferedImage?,
        color: String?,
        plan: ImageSizing.Plan,
        scale: Int,
    ): BufferedImage {
        require(scale in 1..8)
        require(plan.width > 0 && plan.height > 0 && plan.scaledWidth > 0 && plan.scaledHeight > 0)
        require(plan.topPadding in 0..8)
        require(
            plan.layoutWidth >= plan.width && plan.layoutHeight >= plan.height + plan.topPadding
        )
        require((source != null) != (color != null)) { "Image needs exactly one of source/color" }
        if (source != null)
            require(
                source.width == plan.sourceWidth * scale &&
                    source.height == plan.sourceHeight * scale
            ) {
                "Source image dimensions disagree with sizing plan"
            }
        val bitmap =
            BufferedImage(
                plan.layoutWidth * scale,
                plan.layoutHeight * scale,
                BufferedImage.TYPE_INT_ARGB,
            )
        val boxWidth = plan.width * scale
        val boxHeight = plan.height * scale
        // 画布锚定到 floor(y/9)，透明顶行恢复作者 Y。
        val scaledWidth = plan.scaledWidth.toLong() * scale
        val scaledHeight = plan.scaledHeight.toLong() * scale
        val offsetX = plan.offsetX.toLong() * scale
        val offsetY = plan.offsetY.toLong() * scale
        val solid = color?.let { Color.decode(it).rgb }
        for (y in 0 until boxHeight) {
            val sy = y.toLong() - offsetY
            if (sy !in 0 until scaledHeight) continue
            for (x in 0 until boxWidth) {
                val sx = x.toLong() - offsetX
                if (sx !in 0 until scaledWidth) continue
                // 按像素中心最近邻采样，Long 运算避免极端比例溢出。
                val pixel =
                    solid
                        ?: source!!.getRGB(
                            ((2 * sx + 1) * source.width / (2 * scaledWidth)).toInt(),
                            ((2 * sy + 1) * source.height / (2 * scaledHeight)).toInt(),
                        )
                bitmap.setRGB(x, y + plan.topPadding * scale, pixel)
            }
        }
        return bitmap
    }
}
