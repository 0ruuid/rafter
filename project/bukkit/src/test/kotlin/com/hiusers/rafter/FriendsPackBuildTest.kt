package com.hiusers.rafter

import com.hiusers.rafter.asset.DialogFocusShaders
import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.pack.PackBuild
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import taboolib.module.chat.Components

/** 验证真实构包与归档，不止 YAML 或 JAR。 */
class FriendsPackBuildTest {
    @TempDir lateinit var root: Path

    @Test
    fun suppliedFriendsLayoutBuildsAndPublishesAtItsActualPixelWidth() {
        Components.useAdventure = true
        DefaultBundle.install(root)
        // 准备归档不启动 HTTP 或向玩家发送。
        val config = root.resolve("config.yml")
        Files.writeString(
            config,
            Files.readString(config).replace("enabled: false", "enabled: true"),
        )
        val loaded =
            MenuRepository(
                    root,
                    { error("No scripts in the preview") },
                    ::checkStaticItemReference,
                    { Yaml(SafeConstructor(LoaderOptions())).load(it) },
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        assertTrue(candidate.pack.archive)
        assertEquals(
            setOf(432, 560, 800, 864),
            candidate.snapshot.menus.values
                .flatMap { it.pages.values }
                .filter { it.spec.mode.name == "CANVAS" && it.spec.modal == null }
                .map { it.spec.width }
                .toSet(),
        )
        assertTrue(
            candidate.assets.focusLayouts.all {
                (it.bodyWidth to it.bodyHeight) in
                    setOf(
                        CanvasGeometry.bodyWidth(560) to CanvasGeometry.bodyHeight(342),
                        CanvasGeometry.bodyWidth(800) to CanvasGeometry.bodyHeight(360),
                    )
            }
        )
        PackBuild.prepare(root, candidate.assets, candidate.pack).use { build ->
            val expected = "vec4(570.0, 350.0, 5.0, 30.0)"
            val include = "assets/rafter/shaders/include/dialog_focus.glsl"
            assertTrue(expected in Files.readString(build.folder.resolve(include)))
            for (profile in DialogFocusShaders.profiles) {
                assertTrue(
                    Files.isRegularFile(
                        build.folder.resolve(
                            "${profile.directory}/assets/minecraft/shaders/core/gui.fsh"
                        )
                    )
                )
            }
            ZipFile(assertNotNull(build.archive).toFile()).use { zip ->
                assertNotNull(zip.getEntry(include))
                assertTrue(
                    expected in
                        zip.getInputStream(zip.getEntry(include)).bufferedReader().use {
                            it.readText()
                        }
                )
            }
            assertEquals(root.resolve("generate/resource-pack"), build.commit(root))
        }
        assertTrue(Files.isRegularFile(root.resolve("generate/resource-pack/pack.mcmeta")))
        assertTrue(Files.isRegularFile(root.resolve("generate/resource-pack.zip")))
        Files.list(root.resolve("generate")).use { children ->
            assertFalse(children.anyMatch { it.fileName.toString().startsWith(".pack-job-") })
        }
    }
}
