package com.hiusers.rafter

import com.hiusers.rafter.asset.ModalAssets
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import kotlin.test.*

class DialogCompositionTest {
    private val panel = mapOf("rafter:panel" to (512 to 512))

    private fun menu(elements: List<Map<String, Any?>>) =
        mapOf<String, Any?>(
            "id" to "test:composition",
            "entry" to "home",
            "pages" to
                mapOf(
                    "home" to
                        mapOf(
                            "mode" to "canvas",
                            "title" to "",
                            "width" to 180,
                            "elements" to elements,
                        )
                ),
        )

    private fun image(id: String) =
        mapOf<String, Any?>(
            "id" to id,
            "type" to "image",
            "asset" to "rafter:panel",
            "width" to 45,
            "height" to 20,
            "fit" to "contain",
        )

    @Test
    fun groupTemplatesKeepLocalPositionsPaintOrderAndScopedHitTargets() {
        val template =
            mapOf(
                "id" to "test:card",
                "params" to mapOf("label" to "Card"),
                "component" to
                    mapOf(
                        "id" to "card",
                        "type" to "group",
                        "elements" to
                            listOf(
                                image("art") +
                                    ("visible" to mapOf("type" to "constant", "value" to false)),
                                mapOf(
                                    "id" to "label",
                                    "type" to "text",
                                    "x" to 3,
                                    "y" to 1,
                                    "text" to mapOf("param" to "label"),
                                ),
                                mapOf(
                                    "id" to "click",
                                    "type" to "hit-area",
                                    "target" to "art",
                                    "actions" to listOf(mapOf("type" to "close")),
                                ),
                            ),
                    ),
            )
        val instances =
            listOf("first" to 0, "second" to 36).map { (id, y) ->
                mapOf<String, Any?>(
                    "id" to id,
                    "type" to "template",
                    "key" to "test:card",
                    "x" to 9,
                    "y" to y,
                    "params" to mapOf("label" to id),
                )
            }
        val result =
            MenuCompiler(panel)
                .compile(mapOf("menu.yml" to menu(instances)), mapOf("card.yml" to template))
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:composition")
                .pages
                .getValue("home")
        assertEquals(
            listOf(
                "first.art",
                "first.label",
                "first.click",
                "second.art",
                "second.label",
                "second.click",
            ),
            page.scene.map { it.node.id },
        )
        assertEquals(Rect(9, 36, 45, 27), page.scene.last().bounds)
        assertEquals("second.art", page.scene.last().node.hitTarget)
        assertEquals(
            false,
            page.scene.last().node.visible!!.value,
            "Hidden art must not leave a live followed hit region",
        )
        assertEquals(12 to 37, page.spec.elements[4].let { it.x to it.y })
        assertEquals("second", page.spec.elements[4].text.value)
        assertTrue(page.spec.elements[4].origins.any { it.file == "card.yml" })
        val export = AuthoringContract.layouts(result.snapshot!!)
        assertEquals(AuthoringContract.VERSION, export["contract-version"])
    }

    @Test
    fun danglingAmbiguousAndCrossPlaneTargetsNeverBecomeGhostClickRegions() {
        val hit =
            mapOf<String, Any?>(
                "id" to "click",
                "type" to "hit-area",
                "target" to "missing",
                "actions" to emptyList<Any?>(),
            )
        for (nodes in
            listOf(
                listOf(hit),
                listOf(image("art"), hit + ("target" to "art") + ("x" to 0)),
                listOf(
                    mapOf("id" to "text", "type" to "text", "text" to "unbounded"),
                    hit + ("target" to "text"),
                ),
            )) {
            val result = MenuCompiler(panel).compile(mapOf("bad.yml" to menu(nodes)))
            assertNull(result.snapshot)
            assertTrue(
                result.diagnostics.single().code in setOf("REFERENCE", "LAYOUT", "CAPABILITY")
            )
        }
        val duplicate =
            mapOf<String, Any?>(
                "id" to "same",
                "type" to "group",
                "elements" to listOf(image("art")),
            )
        assertEquals(
            "DUPLICATE_ID",
            MenuCompiler(panel)
                .compile(mapOf("bad.yml" to menu(listOf(duplicate, duplicate))))
                .diagnostics
                .single()
                .code,
        )
        val distinctChildren = duplicate + ("elements" to listOf(image("different")))
        assertEquals(
            "DUPLICATE_ID",
            MenuCompiler(panel)
                .compile(mapOf("bad.yml" to menu(listOf(duplicate, distinctChildren))))
                .diagnostics
                .single()
                .code,
        )
        assertEquals(
            "DUPLICATE_ID",
            MenuCompiler(panel)
                .compile(mapOf("bad.yml" to menu(listOf(duplicate, image("same")))))
                .diagnostics
                .single()
                .code,
        )
    }

