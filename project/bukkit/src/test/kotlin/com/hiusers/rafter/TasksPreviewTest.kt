package com.hiusers.rafter

import com.hiusers.rafter.asset.MeterAssets
import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.CanvasRenderer
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TasksPreviewTest {
    @TempDir lateinit var root: Path

    @Test
    fun recordsDriveAllPagesProgressAndActionsWithoutTaskSpecificSkinBranches() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val loaded =
            MenuRepository(
                    root,
                    { error("No script") },
                    { _, _, _ -> error("No business provider") },
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        assertEquals("rafter:friends", candidate.defaultMenu)
        val menu = candidate.snapshot.menus.getValue("rafter:tasks")
        assertEquals(setOf("journal"), menu.pages.keys)
        val session = UiSession(UUID.randomUUID(), menu, emptyMap())
        val text = CompositeText(candidate.assets)
        var frame = OfflineFrame.resolve(session)
        fun label(id: String) =
            text.render(frame.resolvedNodes.getValue(id).text, frame.values).toPlainText()
        fun render() = CanvasRenderer.render(session, frame, text)
        fun preview(name: String) {
            val page = session.current()
            val hidden = page.spec.popups[session.popup]?.hideUnderlay.orEmpty()
            val scene =
                (page.scene.filter { it.node.id !in hidden } + page.popups[session.popup].orEmpty())
                    .filter { frame.visible[it.node.id] == true }
                    .map { placed ->
                        val node = frame.resolvedNodes[placed.node.id] ?: placed.node
                        val width =
                            if (node.content == NodeContent.Text)
                                text.width(text.render(node.text, frame.values)).toInt()
                            else placed.bounds.width
                        val x =
                            when (node.align) {
                                "center" -> node.x - width / 2
                                "right" -> node.x - width
                                else -> placed.bounds.x
                            }
                        PositionedNode(node, placed.bounds.copy(x = x))
                    }
            UiPreview.render(
                page.copy(scene = scene),
                candidate.assets,
                root.resolve("core/ui"),
                Path.of("../../build/previews/tasks/$name.png"),
                frame.values,
            )
        }
        assertEquals("林地巡护", label("tasks.row-0.title"))
        assertEquals("1 / 2", label("main-labels.page"))
        assertTrue(frame.visible.getValue("categories.row-0.label"))
        render()
        preview("daily")
        session.data.presented()
        session.data.select(RowScope("tasks", 0)).let { session.setState(it.first, it.second) }
        session.openPopup("detail")
        frame = OfflineFrame.resolve(session)
        assertEquals("林地巡护", label("detail-title"))
        assertEquals("2 / 2", label("goals-count"))
        val plain = render().messages.single().parts.joinToString("") { it.text.toPlainText() }
        assertFalse(
            "巡视林地，为营地收集沿途资源。" in plain,
            "Covered labels must not be sent, regardless of client draw batches",
        )
        assertFalse(session.acceptsTarget("tasks.row-0.body"))
        preview("ready-detail")
        session.data.patch("tasks", "forest_patrol", "phase", "claimed")
        frame = OfflineFrame.resolve(session)
        assertFalse(frame.enabled.getValue("primary"))
        assertEquals("已领取", label("primary-label"))
        session.closePopup()
        frame = OfflineFrame.resolve(session)
        assertEquals("待领取 0 项奖励", label("main-labels.ready-count"))
        assertFalse(frame.visible.getValue("tasks.row-0.action"))
        val other = UiSession(UUID.randomUUID(), menu, emptyMap())
        assertTrue(OfflineFrame.resolve(other).visible.getValue("tasks.row-0.action"))
        session.setState("task-page", 1)
        frame = OfflineFrame.resolve(session)
        assertEquals("限时补给", label("tasks.row-0.title"))
        render()
        session.setState("type-page", 1)
        frame = OfflineFrame.resolve(session)
        assertEquals(1, session.state["task-page"])
        assertEquals("主线任务", label("categories.row-0.label"))
        preview("types")
        render()
        session.setState("category", "weekly")
        session.setState("task-page", 0)
        session.setState("type-page", 0)
        frame = OfflineFrame.resolve(session)
        assertEquals("共建营地", label("tasks.row-0.title"))
        session.openPopup("detail")
        frame = OfflineFrame.resolve(session)
        assertEquals("完成营地建造项目", label("goals.row-0.name"))
        val meter =
            frame.resolvedNodes.getValue("goals.row-0.progress").content as NodeContent.Meter
        assertEquals(
            82,
            MeterAssets.filled(110, frame.values[meter.value], frame.values[meter.maximum]),
        )
        preview("weekly-detail")
        render()
        session.setState("goal-page", 1)
        frame = OfflineFrame.resolve(session)
        assertEquals("摆放公共照明", label("goals.row-0.name"))
        assertEquals("共建营地", label("detail-title"))
        assertEquals("1 / 1", label("main-labels.page"))
        preview("weekly-goals-2")
        render()
        session.data.patch("tasks", "weekly_builder", "tracked", null, toggle = true)
        frame = OfflineFrame.resolve(session)
        assertEquals("取消追踪", label("primary-label"))
        // 只修改记录数量与目标，不增加贴图或皮肤分支。
        val goals =
            (menu.spec.documents
                    .getValue("preview:tasks")
                    .first { it["id"] == "weekly_builder" }["goals"]
                    as List<*>)
                .map { it as Map<*, *> }
        val changed =
            goals.mapIndexed { index, g ->
                if (index == 0) g + mapOf("current" to 1, "required" to 4) else g
            } +
                (5..8).map {
                    mapOf(
                        "id" to "$it",
                        "name" to "新增目标 $it",
                        "current" to 0,
                        "required" to 2,
                        "complete" to false,
                    )
                }
        session.data.patch("tasks", "weekly_builder", "goals", changed)
        session.setState("goal-page", 0)
        frame = OfflineFrame.resolve(session)
        assertEquals(
            27,
            MeterAssets.filled(110, frame.values[meter.value], frame.values[meter.maximum]),
        )
        assertEquals("第 1 / 3 页", label("goal-page"))
        render()
        session.setState("goal-page", 2)
        frame = OfflineFrame.resolve(session)
        assertEquals("新增目标 7", label("goals.row-0.name"))
        render()
        session.closePopup()
        session.setState("category", "events")
        frame = OfflineFrame.resolve(session)
        assertTrue(frame.visible.getValue("main-labels.empty-title"))
        assertEquals("0 项", label("main-labels.page"))
        render()
        assertEquals(0, MeterAssets.filled(110, 0, 0))
        assertEquals(110, MeterAssets.filled(110, 9, 4))
        assertFailsWith<IllegalArgumentException> { MeterAssets.filled(110, Double.NaN, 4) }
        assertFailsWith<IllegalArgumentException> {
            session.data.patch("tasks", "forest_patrol", "id", "changed")
        }
        assertFailsWith<IllegalArgumentException> { session.data.patch("goals", "1", "current", 2) }
    }

    @Test
    fun authoringContainsNoTransportPaddingExpandedOcclusionIdsOrTaskSpecificSkins() {
        DefaultBundle.install(root)
        val menu = Files.readString(root.resolve("core/menus/tasks.yml"))
        val skin = Files.readString(root.resolve("core/ui/skins/tasks/journal.yml"))
        val row = Files.readString(root.resolve("core/ui/templates/tasks/row.yml"))
        for (value in listOf(menu, skin, row)) {
            assertFalse("__pad" in value)
            assertFalse("row-0." in value)
            assertFalse("forest_patrol" in value)
            assertFalse("weekly_builder" in value)
        }
        assertTrue("main-labels" in skin)
        assertFalse("detail-hidden" in menu)
        Files.walk(root.resolve("core/ui/images/tasks")).use { paths ->
            assertFalse(paths.anyMatch { "__pad" in it.fileName.toString() })
        }
    }
}
