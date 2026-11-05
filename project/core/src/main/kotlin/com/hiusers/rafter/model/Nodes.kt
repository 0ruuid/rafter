package com.hiusers.rafter.model

import com.hiusers.rafter.asset.ImageRequest

/** 组件类型显式声明，安装快照前已展开模板。 */
enum class NodeKind(val id: String) {
    TEXT("text"),
    IMAGE("image"),
    METER("meter"),
    HIT_AREA("hit-area"),
    ITEM("item"),
    BUTTON("button"),
    INPUT("input"),
    CHECKBOX("checkbox"),
    SELECT("select"),
    SLIDER("slider"),
}

/** 状态键指向会话值，文档不持有实时状态。 */
sealed interface StateContent {
    val state: String
}

sealed interface FormContent : NodeContent, StateContent

sealed interface ClickContent : NodeContent {
    val actions: List<ActionSpec>
}

/** 仅替换原生按钮外观，不改变输入与动作协议。 */
data class ButtonSkinSpec(val asset: String, val inset: Int = 1, val labelOffsetX: Int = 0) {
    fun request(width: Int): ImageRequest {
        require(inset in 0..4 && width > inset * 2) {
            "Button skin must fit inside the native button"
        }
        return ImageRequest(
            asset,
            width - inset * 2,
            NativeGeometry.BUTTON_HEIGHT - inset * 2,
            "stretch",
        )
    }
}

/** 显式 exit 替换默认关闭控件，不猜测动作含义。 */
enum class ButtonRole(val id: String) {
    ACTION("action"),
    EXIT("exit"),
}

data class TextInputAppearance(
    val background: String = "native",
    val textColor: String? = null,
)

/** 组件只允许自身类型字段，图片不携带滑条或表单状态。 */
sealed interface NodeContent {
    val kind: NodeKind

    data object HitArea : NodeContent {
        override val kind = NodeKind.HIT_AREA
    }

    data object Text : NodeContent {
        override val kind = NodeKind.TEXT
    }

    data class Image(val asset: String, val request: ImageRequest? = null) : NodeContent {
        override val kind = NodeKind.IMAGE
    }

    /** 只读进度比例；拒绝非法数值，最大值为零时显示空条。 */
    data class Meter(
        val value: BindingSpec,
        val maximum: BindingSpec,
        val fill: String,
        val track: String,
    ) : NodeContent {
        override val kind = NodeKind.METER
    }

    data class Item(
        val reference: String,
        val size: Int = 18,
        val showCount: Boolean = true,
        val showTooltip: Boolean = true,
    ) : NodeContent {
        override val kind = NodeKind.ITEM
    }

    data class Button(
        override val actions: List<ActionSpec> = emptyList(),
        val skin: ButtonSkinSpec? = null,
        val role: ButtonRole = ButtonRole.ACTION,
    ) : ClickContent {
        override val kind = NodeKind.BUTTON
    }

    data class TextInput(
        override val state: String,
        val maxLength: Int = 64,
        val multiline: Boolean = false,
        val labelVisible: Boolean = true,
        val multilineHeight: Int = 48,
        val maxLines: Int = 3,
        val appearance: TextInputAppearance? = null,
    ) : FormContent {
        override val kind = NodeKind.INPUT
        val inputHeight: Int
            get() = if (multiline) multilineHeight else NativeGeometry.BUTTON_HEIGHT
    }

    data class Checkbox(override val state: String) : FormContent {
        override val kind = NodeKind.CHECKBOX
    }

    data class Select(override val state: String, val options: Map<String, TextSpec>) :
        FormContent {
        override val kind = NodeKind.SELECT
    }

    data class Slider(
        override val state: String,
        val min: Double = 0.0,
        val max: Double = 100.0,
        val step: Double = 1.0,
    ) : FormContent {
        override val kind = NodeKind.SLIDER

        fun accepts(value: Double): Boolean =
            value.isFinite() &&
                min.isFinite() &&
                max.isFinite() &&
                step.isFinite() &&
                step > 0 &&
                min < max &&
                value >= min &&
                value <= max &&
                kotlin.math.abs((value - min) / step - kotlin.math.round((value - min) / step)) <=
                    0.0001
    }
}

/** 共享放置与文本字段，控件数据按类型分离。 */
data class NodeSpec(
    val id: String,
    val content: NodeContent,
    val text: TextSpec = TextSpec(),
    val tooltip: TextSpec? = null,
    val visible: ConditionSpec? = null,
    val enabled: ConditionSpec? = null,
    val width: Int? = null,
    val height: Int? = null,
    val x: Int = 0,
    val y: Int = 0,
    val origin: SourceLocation? = null,
    val origins: List<SourceLocation> = emptyList(),
    val variants: List<NodeVariant> = emptyList(),
    val interaction: ElementInteraction? = null,
    val align: String = "left",
    val hitTarget: String? = null,
    val rowScope: RowScope? = null,
    val overflow: String = "error",
) {
    val kind: NodeKind
        get() = content.kind

    val type: String
        get() = kind.id

    val actions: List<ActionSpec>
        get() = interaction?.actions ?: (content as? ClickContent)?.actions.orEmpty()

    val clickable: Boolean
        get() = content is ClickContent || interaction?.actions?.isNotEmpty() == true

    val state: String?
        get() = (content as? StateContent)?.state
}

/** 画布交互独立于图片、背景和标签。 */
data class ElementInteraction(val actions: List<ActionSpec>)

/** 叶变体共用原身份与几何，不再嵌套变体。 */
data class NodeVariant(val condition: ConditionSpec, val node: NodeSpec)

/** 初始值、状态修改和表单提交使用相同控件校验。 */
fun StateContent.accepts(value: Any?): Boolean =
    when (this) {
        is NodeContent.TextInput ->
            value is String &&
                value.length <= maxLength &&
                (multiline || '\n' !in value && '\r' !in value)
        is NodeContent.Select -> value in options.keys
        is NodeContent.Checkbox -> value is Boolean
        is NodeContent.Slider -> value is Number && accepts(value.toDouble())
    }
