package com.hiusers.rafter.compile

import com.hiusers.rafter.model.*
import kotlin.math.roundToInt

/** 原生控件布局：正文间隔 10px，居中操作网格间隔 2px。 */
object ModalLayoutCompiler {
    /** 标题坐标相对面板；原生流与传输行只在编译期解析一次。 */
    fun normalize(page: PageSpec): PageSpec {
        val modal = page.modal ?: return page
        require(page.height == null) {
            "Modal page height is derived; use modal.top and modal.content-top instead"
        }
        val bodyLead = CanvasGeometry.TEXT_PADDING * 2 + NativeGeometry.BODY_GAP
        val minimumY = page.elements.filter { it.hitTarget == null }.minOfOrNull { it.y }
        val keepHeaderVisible =
            minimumY?.let {
                modal.contentTop - bodyLead + CanvasGeometry.TEXT_PADDING - it
            } ?: CanvasGeometry.ROW_HEIGHT
        val height =
            LayoutCompiler.rows(
                maxOf(
                    CanvasGeometry.ROW_HEIGHT,
                    modal.requestedTop + modal.contentTop - bodyLead,
                    keepHeaderVisible,
                )
            )
        require(height <= LayoutCompiler.MAX_HEIGHT) {
            "Modal top/content-top exceeds the transport height"
        }
        val top = height + bodyLead - modal.contentTop
        val shift = top - CanvasGeometry.TEXT_PADDING
        fun place(node: NodeSpec): NodeSpec =
            node.copy(
                y = node.y + shift,
                variants = Values.list(node.variants.map { it.copy(node = place(it.node)) }),
            )
        return page.copy(
            height = height,
            elements = Values.list(page.elements.map(::place)),
            modal = modal.copy(offsetY = top),
        )
    }

