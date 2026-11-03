package com.hiusers.rafter

import com.hiusers.rafter.api.ParameterSpec
import com.hiusers.rafter.api.ParameterType
import com.hiusers.rafter.api.ProviderDescriptor
import com.hiusers.rafter.asset.ModalBackdropLayout
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
    fun modalReplacementReusesBackdropWithoutHistoryChainOrDraftCommit() {
        val pages =
            listOf(
                PageSpec(
                    id = "canvas",
                    mode = PageMode.CANVAS,
                    title = TextSpec(),
                    width = 180,
                    columns = 1,
                    components = emptyList(),
                    height = 90,
                    popups = mapOf("popup" to PopupSpec(emptyList())),
                ),
                PageSpec(
                    id = "modal1",
                    mode = PageMode.CANVAS,
                    title = TextSpec(),
                    width = 180,
                    columns = 1,
                    components = emptyList(),
                    modal = ModalSpec("rafter:panel", "#101820"),
                ),
                PageSpec(
                    id = "modal2",
                    mode = PageMode.CANVAS,
                    title = TextSpec(),
                    width = 180,
                    columns = 1,
                    components = emptyList(),
                    modal = ModalSpec("rafter:panel", "#101820"),
                ),
            ).associateBy { it.id }
        val menuSpec =
            MenuSpec(
                id = "test:modal-chain",
                entry = "canvas",
                permission = null,
                state = mapOf<String, Any?>("draft" to "old"),
                pages = pages,
                source = "test.yml",
            )
        val menu =
            CompiledMenu(
                menuSpec,
                pages.mapValues { (_, page) -> CompiledPage(page, emptyList(), 90) },
            )
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        session.ready(session.beginRender(), emptyList())
        val backdrop =
            FrozenCanvas(
                page = "canvas",
                generation = session.generation,
                layout = ModalBackdropLayout(180, 90),
                title = "",
                titleWidth = 0,
                parts = emptyList(),
            )
        session.rememberCanvas(backdrop)
        session.openPopup("popup")

        session.navigate("modal1")
        assertSame(backdrop, session.modalBackdrop)
        val oldHandle = session.ready(session.beginRender(), listOf("old")).getValue("old")
        session.form["draft"] = "typed"
        val oldGeneration = session.generation

        session.navigate("modal2")
        assertEquals(oldGeneration + 1, session.generation)
        assertEquals(SessionPhase.CREATED, session.phase)
        assertNull(session.consume(oldHandle, session.lastInteraction))
        assertSame(backdrop, session.modalBackdrop)
        assertTrue(session.form.isEmpty())
        assertEquals("old", session.state["draft"])

        session.navigate("modal1")
        assertSame(backdrop, session.modalBackdrop)
        session.form["draft"] = "typed-again"
        session.back()
        assertEquals("canvas", session.page)
        assertEquals("popup", session.popup)
        assertNull(session.modalBackdrop)
        assertTrue(session.form.isEmpty())
        assertEquals("old", session.state["draft"])

        session.navigate("modal1")
        session.navigate("canvas")
        session.back()
        assertEquals("canvas", session.page)
        assertNull(session.modalBackdrop, "Leaving a modal must not leave it in history")
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
