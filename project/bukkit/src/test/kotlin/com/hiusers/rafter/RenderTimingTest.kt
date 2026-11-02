package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/** 仅显式启用时测量 CPU 耗时，不作为默认界面的性能承诺。 */
@EnabledIfEnvironmentVariable(named = "RAFTER_RENDER_BENCHMARK", matches = "1")
class RenderTimingTest {
    @TempDir lateinit var temp: Path

    @Test
    fun renderAndWireTiming() {
        taboolib.module.chat.Components.useAdventure = true
        val root = temp.resolve("core/ui")
        copyLegacyUiTo(root)
        val assets = AssetCompiler.compile(root, legacyUiDocument())
        val text = CompositeText(assets)
        val menu =
            MenuCompiler(
                    assets.dimensions(),
                    textCheck = text::validate,
                    canvasTextCheck = text::validateCanvas,
                    styles = assets.theme.styles,
                )
                .compile(mapOf("demo.yml" to legacyYaml("core/menus/demo.yml")))
                .snapshot!!
                .menus
                .getValue("rafter:demo")
        val results = linkedMapOf<String, Any?>()
        for (pageId in listOf("home", "journal", "timeline")) {
            val session = UiSession(UUID.randomUUID(), menu, emptyMap())
            if (session.page != pageId) session.navigate(pageId)
            val visible = session.current().scene.associate { it.node.id to true }
            val frame = Frame(mapOf(BindingSpec("state", "name") to "旅行者"), visible, visible)
            val targets =
                session.current().scene.filter { it.node.clickable }.map { it.node.id } + "__close"
            val render = mutableListOf<Double>()
            val wire = mutableListOf<Double>()
            var fragments = 0
            var bytes = 0
            repeat(15) { n ->
                val handles = session.ready(session.beginRender(), targets)
                val t0 = System.nanoTime()
                val view = CanvasRenderer.render(session, frame, text, handles)
                val t1 = System.nanoTime()
                val encoded = DialogTransport().wire(view.messages.single().parts)
                val t2 = System.nanoTime()
                if (n >= 5) {
                    render += (t1 - t0) / 1_000_000.0
                    wire += (t2 - t1) / 1_000_000.0
                }
                fragments = view.messages.single().parts.size
                bytes =
                    net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                        .serialize(encoded)
                        .toByteArray()
                        .size
            }
            fun summary(values: List<Double>): Map<String, Any> {
                val sorted = values.sorted()
                return mapOf("median-ms" to sorted[sorted.size / 2], "max-ms" to sorted.last())
            }
            results[pageId] =
                mapOf(
                    "render" to summary(render),
                    "wire" to summary(wire),
                    "fragments" to fragments,
                    "json-bytes" to bytes,
                )
            println("RENDER_TIMING $pageId ${results[pageId]}")
        }
        Json.write(Path.of("build/benchmarks/render-timing-legacy.json"), results)
    }
}
