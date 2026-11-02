package com.hiusers.rafter

import com.hiusers.rafter.compile.LayoutCompiler
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import kotlin.math.ceil
import kotlin.test.*

class CanvasTextBoundsTest {
    private val origin =
        SourceLocation(
            "core/ui/skins/profile.yml",
            "$.elements.caption",
            "caption",
            SourceRange(12, 3, 18, 1),
        )
    private val expandedFrom =
        SourceLocation("core/menus/profile.yml", "$.pages.home.content.caption", "caption")
    private val caption =
        NodeSpec(
            "caption",
            NodeContent.Text,
            TextSpec(value = "Hello"),
            x = 9,
            y = 9,
            origin = origin,
            origins = listOf(expandedFrom),
        )

    private fun page(vararg nodes: NodeSpec, height: Int? = 54) =
        PageSpec(
            "home",
            PageMode.CANVAS,
            TextSpec(value = "Profile"),
            90,
            1,
            emptyList(),
            height = height,
            elements = nodes.toList(),
        )

    private fun rejected(block: () -> Unit): Diagnostic {
        val diagnostic = assertFailsWith<DocumentError>(block = block).diagnostic
        assertEquals("LAYOUT", diagnostic.code)
        assertEquals(origin.file, diagnostic.file)
        assertEquals(origin.path, diagnostic.path)
        assertEquals("caption", diagnostic.component)
        assertEquals(origin.range, diagnostic.range)
        assertEquals(listOf(expandedFrom), diagnostic.origins)
        return diagnostic
    }

    @Test
    fun measuredStaticHeightCannotBeHiddenByASmallerDeclaration() {
        val node = caption.copy(width = 30, height = 9)
        val diagnostic = rejected { LayoutCompiler.layout(page(node), emptyMap()) { 25 to 18 } }
        assertTrue("natural height 18px" in diagnostic.message)
        assertTrue("declared height 9px" in diagnostic.message)
    }

    @Test
    fun menuCompilationReportsTheOffendingTextInsteadOfPublishingASnapshot() {
        val result =
            MenuCompiler(canvasMeasure = { 25 to 18 })
                .compile(
                    mapOf(
                        "menus/profile.yml" to
                            minimal(
                                "canvas",
                                listOf(
                                    mapOf(
                                        "id" to "caption",
                                        "type" to "text",
                                        "text" to "Hello",
                                        "width" to 30,
                                        "height" to 9,
                                    )
                                ),
                            )
                    )
                )
        assertNull(result.snapshot)
        val diagnostic = result.diagnostics.single()
        assertEquals("menus/profile.yml", diagnostic.file)
        assertTrue("elements" in diagnostic.path)
        assertEquals("caption", diagnostic.component)
        assertEquals("LAYOUT", diagnostic.code)
    }

    @Test
    fun measuredStaticWidthIsRejectedUnlessHorizontalClippingIsExplicit() {
        val node = caption.copy(width = 24, height = 18)
        val diagnostic = rejected { LayoutCompiler.layout(page(node), emptyMap()) { 25 to 18 } }
        assertTrue("natural width 25px" in diagnostic.message)
        val clipped = node.copy(overflow = "clip")
        assertEquals(
            Rect(9, 9, 24, 18),
            LayoutCompiler.layout(page(clipped), emptyMap()) { 25 to 18 }.scene.single().bounds,
        )
        rejected { LayoutCompiler.layout(page(clipped.copy(height = 9)), emptyMap()) { 25 to 18 } }
    }

    @Test
    fun runtimeMetricsRecheckADynamicLineAgainstItsDeclarationAndCompiledPage() {
        val node =
            caption.copy(
                text = TextSpec(binding = BindingSpec("state", "name")),
                width = 30,
                height = 18,
            )
        val compiled = LayoutCompiler.layout(page(node, height = 27), emptyMap()) { 1 to 1 }
        fun rendered(natural: Pair<Int, Int>) =
            LayoutCompiler.textBounds(
                compiled.spec,
                node,
                natural,
                top = 0,
                heightLimit = compiled.height,
            )
        assertEquals(Rect(9, 9, 30, 18), rendered(25 to 18))
        rejected { rendered(25 to 19) }
        rejected { rendered(31 to 18) }
        val automatic = node.copy(width = null, height = null)
        rejected {
            LayoutCompiler.textBounds(
                compiled.spec,
                automatic,
                25 to 19,
                heightLimit = compiled.height,
            )
        }
        val clipped = node.copy(overflow = "clip")
        // 裁切后仍使用真实度量，不能跳过宽度校验。
        rejected {
            LayoutCompiler.textBounds(
                compiled.spec,
                clipped,
                31 to 18,
                heightLimit = compiled.height,
            )
        }
    }

    @Test
    fun negativeCoordinatesAndAllFourCanvasEdgesAreChecked() {
        val nodes =
            listOf(
                caption.copy(x = -1),
                caption.copy(y = -1),
                caption.copy(width = 0),
                caption.copy(height = 0),
                caption.copy(width = -1),
                caption.copy(height = -1),
                caption.copy(x = 85),
                caption.copy(y = 50),
                caption.copy(x = 0, align = "right"),
                caption.copy(x = 0, align = "center"),
                caption.copy(x = Int.MAX_VALUE, width = 20),
                caption.copy(y = Int.MAX_VALUE, height = 20),
            )
        for (node in nodes) {
            rejected { LayoutCompiler.layout(page(node), emptyMap()) { 10 to 10 } }
            rejected { LayoutCompiler.textBounds(page(node), node, 10 to 10) }
        }
        for (node in listOf(caption.copy(width = 82), caption.copy(height = 46))) {
            // 即使自然文字可容纳，声明框也不能越界。
            rejected { LayoutCompiler.textBounds(page(node), node, 10 to 10) }
        }
    }

