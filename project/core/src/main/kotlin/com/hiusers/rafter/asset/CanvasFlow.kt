package com.hiusers.rafter.asset

import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.NativeGeometry
import java.util.Collections

private val ROW_HEIGHT = CanvasGeometry.ROW_HEIGHT
private val TEXT_TAIL = CanvasGeometry.TEXT_PADDING * 2
private val BODY_GAP = NativeGeometry.BODY_GAP
private val LAYOUT_TAIL = CanvasGeometry.BODY_ACTION_TAIL
private const val MAX_CARRIER_HEIGHT = 256

/** 原生正文的占位与命中行，坐标使用逻辑 GUI 像素。 */
sealed interface CanvasFlowBlock {
    val y: Int
    val height: Int
}

data class CanvasTextBand(
    override val y: Int,
    val startRow: Int,
    val rowCount: Int,
) : CanvasFlowBlock {
    init {
        require(y >= 0) { "text band y must be non-negative" }
        require(startRow >= 0) { "text band startRow must be non-negative" }
        require(rowCount > 0) { "text band rowCount must be positive" }
        require(rowCount.toLong() * ROW_HEIGHT + TEXT_TAIL <= Int.MAX_VALUE) {
            "text band height exceeds Int range"
        }
    }

    override val height: Int
        get() = rowCount * ROW_HEIGHT + TEXT_TAIL
}

data class CanvasCarrier(
    override val y: Int,
    override val height: Int,
    val node: String?,
) : CanvasFlowBlock {
    init {
        require(y >= 0) { "carrier y must be non-negative" }
        require(height in 1..MAX_CARRIER_HEIGHT) {
            "carrier height must be in 1..$MAX_CARRIER_HEIGHT"
        }
    }
}

data class CanvasFlowPlan(
    val width: Int,
    val height: Int,
    val inset: Int,
    val bodyHeight: Int,
    val blocks: List<CanvasFlowBlock>,
) {
    val layoutHeight: Int
        get() = bodyHeight + LAYOUT_TAIL
}

/** 先保留命中行，再复用空白；不足部分计入前置 inset。 */
object CanvasFlow {
    fun plan(
        width: Int,
        height: Int,
        items: List<String>,
        hitRows: Set<Int>,
        minimumInset: Int = 0,
    ): CanvasFlowPlan {
        require(width > 0) { "width must be positive" }
        require(height > 0 && height % ROW_HEIGHT == 0) {
            "height must be a positive multiple of $ROW_HEIGHT"
        }
        require(items.all { it.isNotEmpty() }) { "item IDs must be non-empty" }
        require(items.toSet().size == items.size) { "item IDs must be unique" }
        require(height.toLong() + TEXT_TAIL + LAYOUT_TAIL <= Int.MAX_VALUE) {
            "height is too large for an integer layout"
        }

        val rowCount = height / ROW_HEIGHT
        require(hitRows.all { it in 0 until rowCount }) {
            "hitRows must be in 0..${rowCount - 1}"
        }
        val selectedRows = if (hitRows.isEmpty()) listOf(rowCount - 1) else hitRows.sorted()

        // 前置 inset 每次增加一行；上限保证异常布局不会死循环。
        val maxAttempts = items.size.toLong() * 2 + 8
        require(minimumInset >= 0 && minimumInset <= Int.MAX_VALUE - ROW_HEIGHT) {
            "minimum inset is out of range"
        }
        var inset = (minimumInset + ROW_HEIGHT - 1) / ROW_HEIGHT * ROW_HEIGHT
        var attempt = 0L
        while (attempt <= maxAttempts) {
            val blocks = tryBuild(height, items, selectedRows, inset)
            if (blocks != null) {
                return CanvasFlowPlan(
                    width = width,
                    height = height,
                    inset = inset,
                    bodyHeight = checkedBodyHeight(height, inset),
                    blocks = Collections.unmodifiableList(ArrayList(blocks)),
                )
            }
            attempt++
            if (attempt > maxAttempts) break
            require(inset <= Int.MAX_VALUE - ROW_HEIGHT) { "inset exceeds Int range" }
            inset += ROW_HEIGHT
        }
        throw IllegalArgumentException("items do not fit in the canvas flow layout")
    }

