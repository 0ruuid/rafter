package com.hiusers.rafter

import com.hiusers.rafter.asset.ImageRequest
import com.hiusers.rafter.asset.ImageSizing
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import kotlin.test.*

class CanvasImagePaddingTest {
    private val asset = "test:photo"
    private val image = mapOf<String, Any?>("id" to "photo", "type" to "image", "asset" to asset)
    private val compiler =
        MenuCompiler(assets = mapOf(asset to (18 to 18)), imageSources = mapOf(asset to (11 to 10)))

    private fun compile(elements: List<Map<String, Any?>>, width: Int = 180) =
        compiler.compile(
            mapOf(
                "menu.yml" to
                    mapOf(
                        "id" to "test:menu",
                        "entry" to "main",
                        "pages" to
                            mapOf(
                                "main" to
                                    mapOf(
                                        "mode" to "canvas",
                                        "title" to "",
                                        "width" to width,
                                        "elements" to elements,
                                    )
                            ),
                    )
            )
        )

    private fun page(result: CompileResult) =
        assertNotNull(result.snapshot, result.diagnostics.toString())
            .menus
            .getValue("test:menu")
            .pages
            .getValue("main")

    @Test
    fun everyRemainderKeepsVisualBoundsAndUsesOnlyInternalPadding() {
        val sizes =
            listOf(emptyMap(), mapOf("width" to 11), mapOf("height" to 10)) +
                listOf("contain", "cover", "stretch").map {
                    mapOf("width" to 11, "height" to 10, "fit" to it)
                }
        for (size in sizes) for (y in 0..35) {
            val authored = image + size + mapOf("x" to 169, "y" to y, "actions" to emptyList<Any>())
            val result = compile(listOf(authored))
            val page = page(result)
            val placed = page.scene.single()
            assertEquals(Rect(169, y, 11, 10), placed.bounds)
            assertEquals(y, placed.node.y)
            assertEquals((y + 10 + 8) / 9 * 9, page.height)
            val content = placed.node.content as NodeContent.Image
            if (size.isEmpty() && y % 9 == 0) {
                assertEquals(NodeContent.Image(asset), content)
                assertTrue(ImageSizing.requests(result.snapshot!!).isEmpty())
            } else {
                val request = assertNotNull(content.request)
                assertEquals(asset, request.asset)
                assertEquals(y % 9, request.topPadding)
                val plan = ImageSizing.resolve(request, 11, 10)
                assertEquals(11 to 10, plan.width to plan.height)
                assertEquals(y, y / 9 * 9 + plan.topPadding)
                assertEquals(page.height, y / 9 * 9 + plan.layoutHeight)
                assertEquals(request.id, content.asset)
            }
            val document = result.snapshot!!.documents.getValue("menu.yml")
            val main = (document["pages"] as Map<*, *>)["main"] as Map<*, *>
            assertEquals(listOf(authored), main["elements"])
        }
    }

    @Test
    fun finalAncestryCoordinatesDriveBaseAndVariantRequests() {
        val alternate = "test:other"
        for (sized in listOf(false, true)) {
            val variantImage =
                image +
                    (if (sized) mapOf("width" to 11) else emptyMap()) +
                    mapOf(
                        "variants" to
                            listOf(
                                mapOf(
                                    "when" to mapOf("type" to "constant", "value" to true),
                                    "override" to mapOf("asset" to alternate),
                                )
                            )
                    )
            val template =
                mapOf(
                    "id" to "test:picture",
                    "component" to
                        mapOf(
                            "id" to "group",
                            "type" to "group",
                            "y" to 5,
                            "elements" to listOf(variantImage),
                        ),
                )
            val elements =
                listOf(3, 4, 12).mapIndexed { index, y ->
                    mapOf(
                        "id" to "instance-$index",
                        "type" to "template",
                        "key" to "test:picture",
                        "y" to y,
                    )
                }
            val document = minimal("canvas", elements)
            val result =
                MenuCompiler(
                        assets = mapOf(asset to (18 to 18), alternate to (18 to 18)),
                        imageSources = mapOf(asset to (11 to 10), alternate to (11 to 10)),
                    )
                    .compile(mapOf("menu.yml" to document), mapOf("picture.yml" to template))
            val page = page(result)
            assertEquals(listOf(8, 9, 17), page.scene.map { it.bounds.y })
            page.scene.forEach { placed ->
                for (node in listOf(placed.node) + placed.node.variants.map { it.node }) {
                    val content = node.content as NodeContent.Image
                    if (!sized && placed.bounds.y % 9 == 0) {
                        assertNull(content.request)
                        assertTrue(content.asset in setOf(asset, alternate))
                    } else {
                        val request = assertNotNull(content.request)
                        assertEquals(placed.bounds.y % 9, request.topPadding)
                    }
                    assertEquals(placed.bounds.y, node.y)
                    assertEquals(if (sized) 11 to 10 else null to null, node.width to node.height)
                }
            }
            assertEquals(if (sized) 4 else 2, ImageSizing.requests(result.snapshot!!).size)
        }
    }

    @Test
    fun hitsAndCanvasEdgesStillUseAuthoredVisualBox() {
        val hit =
            mapOf(
                "id" to "hit",
                "type" to "hit-area",
                "target" to "photo",
                "actions" to emptyList<Any>(),
            )
        for (y in 0..8) {
            val page = page(compile(listOf(image + mapOf("x" to 169, "y" to y), hit)))
            assertEquals(Rect(169, y, 11, 10), page.scene.first().bounds)
            assertEquals(Rect(169, 0, 11, (y + 10 + 8) / 9 * 9), page.scene.last().bounds)
        }
        assertEquals(
            Rect(169, 4085, 11, 10),
            page(compile(listOf(image + mapOf("x" to 169, "y" to 4085)))).scene.single().bounds,
        )
        for (fields in
            listOf(mapOf("y" to -1), mapOf("y" to 4086), mapOf("x" to 170), mapOf("y" to 1.5))) {
            assertNull(compile(listOf(image + fields)).snapshot)
        }
        assertNull(
            compile(
                    listOf(
                        mapOf(
                            "id" to "hit",
                            "type" to "hit-area",
                            "y" to 1,
                            "width" to 9,
                            "height" to 9,
                            "actions" to emptyList<Any>(),
                        )
                    )
                )
                .snapshot
        )
    }

    @Test
    fun paddingIdentityValidationAndAlignedLegacyAssetsStayStable() {
        val request = ImageRequest(asset, 11, 10, "stretch")
        assertEquals(request.id, request.copy(topPadding = 0).id)
        assertEquals(9, (0..8).map { request.copy(topPadding = it).id }.toSet().size)
        for (padding in listOf(-1, 9, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> {
            ImageSizing.resolve(request.copy(topPadding = padding), 11, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageSizing.resolve(ImageRequest(asset, topPadding = 1), 1, 4095)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageSizing.resolve(ImageRequest(asset, topPadding = 1), 865, 1)
        }
        for (y in listOf(0, 9, 27)) {
            val placed = page(compile(listOf(image + ("y" to y)))).scene.single()
            assertEquals(NodeContent.Image(asset), placed.node.content)
            assertEquals(Rect(0, y, 11, 10), placed.bounds)
        }
    }
}
