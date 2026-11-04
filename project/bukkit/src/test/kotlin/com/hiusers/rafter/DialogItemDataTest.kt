package com.hiusers.rafter

import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTByte
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTFloat
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.nbt.NBTType
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer
import com.hiusers.rafter.transport.DialogItemData
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DialogItemDataTest {
    @Test
    fun decodeKeepsCompleteComponentsAndDropsDataVersion() {
        val root =
            item("minecraft:diamond_sword", 3).apply {
                setTag("DataVersion", NBTInt(4189))
                setTag("components", richComponents())
            }

        val decoded = DialogItemData.decode(encode(root))
        val expected = root.copy().apply { removeTag("DataVersion") }

        assertEquals(expected, decoded)
        assertNull(decoded.getTagOrNull("DataVersion"))
        val components = decoded.getCompoundTagOrThrow("components")
        assertEquals(richComponents(), components)
        assertNotNull(components.getTagOrNull("!minecraft:removed_component"))
    }

    @Test
    fun restoreUsesBodyIndexesKeepsSourcesAndDeepCopiesItems() {
        val dialog = dialog()
        val first = item("minecraft:diamond_sword", 3).apply {
            setTag("components", richComponents())
        }
        val second = item("minecraft:apple", 5).apply {
            setTag("components", NBTCompound().apply { setTag("minecraft:damage", NBTInt(2)) })
        }
        val items = mapOf(0 to first, 2 to second)
        val dialogBefore = dialog.copy()
        val itemsBefore = items.mapValues { (_, value) -> value.copy() }

        val restored = DialogItemData.restore(dialog, items)
        val bodies = restored.getCompoundListTagOrThrow("body")

        assertEquals(dialogBefore, dialog)
        assertEquals(itemsBefore, items)
        assertEquals(first, bodies.getTag(0).getCompoundTagOrThrow("item"))
        assertEquals(second, bodies.getTag(2).getCompoundTagOrThrow("item"))
        assertEquals(NBTString("middle"), bodies.getTag(1).getTagOrNull("text"))
        assertEquals(NBTString("first"), bodies.getTag(0).getTagOrNull("label"))
        assertEquals(dialog.getTagOrNull("metadata"), restored.getTagOrNull("metadata"))

        dialog.getCompoundListTagOrThrow("body").getTag(0).setTag("label", NBTString("dialog changed"))
        dialog.getCompoundTagOrThrow("metadata").setTag("version", NBTInt(99))
        first.setTag("id", NBTString("minecraft:changed"))
        first.getCompoundTagOrThrow("components").setTag("minecraft:damage", NBTInt(99))
        second.setTag("count", NBTInt(1))

        assertEquals(NBTString("first"), bodies.getTag(0).getTagOrNull("label"))
        assertEquals(NBTInt(2), restored.getCompoundTagOrThrow("metadata").getTagOrNull("version"))
        val restoredFirst = bodies.getTag(0).getCompoundTagOrThrow("item")
        assertEquals(NBTString("minecraft:diamond_sword"), restoredFirst.getTagOrNull("id"))
        assertEquals(
            NBTInt(17),
            restoredFirst.getCompoundTagOrThrow("components").getTagOrNull("minecraft:damage"),
        )
        assertEquals(NBTInt(5), bodies.getTag(2).getCompoundTagOrThrow("item").getTagOrNull("count"))
    }

    @Test
    fun restoreSupportsTheSingleBodyCompactEncoding() {
        val original =
            NBTCompound().apply {
                setTag("body", dialog().getCompoundListTagOrThrow("body").getTag(0).copy())
            }
        val item = item("minecraft:paper", 3).apply { setTag("components", richComponents()) }
        val restored = DialogItemData.restore(original, mapOf(0 to item))
        assertEquals(item, restored.getCompoundTagOrThrow("body").getCompoundTagOrThrow("item"))
        assertEquals(
            NBTString("minecraft:stone"),
            original.getCompoundTagOrThrow("body").getCompoundTagOrThrow("item").getTagOrNull("id"),
        )
    }

    @Test
    fun decodeAndRestoreRejectInvalidNbtPositionsAndNonItemSlots() {
        val invalidRoots =
            listOf<NBT>(
                NBTString("not a compound"),
                NBTCompound().apply { setTag("count", NBTInt(1)) },
                item("minecraft:air"),
                item("minecraft:stone", 0),
                item("minecraft:stone", 100),
                item("minecraft:stone").apply { setTag("count", NBTString("one")) },
            )
        invalidRoots.forEach { invalid -> assertFails { DialogItemData.decode(encode(invalid)) } }

        val dialog = dialog()
        assertFails { DialogItemData.restore(dialog, mapOf(0 to item(), 3 to item())) }
        assertFails { DialogItemData.restore(dialog, mapOf(0 to item(), 1 to item())) }
    }

    private fun item(id: String = "minecraft:stone", count: Int? = 1) =
        NBTCompound().apply {
            setTag("id", NBTString(id))
            count?.let { setTag("count", NBTInt(it)) }
        }

    private fun richComponents() =
        NBTCompound().apply {
            setTag("minecraft:item_model", NBTString("rafter:blade"))
            setTag(
                "minecraft:enchantments",
                NBTCompound().apply {
                    setTag(
                        "levels",
                        NBTCompound().apply {
                            setTag("minecraft:sharpness", NBTInt(5))
                            setTag("minecraft:looting", NBTInt(2))
                        },
                    )
                    setTag("show_in_tooltip", NBTByte(true))
                },
            )
            setTag("minecraft:custom_name", NBTString("{\"text\":\"回声之刃\"}"))
            setTag(
                "minecraft:lore",
                NBTList.createStringList().apply {
                    addTag(NBTString("{\"text\":\"第一行\"}"))
                    addTag(NBTString("{\"text\":\"第二行\"}"))
                },
            )
            setTag("minecraft:damage", NBTInt(17))
            setTag(
                "minecraft:custom_model_data",
                NBTCompound().apply {
                    setTag(
                        "floats",
                        NBTList(NBTType.FLOAT).apply {
                            addTag(NBTFloat(1.25f))
                            addTag(NBTFloat(-0.5f))
                        },
                    )
                    setTag("strings", NBTList.createStringList().apply { addTag(NBTString("variant")) })
                },
            )
            setTag(
                "minecraft:custom_data",
                NBTCompound().apply {
                    setTag("int", NBTInt(42))
                    setTag("float", NBTFloat(0.75f))
                },
            )
            setTag("!minecraft:removed_component", NBTCompound())
        }

    private fun dialog() =
        NBTCompound().apply {
            setTag("title", NBTString("Test"))
            setTag("metadata", NBTCompound().apply { setTag("version", NBTInt(2)) })
            setTag(
                "body",
                NBTList.createCompoundList().apply {
                    addTag(
                        NBTCompound().apply {
                            setTag("type", NBTString("minecraft:item"))
                            setTag("label", NBTString("first"))
                            setTag("item", item("minecraft:stone"))
                        },
                    )
                    addTag(
                        NBTCompound().apply {
                            setTag("type", NBTString("minecraft:plain_message"))
                            setTag("text", NBTString("middle"))
                        },
                    )
                    addTag(
                        NBTCompound().apply {
                            setTag("type", NBTString("minecraft:item"))
                            setTag("label", NBTString("last"))
                            setTag("item", item("minecraft:dirt"))
                        },
                    )
                },
            )
        }

    // 用真实 named NBT + GZIP 生成与 Paper serializeAsBytes 相同的输入形态。
    private fun encode(root: NBT): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            DataOutputStream(gzip).use { output ->
                DefaultNBTSerializer.INSTANCE.serializeTag(output, root, true)
            }
        }
        return bytes.toByteArray()
    }
}
