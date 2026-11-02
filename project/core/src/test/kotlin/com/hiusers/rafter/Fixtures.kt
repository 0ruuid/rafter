package com.hiusers.rafter

import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*

fun minimal(
    mode: String = "native",
    components: List<Map<String, Any?>> =
        listOf(mapOf("id" to "label", "type" to "text", "text" to "Hello")),
): Map<String, Any?> =
    mapOf(
        "id" to "test:menu",
        "entry" to "main",
        "state" to mapOf("name" to "old", "enabled" to true, "choice" to "a", "number" to 5),
        "pages" to
            mapOf(
                "main" to
                    mapOf(
                        "mode" to mode,
                        "title" to "Title",
                        (if (mode == "canvas") "elements" else "components") to components,
                    )
            ),
    )

fun compiled(document: Map<String, Any?> = minimal()): CompiledMenu =
    MenuCompiler().compile(mapOf("test.yml" to document)).snapshot!!.menus.getValue("test:menu")
