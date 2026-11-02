package com.hiusers.rafter

import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.pack.PackMerge
import com.hiusers.rafter.pack.PackSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackSettingsTest {
    @TempDir lateinit var temp: Path
    private val root
        get() = temp.resolve("plugins/Rafter")

    private val marker = "assets/craftengine/textures/merge-marker.txt"
    private val metadata = """{"pack":{"pack_format":88,"min_format":[88,0],"max_format":[97,1]}}"""

    private fun craftEngine(): Path {
        val path = temp.resolve("plugins/CraftEngine/generated/resource_pack.zip")
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((name, text) in mapOf("pack.mcmeta" to metadata, marker to "craftengine-only")) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    private fun settings(vararg sources: String): PackSettings {
        Files.createDirectories(root)
        val repository = MenuRepository(root, {}, { _, _, _ -> })
        val read =
            MenuRepository::class.java.getDeclaredMethod("pack", Map::class.java).apply {
                isAccessible = true
            }
        return read.invoke(
            repository,
            mapOf(
                "resource-pack" to
                    mapOf(
                        "archive" to false,
                        "merge" to mapOf("sources" to sources.toList()),
                        "self-host" to
                            mapOf(
                                "enabled" to false,
                                "send-on-join" to false,
                                "send-after-build" to false,
                            ),
                    )
            ),
        ) as PackSettings
    }

    private fun merge(settings: PackSettings): Path {
        val stage = temp.resolve("stage")
        Json.write(
            stage.resolve("pack.mcmeta"),
            mapOf(
                "pack" to
                    mapOf(
                        "pack_format" to 88,
                        "min_format" to listOf(88, 0),
                        "max_format" to listOf(97, 1),
                    )
            ),
        )
        PackMerge.merge(root, stage, settings.sources)()
        return stage
    }

    @Test
    fun hostingIsOptInWithLocalFallbackAndExplicitPublicUrl() {
        val defaults = PackSettings.read(emptyMap())
        assertFalse(defaults.archive)
        assertNull(defaults.host)
        val local =
            PackSettings.read(
                mapOf(
                    "self-host" to
                        mapOf(
                            "enabled" to true,
                            "bind-address" to "0.0.0.0",
                            "port" to 9197,
                            "public-url" to " ",
                        )
                )
            )
        assertTrue(local.archive)
        val host = assertNotNull(local.host)
        assertEquals("0.0.0.0", host.bind)
        assertEquals("http://127.0.0.1:9197", host.publicUrl)
        assertFalse(host.join)
        assertFalse(host.afterBuild)
        assertFalse(host.required)
        val ipv6 =
            PackSettings.read(
                mapOf("self-host" to mapOf("enabled" to true, "bind-address" to "::1"))
            )
        assertEquals("http://[::1]:8187", assertNotNull(ipv6.host).publicUrl)
        assertFailsWith<IllegalArgumentException> {
            PackSettings.read(
                mapOf("self-host" to mapOf("enabled" to true, "bind-address" to "192.168.1.20"))
            )
        }
        val external =
            PackSettings.read(
                mapOf(
                    "self-host" to
                        mapOf(
                            "enabled" to true,
                            "public-url" to "https://packs.example.test/proxy/",
                            "send-on-join" to true,
                            "send-after-build" to true,
                        )
                )
            )
        val publicHost = assertNotNull(external.host)
        assertEquals("https://packs.example.test/proxy", publicHost.publicUrl)
        assertTrue(publicHost.join)
        assertTrue(publicHost.afterBuild)
        assertFailsWith<IllegalArgumentException> {
            PackSettings.read(
                mapOf(
                    "self-host" to mapOf("enabled" to true, "public-url" to "http://0.0.0.0:9197")
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PackSettings.read(
                mapOf(
                    "self-host" to
                        mapOf("enabled" to true, "public-url" to "file:///resource-pack.zip")
                )
            )
        }
    }

    @Test
    fun emptyMergeSourcesDoNotCopyDetectedCraftEnginePack() {
        craftEngine()
        val settings = settings()
        val stage = merge(settings)
        assertFalse(
            Files.exists(stage.resolve(marker)),
            "merge.sources: [] must not copy the detected CraftEngine pack",
        )
        assertEquals(emptyList(), settings.sources)
        assertFalse(settings.archive)
        assertNull(settings.host)
    }

    @Test
    fun explicitlyConfiguredCraftEnginePackStillMerges() {
        craftEngine()
        val relative = "../CraftEngine/generated/resource_pack.zip"
        val settings = settings(relative)
        assertEquals(listOf(relative), settings.sources)
        assertEquals("craftengine-only", Files.readString(merge(settings).resolve(marker)))
    }

    @Test
    fun explicitOtherPackDoesNotImplicitlyMergeDetectedCraftEnginePack() {
        craftEngine()
        val source = temp.resolve("plugins/CustomPack")
        Files.createDirectories(source)
        Files.writeString(source.resolve("pack.mcmeta"), metadata)
        val own = "assets/custom/textures/merge-marker.txt"
        Files.createDirectories(source.resolve(own).parent)
        Files.writeString(source.resolve(own), "custom-only")
        val settings = settings("../CustomPack")
        assertEquals(listOf("../CustomPack"), settings.sources)
        val stage = merge(settings)
        assertEquals("custom-only", Files.readString(stage.resolve(own)))
        assertFalse(Files.exists(stage.resolve(marker)))
    }

    @Test
    fun missingCraftEngineAndEmptySourcesNeedNoExternalPack() {
        val settings = settings()
        assertEquals(emptyList(), settings.sources)
        assertFalse(Files.exists(merge(settings).resolve(marker)))
    }
}
