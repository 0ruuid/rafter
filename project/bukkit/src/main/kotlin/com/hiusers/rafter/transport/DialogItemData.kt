package com.hiusers.rafter.transport

import com.github.retrooper.packetevents.protocol.dialog.Dialog
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.asset.CanvasItems
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/** 补回 PacketEvents 2.14 Dialog 物品编码遗漏的组件，不维护组件白名单。 */
internal object DialogItemData {
    private val serialize by lazy { ItemStack::class.java.getMethod("serializeAsBytes") }

    /** 服务端序列化在主线程完成，发送线程只接收独立的 NBT 快照。 */
    fun capture(item: ItemStack): NBTCompound {
        check(Bukkit.isPrimaryThread()) { "Dialog items must be serialized on the main thread" }
        return decode(serialize.invoke(item) as ByteArray)
    }

    internal fun decode(bytes: ByteArray): NBTCompound {
        val plain = GZIPInputStream(ByteArrayInputStream(bytes)).use {
            it.readNBytes(NBTLimiter.DEFAULT_MAX_SIZE + 1)
        }
        require(plain.size <= NBTLimiter.DEFAULT_MAX_SIZE) { "Dialog item exceeds the NBT limit" }
        return DataInputStream(ByteArrayInputStream(plain)).use { input ->
            val limiter =
                object : NBTLimiter {
                    private var allocated = 0L
                    private var depth = 0

                    override fun increment(bytes: Int) {
                        require(bytes >= 0) { "Negative NBT allocation" }
                        allocated += bytes
                        require(allocated <= NBTLimiter.DEFAULT_MAX_SIZE) {
                            "Dialog item exceeds the NBT limit"
                        }
                    }

                    override fun checkReadability(bytes: Int) {
                        require(bytes >= 0 && bytes <= input.available()) { "Truncated item NBT" }
                    }

                    override fun enterDepth() {
                        require(depth < NBTLimiter.DEFAULT_MAX_DEPTH) { "Item NBT is too deep" }
                        depth++
                    }

                    override fun exitDepth() {
                        check(depth > 0)
                        depth--
                    }
                }
            val result =
                DefaultNBTSerializer.INSTANCE.deserializeTag(limiter, input, true) as? NBTCompound
                    ?: throw IllegalArgumentException("Dialog item NBT must be a compound")
            require(input.available() == 0) { "Trailing item NBT data" }
            validate(result)
            result.apply { removeTag("DataVersion") }
        }
    }

    fun empty() = NBTCompound().apply {
        setTag("id", NBTString("minecraft:paper"))
        setTag("components", NBTCompound().apply {
            setTag("minecraft:item_model", NBTString("rafter:empty"))
        })
    }

    internal fun restore(
        dialog: NBTCompound,
        items: Map<Int, NBTCompound>,
        hovers: Map<String, NBTCompound> = emptyMap(),
    ): NBTCompound {
        val result = dialog.copy()
        // 只处理引擎签发的文本标记；先处理文本，再附加真实物品，避免解释物品自身的组件。
        fun hover(value: NBT) {
            when (value) {
                is NBTCompound -> {
                    val insertion = value.getStringTagValueOrNull("insertion")
                    val id = insertion?.takeIf { it.startsWith(CanvasItems.HOVER_PREFIX) }
                        ?.removePrefix(CanvasItems.HOVER_PREFIX)
                    if (id != null && id in hovers) {
                        value.removeTag("insertion")
                        value.setTag("hover_event", hovers.getValue(id).copy().apply {
                            setTag("action", NBTString("show_item"))
                        })
                    } else value.tags.values.toList().forEach(::hover)
                }
                is NBTList<*> -> value.tags.forEach(::hover)
            }
        }
        hover(result)
        val bodies =
            when (val body = result.getTagOrNull("body")) {
                null -> emptyList()
                is NBTCompound -> listOf(body)
                else -> result.getCompoundListTagOrThrow("body").tags
            }
        val expected =
            bodies.indices.filter { bodies[it].getStringTagValueOrNull("type") == "minecraft:item" }
        require(items.keys == expected.toSet()) { "Dialog item positions do not match the body" }
        items.forEach { (index, item) ->
            validate(item)
            bodies[index].setTag("item", item.copy())
        }
        return result
    }

    private fun validate(item: NBTCompound) {
        val id = item.getStringTagValueOrNull("id")
        require(!id.isNullOrBlank() && id != "minecraft:air") { "Dialog item must be nonempty" }
        val count = item.getTagOrNull("count")
        require(
            count == null ||
                count is NBTInt &&
                    count.asInt in NativeGeometry.ITEM_MIN_COUNT..NativeGeometry.ITEM_MAX_COUNT
        ) {
            "Dialog item count must be an integer in 1..99"
        }
    }
}

/** 沿用原有 Dialog 编码，只恢复真实物品数据；不读写 Bukkit 或修改全局 Codec。 */
internal class ItemDataShowDialog(
    dialog: Dialog,
    items: Map<Int, NBTCompound>,
    hovers: Map<String, NBTCompound> = emptyMap(),
) : WrapperPlayServerShowDialog(dialog) {
    private val snapshots = items.mapValues { (_, item) -> item.copy() }
    private val hoverSnapshots = hovers.mapValues { (_, item) -> item.copy() }

    override fun write() {
        writeMappedEntityOrDirect(dialog) { wrapper, value ->
            val payload = Dialog.encodeDirect(value, wrapper) as NBTCompound
            wrapper.writeNBTRaw(DialogItemData.restore(payload, snapshots, hoverSnapshots))
        }
    }
}
