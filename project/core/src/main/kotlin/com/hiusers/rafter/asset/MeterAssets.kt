package com.hiusers.rafter.asset

import com.hiusers.rafter.model.*
import kotlin.math.floor

/** 复用二进制宽度的白色条带，数值变化不生成新资源。 */
object MeterAssets {
    const val PIXEL = "rafter:_meter/pixel"
    val definition: Map<String, Any> =
        mapOf(
            "width" to 9,
            "height" to 9,
            "content-width" to 1,
            "content-height" to 1,
            "color" to "#FFFFFF",
        )

    fun request(node: NodeSpec, width: Int) =
        ImageRequest(PIXEL, width, requireNotNull(node.height), "stretch", node.y % 9)

    fun requests(snapshot: MenuSnapshot): Set<ImageRequest> = buildSet {
        fun node(value: NodeSpec) {
            if (value.content is NodeContent.Meter) {
                var width = 1
                while (width <= requireNotNull(value.width)) {
                    add(request(value, width))
                    width *= 2
                }
            }
            value.variants.forEach { node(it.node) }
        }
        snapshot.menus.values.forEach {
            it.spec.pages.values.forEach { page -> page.nodes.forEach(::node) }
        }
    }

    fun filled(width: Int, value: Any?, maximum: Any?): Int {
        val amount = (value as? Number)?.toDouble() ?: error("Meter value must be numeric")
        val max = (maximum as? Number)?.toDouble() ?: error("Meter maximum must be numeric")
        require(amount.isFinite() && max.isFinite() && amount >= 0 && max >= 0) {
            "Meter quantities must be finite and nonnegative"
        }
        return if (max == 0.0) 0 else floor(width * (amount / max).coerceAtMost(1.0)).toInt()
    }

    fun segments(width: Int): List<Int> = buildList {
        var remaining = width
        while (remaining > 0) {
            val part = Integer.highestOneBit(remaining)
            add(part)
            remaining -= part
        }
    }
}
