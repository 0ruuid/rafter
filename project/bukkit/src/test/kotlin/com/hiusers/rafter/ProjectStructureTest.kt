package com.hiusers.rafter

import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.ProjectPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectStructureTest {
    @TempDir lateinit var root: Path

    @Test
    fun sourcesMoveWithoutTouchingRootSettingsKetherOrLanguages() {
        val config = javaClass.getResourceAsStream("/config.yml")!!.reader().use { it.readText() }
        Files.writeString(root.resolve("config.yml"), config)
        Files.writeString(root.resolve("kether.yml"), "# keep kether\n")
        Files.createDirectories(root.resolve("lang"))
        Files.writeString(root.resolve("lang/zh_CN.yml"), "# keep language\n")
        val old = root.resolve("menus/friends.yml")
        Files.createDirectories(old.parent)
        javaClass.getResourceAsStream("/${ProjectPaths.MENUS}/friends.yml")!!.use {
            Files.copy(it, old)
        }
        DefaultBundle.install(root)
        assertFalse(Files.exists(old), "Known unchanged old defaults are retired, not loaded twice")
        assertTrue(Files.exists(root.resolve("${ProjectPaths.SKINS}/friends/search.yml")))
        assertEquals(config, Files.readString(root.resolve("config.yml")))
        assertEquals("# keep kether\n", Files.readString(root.resolve("kether.yml")))
        assertEquals("# keep language\n", Files.readString(root.resolve("lang/zh_CN.yml")))
        taboolib.module.chat.Components.useAdventure = true
        val result = MenuRepository(root, {}, { _, _, _ -> }).load()
        assertNotNull(result.candidate, result.diagnostics.joinToString("\n"))
    }

    @Test
    fun customizedRootSettingsKeepOldSourcesUntilManualMigration() {
        val config =
            javaClass.getResourceAsStream("/config.yml")!!.reader().use { it.readText() } +
                "\n# administrator comment\n"
        Files.writeString(root.resolve("config.yml"), config)
        val old = root.resolve("menus/friends.yml")
        Files.createDirectories(old.parent)
        javaClass.getResourceAsStream("/${ProjectPaths.MENUS}/friends.yml")!!.use {
            Files.copy(it, old)
        }
        DefaultBundle.install(root)
        assertTrue(Files.exists(old), "A vetoed bundle must not partially retire source files")
        assertEquals(config, Files.readString(root.resolve("config.yml")))
        val loaded = MenuRepository(root, {}, { _, _, _ -> }).load()
        assertNull(loaded.candidate)
        assertEquals("SOURCE_DIRECTORY", loaded.diagnostics.single().code)
    }

    @Test
    fun customizedLegacySourcesArePreservedAndDiagnosedRatherThanSilentlyIgnored() {
        val old = root.resolve("menus/custom.yml")
        Files.createDirectories(old.parent)
        Files.writeString(old, "# owner-authored source\n")
        DefaultBundle.install(root)
        val result = MenuRepository(root, {}, { _, _, _ -> }).load()
        assertNull(result.candidate)
        assertEquals("SOURCE_DIRECTORY", result.diagnostics.single().code)
        assertEquals("# owner-authored source\n", Files.readString(old))
    }
}
