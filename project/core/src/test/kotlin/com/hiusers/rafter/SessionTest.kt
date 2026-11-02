package com.hiusers.rafter

import com.hiusers.rafter.api.ParameterSpec
import com.hiusers.rafter.api.ParameterType
import com.hiusers.rafter.api.ProviderDescriptor
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class SessionTest {
    @Test
    fun playersDoNotShareArgsStateOrForm() {
        val menu = compiled()
        val args = mutableMapOf<String, Any?>("x" to "original")
        val a = UiSession(UUID.randomUUID(), menu, args)
        val b = UiSession(UUID.randomUUID(), menu, emptyMap())
        args["x"] = "mutated"
        a.state["name"] = "changed"
        a.form["draft"] = "draft"
        assertEquals("original", a.args["x"])
        assertEquals("old", b.state["name"])
        assertTrue(b.form.isEmpty())
    }

    @Test
    fun handlesAreOpaqueSingleUseAndExpire() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap(), 1000)
        val handles = session.ready(session.beginRender(), listOf("a", "b", "a"))
        val first = handles.getValue("a")
        val other = handles.getValue("b")
        assertEquals(2, handles.size)
        assertNotEquals(first, other)
        assertEquals(first, UUID.fromString(first).toString())
        assertFailsWith<UnsupportedOperationException> {
            (handles as MutableMap<String, String>)["forged"] = "token"
        }
        val now = session.lastInteraction
        assertNull(session.consume("forged", now))
        assertEquals("a", session.consume(first, now))
        assertNull(session.consume(first, now))
        assertNull(session.consume(other, now))
        assertTrue(session.finishAction(session.generation))
        val late = session.ready(session.beginRender(), listOf("a")).getValue("a")
        assertNull(session.consume(late, session.lastInteraction + 1000))
        session.close()
        assertNull(session.consume(late, now))
        assertTrue(session.closed)
        assertFalse(session.busy)
        assertFailsWith<IllegalStateException> { session.beginRender() }
    }

    @Test
    fun refreshCloseAndReplacementFenceOldFutures() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap())
        val stale = CompletableFuture<String>()
        val old = session.invalidate()
        var value = "original"
        stale.thenAccept { if (session.isCurrent(old)) value = it }
        session.invalidate()
        stale.complete("stale")
        assertEquals("original", value)
        val active = session.generation
        session.close()
        assertFalse(session.isCurrent(active))
    }

    @Test
    fun formsValidateAtomicallyAndCancelDoesNotCommit() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap())
        val fields =
            listOf(
                NodeSpec("name", NodeContent.TextInput("name", maxLength = 4)),
                NodeSpec("number", NodeContent.Slider("number", min = 0.0, max = 10.0, step = 5.0)),
                NodeSpec("enabled", NodeContent.Checkbox("enabled")),
                NodeSpec(
                    "choice",
                    NodeContent.Select(
                        "choice",
                        options = mapOf("a" to TextSpec(value = "A"), "b" to TextSpec(value = "B")),
                    ),
                ),
            )
        val failure =
            session.submit(
                mapOf("name" to "new", "number" to "NaN", "enabled" to "true", "choice" to "a"),
                fields,
            )
        assertTrue(failure.isNotEmpty())
        assertEquals("old", session.state["name"])
        val valid =
            session.submit(
                mapOf("name" to "new", "number" to "10.0", "enabled" to "false", "choice" to "b"),
                fields,
            )
        assertTrue(valid.isEmpty())
        assertEquals("new", session.state["name"])
        assertEquals(false, session.state["enabled"])
        assertEquals(10.0, session.state["number"])
        session.form["name"] = "draft"
        session.back()
        assertEquals("new", session.state["name"])
        assertTrue(session.form.isEmpty())
        assertTrue(
            session
                .submit(
                    mapOf(
                        "name" to "new\n",
                        "number" to 3,
                        "enabled" to "yes",
                        "choice" to "forged",
                    ),
                    fields,
                )
                .isNotEmpty()
        )
    }

    @Test
    fun ownerRegistrationsUnregisterWithoutRemovingAReplacement() {
        val registry = Registry<String>()
        val owner = Any()
        val descriptor =
            ProviderDescriptor("test", mapOf("value" to ParameterSpec(ParameterType.STRING)))
        val first = registry.register(owner, "test:data", descriptor, "first")
        assertFails { registry.register(owner, "test:data", descriptor, "duplicate") }
        first.close()
        registry.register(owner, "test:data", descriptor, "second")
        first.close()
        assertEquals("second", registry.get("test:data"))
        registry.check("test:data", mapOf("value" to "ok"))
        assertFails { registry.check("test:data", mapOf("unknown" to true)) }
        assertEquals(setOf("test:data"), registry.removeOwner(owner))
        assertFails { registry.get("test:data") }
    }
}
