package com.hiusers.rafter

import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.FormContent
import com.hiusers.rafter.runtime.UiSession
import java.util.UUID
import kotlin.test.*

class CanvasNativeComponentsTest {
    private val fields =
        listOf(
            mapOf("id" to "name", "type" to "input", "state" to "name", "max-length" to 4),
            mapOf("id" to "check", "type" to "checkbox", "state" to "enabled"),
            mapOf(
                "id" to "choice",
                "type" to "select",
                "state" to "choice",
                "options" to mapOf("a" to "A", "b" to "B"),
            ),
            mapOf(
                "id" to "range",
                "type" to "slider",
                "state" to "number",
                "min" to 0,
                "max" to 10,
                "step" to 1,
            ),
        )
    private val submit =
        mapOf("id" to "save", "type" to "button", "actions" to listOf(mapOf("type" to "submit")))
    private val hit =
        mapOf(
            "id" to "hit",
            "type" to "hit-area",
            "width" to 9,
            "height" to 9,
            "actions" to listOf(mapOf("type" to "close")),
        )

    private fun document(
        components: List<Map<String, Any?>> = fields + submit,
        elements: List<Map<String, Any?>> = listOf(hit),
    ) =
        minimal() +
            ("pages" to
                mapOf(
                    "main" to
                        mapOf(
                            "mode" to "canvas",
                            "title" to "Mixed",
                            "elements" to elements,
                            "components" to components,
                            "columns" to 2,
                        )
                ))

    private fun compile(document: Map<String, Any?>) =
        MenuCompiler().compile(mapOf("mixed.yml" to document))

    @Test
    fun mixedRegionsHaveSharedIdentityButSeparateLayoutAndNativeContracts() {
        val snapshot = assertNotNull(compile(document()).snapshot)
        val page = snapshot.menus.getValue("test:menu").pages.getValue("main")
        assertEquals(listOf("hit"), page.scene.map { it.node.id })
        assertEquals(listOf("hit"), page.spec.elements.map { it.id })
        assertEquals(
            listOf("name", "check", "choice", "range", "save"),
            page.spec.components.map { it.id },
        )
        assertEquals(6, page.spec.nodes.size)
        assertEquals("nativeNode", Contract.pageFields("canvas").getValue("components").items)
        assertFalse(Contract.pageFields("canvas").getValue("components").required)
        @Suppress("UNCHECKED_CAST")
        val capabilities = Contract.manifest()["capabilities"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val collections =
            capabilities.getValue("canvas").getValue("collections")
                as Map<String, Map<String, String>>
        assertEquals("native", collections.getValue("components")["mode"])
        val exported = AuthoringContract.layouts(snapshot).toString()
        assertTrue("native-components" in exported && "native-client" in exported)
        assertTrue("$.pages.main.components[0]" in exported)
        assertNull(compile(document(fields + (submit + ("x" to 0)))).snapshot)
        assertNull(compile(document(fields + submit, listOf(fields.first()))).snapshot)
        assertTrue(
            compile(document(fields + (submit + ("id" to "hit")))).diagnostics.any {
                it.code == "DUPLICATE_ID"
            }
        )
        assertTrue(
            compile(document(fields + (fields.first() + ("id" to "duplicate")))).diagnostics.any {
                it.code == "STATE"
            }
        )
        assertTrue(
            compile(
                    document(
                        elements = listOf(hit + ("actions" to listOf(mapOf("type" to "submit"))))
                    )
                )
                .diagnostics
                .any { it.code == "ACTION" }
        )
    }

    @Test
    fun formVariantsRetainTheirValidationAndStateCommitsAreAtomic() {
        val variant =
            mapOf(
                "when" to mapOf("type" to "constant", "value" to true),
                "override" to mapOf("text" to "Alternate"),
            )
        val components = fields.map { it + ("variants" to listOf(variant)) } + submit
        val result = compile(document(components))
        val menu =
            assertNotNull(result.snapshot, result.diagnostics.toString())
                .menus
                .getValue("test:menu")
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        val inputs = session.current().spec.components.filter { it.content is FormContent }
        assertTrue(inputs.all { it.variants.single().node.state == it.state })
        assertFailsWith<IllegalArgumentException> { session.setState("name", "too long") }
        assertFailsWith<IllegalArgumentException> { session.setState("number", 10.5) }
        val original = session.state.toMap()
        val bad =
            session.submit(
                mapOf("name" to "too long", "check" to "false", "choice" to "b", "range" to "6"),
                inputs,
            )
        assertEquals(setOf("name"), bad.keys)
        assertEquals(original, session.state)
        assertEquals("b", session.form["choice"])
        assertTrue(
            session
                .submit(
                    mapOf("name" to "new", "check" to "false", "choice" to "b", "range" to "6"),
                    inputs,
                )
                .isEmpty()
        )
        assertEquals(
            mapOf<String, Any?>(
                "name" to "new",
                "enabled" to false,
                "choice" to "b",
                "number" to 6.0,
            ),
            session.state.toMap(),
        )
        val generation = session.beginRender()
        val token = session.ready(generation, listOf("save")).getValue("save")
        assertEquals("save", session.consume(token))
        assertNull(session.consume(token))
        session.close()
        assertTrue(session.form.isEmpty())
        assertNull(session.consume(token))
        val invalidVariant = variant + ("override" to mapOf("state" to "other"))
        assertNull(
            compile(document(fields.map { it + ("variants" to listOf(invalidVariant)) })).snapshot
        )
    }
}