    @Test
    fun mixedNativeFormsAndThreeActionsShareOnePlanWithPackAndEditor() {
        val form =
            listOf(
                mapOf("id" to "name", "type" to "input", "state" to "name", "width" to 220),
                mapOf(
                    "id" to "message",
                    "type" to "input",
                    "state" to "message",
                    "width" to 220,
                    "label-visible" to false,
                    "multiline" to true,
                    "multiline-height" to 64,
                ),
                mapOf(
                    "id" to "check",
                    "type" to "checkbox",
                    "state" to "check",
                    "width" to 200,
                    "text" to "Enabled",
                ),
                mapOf(
                    "id" to "choice",
                    "type" to "select",
                    "state" to "choice",
                    "width" to 200,
                    "options" to mapOf("a" to "A"),
                ),
                mapOf("id" to "volume", "type" to "slider", "state" to "volume", "width" to 200),
            )
        val descriptions =
            listOf("one", "two").map {
                mapOf("id" to it, "type" to "text", "width" to 220, "text" to it)
            }
        val buttons =
            listOf("save", "reset", "exit").map {
                mapOf(
                    "id" to it,
                    "type" to "button",
                    "width" to 98,
                    "actions" to listOf(mapOf("type" to "close")),
                )
            }
        val doc =
            menu(emptyList()) +
                mapOf(
                    "state" to
                        mapOf(
                            "name" to "",
                            "message" to "",
                            "check" to true,
                            "choice" to "a",
                            "volume" to 5,
                        ),
                    "pages" to
                        mapOf(
                            "home" to
                                mapOf(
                                    "mode" to "canvas",
                                    "title" to "",
                                    "elements" to emptyList<Any?>(),
                                ),
                            "form" to
                                mapOf(
                                    "mode" to "canvas",
                                    "title" to "",
                                    "width" to 180,
                                    "columns" to 2,
                                    "elements" to emptyList<Any?>(),
                                    "modal" to
                                        mapOf(
                                            "asset" to "rafter:panel",
                                            "background" to "#101820",
                                            "feedback" to "one",
                                        ),
                                    "components" to
                                        (descriptions +
                                            listOf(form[0], form[2], form[3], form[4], form[1]) +
                                            buttons),
                                ),
                        ),
                )
        val result = MenuCompiler(panel).compile(mapOf("form.yml" to doc))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        val plan =
            assertNotNull(
                snapshot.menus.getValue("test:composition").pages.getValue("form").modalLayout
            )
        assertEquals(listOf(33, 17, 20, 20, 64), plan.inputs.map { it.bounds.height })
        assertEquals(listOf(90, 133, 160, 190, 220), plan.inputs.map { it.bounds.y })
        assertEquals(358, plan.bodyHeight)
        assertEquals(3, plan.columns)
        assertEquals(listOf("save", "reset", "exit"), plan.buttons.mapNotNull { it.target })
        assertSame(plan, ModalAssets.collect(snapshot, panel).layouts.single().plan)
        val menus = assertIs<Map<*, *>>(AuthoringContract.layouts(snapshot)["menus"])
        val modal =
            assertIs<Map<*, *>>(
                assertIs<Map<*, *>>(assertIs<Map<*, *>>(menus["test:composition"])["form"])["modal"]
            )
        val layout = assertIs<Map<*, *>>(modal["layout"])
        assertEquals(plan.bodyHeight, layout["body-height"])
        assertEquals(plan.inputs.size, assertIs<List<*>>(layout["inputs"]).size)
    }
}
