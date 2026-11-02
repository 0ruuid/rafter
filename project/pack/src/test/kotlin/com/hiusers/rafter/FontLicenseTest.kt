package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.AutoAssets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FontLicenseTest {
    @TempDir lateinit var temp: Path

    private fun source(): Path {
        val root = temp.resolve("ui")
        for (name in listOf("fonts/rafter-mono.ttf", "theme.yml")) {
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            requireNotNull(javaClass.getResourceAsStream("/core/ui/$name")).use {
                Files.copy(it, target)
            }
        }
        return root
    }

    @Test
    fun companionIsDistributedAndTrackedWithoutChangingFontBytes() {
        val root = source()
        val license = root.resolve("fonts/rafter-mono.license")
        Files.writeString(license, "Fixture font license\n")
        val font = Files.readAllBytes(root.resolve("fonts/rafter-mono.ttf"))
        val catalog = AssetCompiler.compile(root, AutoAssets.document(root))
        assertTrue(license in catalog.sourceHashes)
        val pack = AssetCompiler.build(root, catalog, temp.resolve("output"))
        val copied = pack.resolve("licenses/fonts/rafter-mono.txt")
        assertEquals(Files.readString(license), Files.readString(copied))
        assertContentEquals(
            font,
            Files.readAllBytes(pack.resolve("assets/rafter/font/sources/rafter-mono.ttf")),
        )
        Files.writeString(license, "Changed after discovery\n")
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.build(root, catalog, temp.resolve("output"))
        }
        assertEquals("Fixture font license\n", Files.readString(copied))
    }

    @Test
    fun aFontDoesNotRequireAnInventedLicenseFile() {
        val root = source()
        val catalog = AssetCompiler.compile(root, AutoAssets.document(root))
        val pack = AssetCompiler.build(root, catalog, temp.resolve("output"))
        assertFalse(Files.exists(pack.resolve("licenses/fonts/rafter-mono.txt")))
    }

    @Test
    fun companionCannotFollowASymbolicLink() {
        val root = source()
        val external = temp.resolve("external-license")
        Files.writeString(external, "outside")
        Files.createSymbolicLink(root.resolve("fonts/rafter-mono.license"), external)
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(root, AutoAssets.document(root))
        }
    }
}
