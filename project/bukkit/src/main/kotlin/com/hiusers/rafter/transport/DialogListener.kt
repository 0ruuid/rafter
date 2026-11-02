package com.hiusers.rafter.transport

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction
import com.hiusers.rafter.runtime.RafterEngine
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit

/** 网络线程只解码受限普通值；玩家状态与引擎调用回到主线程处理。 */
internal class DialogListener(private val engine: () -> RafterEngine?) : PacketListenerAbstract() {
    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return
        val packet = WrapperPlayClientCustomClickAction(event)
        val id = packet.id.toString()
        if (!id.startsWith("rafter:ui/")) return
        event.isCancelled = true
        val handle = id.removePrefix("rafter:ui/")
        if (!handle.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            return
        val player = event.getPlayer<Player>() ?: return
        val payload = packet.payload
        if (payload != null && payload !is NBTCompound) return
        val tags = (payload as? NBTCompound)?.tags ?: emptyMap()
        if (tags.size > 64) return
        val values = linkedMapOf<String, Any?>()
        for ((key, value) in tags) {
            if (key.length > 256) return
            values[key] =
                when (value) {
                    is NBTString -> value.value.takeIf { it.length <= 4096 } ?: return
                    is NBTNumber -> value.asNumber.takeIf { it.toDouble().isFinite() } ?: return
                    else -> return
                }
        }
        submit {
            if (player.isOnline) engine()?.clickFromPacket(player, handle, values)
        }
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        val id = event.user.uuid ?: return
        submit { engine()?.forgetIfOffline(id) }
    }
}
