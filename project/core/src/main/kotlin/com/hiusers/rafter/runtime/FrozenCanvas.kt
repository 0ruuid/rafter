package com.hiusers.rafter.runtime

import com.hiusers.rafter.asset.ModalBackdropLayout
import com.hiusers.rafter.model.Values
import com.hiusers.rafter.render.CanvasItem
import com.hiusers.rafter.render.DialogView
import com.hiusers.rafter.text.CompositeText
import kotlin.math.ceil

/** 会话持有的不可变组件快照，不含输入、回调或点击句柄。 */
data class FrozenCanvas(
    val page: String,
    val generation: Long,
    val layout: ModalBackdropLayout,
    val title: String,
    val titleWidth: Int,
    val parts: List<String>,
    val items: List<CanvasItem> = emptyList(),
    val flowKey: String? = null,
) {
    companion object {
        fun capture(session: UiSession, view: DialogView, text: CompositeText): FrozenCanvas {
            val page = session.current()
            require(
                page.spec.components.isEmpty() && page.spec.frame == null && page.spec.modal == null
            )
            require(view.inputs.isEmpty())
            val title = view.canvasTitle ?: view.title
            val width = ceil(text.width(title)).toInt()
            require(width in 0..864) { "Canvas title cannot be measured for a modal backdrop" }
            return FrozenCanvas(
                page.spec.id,
                session.generation,
                view.canvasLayout ?: ModalBackdropLayout(page.spec.width, page.height),
                title.toRawMessage(),
                width,
                Values.list(
                    (view.painting ?: view.messages.single()).parts.map { it.text.toRawMessage() }
                ),
                Values.list(view.canvasItems),
                view.canvasFlowKey,
            )
        }
    }
}
