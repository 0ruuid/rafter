package com.hiusers.rafter

import com.hiusers.rafter.asset.ButtonSkins
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.ButtonRole
import com.hiusers.rafter.model.ButtonSkinSpec
import com.hiusers.rafter.model.NodeContent
import kotlin.test.*

class ButtonSkinCompilerTest {
    private val assets = mapOf("rafter:green" to (90 to 18), "rafter:gray" to (90 to 18))
    private val compiler = MenuCompiler(assets)
    private val button =
        mapOf<String, Any?>(
            "id" to "save",
            "type" to "button",
            "width" to 90,
            "text" to "Save",
            "skin" to mapOf("asset" to "rafter:green"),
            "actions" to listOf(mapOf("type" to "close")),
        )

    @Test
    fun skinRequestsIncludeVariantsButRoleAndPlacementStayStructural() {
        val variants =
            listOf(
                mapOf(
                    "when" to mapOf("type" to "constant", "value" to true),
                    "override" to mapOf("skin" to mapOf("asset" to "rafter:gray", "inset" to 0)),
                )
            )
        val result =
            compiler.compile(
                mapOf(
                    "menu.yml" to
                        minimal(
                            components =
                                listOf(button + mapOf("role" to "exit", "variants" to variants))
                        )
                )
            )
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        val node =
            snapshot.menus.getValue("test:menu").spec.pages.getValue("main").components.single()
        val content = assertIs<NodeContent.Button>(node.content)
        assertEquals(ButtonRole.EXIT, content.role)
        assertEquals(ButtonSkinSpec("rafter:green"), content.skin)
        assertEquals(
            mapOf(
                ButtonSkinSpec("rafter:green").request(90) to 1,
                ButtonSkinSpec("rafter:gray", 0).request(90) to 0,
            ),
            ButtonSkins.requests(snapshot),
        )
        assertFalse("role" in Contract.variantFields("button", "native"))
        assertEquals("button-skin", Contract.fields("button", "native").getValue("skin").type)
        assertTrue("button-skin" in (Contract.schema().getValue("\$defs") as Map<*, *>))
    }

    @Test
    fun skinFieldsSourcesAndDuplicateExitsAreRejectedWithSourcePaths() {
        for (skin in
            listOf<Any?>(
                "rafter:green",
                emptyMap<String, Any?>(),
                mapOf("asset" to "rafter:missing"),
                mapOf("asset" to "rafter:_sized/generated"),
                mapOf("asset" to "rafter:green", "inset" to 5),
                mapOf("asset" to "rafter:green", "typo" to true),
            )) {
            val result =
                compiler.compile(
                    mapOf("menu.yml" to minimal(components = listOf(button + ("skin" to skin))))
                )
            assertNull(result.snapshot, skin.toString())
            assertTrue(result.diagnostics.single().path.contains("skin"))
        }
        val tooSmall =
            compiler.compile(
                mapOf("menu.yml" to minimal(components = listOf(button + ("width" to 2))))
            )
        assertNull(tooSmall.snapshot)
        val exits =
            compiler.compile(
                mapOf(
                    "menu.yml" to
                        minimal(
                            components =
                                listOf(
                                    button + ("role" to "exit"),
                                    button + mapOf("id" to "other", "role" to "exit"),
                                )
                        )
                )
            )
        assertEquals("DUPLICATE_EXIT", exits.diagnostics.single().code)
        val canvas = compiler.compile(mapOf("menu.yml" to minimal("canvas", listOf(button))))
        assertNull(canvas.snapshot, "Canvas elements and native buttons must remain separate")
    }

    @Test
    fun knownLabelChecksRunForSkinsWithoutConstrainingOrdinaryNativeButtons() {
        val checked = mutableListOf<Pair<String, Int>>()
        val strict =
            MenuCompiler(
                assets,
                buttonTextCheck = { text, width ->
                    checked += text.value to width
                    require(text.value != "bad") { "Label cannot be measured" }
                },
            )
        assertNotNull(
            strict.compile(mapOf("menu.yml" to minimal(components = listOf(button)))).snapshot
        )
        assertEquals(listOf("Save" to 90), checked)
        val failed =
            strict.compile(
                mapOf("menu.yml" to minimal(components = listOf(button + ("text" to "bad"))))
            )
        assertEquals("BUTTON_TEXT", failed.diagnostics.single().code)
        assertNotNull(
            strict
                .compile(
                    mapOf(
                        "menu.yml" to
                            minimal(components = listOf((button - "skin") + ("text" to "bad")))
                    )
                )
                .snapshot
        )
    }
}
