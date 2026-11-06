package com.hiusers.rafter

import com.hiusers.rafter.asset.ModalEncoding
import com.hiusers.rafter.asset.ClientRenderProfiles
import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.pack.PackBuild
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.runtime.FrozenCanvas
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InputAppearanceIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun configuredAppearanceReachesTheModalAndEverySupportedClientProfile() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val skin = root.resolve("core/ui/skins/friends/search.yml")
        Files.writeString(skin, Files.readString(skin).replace(
            "        type: input\n",
            "        type: input\n        appearance:\n          background: transparent\n          text-color: '#123456'\n",
        ))
        val repository = MenuRepository(root, {}, ::checkStaticItemReference)
        val loaded = repository.load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.toString())
        val layout = candidate.assets.inputAppearances.single {
            it.key == ModalEncoding.key("rafter:friends", "search")
        }
        assertTrue(layout.id in 1..64)
        val compiled = candidate.snapshot.menus.getValue("rafter:friends").pages.getValue("search")
        assertEquals(compiled.modalLayout!!.inputs.single().controlBounds, layout.inputs.single().bounds)
        assertEquals("transparent", layout.inputs.single().appearance.background)
        assertEquals("#123456", layout.inputs.single().appearance.textColor)

        val session = UiSession(UUID.randomUUID(), candidate.snapshot.menus.getValue("rafter:friends"), emptyMap())
        val text = CompositeText(candidate.assets)
        fun show(): com.hiusers.rafter.render.DialogView {
            val frame = OfflineFrame.resolve(session)
            val targets = frame.resolvedNodes.values.filter {
                it.clickable && frame.visible[it.id] == true && frame.enabled[it.id] == true
            }.map { it.id }
            return CanvasRenderer.render(session, frame, text,
                session.ready(session.beginRender(), targets))
        }
        assertEquals(0, show().inputAppearanceId)
        session.rememberCanvas(FrozenCanvas.capture(session, show(), text))
        session.navigate("search")
        val view = show()
        assertEquals(layout.id, view.inputAppearanceId)
        assertEquals(listOf("input"), view.inputs.map { it.node.id })
        val shader = "rafter_v260200/assets/minecraft/shaders/core/text.fsh"
        val firstShader = PackBuild.prepare(root, candidate.assets, candidate.pack).use { build ->
            assertFalse(Files.exists(build.folder.resolve("assets/minecraft/textures/gui/sprites/widget/text_field.png")))
            for (profile in ClientRenderProfiles.all) {
                val folder = build.folder.resolve("${profile.directory}/assets/minecraft/shaders/core")
                for (program in profile.textPrograms + "position_tex_color") {
                    val vertex = Files.readString(folder.resolve("$program.vsh"))
                    val fragment = Files.readString(folder.resolve("$program.fsh"))
                    assertTrue("rafterInputPoint" in vertex, "${profile.directory}/$program.vsh")
                    assertTrue("GameTime" in fragment, "${profile.directory}/$program.fsh")
                    assertTrue("rafterInputAppearanceRegion" in fragment)
                    assertTrue("18.0 / 255.0" in fragment && "52.0 / 255.0" in fragment && "86.0 / 255.0" in fragment)
                    if (profile.vulkanInterface) {
                        val location = if (program == "position_tex_color") 6 else 8
                        assertTrue("layout(location = $location)" in vertex)
                        assertTrue("layout(location = $location)" in fragment)
                    }
                }
            }
            (System.getProperty("rafter.inputAppearance.packOutput")
                ?: System.getenv("RAFTER_INPUT_APPEARANCE_PACK_OUTPUT"))?.let { output ->
                build.folder.toFile().copyRecursively(Path.of(output).toFile(), overwrite = true)
            }
            Files.readString(build.folder.resolve(shader)).also { assertTrue("GameTime" in it) }
        }
        Files.writeString(skin, Files.readString(skin).replace("#123456", "#fEdCbA"))
        val changedLoad = repository.load()
        val changed = assertNotNull(changedLoad.candidate, changedLoad.diagnostics.toString())
        assertNotEquals(candidate.assets.fingerprint, changed.assets.fingerprint)
        PackBuild.prepare(root, changed.assets, changed.pack).use { build ->
            assertNotEquals(firstShader, Files.readString(build.folder.resolve(shader)))
        }
    }

    @Test
    fun oldAppearanceSwitchDoesNotDisableOrBreakCurrentConfiguration() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val config = root.resolve("config.yml")
        Files.writeString(config, Files.readString(config) + "\nruntime:\n  experimental-input-appearance: false\n")
        val loaded = MenuRepository(root, {}, ::checkStaticItemReference).load()
        assertNotNull(loaded.candidate, loaded.diagnostics.toString())
    }
}
