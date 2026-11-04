package com.hiusers.rafter

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.Rect
import kotlin.test.*

class CanvasItemCompilerTest {
    private fun item(
        id: String = "icon",
        reference: String = "minecraft:stone",
        size: Int = 18,
        x: Int = 0,
        y: Int = 0,
    ) =
        mapOf<String, Any?>(
            "id" to id,
            "type" to "item",
            "item" to reference,
            "size" to size,
            "x" to x,
            "y" to y,
        )

    private fun page(result: com.hiusers.rafter.model.CompileResult) =
        assertNotNull(result.snapshot, result.diagnostics.toString())
            .menus
            .getValue("test:menu")
            .pages
            .getValue("main")

    @Test
    fun compilesCanvasItemFramesAndReusesItsHitTargetBounds() {
        val visual = item(size = 36, x = 13, y = 18) +
            mapOf(
                "show-count" to false,
                "show-tooltip" to false,
                "variants" to
                    listOf(
                        mapOf(
                            "when" to mapOf("type" to "constant", "value" to true),
                            "override" to mapOf("item" to "minecraft:diamond"),
                        )
                    ),
            )
        val hit =
            mapOf(
                "id" to "tap",
                "type" to "hit-area",
                "target" to "icon",
                "actions" to listOf(mapOf("type" to "close")),
            )
        val compiled =
            page(MenuCompiler().compile(mapOf("item.yml" to minimal("canvas", listOf(visual, hit)))))
        val node = compiled.spec.elements.first()
        assertEquals(
            NodeContent.Item("minecraft:stone", size = 36, showCount = false, showTooltip = false),
            node.content,
        )
        assertEquals(
            "minecraft:diamond",
            (node.variants.single().node.content as NodeContent.Item).reference,
        )
        assertEquals(Rect(13, 18, 36, 36), compiled.scene[0].bounds)
        assertEquals(Rect(13, 18, 36, 36), compiled.scene[1].bounds)
        assertEquals(54, compiled.height)
        assertTrue("size" in Contract.fields("item", "canvas"))
        assertFalse("size" in Contract.fields("item", "native"))
        assertTrue("show-count" in Contract.fields("item", "canvas"))
        assertFalse("show-count" in Contract.fields("item", "native"))
        assertTrue("show-tooltip" in Contract.fields("item", "canvas"))
        assertTrue("show-tooltip" in Contract.fields("item", "native"))
        assertFalse("text" in Contract.fields("item", "canvas"))
        assertFalse("width" in Contract.fields("item", "canvas"))
        assertFalse("height" in Contract.fields("item", "canvas"))
    }

    @Test
    fun expandsGroupLocalCoordinatesForCanvasItems() {
        val group =
            mapOf(
                "id" to "panel",
                "type" to "group",
                "x" to 10,
                "y" to 9,
                "elements" to listOf(item(x = 3, y = 9)),
            )
        val placed = page(MenuCompiler().compile(mapOf("group.yml" to minimal("canvas", listOf(group)))))
            .scene
            .single()
        assertEquals("panel.icon", placed.node.id)
        assertEquals(13, placed.node.x)
        assertEquals(18, placed.node.y)
        assertEquals(Rect(13, 18, 18, 18), placed.bounds)
    }

    @Test
    fun expandsListSlotsAsGridPositions() {
        val document =
            minimal(
                    "canvas",
                    listOf(
                        mapOf(
                            "id" to "grid",
                            "type" to "list",
                            "source" to "items",
                            "template" to "test:item-row",
                            "page-state" to "page",
                            "slots" to
                                listOf(
                                    mapOf("x" to 4, "y" to 9),
                                    mapOf("x" to 31, "y" to 9),
                                ),
                        )
                    ),
                ) +
                mapOf(
                    "state" to mapOf("page" to 0),
                    "data" to
                        mapOf(
                            "items" to mapOf("type" to "document", "key" to "test:items")
                        ),
                )
        val template =
            mapOf(
                "id" to "test:item-row",
                "component" to item(id = "row-item"),
            )
        val data =
            mapOf(
                "id" to "test:items",
                "items" to listOf(mapOf("id" to "one"), mapOf("id" to "two")),
            )
        val compiled =
            page(
                MenuCompiler().compile(
                    mapOf("grid.yml" to document),
                    templates = mapOf("row.yml" to template),
                    dataDocuments = mapOf("items.yml" to data),
                )
            )
        assertEquals(listOf("grid.row-0", "grid.row-1"), compiled.scene.map { it.node.id })
        assertEquals(listOf(Rect(4, 9, 18, 18), Rect(31, 9, 18, 18)), compiled.scene.map { it.bounds })
    }

    @Test
    fun rejectsCanvasItemGeometryNativeSizeAndPopupPlacement() {
        fun rejected(document: Map<String, Any?>, code: String) {
            val result = MenuCompiler().compile(mapOf("bad.yml" to document))
            assertNull(result.snapshot, result.diagnostics.toString())
            assertEquals(code, result.diagnostics.single().code, result.diagnostics.toString())
        }

        rejected(minimal("canvas", listOf(item(size = 17))), "LAYOUT")
        rejected(minimal("canvas", listOf(item(y = 1))), "LAYOUT")
        rejected(minimal("canvas", listOf(item() + ("width" to 18))), "UNKNOWN_FIELD")
        rejected(minimal("canvas", listOf(item() + ("text" to "not supported"))), "UNKNOWN_FIELD")
        rejected(minimal("native", listOf(item() + ("size" to 36))), "UNKNOWN_FIELD")
        rejected(
            minimal(
                "canvas",
                listOf(
                    item() +
                        ("variants" to
                            listOf(
                                mapOf(
                                    "when" to mapOf("type" to "constant", "value" to true),
                                    "override" to mapOf("size" to 36),
                                )
                            ))
                ),
            ),
            "UNKNOWN_FIELD",
        )

        val popupItem =
            mapOf(
                "id" to "test:popup-item",
                "entry" to "main",
                "state" to emptyMap<String, Any?>(),
                "pages" to
                    mapOf(
                        "main" to
                            mapOf(
                                "mode" to "canvas",
                                "elements" to emptyList<Any>(),
                                "popups" to
                                    mapOf(
                                        "menu" to mapOf("elements" to listOf(item()))
                                    ),
                            )
                    ),
            )
        rejected(popupItem, "CAPABILITY")
    }
}
