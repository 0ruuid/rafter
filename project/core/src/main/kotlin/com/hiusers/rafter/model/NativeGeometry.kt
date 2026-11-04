package com.hiusers.rafter.model

/** 布局与协议适配共用的原生 GUI 尺寸。 */
object NativeGeometry {
    const val LINE_HEIGHT = 9
    const val GRID_GAP = 2
    const val LABEL_GAP = 4
    const val CHECKBOX_SIZE = LINE_HEIGHT + 8
    const val BODY_GAP = 10
    // 多行计数位于输入框下方 4px，需保留 9px 行高，避免操作网格覆盖。
    const val COUNTER_OFFSET = 4
    const val COUNTER_CLEARANCE = COUNTER_OFFSET + LINE_HEIGHT - BODY_GAP
    const val INPUT_WIDTH = 300
    const val BUTTON_WIDTH = 150
    const val BUTTON_HEIGHT = 20
    const val BUTTON_TEXT_MARGIN = 2
    // 原生文字区宽度为负时，即使字形净宽为零也会被裁剪。
    const val PAINTED_SPACER_WIDTH = BUTTON_TEXT_MARGIN * 2
    const val ITEM_WIDTH = 32
    const val ITEM_HEIGHT = 32
    // 原版物品正文 Codec 的数量范围，不改变真实物品的堆叠上限。
    const val ITEM_MIN_COUNT = 1
    const val ITEM_MAX_COUNT = 99
    const val ITEM_DESCRIPTION_GAP = 2
    const val ITEM_DESCRIPTION_WIDTH = 300
    const val TEXT_PADDING = 4
}
