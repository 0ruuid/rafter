package com.hiusers.rafter

import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.SessionData
import kotlin.test.*
import org.junit.jupiter.api.Test

class CollectionOrderTest {
    @Test
    fun childDeclaredBeforeParentUsesTheNewSelectionInTheSameFrame() {
        val parent = ListSpec("tasks", "tasks", "page", "selected", listOf(ListPlacement(0, 0)))
        val child = ListSpec("goals", "goals", "goals-page", null, listOf(ListPlacement(0, 0)))
        val lists = listOf(child, parent)
        val page = PageSpec("home", PageMode.CANVAS, TextSpec(), 180, 1, emptyList(), lists = lists)
        val state = mutableMapOf<String, Any?>("page" to 0, "goals-page" to 0, "selected" to null)
        val menu =
            MenuSpec(
                "test:order",
                "home",
                null,
                state,
                mapOf("home" to page),
                "test.yml",
                data =
                    mapOf(
                        "tasks" to DataSourceSpec(BindingSpec("document", "test:tasks")),
                        "goals" to
                            DataSourceSpec(
                                BindingSpec(
                                    "record",
                                    "goals",
                                    mapOf("source" to "tasks", "state" to "selected"),
                                )
                            ),
                    ),
            )
        val data = SessionData(menu, state)
        data.install(
            mapOf(
                "tasks" to
                    listOf(
                        mapOf(
                            "id" to "task-1",
                            "goals" to listOf(mapOf("id" to "goal-1", "name" to "first")),
                        )
                    )
            ),
            false,
        )
        data.project(lists, emptyMap())
        assertEquals("task-1", state["selected"])
        assertEquals("first", data.item(RowScope("goals", 0))?.get("name"))
        // 选择失效后须先修复，再投影子集合，不等下次点击。
        data.install(
            mapOf(
                "tasks" to
                    listOf(
                        mapOf(
                            "id" to "task-2",
                            "goals" to listOf(mapOf("id" to "goal-2", "name" to "second")),
                        )
                    )
            ),
            false,
        )
        data.project(lists, emptyMap())
        assertEquals("task-2", state["selected"])
        assertEquals("second", data.item(RowScope("goals", 0))?.get("name"))
    }

    @Test
    fun filterSelectionDependenciesAreOrderedAndCyclesRejected() {
        val sources = mapOf("root" to DataSourceSpec(BindingSpec("document", "test:data")))
        val first =
            ListSpec(
                "first",
                "root",
                "page",
                "first-selected",
                listOf(ListPlacement(0, 0)),
                filter = ListFilter("category", null, state = "category"),
            )
        val categories =
            ListSpec("categories", "root", "page", "category", listOf(ListPlacement(0, 0)))
        assertEquals(
            listOf("categories", "first"),
            ListProjectionOrder.resolve(sources, listOf(first, categories)).map { it.id },
        )
        val cycle =
            categories.copy(
                visible = ConditionSpec("state-equals", key = "first-selected", value = "one")
            )
        assertFailsWith<IllegalArgumentException> {
            ListProjectionOrder.resolve(sources, listOf(first, cycle))
        }
    }

    @Test
    fun recordOnlyActionsLoadTheirDocumentWithoutAnyDisplayBinding() {
        val button =
            NodeSpec(
                "change",
                NodeContent.Button(
                    actions =
                        listOf(
                            ActionSpec(
                                "set-record",
                                "records",
                                "claimed",
                                args = mapOf("state" to "selected", "field" to "phase"),
                            ),
                            ActionSpec(
                                "toggle-record",
                                "records",
                                args = mapOf("state" to "selected", "field" to "tracked"),
                            ),
                        )
                ),
            )
        val variant =
            button.copy(
                content = NodeContent.Button(),
                variants = listOf(NodeVariant(ConditionSpec("constant", value = true), button)),
            )
        for (node in listOf(button, variant)) {
            val state = mutableMapOf<String, Any?>("selected" to "one")
            val page = PageSpec("home", PageMode.NATIVE, TextSpec(), 180, 1, listOf(node))
            val menu =
                MenuSpec(
                    "test:action",
                    "home",
                    null,
                    state,
                    mapOf("home" to page),
                    "test.yml",
                    data =
                        mapOf("records" to DataSourceSpec(BindingSpec("document", "test:records"))),
                    documents =
                        mapOf(
                            "test:records" to
                                listOf(
                                    mapOf("id" to "one", "phase" to "active", "tracked" to false)
                                )
                        ),
                )
            val data = SessionData(menu, state)
            val aliases = data.required(page, page.nodes)
            assertEquals(
                setOf("records"),
                aliases,
                "Action-only sources, including variants, are still frame dependencies",
            )
            data.install(
                aliases.associateWith { data.document(menu.data.getValue(it).binding.key) },
                true,
            )
            data.patch("records", "one", "phase", "claimed")
            data.patch("records", "one", "tracked", null, toggle = true)
            fun record(key: String) =
                data.resolve(
                    BindingSpec("record", key, mapOf("source" to "records", "state" to "selected"))
                )
            assertEquals("claimed", record("phase"))
            assertEquals(true, record("tracked"))
            assertEquals("active", menu.documents.getValue("test:records").single()["phase"])
        }
    }

