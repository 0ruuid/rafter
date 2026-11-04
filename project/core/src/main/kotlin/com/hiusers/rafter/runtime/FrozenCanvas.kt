package com.hiusers.rafter.runtime

import com.hiusers.rafter.asset.ModalBackdropLayout
import com.hiusers.rafter.model.Values
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
) {
    companion object {
        fun capture(session: UiSession, view: DialogView, text: CompositeText): FrozenCanvas {
            val page = session.current()
            require(
                page.spec.components.isEmpty() && page.spec.frame == null && page.spec.modal == null
            )
            require(view.bodies.size == 1 && view.inputs.isEmpty() && view.canvasItems.isEmpty())
            val width = ceil(text.width(view.title)).toInt()
            require(width in 0..864) { "Canvas title cannot be measured for a modal backdrop" }
            return FrozenCanvas(
                page.spec.id,
                session.generation,
                ModalBackdropLayout(page.spec.width, page.height),
                view.title.toRawMessage(),
                width,
                Values.list(view.messages.single().parts.map { it.text.toRawMessage() }),
            )
        }
    }
}
