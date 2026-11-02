package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.AutoAssets
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImageDiscoveryLimitTest {
    @TempDir lateinit var root: Path

    @Test
    fun moreThan128TinyImagesCanBeDiscoveredAndCompiled() {
        Files.createDirectories(root.resolve("fonts"))
        javaClass.getResourceAsStream("/core/ui/fonts/rafter-mono.ttf")!!.use {
            Files.copy(it, root.resolve("fonts/rafter-mono.ttf"))
        }
        javaClass.getResourceAsStream("/core/ui/theme.yml")!!.use {
            Files.copy(it, root.resolve("theme.yml"))
        }
        val images = Files.createDirectories(root.resolve("images"))
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, -1) }
        ImageIO.write(image, "png", images.resolve("item0.png").toFile())
        for (index in 1..128) Files.copy(
            images.resolve("item0.png"),
            images.resolve("item$index.png"),
        )
        val document = AutoAssets.document(root)
        assertEquals(129, (document["images"] as Map<*, *>).size)
        assertEquals(129, AssetCompiler.compile(root, document).images.size)
    }
}