    private fun tryBuild(
        canvasHeight: Int,
        items: List<String>,
        selectedRows: List<Int>,
        inset: Int,
    ): List<CanvasFlowBlock>? {
        val bands = makeBands(selectedRows)
        val firstY = inset.toLong() + bands.first().startRow.toLong() * ROW_HEIGHT
        if (firstY == ROW_HEIGHT.toLong()) {
            if (bands.first().startRow == 0) return null
            bands.first().startRow--
            bands.first().rowCount++
        }

        val bodyHeight = checkedBodyHeight(canvasHeight, inset)
        val last = bands.last()
        val lastEnd = bandEnd(last, inset)
        if (bodyHeight - lastEnd == ROW_HEIGHT) {
            last.rowCount++
        }

        val blocks = ArrayList<CanvasFlowBlock>()
        var itemIndex = 0
        var previousEnd = 0
        for ((index, band) in bands.withIndex()) {
            val y = bandY(band, inset)
            val gap = y - previousEnd
            val kind = if (index == 0) GapKind.LEADING else GapKind.BETWEEN
            val packed = packGap(gap, kind, items.size - itemIndex) ?: return null
            val appended = appendGap(blocks, previousEnd, kind, packed, items, itemIndex)
            itemIndex = appended.itemIndex
            if (appended.end != y) return null

            blocks += CanvasTextBand(y, band.startRow, band.rowCount)
            previousEnd = y + blocks.last().height
        }

        val tail = bodyHeight - previousEnd
        val packedTail = packGap(tail, GapKind.TRAILING, items.size - itemIndex) ?: return null
        val appendedTail =
            appendGap(blocks, previousEnd, GapKind.TRAILING, packedTail, items, itemIndex)
        itemIndex = appendedTail.itemIndex
        if (appendedTail.end != bodyHeight) return null
        if (itemIndex != items.size) return null
        if (blocks.firstOrNull()?.y != 0) return null
        if (blocks.lastOrNull()?.let { it.y + it.height } != bodyHeight) return null

        return blocks
    }

    private fun checkedBodyHeight(canvasHeight: Int, inset: Int): Int {
        val bodyHeight = canvasHeight.toLong() + TEXT_TAIL + inset
        require(bodyHeight <= Int.MAX_VALUE - LAYOUT_TAIL) {
            "body height exceeds Int range"
        }
        return bodyHeight.toInt()
    }

    private fun bandY(band: Band, inset: Int): Int =
        (inset.toLong() + band.startRow.toLong() * ROW_HEIGHT)
            .also {
                require(it <= Int.MAX_VALUE) { "band y exceeds Int range" }
            }
            .toInt()

    private fun bandEnd(band: Band, inset: Int): Int =
        bandY(band, inset) + band.rowCount * ROW_HEIGHT + TEXT_TAIL

    private fun makeBands(rows: List<Int>): MutableList<Band> {
        val bands = ArrayList<Band>()
        var start = rows.first()
        var endExclusive = start + 1
        for (row in rows.drop(1)) {
            if (row == endExclusive) {
                endExclusive++
            } else {
                bands += Band(start, endExclusive - start)
                start = row
                endExclusive = row + 1
            }
        }
        bands += Band(start, endExclusive - start)

        var index = 0
        while (index + 1 < bands.size) {
            val current = bands[index]
            val next = bands[index + 1]
            val gap =
                (next.startRow.toLong() - current.endExclusive.toLong()) * ROW_HEIGHT - TEXT_TAIL
            if (gap != BODY_GAP.toLong() && gap < 4L * ROW_HEIGHT - TEXT_TAIL) {
                current.rowCount = next.endExclusive - current.startRow
                bands.removeAt(index + 1)
            } else {
                index++
            }
        }
        return bands
    }

