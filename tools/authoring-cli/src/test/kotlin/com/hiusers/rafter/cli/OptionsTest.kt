package com.hiusers.rafter.cli

import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.compile.AuthoringContract
import java.util.Properties
import kotlin.test.*

class OptionsTest {
    @Test
    fun describeUsesThePackagedVersionAndRuntimeContract() {
        val output = mutableListOf<String>()
        assertEquals(0, runAuthoring(listOf("describe"), output::add))
        val manifest = Json.gson.fromJson(output.single(), Map::class.java)
        val version =
            Properties()
                .apply {
                    requireNotNull(
                            Options::class.java.getResourceAsStream("/rafter-author.properties")
                        )
                        .use(::load)
                }
                .getProperty("version")
        assertEquals(version, manifest["engine-version"])
        assertEquals(AuthoringContract.VERSION, (manifest["contract-version"] as Number).toInt())
        assertEquals(
            Json.gson.toJsonTree(AuthoringContract.manifest(version, emptyMap())["canvas"]),
            Json.gson.toJsonTree(manifest["canvas"]),
        )
    }

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
