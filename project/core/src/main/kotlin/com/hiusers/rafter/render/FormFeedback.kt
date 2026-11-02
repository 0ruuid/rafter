package com.hiusers.rafter.render

import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.PageSpec
import com.hiusers.rafter.model.feedbackText
import com.hiusers.rafter.text.CompositeText
import taboolib.module.chat.ComponentText

/** 仅替换显示内容，不追加正文、改几何或提交草稿。 */
object FormFeedback {
    fun label(
        node: NodeSpec,
        page: PageSpec,
        frame: Frame,
        text: CompositeText,
        errors: Map<String, Any?>,
    ): ComponentText {
        if (errors.isEmpty() || page.modal?.feedback != node.id)
            return text.render(node.text, frame.values)
        val full =
            text.render(feedbackText(node.text, text.theme.formError, text.theme.palette.error))
        return text.fitLine(full, node.width ?: page.width).hoverText(full)
    }
}
