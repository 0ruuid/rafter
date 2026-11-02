package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.UiSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SessionStatePatchTest {
    private fun session(): UiSession {
        val fields =
            listOf(
                NodeSpec("name", NodeContent.TextInput("name", maxLength = 4)),
                NodeSpec("enabled", NodeContent.Checkbox("enabled")),
                NodeSpec(
                    "choice",
                    NodeContent.Select(
                        "choice",
                        mapOf("a" to TextSpec(value = "A"), "b" to TextSpec(value = "B")),
                    ),
                ),
                NodeSpec("number", NodeContent.Slider("number", min = 0.0, max = 10.0, step = 5.0)),
            )
        val page =
            PageSpec(
                "home",
                PageMode.NATIVE,
                TextSpec(),
                360,
                1,
                fields,
                lists =
                    listOf(
                        ListSpec("rows", "rows", "page", "selected", listOf(ListPlacement(0, 0)))
                    ),
            )
        val menu =
            MenuSpec(
                "test:patch",
                "home",
                null,
                Values.map(
                    mapOf(
                        "status" to "before",
                        "name" to "old",
                        "enabled" to true,
                        "choice" to "a",
                        "number" to 5,
                        "page" to 0,
                        "selected" to null,
                        "result" to null,
                        "metadata" to emptyMap<String, Any?>(),
                        "values" to emptyList<Any?>(),
                    )
                ),
                mapOf("home" to page),
                "patch.yml",
                data = mapOf("rows" to DataSourceSpec(BindingSpec("document", "test:rows"))),
                documents = mapOf("test:rows" to emptyList()),
            )
        return UiSession(
            UUID.randomUUID(),
            CompiledMenu(menu, mapOf("home" to CompiledPage(page, emptyList(), 9))),
            emptyMap(),
        )
    }

    @Test
    fun patchesUseDeclaredStateTypesAndControlConstraintsWithoutPartialWrites() {
        val session = session()
        val before = Values.map(session.state)
        for ((key, invalid) in
            listOf(
                "undeclared" to "value",
                "name" to "longer",
                "name" to "a\nb",
                "enabled" to "false",
                "choice" to "missing",
                "number" to 3,
                "number" to 15,
                "page" to -1,
                "page" to 0.5,
                "page" to "1",
                "selected" to 1,
                "metadata" to emptyList<Any?>(),
                "values" to emptyMap<String, Any?>(),
            )) {
            assertFailsWith<IllegalArgumentException>("$key=$invalid") {
                session.patchState(linkedMapOf("status" to "partial", key to invalid))
            }
            assertEquals(before, session.state, "A rejected $key must not commit status")
        }
        session.patchState(
            mapOf(
                "status" to "done",
                "name" to "new",
                "number" to 10.0,
                "selected" to "a",
                "page" to 2,
            )
        )
        assertEquals("done", session.state["status"])
        assertEquals("new", session.state["name"])
        assertEquals(10.0, session.state["number"])
        assertEquals("a", session.state["selected"])
        assertEquals(2, session.state["page"])
        session.patchState(emptyMap())
        session.patchState(mapOf("selected" to null))
        assertNull(session.state["selected"])
    }

    @Test
    fun patchesDeepCopyAndFreezeValuesAtTheExistingDepthBoundary() {
        val session = session()
        val list = mutableListOf<Any?>("original")
        val nested = mutableMapOf<String, Any?>("items" to list)
        val patch = mutableMapOf<String, Any?>("metadata" to nested)
        session.patchState(patch)
        patch.clear()
        nested["injected"] = true
        list[0] = "changed"
        val frozen = assertIs<Map<*, *>>(session.state["metadata"])
        assertEquals(mapOf("items" to listOf("original")), frozen)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (frozen as MutableMap<String, Any?>)["injected"] = true
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST") (frozen["items"] as MutableList<Any?>).clear()
        }
        var deepest: Any? = "leaf"
        repeat(31) { deepest = listOf(deepest) }
        session.patchState(mapOf("result" to deepest))
        val before = Values.map(session.state)
        assertFailsWith<IllegalArgumentException> {
            session.patchState(mapOf("status" to "partial", "result" to listOf(deepest)))
        }
        assertEquals(before, session.state)
    }

    @Test
    fun patchesRejectNonordinaryValuesWithoutWeakeningDataBoundaries() {
        val session = session()
        val before = Values.map(session.state)
        for (invalid in
            listOf(
                Any(),
                StringBuilder("mutable"),
                AtomicInteger(1),
                arrayOf("a"),
                mapOf(1 to "a"),
                Double.NaN,
                Double.POSITIVE_INFINITY,
            )) {
            assertFailsWith<IllegalArgumentException> {
                session.patchState(linkedMapOf("status" to "partial", "result" to invalid))
            }
            assertEquals(before, session.state)
        }
    }

    private fun sharedStateSession(control: Map<String, Any?>? = null): UiSession {
        val row =
            mapOf(
                "id" to "test:shared-row",
                "component" to mapOf("id" to "label", "type" to "text", "text" to "Row"),
            )
        val list =
            mapOf(
                "id" to "rows",
                "type" to "list",
                "source" to "rows",
                "page-state" to "page",
                "selection-state" to "selected",
                "page-size" to 1,
                "template" to "test:shared-row",
            )
        val document =
            minimal(components = listOf(list) + listOfNotNull(control)) +
                mapOf(
                    "state" to mapOf("status" to "before", "page" to 0, "selected" to "a"),
                    "data" to mapOf("rows" to mapOf("type" to "document", "key" to "test:rows")),
                )
        val result =
            MenuCompiler()
                .compile(
                    mapOf("shared.yml" to document),
                    mapOf("row.yml" to row),
                    mapOf(
                        "rows.yml" to
                            mapOf("id" to "test:rows", "items" to listOf(mapOf("id" to "a")))
                    ),
                )
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        return UiSession(UUID.randomUUID(), snapshot.menus.getValue("test:menu"), emptyMap())
    }

    @Test
    fun sharedPageStateMustSatisfyListRulesAndSliderConstraintsAtomically() {
        val session =
            sharedStateSession(
                mapOf(
                    "id" to "page-control",
                    "type" to "slider",
                    "state" to "page",
                    "min" to -10,
                    "max" to 10,
                    "step" to 5,
                )
            )
        val before = Values.map(session.state)
        for (value in listOf(-5, 1, 15, 1.5)) {
            assertFailsWith<IllegalArgumentException> {
                session.patchState(linkedMapOf("status" to "partial", "page" to value))
            }
            assertEquals(before, session.state)
        }
        assertFailsWith<IllegalArgumentException> { session.setState("page", 1) }
        assertEquals(before, session.state)
        session.patchState(mapOf("status" to "done", "page" to 5))
        assertEquals("done", session.state["status"])
        assertEquals(5, session.state["page"])
    }

    @Test
    fun sharedSelectionStateMustRespectSelectOptionsAndNullability() {
        val session =
            sharedStateSession(
                mapOf(
                    "id" to "selection-control",
                    "type" to "select",
                    "state" to "selected",
                    "options" to mapOf("a" to "A", "b" to "B"),
                )
            )
        val before = Values.map(session.state)
        for (value in listOf("missing", null)) {
            assertFailsWith<IllegalArgumentException> {
                session.patchState(linkedMapOf("status" to "partial", "selected" to value))
            }
            assertEquals(before, session.state)
        }
        assertFailsWith<IllegalArgumentException> { session.setState("selected", "missing") }
        assertEquals(before, session.state)
        session.patchState(mapOf("status" to "done", "selected" to "b"))
        assertEquals("done", session.state["status"])
        assertEquals("b", session.state["selected"])
    }

    @Test
    fun sharedSelectionStateMustRespectTextInputLimits() {
        val session =
            sharedStateSession(
                mapOf(
                    "id" to "selection-control",
                    "type" to "input",
                    "state" to "selected",
                    "max-length" to 1,
                )
            )
        val before = Values.map(session.state)
        assertFailsWith<IllegalArgumentException> {
            session.patchState(linkedMapOf("status" to "partial", "selected" to "long"))
        }
        assertEquals(before, session.state)
        session.setState("selected", "b")
        assertEquals("b", session.state["selected"])
    }

    @Test
    fun listOnlySelectionsRemainNullableWithANonNullInitialValue() {
        val session = sharedStateSession()
        session.setState("selected", null)
        assertNull(session.state["selected"])
        session.setState("selected", "b")
        assertEquals("b", session.state["selected"])
    }
}