    @Test
    fun exactRightAndBottomEdgesRemainLegalForEveryAlignment() {
        for ((align, anchor) in listOf("left" to 80, "center" to 85, "right" to 90)) {
            val node = caption.copy(x = anchor, y = 44, align = align)
            assertEquals(
                Rect(80, 44, 10, 10),
                LayoutCompiler.layout(page(node), emptyMap()) { 10 to 10 }.scene.single().bounds,
            )
            assertEquals(
                Rect(80, 44, 10, 10),
                LayoutCompiler.textBounds(page(node), node, 10 to 10),
            )
        }
    }

    @Test
    fun fractionalAdvancesCannotRoundThePaintedRightEdgeOutsideItsLineBox() {
        for (align in listOf("left", "center", "right")) for (advance in
            listOf(1.0, 1.25, 5.25, 5.5, 5.75, 6.0)) {
            val node = caption.copy(x = 40, align = align)
            val width = ceil(advance).toInt()
            val box = LayoutCompiler.textBounds(page(node), node, width to 10)
            val inkLeft = LayoutCompiler.textLeft(node, width)
            assertTrue(inkLeft >= box.x)
            assertTrue(inkLeft + advance <= box.x + box.width)
        }
        val right = caption.copy(x = 90, align = "right")
        assertTrue(LayoutCompiler.textLeft(right, 6) + 5.25 <= 90)
    }

    @Test
    fun negativeFontOffsetsExpandRealBoundsWithoutMovingTheAuthoredBaseline() {
        val text =
            TextSpec(
                format = "parts",
                offsetY = 3,
                parts =
                    listOf(
                        TextSpec(value = "Raised", offsetY = -5),
                        TextSpec(value = "Lowered", offsetY = 1),
                    ),
                hover = TextSpec(value = "Not painted", offsetY = -8),
            )
        val node = caption.copy(text = text, width = 30, height = 14)
        assertEquals(-2, LayoutCompiler.textTop(text))
        val static = LayoutCompiler.layout(page(node), emptyMap()) { 25 to 14 }.scene.single()
        val runtime = LayoutCompiler.textBounds(page(node), node, 25 to 14, top = -2)
        assertEquals(Rect(9, 7, 30, 16), static.bounds)
        assertEquals(static.bounds, runtime)
        assertEquals(9, static.node.y, "The font/scanline baseline is still the authored y")
        val outside = node.copy(y = 1)
        rejected { LayoutCompiler.layout(page(outside), emptyMap()) { 25 to 14 } }
        rejected { LayoutCompiler.textBounds(page(outside), outside, 25 to 14, top = -2) }
    }

    @Test
    fun positiveFontOffsetsCannotHideBelowAShortDeclarationOrThePage() {
        val node = caption.copy(text = TextSpec(value = "Lowered", offsetY = 4), height = 10)
        rejected { LayoutCompiler.layout(page(node), emptyMap()) { 25 to 14 } }
        rejected { LayoutCompiler.textBounds(page(node), node, 25 to 14) }
        val outside = node.copy(y = 41, height = null)
        rejected { LayoutCompiler.layout(page(outside), emptyMap()) { 25 to 14 } }
        rejected { LayoutCompiler.textBounds(page(outside), outside, 25 to 14) }
    }

    @Test
    fun hoverEmptyPartsAndCancelledOffsetsDoNotInventAnUpperPaintEdge() {
        assertEquals(
            0,
            LayoutCompiler.textTop(
                TextSpec(value = "Body", hover = TextSpec(value = "Hover", offsetY = -8))
            ),
        )
        assertEquals(0, LayoutCompiler.textTop(TextSpec(format = "parts", offsetY = -8)))
        assertEquals(0, LayoutCompiler.textTop(TextSpec(offsetY = -8)))
        assertEquals(
            0,
            LayoutCompiler.textTop(
                TextSpec(
                    format = "parts",
                    offsetY = 3,
                    parts = listOf(TextSpec(value = "Body", offsetY = -3)),
                )
            ),
        )
        assertEquals(
            -2,
            LayoutCompiler.textTop(TextSpec(binding = BindingSpec("state", "name"), offsetY = -2)),
        )
    }

    @Test
    fun followedHitAreasCoverLiftedTextAndAutomaticHeightUsesTheNaturalBottom() {
        val node =
            caption.copy(text = TextSpec(value = "Hello", offsetY = -2), width = 25, height = 10)
        val hit = NodeSpec("click", NodeContent.HitArea, hitTarget = "caption")
        val compiled =
            LayoutCompiler.layout(page(node, hit, height = null), emptyMap()) { 25 to 10 }
        assertEquals(Rect(9, 7, 25, 12), compiled.scene[0].bounds)
        assertEquals(Rect(9, 0, 25, 27), compiled.scene[1].bounds)
        assertEquals(27, compiled.height)
        val natural = LayoutCompiler.layout(page(caption, height = null), emptyMap()) { 25 to 28 }
        assertEquals(45, natural.height)
        assertEquals(28, natural.scene.single().bounds.height)
    }

    @Test
    fun measurementFailuresKeepTheirAuthoredLocation() {
        val diagnostic = rejected {
            LayoutCompiler.layout(page(caption), emptyMap()) {
                throw IllegalArgumentException("Unknown font metrics")
            }
        }
        assertEquals("Unknown font metrics", diagnostic.message)
    }

    @Test
    fun missingCompileTimeMetricsAreNotTreatedAsProofOfActualHeight() {
        val node = caption.copy(height = 1)
        assertEquals(1, LayoutCompiler.layout(page(node), emptyMap()).scene.single().bounds.height)
        rejected { LayoutCompiler.textBounds(page(node), node, 25 to 10) }
    }
}
