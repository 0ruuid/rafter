package com.hiusers.rafter

import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FriendsPreviewTest {
    @TempDir lateinit var root: Path

    @Test
    fun dataAndSkinsReplaceCopiedPagesWithoutChangingApprovedRowGeometry() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val loaded =
            MenuRepository(
                    root,
                    { error("Static preview must not execute Kether") },
                    ::checkStaticItemReference,
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        val menu = candidate.snapshot.menus.getValue("rafter:friends")
        assertEquals(setOf("friends", "add", "search", "chat", "memo", "remove"), menu.pages.keys)
        assertEquals(12, menu.spec.documents.getValue("preview:friends").size)
        assertTrue(
            listOf("selected-name", "selected-status", "selected-summary").none {
                it in menu.spec.state
            }
        )
        val authored = Files.readString(root.resolve("core/menus/friends.yml"))
        assertFalse("offset-y:" in authored)
        assertFalse("clear:" in authored)
        assertFalse("row-height:" in authored)
        val page = menu.pages.getValue("friends")
        assertEquals(560 to 342, page.spec.width to page.height)
        assertEquals(
            40 to 12,
            page.spec.elements.single { it.id == "header.title" }.let { it.x to it.y },
        )
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        val text = CompositeText(candidate.assets)
        var frame = OfflineFrame.resolve(session)
        fun label(id: String) =
            text.render(frame.resolvedNodes.getValue(id).text, frame.values).toPlainText()
        assertEquals("林间旅人", label("friends.row-0.name"))
        assertEquals("好友 12/100 · 在线 3", label("header.counts"))
        assertEquals("1 / 2", label("page-label"))
        session.setState("page", 1)
        frame = OfflineFrame.resolve(session)
        assertEquals("2 / 2", label("page-label"))
        assertEquals(
            menu.spec.documents.getValue("preview:friends")[6]["name"],
            label("friends.row-0.name"),
        )
        assertEquals(274, page.spec.elements.single { it.id == "friends.row-5.subtitle" }.y)
        session.setState("page", 0)
        session.setState("tab", "online")
        frame = OfflineFrame.resolve(session)
        assertEquals("1 / 1", label("page-label"))
        assertFalse(frame.visible.getValue("friends.row-3.name"))
        session.setState("tab", "requests")
        frame = OfflineFrame.resolve(session)
        assertTrue(frame.visible.getValue("requests.row-0.name"))
        assertFalse(frame.visible.getValue("friends.row-0.name"))
        assertEquals(
            listOf(83, 143, 203),
            (0..2).map { i -> page.spec.elements.single { it.id == "requests.row-$i.name" }.y },
        )
        assertEquals(
            listOf(72, 135, 189),
            (0..2).map { i -> page.spec.elements.single { it.id == "requests.row-$i.image" }.y },
        )
        val add = menu.pages.getValue("add")
        val search = menu.pages.getValue("search")
        assertNotNull(add.modalLayout)
        assertEquals(search.spec.width, add.spec.width)
        val plan = assertNotNull(add.modalLayout)
        val panel = candidate.assets.modalLayouts.single { it.key.endsWith("/add") }
        assertEquals(listOf("input", "message"), plan.inputs.map { it.id })
        assertTrue(
            plan.inputs.zipWithNext().all { (a, b) -> a.bounds.y + a.bounds.height < b.bounds.y }
        )
        assertTrue(
            (plan.inputs.map { it.bounds } + plan.buttons.map { it.bounds }).all {
                it.y >= panel.offsetY && it.y + it.height <= panel.offsetY + panel.height
            },
            "Both fields and the action row must stay inside the modal artwork",
        )
        val message =
            add.spec.components.single { it.id == "message" }.content as NodeContent.TextInput
        assertEquals("request-message", message.state)
        assertEquals(64, message.maxLength)
        assertTrue(message.labelVisible)
        assertTrue(message.multiline)
        assertEquals(48, message.multilineHeight)
        assertTrue(plan.buttons.take(plan.columns).all { it.preserveInputCounter })
        assertEquals("description", add.spec.modal!!.feedback)
        assertEquals(
            "target-name",
            (add.spec.components.single { it.id == "input" }.content as NodeContent.TextInput)
                .state,
        )
        assertEquals(
            listOf("submit", "set-state", "back"),
            add.spec.components.single { it.id == "confirm" }.actions.map { it.type },
        )
        val remove = menu.pages.getValue("remove")
        assertNotNull(remove.modalLayout)
        assertTrue(remove.spec.components.none { it.content is FormContent })
        assertEquals(
            listOf("set-state", "back"),
            remove.spec.components.single { it.id == "confirm" }.actions.map { it.type },
        )
        assertTrue(
            menu.spec.pages.values
                .flatMap { it.nodes }
                .flatMap { it.actions }
                .none { it.type in setOf("provider", "kether") }
        )
    }
}
