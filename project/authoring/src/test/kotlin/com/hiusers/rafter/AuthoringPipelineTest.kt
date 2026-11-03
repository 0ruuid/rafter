package com.hiusers.rafter

import com.hiusers.rafter.authoring.*
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.compile.MenuRepository
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuthoringPipelineTest {
    @TempDir lateinit var root: Path

    private fun project(): Candidate {
        taboolib.module.chat.Components.useAdventure = true
        AuthoringFixtures.copy(root)
        val loaded =
            MenuRepository(
                    root,
                    { error("Unexpected script") },
                    { _, _, _ -> error("Unexpected provider") },
                )
                .load()
        return assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
    }

    @Test
    fun currentFriendsModalAndTasksCompileAndPreviewWithoutPlatform() {
        val candidate = project()
        val manifest = AuthoringContract.manifest("test", emptyMap())
        val contract = AuthoringExport.write(root, manifest, candidate)
        assertTrue(Files.exists(contract.resolve("authoring.json")))
        assertTrue(Files.exists(contract.resolve("theme.schema.json")))
        assertFalse(Files.exists(contract.resolve("editor.json")))
        assertEquals(
            false,
            (manifest["authoring-capabilities"] as Map<*, *>)["native-free-position"],
        )
        for ((menu, page, scenario) in
            listOf(
                Triple("rafter:friends", "friends", PreviewScenario()),
                Triple(
                    "rafter:friends",
                    "search",
                    PreviewScenario(form = mapOf("input" to "Player")),
                ),
                Triple("rafter:tasks", "journal", PreviewScenario()),
            )) {
            val output = PreviewRenderer.write(root, candidate, menu, page, scenario)
            val image = assertNotNull(ImageIO.read(output.resolve("preview.png").toFile()))
            assertTrue(image.width > 0 && image.height > 0)
            assertTrue(
                Files.readString(output.resolve("report.json")).contains("not-minecraft-screenshot")
            )
        }
        // 可选输出只供人工检查，不写入正式默认配置或测试服。
        System.getProperty("rafter.authoring.fixture")?.let { destination ->
            val target = Path.of(destination)
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).forEach { source ->
                    val file = target.resolve(root.relativize(source))
                    Files.createDirectories(file.parent)
                    Files.copy(source, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    @Test
    fun modalPreviewUsesValidatedInitialStateForSingleAndMultilineInputs() {
        val candidate = project()
        val scenario =
            PreviewScenario(
                state =
                    mapOf(
                        "target-name" to "Player",
                        "request-message" to "First line\nSecond line",
                    )
            )
        val context = PreviewContext(candidate, "rafter:friends", "add", scenario)
        assertEquals("add", context.session.page)
        assertNotNull(context.session.modalBackdrop)
        assertTrue(context.view.modal)
        assertEquals(
            listOf("Player", "First line\nSecond line"),
            context.view.inputs.map { it.initial },
        )
        val output = PreviewRenderer.write(root, candidate, "rafter:friends", "add", scenario)
        assertNotNull(ImageIO.read(output.resolve("preview.png").toFile()))
        assertFailsWith<IllegalArgumentException> {
            PreviewContext(
                candidate,
                "rafter:friends",
                "add",
                PreviewScenario(state = mapOf("target-name" to "x".repeat(17))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PreviewContext(
                candidate,
                "rafter:friends",
                "add",
                PreviewScenario(state = mapOf("undeclared" to "value")),
            )
        }
    }

    @Test
    fun publicationFailureAndSourceChangesRetainTheCompletePreviousExport() {
        val candidate = project()
        val manifest = AuthoringContract.manifest("test", emptyMap())
        val output = AuthoringExport.write(root, manifest, candidate)
        val before = Files.readString(output.resolve("authoring.json"))
        assertFailsWith<IllegalStateException> {
            GeneratedFiles.publish(root, "contract") { directory ->
                Files.writeString(directory.resolve("authoring.json"), "partial")
                error("Injected write failure")
            }
        }
        assertEquals(before, Files.readString(output.resolve("authoring.json")))
        Files.writeString(root.resolve("config.yml"), "default-menu: changed:menu\n")
        assertFailsWith<IllegalArgumentException> {
            AuthoringExport.write(root, manifest, candidate)
        }
        assertEquals(before, Files.readString(output.resolve("authoring.json")))
        AuthoringExport.write(root, manifest, null)
        assertFalse(Files.exists(output.resolve("layouts.json")))
        assertFalse(Files.exists(output.resolve("assets.json")))
        assertTrue(
            Files.readString(output.resolve("authoring.json")).contains("\"has-candidate\": false")
        )
    }

    @Test
    fun missingEnvironmentIsReportedAndPreviewStubsNeverBecomeMarkup() {
        taboolib.module.chat.Components.useAdventure = true
        AuthoringFixtures.copy(root)
        Files.writeString(root.resolve("config.yml"), "default-menu: test:script\n")
        Files.writeString(
            root.resolve("core/menus/script.yml"),
            """
            id: test:script
            entry: home
            pages:
              home:
                mode: canvas
                width: 300
                height: 54
                elements:
                  - id: caption
                    type: text
                    x: 9
                    y: 9
                    text: "{{ literal 'sample' }}"
            """
                .trimIndent(),
        )
        val checks = OfflineChecks()
        val loaded = MenuRepository(root, checks::script, checks::extension).load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.toString())
        assertTrue(checks.incomplete)
        assertTrue(checks.report().any { it["status"] == "unavailable" })
        val requests = PreviewInputs.describe(candidate.snapshot).getValue("requests") as Map<*, *>
        val key = requests.keys.single() as String
        assertFailsWith<IllegalArgumentException> {
            PreviewContext(candidate, "test:script", "home", PreviewScenario())
        }
        val literal = "&a[run_command=/op player]"
        val context =
            PreviewContext(
                candidate,
                "test:script",
                "home",
                PreviewScenario(stubs = mapOf(key to literal)),
            )
        assertEquals(setOf(key), context.usedStubs)
        val rendered =
            context.view.messages.flatMap { it.parts }.joinToString { it.text.toRawMessage() }
        assertTrue(rendered.contains(literal))
        assertFalse(rendered.contains("clickEvent"))
        assertFailsWith<IllegalArgumentException> {
            PreviewContext(
                candidate,
                "test:script",
                "home",
                PreviewScenario(stubs = mapOf("binding:typo" to "x")),
            )
        }
    }

    @Test
    fun generatedAndScenarioPathsCannotEscapeProject() {
        Files.createDirectories(root.resolve("generate"))
        assertFailsWith<IllegalArgumentException> { GeneratedFiles.publish(root, "../outside") {} }
        assertFailsWith<IllegalArgumentException> { PreviewScenario.read(root, "../outside.yml") }
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(root.resolve("generate/contract"), outside)
        assertFailsWith<IllegalArgumentException> { GeneratedFiles.publish(root, "contract") {} }
        assertEquals(0L, Files.list(outside).use { it.count() })
    }
}
