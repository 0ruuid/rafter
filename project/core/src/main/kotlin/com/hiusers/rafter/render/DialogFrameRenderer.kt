package com.hiusers.rafter.render

import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.PageMode
import com.hiusers.rafter.model.PageSpec
import com.hiusers.rafter.text.CompositeText
import kotlin.math.ceil
import taboolib.module.chat.ComponentText

/** 标题背景不占正文行，不移动原生控件。 */
object DialogFrameRenderer {
    fun render(view: DialogView, page: PageSpec, text: CompositeText): DialogView {
        val frame = page.frame ?: return view
        fun labelWidth(component: ComponentText): Int =
            runCatching { ceil(text.width(component)).toInt() }.getOrDefault(page.width)
        var width =
            if (page.mode == PageMode.CANVAS) CanvasGeometry.bodyWidth(page.width)
            else page.width + NativeGeometry.TEXT_PADDING * 2
        width = maxOf(width, labelWidth(view.title) + 25, view.close.node.width ?: 150)
        view.bodies.forEach { body ->
            width =
                maxOf(
                    width,
                    when (body) {
                        is Message -> body.width + NativeGeometry.TEXT_PADDING * 2
                        is CanvasCarrierBody -> body.item?.route?.itemWidth ?: 1
                        is ItemBody ->
                            (body.node.width ?: NativeGeometry.ITEM_DESCRIPTION_WIDTH) +
                                NativeGeometry.ITEM_WIDTH +
                                NativeGeometry.ITEM_DESCRIPTION_GAP +
                                NativeGeometry.TEXT_PADDING * 2
                    },
                )
        }
        view.inputs.forEach { input ->
            val label = labelWidth(input.label)
            val control =
                if (input.node.content is NodeContent.Checkbox)
                    label + NativeGeometry.LINE_HEIGHT + 8 + 4
                else maxOf(input.node.width ?: 300, label)
            width = maxOf(width, control)
        }
        if (view.buttons.isNotEmpty()) {
            val columns = IntArray(minOf(view.columns, view.buttons.size))
            view.buttons.forEachIndexed { index, button ->
                val column = index % columns.size
                columns[column] = maxOf(columns[column], button.node.width ?: 150)
            }
            width = maxOf(width, columns.sum() + 2 * (columns.size - 1))
        }
        val target = maxOf(frame.width ?: 0, width + frame.padding * 2).coerceAtMost(65535)
        return view.copy(
            title = text.frameTitle(frame.asset, target, frame.inset, view.title),
            fallbackTitle = view.title,
        )
    }
}
