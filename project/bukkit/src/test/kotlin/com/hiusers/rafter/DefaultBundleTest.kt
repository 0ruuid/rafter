package com.hiusers.rafter

import com.google.gson.JsonObject
import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.compile.DefaultBundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DefaultBundleTest {
    @TempDir lateinit var root: Path

    @Test
    fun vetoedBundleUpgradeKeepsRetiredAssetsUntilTheSourceBundleCanAdvance() {
        DefaultBundle.install(root)
        val menu = root.resolve("core/menus/friends.yml")
        val original = Files.readAllBytes(menu)
        val retired = root.resolve("ui/images/solid-gray.png")
        copyLegacyResourceTo("ui/images/solid-gray.png", retired)
        val bytes = Files.readAllBytes(retired)
        Files.writeString(menu, "administrator menu still referencing the older asset")
        DefaultBundle.install(root)
        assertTrue(
            Files.exists(retired),
            "Refusing a source update must also refuse retiring its possible dependencies",
        )
        assertTrue(bytes.contentEquals(Files.readAllBytes(retired)))
        assertEquals("administrator menu still referencing the older asset", Files.readString(menu))
        Files.write(menu, original)
        DefaultBundle.install(root)
        assertFalse(
            Files.exists(retired),
            "An unchanged bundle can retire a known original after its update succeeds",
        )
    }

    @Test
    fun manifestDeclaresSafeUniquePackagedResourcesIncludingTheme() {
        val loader = javaClass.classLoader
        val manifest =
            assertNotNull(loader.getResourceAsStream("defaults/bundle.json")).reader().use {
                Json.gson.fromJson(it, JsonObject::class.java)
            }
        assertEquals(
            1,
            manifest["schema-version"].asInt,
        ) // 发布清单不是作者配置。
        val files = manifest.getAsJsonArray("files").map { it.asString }
        assertEquals(files, DefaultBundle.paths)
        assertEquals(files.size, files.distinct().size)
        assertTrue("core/ui/theme.yml" in files)
        assertEquals(
            setOf(
                "core/menus/friends.yml",
                "core/menus/items.yml",
                "core/menus/tasks.yml",
                "core/menus/player-profile.yml",
            ),
            files.filter { it.startsWith("core/menus/") }.toSet(),
        )
        assertFalse(files.any { it.startsWith("fixtures/") })
        assertEquals(4, files.count { it.startsWith("core/ui/templates/friends/") })
        assertEquals(6, files.count { it.startsWith("core/ui/skins/friends/") })
        assertEquals(5, files.count { it.startsWith("core/data/") })
        for (retired in
            listOf(
                "core/menus/demo.yml",
                "core/menus/controls.yml",
                "core/menus/workbench.yml",
                "core/ui/templates/action-button.yml",
            )) {
            assertNull(
                loader.getResource(retired),
                "Retired UI must exist only under test fixtures: $retired",
            )
        }
        assertNull(
            loader.getResource("fixtures/legacy-defaults/ui/fonts/rafter-mono.ttf"),
            "Reuse the single packaged TTF",
        )
        val legacyImages =
            Path.of(assertNotNull(loader.getResource("fixtures/legacy-defaults/ui/images")).toURI())
        Files.walk(legacyImages).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val retired =
                        "core/ui/images/" +
                            legacyImages.relativize(file).toString().replace('\\', '/')
                    assertNull(
                        loader.getResource(retired),
                        "Legacy bitmap leaked into production resources: $retired",
                    )
                }
        }
        assertFalse("core/ui/licenses/rafter-mono.txt" in files)
        assertFalse("core/ui/fonts/OFL.txt" in files)
        for (name in files + manifest["backup-directory"].asString) {
            val path = Path.of(name)
            assertFalse(path.isAbsolute, name)
            assertFalse('\\' in name || ':' in name, name)
            assertTrue(name.split('/').none { it.isEmpty() || it == "." || it == ".." }, name)
            assertEquals(path, path.normalize(), name)
        }
        files.forEach { name ->
            assertEquals(
                1,
                java.util.Collections.list(loader.getResources(name)).size,
                "Duplicate resource: $name",
            )
            assertNotNull(loader.getResourceAsStream(name), name).use {}
        }
        DefaultBundle.install(root)
        assertFalse(Files.exists(root.resolve("core/ui/fonts/OFL.txt")))
        assertFalse(Files.exists(root.resolve("core/ui/licenses/rafter-mono.txt")))
        val menu = files.first { it.startsWith("core/menus/") }
        Files.writeString(root.resolve(menu), "administrator-owned menu")
        Files.writeString(root.resolve("core/ui/theme.yml"), "administrator-owned theme")
        val extra = root.resolve("core/menus/custom.yml")
        Files.writeString(extra, "unlisted administrator file")
        DefaultBundle.install(root)
        assertEquals("administrator-owned menu", Files.readString(root.resolve(menu)))
        assertEquals(
            "administrator-owned theme",
            Files.readString(root.resolve("core/ui/theme.yml")),
        )
        assertEquals("unlisted administrator file", Files.readString(extra))
    }
}