    fun layout(page: PageSpec, minimumBodyWidth: Int = 0, leadingInset: Int = 0): ModalLayoutPlan {
        require(minimumBodyWidth >= 0 && leadingInset >= 0)
        val modal = requireNotNull(page.modal)
        val authored = page.components.filter { it.content is NodeContent.Button }
        require(authored.isNotEmpty()) { "A modal needs at least one native action button" }
        val columns = minOf(page.columns, authored.size)
        val widths =
            List(columns) { column ->
                authored
                    .filterIndexed { index, _ -> index % columns == column }
                    .maxOf { it.width ?: NativeGeometry.BUTTON_WIDTH }
            }
        val buttonWidth = widths.sum() + (columns - 1) * modal.buttonGap
        val forms = page.components.filter { it.content is FormContent }
        fun width(node: NodeSpec) = node.width ?: NativeGeometry.INPUT_WIDTH
        val descriptions = page.components.filter { it.content == NodeContent.Text }
        val bodyWidth =
            maxOf(
                minimumBodyWidth,
                CanvasGeometry.bodyWidth(page.width),
                buttonWidth,
                forms.maxOfOrNull(::width) ?: 0,
                descriptions.maxOfOrNull { it.width ?: page.width } ?: 0,
            )
        var top =
            CanvasGeometry.bodyHeight(requireNotNull(page.height)) +
                NativeGeometry.BODY_GAP +
                leadingInset
        val bodySlots = descriptions.map { node ->
            val slot =
                NativeSlot(
                    node.id,
                    Rect(
                        (bodyWidth - (node.width ?: page.width)) / 2,
                        top,
                        node.width ?: page.width,
                        NativeGeometry.LINE_HEIGHT + NativeGeometry.TEXT_PADDING * 2,
                    ),
                )
            top += slot.bounds.height + NativeGeometry.BODY_GAP
            slot
        }
        val inputSlots = forms.map { node ->
            val height =
                when (val content = node.content) {
                    is NodeContent.TextInput ->
                        content.inputHeight +
                            if (content.labelVisible)
                                NativeGeometry.LINE_HEIGHT + NativeGeometry.LABEL_GAP
                            else 0
                    is NodeContent.Checkbox -> NativeGeometry.CHECKBOX_SIZE
                    else -> NativeGeometry.BUTTON_HEIGHT
                }
            val bounds = Rect((bodyWidth - width(node)) / 2, top, width(node), height)
            val content = node.content as? NodeContent.TextInput
            val label =
                if (content?.labelVisible == true)
                    Rect(bounds.x, top, bounds.width, NativeGeometry.LINE_HEIGHT)
                else null
            val labelHeight =
                if (label != null) NativeGeometry.LINE_HEIGHT + NativeGeometry.LABEL_GAP else 0
            val control = Rect(bounds.x, top + labelHeight, bounds.width, height - labelHeight)
            val counter =
                if (content?.multiline == true)
                    Rect(
                        control.x,
                        control.y + control.height + NativeGeometry.COUNTER_OFFSET,
                        control.width,
                        NativeGeometry.LINE_HEIGHT,
                    )
                else null
            top += height + NativeGeometry.BODY_GAP
            NativeInputSlot(node.id, bounds, control, label, counter)
        }
        val preserveCounter =
            (forms.lastOrNull()?.content as? NodeContent.TextInput)?.multiline == true
        val gridTop = top
        val cells = mutableListOf<ModalButtonCell>()
        val reservedRows = if (preserveCounter) 1 else 0
        val rows = reservedRows + (authored.size + columns - 1) / columns
        repeat(rows) { row ->
            var x = (bodyWidth - buttonWidth) / 2
            repeat(columns) { column ->
                val node =
                    if (row < reservedRows) null
                    else authored.getOrNull((row - reservedRows) * columns + column)
                val cellWidth = node?.width ?: widths[column]
                cells +=
                    ModalButtonCell(
                        node?.id,
                        Rect(
                            x + (widths[column] - cellWidth) / 2,
                            gridTop +
                                row * (NativeGeometry.BUTTON_HEIGHT + NativeGeometry.GRID_GAP),
                            cellWidth,
                            NativeGeometry.BUTTON_HEIGHT,
                        ),
                        if (row < reservedRows) ModalCellRole.COUNTER_SPACER
                        else if (node != null) ModalCellRole.ACTION
                        else ModalCellRole.EMPTY_ACTION_CELL,
                        row < reservedRows,
                    )
                x += widths[column]
                if (column < columns - 1) {
                    cells +=
                        ModalButtonCell(
                            null,
                            Rect(
                                x + NativeGeometry.GRID_GAP,
                                gridTop +
                                    row * (NativeGeometry.BUTTON_HEIGHT + NativeGeometry.GRID_GAP),
                                modal.buttonGap - NativeGeometry.GRID_GAP * 2,
                                NativeGeometry.BUTTON_HEIGHT,
                            ),
                            if (row < reservedRows) ModalCellRole.COUNTER_SPACER
                            else ModalCellRole.HORIZONTAL_SPACER,
                            row < reservedRows,
                        )
                    x += modal.buttonGap
                }
            }
        }
        val feedback =
            modal.feedback?.let { target ->
                bodySlots.firstOrNull { it.id == target }
                    ?: inputSlots
                        .firstOrNull { it.id == target }
                        ?.labelBounds
                        ?.let {
                            NativeSlot(
                                target,
                                it.copy(height = it.height + NativeGeometry.LABEL_GAP),
                            )
                        }
                    ?: page.elements
                        .firstOrNull { it.id == target }
                        ?.let { node ->
                            val width = requireNotNull(node.width)
                            val left =
                                when (node.align) {
                                    "center" -> (node.x - width / 2.0).roundToInt()
                                    "right" -> node.x - width
                                    else -> node.x
                                }
                            val canvasLeft =
                                (bodyWidth - CanvasGeometry.bodyWidth(page.width)) / 2 +
                                    CanvasGeometry.TEXT_PADDING +
                                    CanvasGeometry.EDGE_INSET
                            NativeSlot(
                                target,
                                Rect(
                                    canvasLeft + left,
                                    CanvasGeometry.TEXT_PADDING + leadingInset + node.y,
                                    width,
                                    requireNotNull(node.height),
                                ),
                            )
                        }
            }
        return ModalLayoutPlan(
            bodyWidth,
            gridTop + rows * NativeGeometry.BUTTON_HEIGHT + (rows - 1) * NativeGeometry.GRID_GAP,
            Values.list(bodySlots),
            Values.list(inputSlots),
            Values.list(cells),
            columns * 2 - 1,
            buttonWidth,
            if (preserveCounter)
                Rect(
                    (bodyWidth - buttonWidth) / 2,
                    gridTop,
                    buttonWidth,
                    NativeGeometry.BUTTON_HEIGHT,
                )
            else null,
            feedback,
        )
    }
}