    @Test
    fun nativeListsExpandItemTemplatesAndRejectCanvasCoordinates() {
        val row =
            mapOf(
                "id" to "test:item-row",
                "component" to mapOf("id" to "item", "type" to "item", "item" to "test:items#row"),
            )
        val list =
            mapOf(
                "id" to "attachments",
                "type" to "list",
                "source" to "attachments",
                "page-state" to "page",
                "page-size" to 2,
                "template" to "test:item-row",
            )
        fun compile(mode: String, node: Map<String, Any?>) =
            MenuCompiler()
                .compile(
                    mapOf(
                        "menu.yml" to
                            (minimal(mode, listOf(node)) +
                                mapOf(
                                    "state" to mapOf("page" to 0),
                                    "data" to
                                        mapOf(
                                            "attachments" to
                                                mapOf("type" to "data", "key" to "test:attachments")
                                        ),
                                ))
                    ),
                    mapOf("row.yml" to row),
                )
        val result = compile("native", list)
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:menu")
                .pages
                .getValue("main")
        assertEquals(2, page.spec.components.size)
        assertEquals(
            listOf(RowScope("attachments", 0), RowScope("attachments", 1)),
            page.spec.components.map { it.rowScope },
        )
        assertTrue(
            page.spec.components.all { it.content is NodeContent.Item && it.visible != null }
        )
        assertEquals(2, page.spec.lists.single().pageSize)
        val mixed =
            minimal() +
                mapOf(
                    "state" to mapOf("page" to 0),
                    "data" to
                        mapOf(
                            "attachments" to mapOf("type" to "data", "key" to "test:attachments")
                        ),
                    "pages" to
                        mapOf(
                            "main" to
                                mapOf(
                                    "mode" to "canvas",
                                    "elements" to emptyList<Any?>(),
                                    "components" to listOf(list),
                                )
                        ),
                )
        val mixedResult =
            MenuCompiler().compile(mapOf("mixed.yml" to mixed), mapOf("row.yml" to row))
        val mixedPage =
            assertNotNull(mixedResult.snapshot, mixedResult.diagnostics.toString())
                .menus
                .getValue("test:menu")
                .pages
                .getValue("main")
        assertTrue(mixedPage.scene.isEmpty())
        assertEquals(
            page.spec.components.map { it.rowScope },
            mixedPage.spec.components.map { it.rowScope },
        )
        assertTrue(mixedPage.spec.components.all { it.content is NodeContent.Item })
        for (snapshot in
            listOf(assertNotNull(result.snapshot), assertNotNull(mixedResult.snapshot))) {
            val menus = assertIs<Map<*, *>>(AuthoringContract.layouts(snapshot)["menus"])
            val pages = assertIs<Map<*, *>>(menus["test:menu"])
            val exportedPage = assertIs<Map<*, *>>(pages["main"])
            val lists = assertIs<List<*>>(exportedPage["lists"])
            val exported = assertIs<Map<*, *>>(lists.single())
            assertEquals("native-client", exported["layout-owner"])
            assertTrue(
                assertIs<List<*>>(exported["slots"]).all {
                    val slot = assertIs<Map<*, *>>(it)
                    "x" !in slot && "y" !in slot
                }
            )
        }

        for (field in listOf("x", "y", "slots", "row-height")) {
            val invalid =
                compile("native", list + (field to if (field == "slots") emptyList<Any?>() else 9))
            assertNull(invalid.snapshot)
            assertEquals("UNKNOWN_FIELD", invalid.diagnostics.single().code)
            assertTrue(field !in Contract.fields("list", "native"))
        }
        assertEquals(
            setOf("x", "y", "slots", "row-height"),
            Contract.fields("list", "canvas")
                .keys
                .filter { it in setOf("x", "y", "slots", "row-height") }
                .toSet(),
        )
    }
}
