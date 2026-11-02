package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class IndependentCanvasRenderingTest {
    @Test
    fun independentVisualsAndTopmostHitRegionsPreserveRichHover() {
        taboolib.module.chat.Components.useAdventure = true
        val request = FontRequest("test", 10.0)
        val asset =
            AssetCatalog(
                images = mapOf("rafter:icon" to Glyph("\ue080", 9, 9, 10)),
                widths = (32..127).associateWith { 5.0 },
                allocation = emptyMap(),
                fontSource = Path.of("unused.ttf"),
                imageSpecs = listOf(ImageSpec("rafter:icon", 9, 9, null, null)),
                fingerprint = "fixture",
                sourceHashes = emptyMap(),
                faces = mapOf(request.id to (32..127).associateWith { 5.0 }),
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
        val text = CompositeText(asset)
        val document =
            mapOf(
                "id" to "test:canvas",
                "entry" to "home",
                "pages" to
                    mapOf(
                        "home" to
                            mapOf(
                                "mode" to "canvas",
                                "title" to "Test",
                                "width" to 90,
                                "elements" to
                                    listOf(
                                        mapOf(
                                            "id" to "picture",
                                            "type" to "image",
                                            "asset" to "rafter:icon",
                                            "actions" to listOf(mapOf("type" to "close")),
                                        ),
                                        mapOf(
                                            "id" to "cover",
                                            "type" to "hit-area",
                                            "x" to 3,
                                            "width" to 6,
                                            "height" to 9,
                                            "tooltip" to
                                                mapOf(
                                                    "format" to "literal",
                                                    "value" to "Hover",
                                                    "font" to "test",
                                                    "hover" to
                                                        mapOf(
                                                            "format" to "literal",
                                                            "value" to "Nested",
                                                            "font" to "test",
                                                        ),
                                                ),
                                            "actions" to listOf(mapOf("type" to "close")),
                                        ),
                                    ),
                            )
                    ),
            )
        val result =
            MenuCompiler(asset.dimensions(), canvasMeasure = { text.bounds(text.render(it)) })
                .compile(mapOf("menu.yml" to document))
        val menu =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:canvas")
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        val visible = mapOf("picture" to true, "cover" to true)
        fun view(upper: Boolean) =
            CanvasRenderer.render(
                session,
                Frame(emptyMap(), visible, mapOf("picture" to true, "cover" to upper)),
                text,
                handles = mapOf("picture" to "lower-token", "cover" to "upper-token"),
            )
        val layers = view(true).messages.single().parts
        assertEquals(
            3.0,
            layers.filter { it.handle == "lower-token" }.sumOf { text.width(it.text) },
        )
        assertEquals(
            6.0,
            layers.filter { it.handle == "upper-token" }.sumOf { text.width(it.text) },
        )
        assertTrue(
            layers.any {
                it.text.toRawMessage().contains("Nested") &&
                    it.text.toRawMessage().contains(request.id)
            }
        )
        assertTrue(
            layers.none { it.text.toPlainText().contains('\ue000') },
            "Canvas elements must not acquire default fills",
        )
        val disabled = view(false).messages.single().parts
        assertEquals(
            3.0,
            disabled.filter { it.handle == "lower-token" }.sumOf { text.width(it.text) },
            "Disabled top area must still block click-through",
        )
        assertTrue(disabled.none { it.handle == "upper-token" })
        assertTrue(disabled.any { it.text.toRawMessage().contains("Hover") })
        val hoverOnly =
            menu.spec.pages
                .getValue("home")
                .nodes
                .last()
                .copy(interaction = ElementInteraction(emptyList()))
        val through =
            CanvasRenderer.render(
                    session,
                    Frame(emptyMap(), visible, visible, mapOf("cover" to hoverOnly)),
                    text,
                    handles = mapOf("picture" to "lower-token"),
                )
                .messages
                .single()
                .parts
        assertEquals(
            9.0,
            through.filter { it.handle == "lower-token" }.sumOf { text.width(it.text) },
            "A hover-only element must not eat the image click underneath",
        )

        val popupDocument =
            document +
                ("pages" to
                    mapOf(
                        "home" to
                            mapOf(
                                "mode" to "canvas",
                                "title" to "Test",
                                "width" to 90,
                                "height" to 36,
                                "elements" to
                                    listOf(
                                        mapOf(
                                            "id" to "underlay",
                                            "type" to "hit-area",
                                            "width" to 90,
                                            "height" to 36,
                                            "tooltip" to "Underlay hover",
                                            "actions" to listOf(mapOf("type" to "close")),
                                        )
                                    ),
                                "popups" to
                                    mapOf(
                                        "choices" to
                                            mapOf(
                                                "elements" to
                                                    listOf(
                                                        mapOf(
                                                            "id" to "panel",
                                                            "type" to "text",
                                                            "x" to 9,
                                                            "y" to 9,
                                                            "width" to 27,
                                                            "height" to 18,
                                                            "text" to "Box",
                                                        ),
                                                        mapOf(
                                                            "id" to "option",
                                                            "type" to "hit-area",
                                                            "x" to 18,
                                                            "y" to 9,
                                                            "width" to 9,
                                                            "height" to 9,
                                                            "tooltip" to "Option hover",
                                                            "actions" to
                                                                listOf(
                                                                    mapOf("type" to "close-popup")
                                                                ),
                                                        ),
                                                    )
                                            )
                                    ),
                            )
                    ))
        val resultPopup =
            MenuCompiler(asset.dimensions(), canvasMeasure = { text.bounds(text.render(it)) })
                .compile(mapOf("popup.yml" to popupDocument))
        val popupMenu =
            assertNotNull(resultPopup.snapshot, resultPopup.diagnostics.toString())
                .menus
                .getValue("test:canvas")
        val popupSession =
            UiSession(UUID.randomUUID(), popupMenu, emptyMap()).apply { openPopup("choices") }
        val on = popupSession.displayedNodes().associate { it.id to true }
        val parts =
            CanvasRenderer.render(
                    popupSession,
                    Frame(emptyMap(), on, on),
                    text,
                    handles =
                        mapOf(
                            "underlay" to "blocked",
                            "option" to "option",
                            "__popup-dismiss" to "dismiss",
                        ),
                )
                .messages
                .single()
                .parts
        assertTrue(
            parts.none { it.handle == "blocked" || "Underlay hover" in it.text.toRawMessage() }
        )
        assertEquals(9.0, parts.filter { it.handle == "option" }.sumOf { text.width(it.text) })
        assertEquals(
            306.0,
            parts.filter { it.handle == "dismiss" }.sumOf { text.width(it.text) },
            "Full page 90×4 rows minus panel 27×2 rows; panel gaps must not dismiss",
        )
        assertTrue(parts.any { "Option hover" in it.text.toRawMessage() })
    }
}
