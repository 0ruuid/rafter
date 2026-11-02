package com.hiusers.rafter

import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.SessionPhase
import com.hiusers.rafter.runtime.UiSession
import java.util.UUID
import kotlin.test.*

class SessionSecurityTest {
    private val fields =
        listOf(
            NodeSpec("name", NodeContent.TextInput("name", maxLength = 4)),
            NodeSpec("number", NodeContent.Slider("number", min = 0.0, max = 10.0, step = 5.0)),
            NodeSpec("enabled", NodeContent.Checkbox("enabled")),
            NodeSpec(
                "choice",
                NodeContent.Select(
                    "choice",
                    mapOf("a" to TextSpec(value = "A"), "b" to TextSpec(value = "B")),
                ),
            ),
        )
    private val valid =
        mapOf<String, Any?>(
            "name" to "new",
            "number" to "10.0",
            "enabled" to "false",
            "choice" to "b",
        )

    @Test
    fun phasesFenceRenderPublicationAndActionCompletion() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap())
        assertEquals(SessionPhase.CREATED, session.phase)
        assertFalse(session.closed)
        assertFalse(session.busy)
        assertNull(session.consume("forged"))
        assertFalse(session.finishAction(session.generation))
        assertFailsWith<IllegalStateException> { session.ready(session.generation, listOf("go")) }
        val stale = session.beginRender()
        assertEquals(SessionPhase.RENDERING, session.phase)
        val rendering = session.beginRender()
        assertFalse(session.isCurrent(stale))
        assertFailsWith<IllegalStateException> { session.ready(stale, listOf("go")) }
        val handles = session.ready(rendering, listOf("go", "__close"))
        assertEquals(SessionPhase.READY, session.phase)
        assertEquals(rendering, session.generation)
        assertFailsWith<IllegalStateException> { session.ready(rendering, listOf("new")) }
        assertNull(session.consume("go", session.lastInteraction))
        assertEquals("go", session.consume(handles.getValue("go"), session.lastInteraction))
        val action = session.generation
        assertEquals(rendering + 1, action)
        assertEquals(SessionPhase.EXECUTING, session.phase)
        assertTrue(session.busy)
        assertNull(session.consume(handles.getValue("__close"), session.lastInteraction))
        assertFailsWith<IllegalStateException> { session.beginRender() }
        assertFalse(session.finishAction(rendering))
        assertTrue(session.busy)
        assertTrue(session.finishAction(action))
        assertEquals(SessionPhase.CREATED, session.phase)
        assertEquals(action, session.generation)
        assertFalse(session.busy)
        assertFalse(session.finishAction(action))

        val interrupted = session.beginRender()
        session.invalidate()
        assertEquals(SessionPhase.RENDERING, session.phase)
        assertFailsWith<IllegalStateException> { session.ready(interrupted, listOf("go")) }
        val latest = session.ready(session.generation, listOf("go")).getValue("go")
        assertEquals("go", session.consume(latest, session.lastInteraction))
        val interruptedAction = session.generation
        session.invalidate()
        assertEquals(SessionPhase.EXECUTING, session.phase)
        assertFalse(session.finishAction(interruptedAction))
        assertTrue(session.finishAction(session.generation))
    }

    @Test
    fun navigationClosePlayersAndReplacementNeverReuseAuthorization() {
        val page =
            mapOf(
                "mode" to "native",
                "title" to "Page",
                "components" to
                    listOf(
                        mapOf(
                            "id" to "go",
                            "type" to "button",
                            "text" to "Go",
                            "actions" to listOf(mapOf("type" to "close")),
                        )
                    ),
            )
        val menu = compiled(minimal() + mapOf("pages" to mapOf("main" to page, "other" to page)))
        val a = UiSession(UUID.randomUUID(), menu, emptyMap())
        val b = UiSession(UUID.randomUUID(), menu, emptyMap())
        val aToken = a.ready(a.beginRender(), listOf("go")).getValue("go")
        val bToken = b.ready(b.beginRender(), listOf("go")).getValue("go")
        assertNull(a.consume(bToken, a.lastInteraction))
        assertNull(b.consume(aToken, b.lastInteraction))
        a.form["name"] = "draft"
        val old = a.generation
        a.navigate("other")
        assertEquals(SessionPhase.CREATED, a.phase)
        assertTrue(a.form.isEmpty())
        assertEquals(old + 1, a.generation)
        assertNull(a.consume(aToken, a.lastInteraction))
        val other = a.ready(a.beginRender(), listOf("go")).getValue("go")
        a.form["name"] = "draft"
        a.back()
        assertEquals("main", a.page)
        assertEquals(SessionPhase.CREATED, a.phase)
        assertTrue(a.form.isEmpty())
        assertNull(a.consume(other, a.lastInteraction))
        val closing = a.ready(a.beginRender(), listOf("__close")).getValue("__close")
        assertEquals("__close", a.consume(closing, a.lastInteraction))
        val action = a.generation
        a.form["name"] = "draft"
        a.close()
        assertEquals(SessionPhase.CLOSED, a.phase)
        assertTrue(a.form.isEmpty())
        assertFalse(a.busy)
        assertFalse(a.isCurrent(action))
        assertFalse(a.finishAction(action))
        assertNull(a.consume(closing, a.lastInteraction))
        assertFailsWith<IllegalStateException> { a.ready(a.generation, listOf("go")) }
        assertFailsWith<IllegalStateException> { a.navigate("other") }
        assertFailsWith<IllegalStateException> { a.back() }
        val replacement = UiSession(a.playerId, menu, emptyMap())
        val replacementToken =
            replacement.ready(replacement.beginRender(), listOf("go")).getValue("go")
        assertNotEquals(a.id, replacement.id)
        assertNull(replacement.consume(closing, replacement.lastInteraction))
        assertEquals("go", replacement.consume(replacementToken, replacement.lastInteraction))
        assertEquals("go", b.consume(bToken, b.lastInteraction))
    }

    @Test
    fun refreshRevokesTokensAndOnlySuccessfulConsumptionExtendsExpiry() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap(), 1000)
        val started = session.lastInteraction
        val old = session.ready(session.beginRender(), listOf("go")).getValue("go")
        val generation = session.beginRender()
        assertNull(session.consume(old, started + 100))
        val current = session.ready(generation, listOf("go")).getValue("go")
        assertNull(session.consume(old, started + 500))
        assertNull(session.consume("forged", started + 500))
        assertEquals(started, session.lastInteraction)
        assertEquals(generation, session.generation)
        assertFalse(session.expired(started + 999))
        assertTrue(session.expired(started + 1000))
        assertNull(session.consume(current, started + 1000))
        assertEquals(SessionPhase.READY, session.phase)
        assertEquals(started, session.lastInteraction)
        val active = UiSession(UUID.randomUUID(), compiled(), emptyMap(), 1000)
        val at = active.lastInteraction
        val token = active.ready(active.beginRender(), listOf("go")).getValue("go")
        assertEquals("go", active.consume(token, at + 999))
        assertEquals(at + 999, active.lastInteraction)
        assertFalse(active.expired(at + 1998))
        assertTrue(active.expired(at + 1999))
    }

    @Test
    fun malformedFieldsNeverCommitAndOnlyValidatedScalarsRemainAsDraft() {
        val malformed =
            listOf(
                "number" to Double.NaN,
                "number" to Float.NaN,
                "number" to "NaN",
                "number" to Double.POSITIVE_INFINITY,
                "number" to Double.NEGATIVE_INFINITY,
                "number" to "Infinity",
                "number" to -5,
                "number" to 15,
                "number" to 3,
                "number" to "3.0",
                "choice" to "forged",
                "name" to "new\n",
                "name" to "new\r",
                "name" to "longer",
                "enabled" to "yes",
                "enabled" to 1,
                "number" to listOf(5),
                "number" to null,
            )
        malformed.forEach { (field, value) ->
            val session = UiSession(UUID.randomUUID(), compiled(), emptyMap())
            val before = session.state.toMap()
            session.form["stale"] = "draft"
            val errors = session.submit(valid + mapOf(field to value), fields)
            assertEquals(setOf(field), errors.keys)
            assertEquals(before, session.state)
            assertEquals(valid.keys - field, session.form.keys)
            assertTrue(
                session.form.values.all {
                    it is String || it is Boolean || it is Double && it.isFinite()
                }
            )
        }
    }

    @Test
    fun unknownMissingAndInvalidStateFieldsRejectTheWholeSubmission() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap())
        val before = session.state.toMap()
        val unknown = session.submit(valid + mapOf("unexpected" to Any()), fields)
        assertEquals(setOf("unexpected"), unknown.keys)
        assertEquals(before, session.state)
        assertEquals(valid.keys, session.form.keys)
        valid.keys.forEach { missing ->
            assertEquals(setOf(missing), session.submit(valid - missing, fields).keys)
            assertEquals(before, session.state)
            assertFalse(missing in session.form)
        }
        val wrongState = NodeSpec("wrong", NodeContent.TextInput("number"))
        val errors = session.submit(valid + mapOf("wrong" to "5"), fields + wrongState)
        assertEquals(setOf("wrong"), errors.keys)
        assertEquals(before, session.state)
        assertEquals("5", session.form["wrong"])
        // 先复制草稿再清空，提交参数可能就是同一映射。
        session.form.clear()
        session.form.putAll(valid)
        assertTrue(session.submit(session.form, fields).isEmpty())
        assertEquals("new", session.state["name"])
        assertEquals(10.0, session.state["number"])
        assertEquals(false, session.state["enabled"])
        assertEquals("b", session.state["choice"])
    }

    @Test
    fun untrustedNumbersAreNeitherCoercedBySubmitNorStoredInTheModel() {
        val session = UiSession(UUID.randomUUID(), compiled(), emptyMap())
        val before = session.state.toMap()
        val number = UntrustedNumber()
        valid.keys.forEach { field ->
            assertEquals(setOf(field), session.submit(valid + mapOf(field to number), fields).keys)
            assertEquals(before, session.state)
            assertFalse(field in session.form)
        }
        assertEquals(0, number.reads)
        assertFailsWith<IllegalArgumentException> { session.setState("number", number) }
        assertEquals(before, session.state)
        assertFailsWith<IllegalArgumentException> {
            UiSession(UUID.randomUUID(), compiled(), mapOf("nested" to listOf(number)))
        }
    }

    private class UntrustedNumber : Number() {
        var reads = 0

        override fun toByte(): Byte = 5

        override fun toShort(): Short = 5

        override fun toInt(): Int = 5

        override fun toLong(): Long = 5

        override fun toFloat(): Float = 5f

        override fun toDouble(): Double {
            reads++
            return 5.0
        }
    }
}
