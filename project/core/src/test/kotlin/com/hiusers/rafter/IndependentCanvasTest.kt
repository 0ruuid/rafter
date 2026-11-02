package com.hiusers.rafter

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import kotlin.test.*

class IndependentCanvasTest {
    private fun menu(elements: List<Map<String, Any?>>, extra: Map<String, Any?> = emptyMap()) =
        mapOf(
            "id" to "test:canvas",
            "entry" to "home",
            "pages" to
                mapOf(
                    "home" to
                        (mapOf(
                            "mode" to "canvas",
                            "title" to "Test",
                            "width" to 180,
                            "elements" to elements,
                        ) + extra)
                ),
        )

    private val compiler =
        MenuCompiler(mapOf("rafter:icon" to (18 to 18)), canvasMeasure = { 25 to 18 })

    @Test
    fun independentNaturalElementsDoNotFlowOrPaintAsButtons() {
        val result =
            compiler.compile(
                mapOf(
                    "menu.yml" to
                        menu(
                            listOf(
                                mapOf(
                                    "id" to "caption",
                                    "type" to "text",
                                    "x" to 3,
                                    "y" to 0,
                                    "text" to "Hello",
                                    "actions" to listOf(mapOf("type" to "close")),
                                ),
                                mapOf(
                                    "id" to "picture",
                                    "type" to "image",
                                    "x" to 72,
                                    "y" to 0,
                                    "asset" to "rafter:icon",
                                ),
                                mapOf(
                                    "id" to "target",
                                    "type" to "hit-area",
                                    "x" to 3,
                                    "y" to 0,
                                    "width" to 9,
                                    "height" to 18,
                                    "actions" to listOf(mapOf("type" to "close")),
                                ),
                            )
                        )
                )
            )
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:canvas")
                .pages
                .getValue("home")
        assertEquals(listOf(0, 0, 0), page.scene.map { it.bounds.y })
        assertEquals(Rect(3, 0, 25, 18), page.scene.first().bounds)
        assertTrue(page.scene.first().node.clickable)
        assertFalse(page.scene[1].node.clickable)
        assertEquals("close", page.scene[2].node.actions.single().type)
        assertEquals(18, page.height)
    }

    @Test
    fun emptyCanvasAndNativeControlsHaveExplicitSeparateCollections() {
        assertNotNull(compiler.compile(mapOf("empty.yml" to menu(emptyList()))).snapshot)
        val old =
            menu(emptyList()).toMutableMap().apply {
                put(
                    "pages",
                    mapOf(
                        "home" to
                            mapOf(
                                "mode" to "canvas",
                                "title" to "Old",
                                "components" to emptyList<Any>(),
                            )
                    ),
                )
            }
        assertNull(compiler.compile(mapOf("old.yml" to old)).snapshot)
        for (type in listOf("button", "panel", "toggle", "row", "column", "absolute")) {
            assertNull(
                compiler
                    .compile(mapOf("old.yml" to menu(listOf(mapOf("id" to "old", "type" to type)))))
                    .snapshot,
                type,
            )
        }
        assertEquals(
            setOf("text", "image", "hit-area", "template"),
            Contract.modes.getValue("canvas"),
        )
    }

    @Test
    fun imageTextAndHotspotRemainIndependentAndOutOfBoundsAreRejected() {
        val result =
            compiler.compile(
                mapOf(
                    "menu.yml" to
                        menu(
                            listOf(
                                mapOf(
                                    "id" to "caption",
                                    "type" to "text",
                                    "x" to 90,
                                    "y" to 9,
                                    "align" to "center",
                                    "text" to "Hello",
                                )
                            ),
                            mapOf("height" to 45),
                        )
                )
            )
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:canvas")
                .pages
                .getValue("home")
        assertEquals(Rect(78, 9, 25, 18), page.scene.single().bounds)
        assertEquals(45, page.height)
        val invalid =
            compiler.compile(
                mapOf(
                    "bad.yml" to
                        menu(
                            listOf(
                                mapOf(
                                    "id" to "bad",
                                    "type" to "text",
                                    "x" to 170,
                                    "y" to 1,
                                    "text" to "Hello",
                                )
                            )
                        )
                )
            )
        assertNull(invalid.snapshot)
        assertTrue(invalid.diagnostics.isNotEmpty())
        val instance =
            menu(
                listOf(
                    mapOf(
                        "id" to "instance",
                        "type" to "template",
                        "key" to "test:label",
                        "x" to 9,
                        "y" to 9,
                    )
                )
            )
        fun template(x: Number) =
            mapOf(
                "template.yml" to
                    mapOf(
                        "id" to "test:label",
                        "component" to
                            mapOf(
                                "id" to "body",
                                "type" to "text",
                                "x" to x,
                                "y" to 9,
                                "text" to "Hello",
                            ),
                    )
            )
        val placed = compiler.compile(mapOf("menu.yml" to instance), template(3))
        assertEquals(
            Rect(12, 18, 25, 18),
            assertNotNull(placed.snapshot)
                .menus
                .getValue("test:canvas")
                .pages
                .getValue("home")
                .scene
                .single()
                .bounds,
        )
        assertNull(compiler.compile(mapOf("menu.yml" to instance), template(1.5)).snapshot)
    }

    @Test
    fun visualDimensionsAreNotNativeClickCells() {
        val visual =
            MenuCompiler(
                assets = mapOf("rafter:odd" to (45 to 18)),
                imageSources = mapOf("rafter:odd" to (41 to 12)),
                canvasMeasure = { 25 to 10 },
            )
        val document =
            menu(
                listOf(
                    mapOf(
                        "id" to "picture",
                        "type" to "image",
                        "asset" to "rafter:odd",
                        "x" to 139,
                        "y" to 0,
                    ),
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "text" to "Hello",
                        "height" to 11,
                        "y" to 18,
                    ),
                )
            )
        val result = visual.compile(mapOf("visual.yml" to document))
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:canvas")
                .pages
                .getValue("home")
        assertEquals(Rect(139, 0, 41, 12), page.scene[0].bounds)
        assertEquals(Rect(0, 18, 25, 11), page.scene[1].bounds)
        assertEquals(36, page.height, "Only the enclosing transport is rounded to whole text lines")
        val invalidHit =
            menu(
                listOf(
                    mapOf(
                        "id" to "hit",
                        "type" to "hit-area",
                        "width" to 20,
                        "height" to 11,
                        "actions" to emptyList<Any>(),
                    )
                )
            )
        assertNull(visual.compile(mapOf("hit.yml" to invalidHit)).snapshot)
    }
}
