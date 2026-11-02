package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import kotlin.test.*
import org.junit.jupiter.api.Test

class AuthoringRegionsTest {
    private fun menu(data: Map<String, Any?> = emptyMap(), hidden: String = "content") =
        mapOf(
            "id" to "test:regions",
            "entry" to "home",
            "state" to mapOf("selected" to "one"),
            "data" to data,
            "pages" to
                mapOf(
                    "home" to
                        mapOf(
                            "mode" to "canvas",
                            "width" to 180,
                            "elements" to
                                listOf(
                                    mapOf(
                                        "id" to "content",
                                        "type" to "group",
                                        "elements" to
                                            listOf(
                                                mapOf(
                                                    "id" to "label",
                                                    "type" to "text",
                                                    "text" to "covered",
                                                )
                                            ),
                                    )
                                ),
                            "popups" to
                                mapOf(
                                    "dialog" to
                                        mapOf(
                                            "hide-underlay" to listOf(hidden),
                                            "elements" to
                                                listOf(
                                                    mapOf(
                                                        "id" to "title",
                                                        "type" to "text",
                                                        "text" to "front",
                                                    )
                                                ),
                                        )
                                ),
                        )
                ),
        )

    @Test
    fun regionExpansionRejectsUnknownIdsAndHandlesNestedNamesWithoutWildcards() {
        val good = MenuCompiler(emptyMap()).compile(mapOf("menu.yml" to menu()))
        assertEquals(
            setOf("content.label"),
            assertNotNull(good.snapshot, good.diagnostics.toString())
                .menus
                .getValue("test:regions")
                .pages
                .getValue("home")
                .spec
                .popups
                .getValue("dialog")
                .hideUnderlay,
        )
        val bad = MenuCompiler(emptyMap()).compile(mapOf("menu.yml" to menu(hidden = "cont")))
        assertNull(bad.snapshot)
        assertEquals("REFERENCE", bad.diagnostics.single().code)
    }

    @Test
    fun recordCollectionCyclesAreRejectedBeforeAnyProviderIsRead() {
        fun source(parent: String) =
            mapOf(
                "type" to "record",
                "source" to parent,
                "state" to "selected",
                "key" to "children",
            )
        val result =
            MenuCompiler(emptyMap())
                .compile(mapOf("menu.yml" to menu(mapOf("a" to source("b"), "b" to source("a")))))
        assertNull(result.snapshot)
        assertEquals("DATA_CYCLE", result.diagnostics.single().code)
    }

    @Test
    fun numericConditionConstantsAreRejectedDuringCompilationNotOnFirstClick() {
        fun compile(value: Any?) =
            MenuCompiler(emptyMap())
                .compile(
                    mapOf(
                        "menu.yml" to
                            mapOf(
                                "id" to "test:numeric",
                                "entry" to "home",
                                "state" to mapOf("count" to 1),
                                "pages" to
                                    mapOf(
                                        "home" to
                                            mapOf(
                                                "mode" to "canvas",
                                                "width" to 180,
                                                "elements" to
                                                    listOf(
                                                        mapOf(
                                                            "id" to "label",
                                                            "type" to "text",
                                                            "text" to "Ready",
                                                            "visible" to
                                                                mapOf(
                                                                    "type" to "at-least",
                                                                    "binding" to
                                                                        mapOf(
                                                                            "type" to "state",
                                                                            "key" to "count",
                                                                        ),
                                                                    "value" to value,
                                                                ),
                                                        )
                                                    ),
                                            )
                                    ),
                            )
                    )
                )
        for (bad in listOf("1", true, null)) {
            val result = compile(bad)
            assertNull(result.snapshot)
            assertEquals("FIELD_TYPE", result.diagnostics.single().code)
            assertTrue(result.diagnostics.single().path.endsWith(".value"))
        }
        assertNotNull(compile(1).snapshot)
        assertEquals(
            "number",
            com.hiusers.rafter.compile.Contract.conditions
                .getValue("at-least")
                .getValue("value")
                .type,
        )
    }
}
