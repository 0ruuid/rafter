package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.DefaultBundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AutomaticAssetsTest {
    @TempDir lateinit var temp: Path

    @Test
    fun imageFilesDeclareTheirDensityAndNoAssetsYamlIsNeeded() {
        for (name in
            listOf(
                "core/ui/theme.yml",
                "core/ui/images/frame@4x.png",
                "core/ui/images/paper.png",
            )) {
            copyLegacyResourceTo(name, temp.resolve(name))
        }
        assertFalse(Files.exists(temp.resolve("core/ui/assets.yml")))
        javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            "png",
            temp.resolve("core/ui/images/pixel.png").toFile(),
        )
        val doc = AutoAssets.document(temp.resolve("core/ui"))
        assertFalse("equipment" in doc)
        assertFalse("schema-version" in doc)
        @Suppress("UNCHECKED_CAST") val images = doc["images"] as Map<String, Map<String, Any?>>
        assertEquals(621, images.getValue("rafter:frame")["width"])
        assertEquals(4, images.getValue("rafter:frame")["scale"])
        assertEquals(189, images.getValue("rafter:paper")["height"])
        assertEquals(1, images.getValue("rafter:paper")["scale"])
        assertEquals(3, images.size)
        assertEquals(18, images.getValue("rafter:pixel")["width"])
    }

    @Test
    fun generatedMeterSourceCannotOverwriteAnAuthorImage() {
        copyLegacyResourceTo("ui/theme.yml", temp.resolve("core/ui/theme.yml"))
        val image = temp.resolve("core/ui/images/_meter/pixel.png")
        Files.createDirectories(image.parent)
        javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            "png",
            image.toFile(),
        )
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AutoAssets.document(temp.resolve("core/ui"))
            }
        assertTrue(failure.message.orEmpty().contains("Reserved generated image namespace"))
    }

    @Test
    fun explicitMergeSourcesKeepTheirConfiguredOrder() {
        val sources = listOf("../Override", "../CraftEngine/generated/resource_pack.zip")
        val settings =
            com.hiusers.rafter.pack.PackSettings.read(mapOf("merge" to mapOf("sources" to sources)))
        assertEquals(sources, settings.sources)
        assertFalse(settings.archive)
        assertNull(settings.host)
        val defaults = com.hiusers.rafter.pack.PackSettings.read(emptyMap())
        assertEquals(emptyList(), defaults.sources)
        assertFalse(defaults.archive)
        assertNull(defaults.host)
    }

    @Test
    fun retiringShippedDefaultsNeverDeletesModifiedOrActiveFiles() {
        fun hash(value: String) =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val old = "shipped example"
        val paths =
            listOf("core/menus/retired.yml", "core/menus/modified.yml", "core/menus/active.yml")
        for (path in paths) {
            val file = temp.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, if ("modified" in path) "administrator changes" else old)
        }
        DefaultBundle.retire(
            temp,
            paths.associateWith { listOf(hash(old)) },
            setOf("core/menus/active.yml"),
        )
        assertFalse(Files.exists(temp.resolve(paths[0])))
        assertEquals("administrator changes", Files.readString(temp.resolve(paths[1])))
        assertEquals(old, Files.readString(temp.resolve(paths[2])))
    }

    @Test
    fun uiOnlyContractRejectsRetiredPersonComponents() {
        assertFalse("player-preview" in com.hiusers.rafter.compile.Contract.nodes)
        assertFalse(
            com.hiusers.rafter.compile.Contract.schema().toString().contains("player-preview")
        )
        assertFalse(DefaultBundle.paths.any { it.contains("player-preview") })
        val retired =
            mapOf("id" to "self", "type" to "player-preview", "width" to 126, "height" to 198)
        assertNull(
            com.hiusers.rafter.compile
                .MenuCompiler()
                .compile(mapOf("menu.yml" to minimal(components = listOf(retired))))
                .snapshot
        )
    }
}
