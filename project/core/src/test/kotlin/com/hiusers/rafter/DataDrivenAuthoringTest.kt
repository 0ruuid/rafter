package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.UiSession
import java.util.UUID
import kotlin.test.*

class DataDrivenAuthoringTest {
    private val row =
        mapOf(
            "id" to "test:row",
            "component" to
                mapOf(
                    "id" to "row",
                    "type" to "group",
                    "elements" to
                        listOf(
                            mapOf(
                                "id" to "name",
                                "type" to "text",
                                "width" to 80,
                                "height" to 10,
                                "binding" to mapOf("type" to "item", "key" to "name"),
                            ),
                            mapOf(
                                "id" to "hit",
                                "type" to "hit-area",
                                "width" to 80,
                                "height" to 18,
                                "actions" to listOf(mapOf("type" to "select-item")),
                            ),
                        ),
                ),
        )
    private val list =
        mapOf(
            "id" to "people",
            "type" to "list",
            "template" to "test:row",
            "source" to "people",
            "page-state" to "page",
            "selection-state" to "selected",
            "page-size" to 2,
            "row-height" to 18,
            "filter" to mapOf("field" to "online", "value" to true),
        )
    private val records =
        listOf(
            mapOf("id" to "a", "name" to "A", "online" to true),
            mapOf("id" to "b", "name" to "B", "online" to false),
            mapOf("id" to "c", "name" to "C", "online" to true),
            mapOf("id" to "d", "name" to "D", "online" to false),
        )

    private fun document(elements: List<Map<String, Any?>> = listOf(list)) =
        mapOf<String, Any?>(
            "id" to "test:people",
            "entry" to "home",
            "state" to mapOf("page" to 0, "selected" to null),
            "data" to mapOf("people" to mapOf("type" to "document", "key" to "preview:people")),
            "pages" to
                mapOf("home" to mapOf("mode" to "canvas", "width" to 180, "elements" to elements)),
        )

    private fun compile(
        doc: Map<String, Any?> = document(),
        items: List<Map<String, Any?>> = records,
    ) =
        MenuCompiler()
            .compile(
                mapOf("menus/people.yml" to doc),
                mapOf("templates/row.yml" to row),
                mapOf("data/people.yml" to mapOf("id" to "preview:people", "items" to items)),
            )

    private fun menu() =
        assertNotNull(compile().snapshot, compile().diagnostics.toString())
            .menus
            .getValue("test:people")

    private fun session() = UiSession(UUID.randomUUID(), menu(), emptyMap())

    private fun prepare(
        session: UiSession,
        items: List<Map<String, Any?>> = records,
        click: Boolean = false,
        filter: Boolean = false,
    ) {
        session.data.install(mapOf("people" to items), click)
        session.data.project(session.activeLists(), mapOf("people" to filter))
    }

    @Test
    fun fixedSlotsPaginateAndRecordLookupDoesNotCopyDisplayFieldsIntoState() {
        val session = session()
        prepare(session)
        val scope = RowScope("people", 0)
        assertEquals("a", session.state["selected"])
        assertEquals(
            4,
            session.data.resolve(BindingSpec("source", "size", mapOf("source" to "people"))),
        )
        val next = session.data.page("people", 1)
        session.setState(next.first, next.second)
        prepare(session)
        assertEquals("c", session.data.select(scope).second)
        prepare(session, filter = true)
        assertEquals(
            1,
            session.data.resolve(BindingSpec("list", "pages", mapOf("list" to "people"))),
        )
        assertEquals(
            "A",
            session.data.resolve(
                BindingSpec("record", "name", mapOf("source" to "people", "state" to "selected"))
            ),
        )
        assertEquals(setOf("page", "selected"), session.state.keys)
        assertEquals(4, session.current().scene.size)
    }

    @Test
    fun reorderedClicksKeepTheirPresentedIdentityAndRemovedRowsAreRejected() {
        val session = session()
        prepare(session)
        session.data.presented()
        prepare(session, records.reversed(), click = true)
        assertEquals("a", session.data.select(RowScope("people", 0)).second)
        prepare(session, records.drop(1), click = true)
        assertFalse(session.data.accepts(RowScope("people", 0)))
        assertFailsWith<IllegalArgumentException> { session.data.select(RowScope("people", 0)) }
        assertFailsWith<IllegalArgumentException> {
            prepare(session, listOf(records[0], records[0]))
        }
        assertNull(compile(items = listOf(records[0], records[0])).snapshot)
    }

    @Test
    fun recordsAndSelectionsAreSessionOwnedAndAbsentSlotsHaveNoRecord() {
        val one = session()
        val two = session()
        val mutable = records.map { it.toMutableMap() }.toMutableList()
        prepare(one, mutable)
        mutable[0]["name"] = "[font=evil]{{ run }}&a"
        prepare(two)
        one.setState("selected", "b")
        assertEquals("a", two.state["selected"])
        assertEquals(
            "A",
            one.data.resolve(BindingSpec("item", "name", mapOf("list" to "people", "slot" to 0))),
        )
        prepare(one, emptyList())
        assertNull(one.state["selected"])
        assertNull(
            one.data.resolve(BindingSpec("item", "name", mapOf("list" to "people", "slot" to 0)))
        )
    }

    @Test
    fun skinContentCannotOverrideGeometryAndDiagnosticsKeepBothSources() {
        val skin =
            mapOf(
                "id" to "test:window",
                "page" to
                    mapOf(
                        "mode" to "canvas",
                        "width" to 180,
                        "height" to 36,
                        "elements" to
                            listOf(
                                mapOf(
                                    "id" to "title",
                                    "type" to "slot",
                                    "required" to true,
                                    "component" to
                                        mapOf(
                                            "type" to "text",
                                            "x" to 12,
                                            "y" to 7,
                                            "width" to 120,
                                            "height" to 10,
                                        ),
                                )
                            ),
                    ),
            )
        fun using(content: Map<String, Any?>) =
            MenuCompiler()
                .compile(
                    mapOf(
                        "menus/skin.yml" to
                            mapOf(
                                "id" to "test:skin",
                                "entry" to "home",
                                "pages" to
                                    mapOf(
                                        "home" to
                                            mapOf(
                                                "mode" to "canvas",
                                                "skin" to "test:window",
                                                "content" to content,
                                            )
                                    ),
                            )
                    ),
                    skins = mapOf("ui/skins/window.yml" to skin),
                )
        val result = using(mapOf("title" to mapOf("text" to "Hello")))
        val node =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:skin")
                .pages
                .getValue("home")
                .scene
                .single()
        assertEquals(Rect(12, 7, 120, 10), node.bounds)
        assertTrue(node.node.origins.any { it.file == "ui/skins/window.yml" })
        assertNull(using(mapOf("title" to mapOf("text" to "Hello", "x" to 20))).snapshot)
        assertNull(using(mapOf("unknown" to mapOf("text" to "Hello"))).snapshot)
        assertNull(using(emptyMap()).snapshot)
    }

    @Test
    fun explicitRowSlotsKeepIrregularArtButStillUseDataDrivenPaging() {
        val custom =
            list - setOf("row-height", "page-size") +
                ("slots" to listOf(mapOf("y" to 9), mapOf("x" to 18, "y" to 45)))
        val result = compile(document(listOf(custom)))
        val page =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:people")
                .pages
                .getValue("home")
        assertEquals(
            listOf(0 to 9, 18 to 45),
            page.spec.lists.single().placements.map { it.x to it.y },
        )
        assertEquals(2, page.spec.lists.single().pageSize)
        assertNull(compile(document(listOf(custom + ("page-size" to 2)))).snapshot)
    }
}
