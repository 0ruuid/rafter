package com.hiusers.rafter

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.DialogFrameSpec
import kotlin.test.*

class DialogFrameCompilerTest {
    private val compiler = MenuCompiler(mapOf("rafter:panel" to (180 to 90)))

    private fun document(mode: String, frame: Map<String, Any?>) =
        mapOf(
            "id" to "test:frame",
            "entry" to "main",
            "pages" to
                mapOf(
                    "main" to
                        mapOf(
                            "mode" to mode,
                            "title" to "Title",
                            "frame" to frame,
                            (if (mode == "canvas") "elements" else "components") to
                                emptyList<Any?>(),
                        )
                ),
        )

    @Test
    fun aFrameIsAnExplicitViewportBackdropNotABodyComponent() {
        for (mode in listOf("native", "canvas")) {
            val result =
                compiler.compile(
                    mapOf("menu.yml" to document(mode, mapOf("asset" to "rafter:panel")))
                )
            val page =
                assertNotNull(result.snapshot, result.diagnostics.toString())
                    .menus
                    .getValue("test:frame")
                    .spec
                    .pages
                    .getValue("main")
            assertEquals(DialogFrameSpec("rafter:panel"), page.frame)
            assertTrue(
                page.nodes.isEmpty(),
                "A background must not become an extra body or input row",
            )
            assertEquals("frame", Contract.pageFields(mode).getValue("frame").type)
        }
        val fields = Contract.schema()["\$defs"] as Map<*, *>
        assertTrue("frame" in fields)
    }

    @Test
    fun frameSourcesAndParametersAreStrictlyValidated() {
        val invalid =
            listOf(
                emptyMap(),
                mapOf("asset" to "rafter:missing"),
                mapOf("asset" to "rafter:panel", "padding" to -1),
                mapOf("asset" to "rafter:panel", "inset" to 5),
                mapOf("asset" to "rafter:panel", "width" to 89),
                mapOf("asset" to "rafter:panel", "unknown" to true),
            )
        invalid.forEach { frame ->
            val result = compiler.compile(mapOf("menu.yml" to document("native", frame)))
            assertNull(result.snapshot, frame.toString())
            assertTrue(result.diagnostics.single().path.contains("frame"))
        }
    }
}