    private enum class GapKind {
        LEADING,
        BETWEEN,
        TRAILING,
    }

    private data class Band(
        var startRow: Int,
        var rowCount: Int,
    ) {
        val endExclusive: Int
            get() = startRow + rowCount
    }

    private data class PackedGap(
        val heights: List<Int>,
        val nodeCount: Int,
    )

    private data class AppendedGap(
        val itemIndex: Int,
        val end: Int,
    )

    private fun packGap(length: Int, kind: GapKind, remainingItems: Int): PackedGap? {
        if (length < 0) return null
        val fixedGap = if (kind == GapKind.BETWEEN) BODY_GAP.toLong() else 0L
        if (length.toLong() < fixedGap) return null

        val possibleNodes = (length.toLong() - fixedGap) / (BODY_GAP + 1L)
        val nodeCount = minOf(remainingItems.toLong(), possibleNodes).toInt()
        val minimum = fixedGap + nodeCount.toLong() * (BODY_GAP + 1L)
        val extra = length.toLong() - minimum
        if (extra < 0L) return null
        if (nodeCount == 0 && extra in (1L until (BODY_GAP + 1L))) return null

        val heights = ArrayList<Int>(nodeCount)
        repeat(nodeCount) { heights += 1 }
        when {
            extra == 0L -> Unit
            extra in (1L until (BODY_GAP + 1L)) -> {
                if (nodeCount == 0) return null
                heights[heights.lastIndex] += extra.toInt()
            }
            else -> heights += fillerHeights(extra)
        }
        return PackedGap(heights, nodeCount)
    }

    private fun fillerHeights(extra: Long): List<Int> {
        require(extra >= BODY_GAP + 1L) { "filler needs one carrier and a body gap" }
        var count =
            maxOf(
                1L,
                (extra + MAX_CARRIER_HEIGHT + BODY_GAP - 1L) / (MAX_CARRIER_HEIGHT + BODY_GAP),
            )
        while (extra < count * (BODY_GAP + 1L)) count++

        val totalHeight = extra - count * BODY_GAP
        require(totalHeight in count..count * MAX_CARRIER_HEIGHT) {
            "filler cannot represent the remaining gap"
        }
        val heights = ArrayList<Int>(count.toInt())
        var remaining = totalHeight
        repeat(count.toInt()) { index ->
            val slotsLeft = count.toInt() - index - 1
            val minimumForRest = slotsLeft.toLong()
            val maximumForRest = slotsLeft.toLong() * MAX_CARRIER_HEIGHT
            val height =
                (remaining - maximumForRest)
                    .coerceAtLeast(1L)
                    .coerceAtMost(MAX_CARRIER_HEIGHT.toLong())
            heights += height.toInt()
            remaining -= height
            require(remaining in minimumForRest..maximumForRest)
        }
        return heights
    }

    private fun appendGap(
        blocks: MutableList<CanvasFlowBlock>,
        start: Int,
        kind: GapKind,
        packed: PackedGap,
        items: List<String>,
        itemIndex: Int,
    ): AppendedGap {
        var nextItem = itemIndex
        var y =
            when (kind) {
                GapKind.LEADING -> start
                GapKind.BETWEEN -> start + BODY_GAP
                GapKind.TRAILING -> if (packed.heights.isEmpty()) start else start + BODY_GAP
            }
        for ((index, height) in packed.heights.withIndex()) {
            val node =
                if (index < packed.nodeCount) {
                    items[nextItem++]
                } else {
                    null
                }
            blocks += CanvasCarrier(y, height, node)
            y += height
            if (kind != GapKind.TRAILING || index + 1 < packed.heights.size) y += BODY_GAP
        }
        require(y >= start) { "gap cursor moved backwards" }
        return AppendedGap(nextItem, y)
    }
}
