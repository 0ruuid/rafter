package com.hiusers.rafter

import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.UiSession
import java.util.UUID
import kotlin.test.*

class CanvasPopupTest {
    private val compiler = MenuCompiler(emptyMap(), canvasMeasure = { 27 to 9 })

    private fun action(type: String, vararg values: Pair<String, Any?>) =
        mapOf("type" to type) + values

    private fun hit(id: String, actions: List<Map<String, Any?>>, x: Int = 0, y: Int = 0) =
        mapOf(
            "id" to id,
            "type" to "hit-area",
            "x" to x,
            "y" to y,
            "width" to 54,
            "height" to 18,
            "actions" to actions,
        )

    private fun document(
        elements: List<Map<String, Any?>> =
            listOf(hit("open", listOf(action("open-popup", "popup" to "choices")))),
        popupElements: List<Map<String, Any?>> =
            listOf(
                hit(
                    "choose",
                    listOf(
                        action("set-state", "state" to "choice", "value" to "hard"),
                        action("close-popup"),
                    ),
                    18,
                    27,
                )
            ),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "id" to "test:popups",
            "entry" to "home",
            "state" to mapOf("choice" to "normal"),
            "pages" to
                mapOf(
                    "home" to
                        (mapOf(
                            "mode" to "canvas",
                            "title" to "Choose",
                            "width" to 180,
                            "elements" to elements,
                            "popups" to mapOf("choices" to mapOf("elements" to popupElements)),
                        ) + extra),
                    "input" to
                        mapOf(
                            "mode" to "native",
                            "title" to "Input",
                            "components" to emptyList<Any>(),
                        ),
                ),
        )

    private fun compile(doc: Map<String, Any?> = document()): CompileResult =
        compiler.compile(mapOf("menu.yml" to doc))

    private fun compiled(doc: Map<String, Any?> = document()): CompiledMenu =
        compile(doc).let {
            assertNotNull(it.snapshot, it.diagnostics.toString()).menus.getValue("test:popups")
        }

    @Test
    fun popupLayoutAndContractAreExplicitAndSharePageCoordinates() {
        val menu = compiled()
        val page = menu.pages.getValue("home")
        assertEquals(listOf("open"), page.scene.map { it.node.id })
        assertEquals(Rect(18, 27, 54, 18), page.popups.getValue("choices").single().bounds)
        assertEquals(45, page.height, "Opening must not change the scroll body's height")
        assertEquals(listOf("open", "choose"), page.spec.nodes.map { it.id })
        assertTrue("popups" in Contract.pageFields("canvas"))
        assertFalse("popups" in Contract.pageFields("native"))
        assertEquals("popup", Contract.pageFields("canvas").getValue("popups").items)
        assertEquals(setOf("type", "popup"), Contract.actions.getValue("open-popup").keys)
        val exported =
            AuthoringContract.layouts(MenuSnapshot(mapOf(menu.spec.id to menu), emptyMap()))
        assertTrue(exported.toString().contains("popups={choices=[{id=choose"))
    }

    @Test
    fun invalidPopupDefinitionsFailBeforePublishing() {
        val cases =
            listOf(
                document(
                    elements =
                        listOf(hit("open", listOf(action("open-popup", "popup" to "missing"))))
                ),
                document(popupElements = listOf(hit("open", listOf(action("close-popup"))))),
                document(popupElements = emptyList()),
                document(
                    popupElements = listOf(hit("choose", listOf(action("close-popup")), y = 1))
                ),
                document(elements = listOf(hit("open", listOf(action("close-popup"))))),
                document(
                    elements =
                        listOf(
                            hit(
                                "open",
                                listOf(action("open-popup", "popup" to "choices"), action("close")),
                            )
                        )
                ),
                document(
                    extra =
                        mapOf(
                            "components" to
                                listOf(
                                    mapOf(
                                        "id" to "native",
                                        "type" to "text",
                                        "text" to "Not a canvas plane",
                                    )
                                )
                        )
                ),
            )
        cases.forEachIndexed { index, doc ->
            assertNull(compile(doc).snapshot, "Case $index must fail")
        }
        val unknown =
            document(
                extra =
                    mapOf(
                        "popups" to
                            mapOf(
                                "choices" to
                                    mapOf(
                                        "elements" to
                                            listOf(hit("choose", listOf(action("close-popup")))),
                                        "width" to 20,
                                    )
                            )
                    )
            )
        assertEquals("UNKNOWN_FIELD", compile(unknown).diagnostics.single().code)
    }

    @Test
    fun popupStateIsTransientIsolatedAndInvalidatesOldCapabilities() {
        val menu = compiled()
        val first = UiSession(UUID.randomUUID(), menu, emptyMap())
        val second = UiSession(UUID.randomUUID(), menu, emptyMap())
        val generation = first.beginRender()
        val old = first.ready(generation, listOf("open")).getValue("open")
        assertEquals(listOf("open"), first.displayedNodes().map { it.id })
        first.openPopup("choices")
        assertNull(second.popup)
        assertNull(first.consume(old))
        assertFalse(first.acceptsTarget("open"))
        assertTrue(first.acceptsTarget("choose"))
        assertTrue(first.acceptsTarget("__popup-dismiss"))
        assertEquals(listOf("open", "choose"), first.displayedNodes().map { it.id })
        assertEquals(
            mapOf<String, Any?>("choice" to "normal"),
            first.state,
            "Popup visibility must not leak into business state",
        )
        first.setState("choice", "hard")
        first.closePopup()
        assertTrue(first.acceptsTarget("open"))
        assertFalse(first.acceptsTarget("choose"))
        assertEquals("normal", second.state["choice"])
        first.openPopup("choices")
        first.navigate("input")
        assertNull(first.popup)
        first.back()
        assertNull(first.popup)
        assertEquals("hard", first.state["choice"])
        first.openPopup("choices")
        first.close()
        assertNull(first.popup)
    }
}
