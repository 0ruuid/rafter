package com.hiusers.rafter.model

/** 相对原生正文的 GUI 像素，尚未计入视口居中与滚动。 */
data class NativeSlot(val id: String, val bounds: Rect)

/** 整个槽位参与流排版，仅 controlBounds 是输入控件。 */
data class NativeInputSlot(
    val id: String,
    val bounds: Rect,
    val controlBounds: Rect,
    val labelBounds: Rect? = null,
    /** 预留完整计数行，不按某个计数字符串的字宽计算。 */
    val counterRegion: Rect? = null,
)

/** 传输补偿单元不属于作者按钮或业务动作。 */
enum class ModalCellRole(val id: String) {
    ACTION("action"),
    HORIZONTAL_SPACER("horizontal-spacer"),
    COUNTER_SPACER("counter-spacer"),
    EMPTY_ACTION_CELL("empty-action-cell"),
    CLIP_SPACER("clip-spacer"),
}

data class ModalButtonCell(
    val target: String?,
    val bounds: Rect,
    val role: ModalCellRole,
    val preserveInputCounter: Boolean = false,
) {
    val nativeWidth: Int
        get() =
            when {
                role == ModalCellRole.CLIP_SPACER -> 1
                preserveInputCounter -> NativeGeometry.PAINTED_SPACER_WIDTH
                else -> bounds.width
            }

    val labelShift: Int
        get() = bounds.width / 2 - ((bounds.width - nativeWidth) / 2 + nativeWidth / 2)
}

/** 渲染、构包与预览共用一份不可变几何。错误提示复用现有槽位，仅框外计数需要额外避让。 */
data class ModalLayoutPlan(
    val bodyWidth: Int,
    val bodyHeight: Int,
    val descriptions: List<NativeSlot>,
    val inputs: List<NativeInputSlot>,
    val buttons: List<ModalButtonCell>,
    val columns: Int,
    val buttonRowWidth: Int,
    val counterClearance: Rect?,
    val feedback: NativeSlot? = null,
)
