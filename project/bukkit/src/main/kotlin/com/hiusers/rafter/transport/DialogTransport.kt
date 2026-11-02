package com.hiusers.rafter.transport

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData
import com.github.retrooper.packetevents.protocol.dialog.DialogAction
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody
import com.github.retrooper.packetevents.protocol.dialog.body.ItemDialogBody
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.Input
import com.github.retrooper.packetevents.protocol.dialog.input.InputControl
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.render.DialogView
import com.hiusers.rafter.render.Fragment
import com.hiusers.rafter.render.ItemBody
import com.hiusers.rafter.render.Message
import com.hiusers.rafter.render.RenderButton
import com.hiusers.rafter.text.CompositeText
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.api.BinaryTagHolder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 仅在此组装协议组件和 CUSTOM 点击元数据；保留渲染顺序与尺寸，玩家操作限主线程。 */
open class DialogTransport {
    open fun supported(player: Player) =
        !PacketEvents.getAPI()
            .playerManager
            .getClientVersion(player)
            .isOlderThan(ClientVersion.V_1_21_6)

    fun wire(parts: List<Fragment>): Component {
        // 复用构建器，避免反复复制增长中的组件列表。
        val result = Component.text()
        parts.forEach { part ->
            val converted = part.text.toAdventureObject()
            result.append(
                if (part.handle == null) converted
                else
                    converted.clickEvent(
                        ClickEvent.custom(
                            Key.key("rafter:ui/${part.handle}"),
                            BinaryTagHolder.binaryTagHolder("{}"),
                        )
                    )
            )
        }
        return result.build()
    }

    private fun button(value: RenderButton) =
        ActionButton(
            CommonButtonData(
                value.label.toAdventureObject(),
                value.tooltip?.toAdventureObject(),
                value.node.width ?: 150,
            ),
            value.handle?.let {
                DynamicCustomAction(ResourceLocation("rafter:ui/$it"), NBTCompound())
            },
        )

    open fun show(
        player: Player,
        view: DialogView,
        text: CompositeText,
        values: Map<BindingSpec, Any?>,
        items: Map<String, ItemStack>,
    ) {
        val dialog =
            create(
                view,
                text,
                values,
                items,
                supportsFrame(PacketEvents.getAPI().playerManager.getClientVersion(player)),
            )
        // SHOW_DIALOG 可直接替换，先清空会造成闪屏。
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerShowDialog(dialog))
    }

    /** 发送与协议测试共用的纯数据包组装。 */
    internal fun create(
        view: DialogView,
        text: CompositeText,
        values: Map<BindingSpec, Any?>,
        items: Map<String, ItemStack>,
        frameSupported: Boolean = true,
    ): MultiActionDialog {
        require(!view.modal || frameSupported) {
            "Native graphical modals require the verified 26.2/26.3 text shader"
        }
        val bodies: List<DialogBody> =
            view.bodies.map { body ->
                when (body) {
                    is Message -> PlainMessageDialogBody(PlainMessage(wire(body.parts), body.width))
                    is ItemBody ->
                        ItemDialogBody(
                            SpigotConversionUtil.fromBukkitItemStack(items.getValue(body.node.id)),
                            PlainMessage(
                                body.label.toAdventureObject(),
                                body.node.width ?: NativeGeometry.ITEM_DESCRIPTION_WIDTH,
                            ),
                            true,
                            true,
                            NativeGeometry.ITEM_WIDTH,
                            NativeGeometry.ITEM_HEIGHT,
                        )
                }
            }
        val inputs =
            view.inputs.mapIndexed { index, input ->
                val node = input.node
                val label = input.label.toAdventureObject()
                val width = node.width ?: NativeGeometry.INPUT_WIDTH
                val control: InputControl =
                    when (val content = node.content) {
                        is NodeContent.TextInput ->
                            TextInputControl(
                                width,
                                label,
                                content.labelVisible,
                                input.initial as? String ?: "",
                                content.maxLength,
                                if (content.multiline)
                                    TextInputControl.MultilineOptions(
                                        content.maxLines,
                                        content.multilineHeight,
                                    )
                                else null,
                            )
                        is NodeContent.Checkbox ->
                            BooleanInputControl(label, input.initial == true, "true", "false")
                        is NodeContent.Select ->
                            SingleOptionInputControl(
                                width,
                                content.options.map { (key, value) ->
                                    SingleOptionInputControl.Entry(
                                        key,
                                        text.render(value, values).toAdventureObject(),
                                        input.initial == key,
                                    )
                                },
                                label,
                                true,
                            )
                        is NodeContent.Slider ->
                            NumberRangeInputControl(
                                width,
                                label,
                                "options.generic_value",
                                NumberRangeInputControl.RangeInfo(
                                    content.min.toFloat(),
                                    content.max.toFloat(),
                                    (input.initial as? Number)?.toFloat() ?: content.min.toFloat(),
                                    content.step.toFloat(),
                                ),
                            )
                        else -> error("Unsupported input ${node.type}")
                    }
                Input(DialogInputNames.key(index), control)
            }
        val title = if (frameSupported) view.title else view.fallbackTitle ?: view.title
        val common =
            CommonDialogData(
                title.toAdventureObject(),
                null,
                !view.modal,
                false,
                DialogAction.NONE,
                bodies,
                inputs,
            )
        val close = button(view.close)
        val buttons = view.buttons.map(::button)
        return MultiActionDialog(
            common,
            if (buttons.isEmpty()) listOf(close) else buttons,
            if (view.modal || buttons.isEmpty()) null else close,
            view.columns,
        )
    }

    /** 未知文字 Shader ABI 使用普通原生控件，不显示内部标记。 */
    internal fun supportsFrame(version: ClientVersion): Boolean =
        when (version) {
            ClientVersion.V_26_2 ->
                com.hiusers.rafter.asset.DialogFrameShaders.supportsPackFormat(88, 0)
            ClientVersion.V_26_3 ->
                com.hiusers.rafter.asset.DialogFrameShaders.supportsPackFormat(97, 1)
            else -> false
        }

    open fun clear(player: Player) {
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerClearDialog())
    }
}
