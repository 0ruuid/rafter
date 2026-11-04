package com.hiusers.rafter.asset

import com.hiusers.rafter.model.*
import java.util.Collections

/** 背景仅保存画布尺寸，相同尺寸共用字体，不持有玩家或截图。 */
data class ModalBackdropLayout(val width: Int, val height: Int) {
    val bodyWidth
        get() = CanvasGeometry.bodyWidth(width)

    val bodyHeight
        get() = CanvasGeometry.bodyHeight(height)

    val layoutHeight
        get() = bodyHeight + CanvasGeometry.BODY_ACTION_TAIL
}

/** 按真实原生控件流计算面板，不移动命中区。 */
data class NativeModalLayout(
    val key: String,
    val asset: String,
    val width: Int,
    val height: Int,
    val background: String,
    val clear: List<Rect>,
    val offsetY: Int,
    val plan: ModalLayoutPlan,
) {
    val bodyWidth
        get() = plan.bodyWidth

    val bodyHeight
        get() = plan.bodyHeight
}

/** 仅用于已适配 GUI Shader 的背景字体坐标域。 */
object ModalEncoding {
    const val X_BASE = 32768
    const val ROW_STRIDE = 2048
    const val FONT_Y_SHIFT = 256
    // 原生标题净宽为零，警告控件宽 20，间距 10。
    const val TITLE_GROUP_WIDTH = 30
    // Y 两次使用 Math.round，结果为 13，不能按 X 的整数截断处理。
    const val TITLE_Y = 13

    /** TTF 位移受客户端限制；用 X 编码布局，避免无限 Y 偏移。 */
    fun xBase(index: Int, layouts: List<ModalBackdropLayout>): Int {
        require(index in layouts.indices)
        val slots = layouts.take(index).sumOf { it.height / CanvasGeometry.ROW_HEIGHT + 2 }
        val base = X_BASE + slots * ROW_STRIDE
        // 此坐标域内仍能保留半像素精度。
        require(
            base.toLong() + (layouts[index].height / CanvasGeometry.ROW_HEIGHT) * ROW_STRIDE <=
                8387584L
        ) {
            "Too many distinct Canvas backdrop rows for lossless shader coordinates"
        }
        return base
    }

    fun font(index: Int, original: String) =
        "rafter:modal_backdrop/$index/" + original.substringAfter(':')

    fun key(menu: String, page: String) = "$menu/$page"
}

/** 模板展开后收集纯资源请求，再进入构包。 */
object ModalAssets {
    data class Requests(
        val layouts: List<NativeModalLayout>,
        val backdrops: List<ModalBackdropLayout>,
    )

    fun collect(snapshot: MenuSnapshot, sources: Map<String, Pair<Int, Int>>): Requests {
        val layouts =
            snapshot.menus.toSortedMap().flatMap { (id, menu) ->
                menu.pages.toSortedMap().values.mapNotNull { compiled ->
                    val page = compiled.spec
                    val modal = page.modal ?: return@mapNotNull null
                    val plan =
                        requireNotNull(compiled.modalLayout) {
                            "Modal geometry must be compiled before pack construction"
                        }
                    val source = sources.getValue(modal.asset)
                    NativeModalLayout(
                        ModalEncoding.key(id, page.id),
                        modal.asset,
                        source.first,
                        source.second,
                        modal.background,
                        modal.clear,
                        modal.offsetY,
                        plan,
                    )
                }
            }
        val backdrops =
            if (layouts.isEmpty()) emptyList()
            else
                snapshot.menus.values
                    .filter { menu -> menu.pages.values.any { it.spec.modal != null } }
                    .flatMap { menu ->
                        menu.pages.values
                            .filter {
                                it.spec.mode == PageMode.CANVAS &&
                                    it.spec.modal == null &&
                                    it.spec.components.isEmpty() &&
                                    it.spec.frame == null &&
                                    it.scene.none { node -> node.node.content is NodeContent.Item }
                            }
                            .map { ModalBackdropLayout(it.spec.width, it.height) }
                    }
                    .distinct()
                    .sortedWith(compareBy<ModalBackdropLayout> { it.width }.thenBy { it.height })
        return Requests(
            Collections.unmodifiableList(layouts),
            Collections.unmodifiableList(backdrops),
        )
    }
}
