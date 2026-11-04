package com.hiusers.rafter

import com.hiusers.rafter.asset.CanvasCarrier
import com.hiusers.rafter.asset.CanvasFlow
import com.hiusers.rafter.asset.CanvasFlowBlock
import com.hiusers.rafter.asset.CanvasFlowPlan
import com.hiusers.rafter.asset.CanvasTextBand
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.NativeGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CanvasFlowTest {
    @Test
    fun prototypeCanvasReusesBlankBandsForAllItems() {
        val items = (0 until 18).map { "item-$it" }
        val plan = CanvasFlow.plan(560, 342, items, (11..14).toSet() + (17..20).toSet())

        assertEquals(0, plan.inset)
        assertEquals(350, plan.bodyHeight)
        assertEquals(380, plan.layoutHeight)
        assertEquals(18, carriers(plan).count { it.node != null })
        assertLayout(plan, items, (11..14).toSet() + (17..20).toSet())
    }

    @Test
    fun fullInteractionCanGrowFiniteLeadingInsetForSixtyFourItems() {
        val items = List(64) { "item-$it" }
        val plan = CanvasFlow.plan(71, 18, items, setOf(0, 1))

        assertTrue(plan.inset > 0)
        assertLayout(plan, items, setOf(0, 1))
    }

    @Test
    fun irregularRowsMergeOnlyInvalidGapsAndKeepAStableNativeInterval() {
        val plan = CanvasFlow.plan(123, 99, emptyList(), setOf(0, 2, 5, 10))
        val bands = plan.blocks.filterIsInstance<CanvasTextBand>()

        assertEquals(3, bands.size)
        assertTrue(carriers(plan).any { it.node == null })
        assertLayout(plan, emptyList(), setOf(0, 2, 5, 10))
    }

    @Test
    fun emptyItemListUsesTheLastCanvasRow() {
        val plan = CanvasFlow.plan(37, 36, emptyList(), emptySet())

        assertTrue(carriers(plan).all { it.node == null })
        assertTrue(plan.blocks.filterIsInstance<CanvasTextBand>().any { it.startRow == 3 })
        assertLayout(plan, emptyList(), setOf(3))
    }

    @Test
    fun differentDimensionsAndInvalidInputsStayDeterministic() {
        for ((width, height, rows) in
            listOf(
                Triple(1, 9, setOf(0)),
                Triple(73, 63, setOf(1, 3, 6)),
                Triple(2048, 180, setOf(0, 8, 19)),
            )) {
            val items = listOf("a", "b", "c")
            assertLayout(CanvasFlow.plan(width, height, items, rows), items, rows)
        }

        assertFails { CanvasFlow.plan(0, 9, emptyList(), emptySet()) }
        assertFails { CanvasFlow.plan(10, 10, emptyList(), emptySet()) }
        assertFails { CanvasFlow.plan(10, 18, emptyList(), setOf(2)) }
        assertFails { CanvasFlow.plan(10, 9, listOf(""), setOf(0)) }
        assertFails { CanvasFlow.plan(10, 9, listOf("same", "same"), setOf(0)) }
    }

    @Test
    fun planExposesReadOnlyBlocks() {
        val plan = CanvasFlow.plan(20, 9, emptyList(), setOf(0))

        @Suppress("UNCHECKED_CAST")
        assertFails { (plan.blocks as MutableList<CanvasFlowBlock>).add(CanvasCarrier(0, 1, null)) }
    }

    private fun carriers(plan: CanvasFlowPlan): List<CanvasCarrier> =
        plan.blocks.filterIsInstance<CanvasCarrier>()

    private fun assertLayout(
        plan: CanvasFlowPlan,
        items: List<String>,
        hitRows: Set<Int>,
    ) {
        assertEquals(plan.bodyHeight + CanvasGeometry.BODY_ACTION_TAIL, plan.layoutHeight)
        assertTrue(plan.blocks.isNotEmpty())
        assertEquals(0, plan.blocks.first().y)
        assertEquals(plan.bodyHeight, plan.blocks.last().y + plan.blocks.last().height)

        plan.blocks.zipWithNext().forEach { (previous, current) ->
            assertEquals(previous.y + previous.height + NativeGeometry.BODY_GAP, current.y)
        }
        plan.blocks.filterIsInstance<CanvasTextBand>().forEach { band ->
            assertEquals(plan.inset + band.startRow * CanvasGeometry.ROW_HEIGHT, band.y)
            assertEquals(
                band.rowCount * CanvasGeometry.ROW_HEIGHT + CanvasGeometry.TEXT_PADDING * 2,
                band.height,
            )
        }
        carriers(plan).forEach { assertTrue(it.height in 1..256) }

        val actualItems = carriers(plan).mapNotNull { it.node }
        assertEquals(items, actualItems)
        hitRows.forEach { row ->
            val y = plan.inset + row * CanvasGeometry.ROW_HEIGHT
            assertTrue(
                plan.blocks.filterIsInstance<CanvasTextBand>().any {
                    y >= it.y && y + CanvasGeometry.ROW_HEIGHT - 1 < it.y + it.height
                },
                "hit row $row is not covered at y=$y",
            )
        }
    }
}
