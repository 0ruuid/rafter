package com.hiusers.rafter.cli

import kotlin.test.*

class OptionsTest {
    @Test
    fun optionsAreExplicitAndCommandSpecific() {
        assertEquals("describe", Options.parse(listOf("describe")).command)
        assertEquals(
            "core/previews/search.yml",
            Options.parse(
                    listOf("preview", "--project", ".", "--scenario", "core/previews/search.yml")
                )
                .scenario,
        )
        assertTrue(Options.parse(listOf("pack", "--project", ".", "--archive")).archive)
        for (args in
            listOf(
                listOf("validate"),
                listOf("preview", "--project"),
                listOf("pack", "--project", ".", "--menu", "x"),
                listOf("validate", "--project", ".", "--project", "."),
            )) assertFailsWith<IllegalArgumentException> { Options.parse(args) }
    }
}
