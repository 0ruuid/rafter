package com.hiusers.rafter.asset

import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import java.security.MessageDigest
import kotlin.math.floor

/** 绘制尺寸和传输留白使用逻辑像素，与贴图倍率无关。 */
data class ImageRequest(
    val asset: String,
    val width: Int? = null,
    val height: Int? = null,
    val fit: String? = null,
    val topPadding: Int = 0,
) {
    val id: String =
        "rafter:_sized/" +
            MessageDigest.getInstance("SHA-256")
                .digest(
                    ("${asset.length}:$asset|$width|$height|$fit" +
                            if (topPadding == 0) "" else "|top=$topPadding")
                        .toByteArray(Charsets.UTF_8)
                )
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

object ImageSizing {
    /** 派生资源不得覆盖作者原件。 */
    fun isGeneratedAsset(id: String) =
        id.startsWith("rafter:_sized/") || id.startsWith("rafter:_meter/")

    data class Plan(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val width: Int,
        val height: Int,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
        val layoutWidth: Int,
        val layoutHeight: Int,
        val topPadding: Int = 0,
    )

    fun resolve(request: ImageRequest, sourceWidth: Int, sourceHeight: Int): Plan {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(request.topPadding in 0..8) { "Image top padding must be 0..8" }
        require(request.width != null || request.height != null || request.topPadding > 0) {
            "Image sizing requires width, height or top padding"
        }
        require(request.width == null || request.width in 1..864) { "Image width must be 1..864" }
        require(request.height == null || request.height in 1..4095) {
            "Image height must be 1..4095"
        }
        val both = request.width != null && request.height != null
        require(
            if (both) request.fit in setOf("contain", "cover", "stretch") else request.fit == null
        ) {
            "fit is required for two dimensions and only allowed with both width and height"
        }
        fun pixels(value: Double, limit: Int): Int {
            // 使用四舍五入而非银行家舍入，转换整数前检查范围。
            val rounded = floor(value + 0.5).coerceAtLeast(1.0)
            require(value.isFinite() && rounded <= limit.toDouble()) {
                "Image sizing exceeds $limit pixels"
            }
            return rounded.toInt()
        }
        val width =
            request.width
                ?: if (request.height == null) sourceWidth
                else pixels(sourceWidth.toDouble() * request.height / sourceHeight, 864)
        val height =
            request.height
                ?: if (request.width == null) sourceHeight
                else pixels(sourceHeight.toDouble() * width / sourceWidth, 4095)
        require(width in 1..864 && height in 1..(4095 - request.topPadding)) {
            "Image sizing and top padding exceed canvas bounds"
        }
        val ratio =
            when (request.fit) {
                "contain" -> minOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
                "cover" -> maxOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
                else -> null
            }
        // 构包时逆向采样，仅保存坐标，不分配中间大图。
        // 限制极端 cover 比例，避免采样溢出或误分配大缓冲。
        val scaledWidth = ratio?.let { pixels(sourceWidth * it, 1_048_576) } ?: width
        val scaledHeight = ratio?.let { pixels(sourceHeight * it, 1_048_576) } ?: height
        return Plan(
            sourceWidth,
            sourceHeight,
            width,
            height,
            scaledWidth,
            scaledHeight,
            Math.floorDiv(width - scaledWidth, 2),
            Math.floorDiv(height - scaledHeight, 2),
            (width + 8) / 9 * 9,
            (height + request.topPadding + 8) / 9 * 9,
            request.topPadding,
        )
    }

    fun requests(snapshot: MenuSnapshot): Set<ImageRequest> {
        val result = linkedSetOf<ImageRequest>()
        fun visit(node: NodeSpec) {
            (node.content as? NodeContent.Image)?.request?.let(result::add)
            node.variants.forEach { visit(it.node) }
        }
        snapshot.menus.values.forEach { menu ->
            menu.spec.pages.values.forEach { page -> page.nodes.forEach(::visit) }
        }
        return java.util.Collections.unmodifiableSet(result)
    }
}
