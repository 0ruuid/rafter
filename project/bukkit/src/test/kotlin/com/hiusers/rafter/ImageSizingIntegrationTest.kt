package com.hiusers.rafter

import com.hiusers.rafter.asset.ImageSizing
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml

class ImageSizingIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun repositoryCollectsRequestedImagesAndKeepsDrawBoundsSeparateFromIndependentHits() {
        taboolib.module.chat.Components.useAdventure = true
        for (name in listOf("core/ui/theme.yml", "core/ui/fonts/rafter-mono.ttf")) {
            val destination = root.resolve(name)
            copyLegacyResourceTo(name, destination)
        }
        Files.createDirectories(root.resolve("core/ui/images"))
        ImageIO.write(
            BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB),
            "png",
            root.resolve("core/ui/images/icon.png").toFile(),
        )
        Files.writeString(root.resolve("config.yml"), "default-menu: test:images\n")
        Files.createDirectories(root.resolve("core/menus"))
        val menuFile = root.resolve("core/menus/images.yml")
        val authored =
            """
            id: test:images
            entry: home
            pages:
              home:
                mode: canvas
                title: Images
                width: 180
                elements:
                  - id: icon
                    type: image
                    asset: rafter:icon
                    height: 25
                  - id: close
                    type: hit-area
                    y: 36
                    width: 90
                    height: 18
                    actions:
                      - type: close
              detail:
                mode: native
                title: Detail
                components:
                  - id: icon
                    type: image
                    asset: rafter:icon
                    height: 13
            """
                .trimIndent()
        Files.writeString(menuFile, authored)
        val repository = MenuRepository(root, {}, { _, _, _ -> }, { Yaml().load(it) })
        val loaded = repository.load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.toString())
        assertEquals(600_000L, candidate.timeoutMillis)
        val menu = candidate.snapshot.menus.getValue("test:images")
        val canvas = menu.pages.getValue("home")
        val image = canvas.spec.nodes.first().content as NodeContent.Image
        val glyph = candidate.assets.images.getValue(image.asset)
        assertEquals(54, glyph.width)
        assertEquals(27, glyph.height)
        val spec = candidate.assets.imageSpecs.single { it.id == image.asset }
        assertEquals(50, spec.contentWidth)
        assertEquals(25, spec.contentHeight)
        assertEquals(50, canvas.scene.first().bounds.width)
        assertEquals(25, canvas.scene.first().bounds.height)
        assertEquals(90, canvas.scene.last().bounds.width)
        assertEquals(18, canvas.scene.last().bounds.height)
        assertEquals(36, canvas.scene.last().bounds.y)
        assertEquals(2, ImageSizing.requests(candidate.snapshot).size)
        assertEquals(20 to 10, candidate.assets.sourceDimensions().getValue("rafter:icon"))
        val native = menu.pages.getValue("detail").spec.nodes.single().content as NodeContent.Image
        assertNotEquals(image.asset, native.asset)
        assertEquals(27, candidate.assets.images.getValue(native.asset).width)
        assertEquals(18, candidate.assets.images.getValue(native.asset).height)
        val view =
            CanvasRenderer.render(
                UiSession(UUID.randomUUID(), menu, emptyMap()),
                Frame(
                    emptyMap(),
                    mapOf("icon" to true, "close" to true),
                    mapOf("icon" to true, "close" to true),
                ),
                CompositeText(candidate.assets),
                handles = mapOf("close" to "only-button"),
            )
        assertTrue(view.messages.flatMap { it.parts }.any { it.handle == "only-button" })
        assertTrue(
            AuthoringContract.layouts(candidate.snapshot).toString().contains("requested-height=25")
        )
        assertEquals(authored, Files.readString(menuFile))
    }
}
