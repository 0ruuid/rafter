package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class RenderBodyOrderTest {
    @Test
    fun nativeBodiesKeepAuthoredOrderOnEitherSideOfCanvasWithoutMovingInputsOrFooter() {
        taboolib.module.chat.Components.useAdventure = true
        val request = FontRequest("test", 10.0)
        val metrics = (32..127).associateWith { 5.0 }
        val assets =
            AssetCatalog(
                images = mapOf("rafter:icon" to Glyph("\ue080", 9, 9, 10)),
                widths = metrics,
                allocation = emptyMap(),
                fontSource = Path.of("unused.ttf"),
                imageSpecs = emptyList(),
                fingerprint = "fixture",
                sourceHashes = emptyMap(),
                faces = mapOf(request.id to metrics),
                theme =
                    UiTheme(
                        "test",
                        10.0,
                        1.0,
                        emptyMap(),
                        UiTheme.Palette("#FF6666"),
                        TextSpec(value = "Close"),
                        TextSpec(value = "Error"),
                        false,
                        false,
                        false,
                    ),
                hideWarning = false,
                hideFocusOutline = false,
                hideNativeFocusOutline = false,
                fontFaces = mapOf(request.id to FontFace(request, Path.of("unused.ttf"))),
                fontFamilies = mapOf("test" to Path.of("unused.ttf")),
            )
        val text = CompositeText(assets)
        val components =
            listOf(
                mapOf(
                    "id" to "save",
                    "type" to "button",
                    "actions" to listOf(mapOf("type" to "submit")),
                ),
                mapOf("id" to "item-a", "type" to "item", "item" to "minecraft:stone"),
                mapOf("id" to "name", "type" to "input", "state" to "name", "text" to "Name"),
                mapOf("id" to "first", "type" to "text", "text" to "First"),
                mapOf("id" to "image", "type" to "image", "asset" to "rafter:icon"),
                mapOf("id" to "check", "type" to "checkbox", "state" to "enabled"),
                mapOf("id" to "item-b", "type" to "item", "item" to "minecraft:dirt"),
                mapOf("id" to "last", "type" to "text", "text" to "Last"),
                mapOf("id" to "hidden", "type" to "text", "text" to "Hidden"),
            )
        fun key(body: RenderBody): String =
            when (body) {
                is CanvasCarrierBody ->
                    error("No Canvas carrier belongs to a native or mixed-page fixture")
                is ItemBody -> body.node.id
                is Message ->
                    body.parts
                        .joinToString("") { it.text.toPlainText() }
                        .let { plain ->
                            when {
                                '\ue001' in plain -> "canvas"
                                '\ue080' in plain -> "image"
                                plain.startsWith("Error\n") -> "error"
                                else -> plain
                            }
                        }
            }
        val nativeOrder = listOf("item-a", "Resolved", "image", "item-b", "Last", "error")
        for ((mode, position) in
            listOf(
                "native" to null,
                "canvas" to null,
                "canvas" to "before-canvas",
                "canvas" to "after-canvas",
            )) {
            val page =
                mapOf(
                    "mode" to mode,
                    "title" to "Order",
                    "width" to 90,
                    "components" to components,
                ) +
                    (if (mode == "canvas") mapOf("elements" to emptyList<Any>()) else emptyMap()) +
                    (position?.let { mapOf("native-body-position" to it) } ?: emptyMap())
            val result =
                MenuCompiler(assets.dimensions())
                    .compile(mapOf("order.yml" to (minimal() + ("pages" to mapOf("main" to page)))))
            val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
            val focus = DialogFocusShaders.bind(assets, snapshot)
            assertTrue(
                focus.focusLayouts.isEmpty(),
                "Mixed pages cannot reuse Canvas-only focus geometry",
            )
            assertEquals(
                listOf("item-a", "item-a", "first", "image", "item-b", "item-b", "last", "hidden"),
                focus.nativeFocusLayouts.map { it.component },
            )
            val menu = snapshot.menus.getValue("test:menu")
            val session = UiSession(UUID.randomUUID(), menu, emptyMap())
            val nodes = session.current().spec.components
            val visible = nodes.associate { it.id to (it.id != "hidden") }
            val resolved =
                nodes.single { it.id == "first" }.copy(text = TextSpec(value = "Resolved"))
            val frame = Frame(emptyMap(), visible, visible, mapOf("first" to resolved))
            val errors = mapOf("name" to "invalid")
            val view =
                if (mode == "canvas") CanvasRenderer.render(session, frame, text, errors = errors)
                else NativeRenderer.render(session, frame, text, errors = errors)
            val expected =
                when {
                    mode == "native" -> nativeOrder
                    position == "before-canvas" -> nativeOrder + "canvas"
                    else -> listOf("canvas") + nativeOrder
                }
            assertEquals(expected, view.bodies.map(::key), "$mode / $position")
            assertEquals(view.bodies.filterIsInstance<Message>(), view.messages)
            assertEquals(listOf("item-a", "item-b"), view.items.map { it.node.id })
            assertEquals(listOf("name", "check"), view.inputs.map { it.node.id })
            assertEquals(listOf("save"), view.buttons.map { it.node.id })
            assertEquals("__close", view.close.node.id)
        }
    }
}
