package com.hiusers.rafter

import com.hiusers.rafter.asset.ImageRequest
import com.hiusers.rafter.asset.ImageSizing
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.CompileResult
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageSizingTest {
    private val asset = "test:photo"
    private val image = mapOf<String, Any?>("id" to "photo", "type" to "image", "asset" to asset)
    private val dimensions = mapOf(asset to (108 to 36))
    private val sources = mapOf(asset to (100 to 30))

    private fun compile(node: Map<String, Any?>, mode: String = "canvas"): CompileResult =
        MenuCompiler(assets = dimensions, imageSources = sources)
            .compile(mapOf("menu.yml" to minimal(mode, listOf(node))))

    private fun snapshot(result: CompileResult): MenuSnapshot =
        assertNotNull(result.snapshot, result.diagnostics.toString())

    @Test
    fun singleSideRoundsHalfUpAndSeparatesDrawBoxFromNinePixelPadding() {
        val width = ImageSizing.resolve(ImageRequest(asset, width = 41), 100, 30)
        assertEquals(100 to 30, width.sourceWidth to width.sourceHeight)
        assertEquals(41 to 12, width.width to width.height)
        assertEquals(41 to 12, width.scaledWidth to width.scaledHeight)
        assertEquals(45 to 18, width.layoutWidth to width.layoutHeight)
        assertEquals(0 to 0, width.offsetX to width.offsetY)
        assertEquals(3, ImageSizing.resolve(ImageRequest(asset, width = 5), 2, 1).height)
        val height = ImageSizing.resolve(ImageRequest(asset, height = 5), 3, 2)
        assertEquals(8 to 5, height.width to height.height)
        assertEquals(9 to 9, height.layoutWidth to height.layoutHeight)
        assertEquals(1, ImageSizing.resolve(ImageRequest(asset, width = 1), 10000, 1).height)
    }

    @Test
    fun fitCentersContainAndCoverInsideUnpaddedBox() {
        val contain = ImageSizing.resolve(ImageRequest(asset, 11, 11, "contain"), 100, 50)
        assertEquals(11 to 6, contain.scaledWidth to contain.scaledHeight)
        assertEquals(0 to 2, contain.offsetX to contain.offsetY)
        val cover = ImageSizing.resolve(ImageRequest(asset, 11, 11, "cover"), 100, 50)
        assertEquals(22 to 11, cover.scaledWidth to cover.scaledHeight)
        assertEquals(-6 to 0, cover.offsetX to cover.offsetY)
        val stretch = ImageSizing.resolve(ImageRequest(asset, 11, 11, "stretch"), 100, 50)
        assertEquals(11 to 11, stretch.scaledWidth to stretch.scaledHeight)
        assertEquals(0 to 0, stretch.offsetX to stretch.offsetY)
        for (plan in listOf(contain, cover, stretch)) {
            assertEquals(11 to 11, plan.width to plan.height)
            assertEquals(18 to 18, plan.layoutWidth to plan.layoutHeight)
        }
    }

    @Test
    fun fitIsRequiredExactlyWhenBothDimensionsArePresent() {
        for (fields in
            listOf(
                mapOf("width" to 11, "height" to 11),
                mapOf("width" to 11, "fit" to "contain"),
                mapOf("height" to 11, "fit" to "cover"),
                mapOf("fit" to "stretch"),
                mapOf("width" to 11, "height" to 11, "fit" to "unknown"),
            )) {
            val result = compile(image + fields)
            assertNull(result.snapshot, fields.toString())
            assertEquals("LAYOUT", result.diagnostics.single().code)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageSizing.resolve(ImageRequest(asset), 100, 30)
        }
    }

    @Test
    fun sizingRejectsOverflowAndExtremeCoverButAllowsVirtualOversize() {
        for (request in
            listOf(
                ImageRequest(asset, width = 0),
                ImageRequest(asset, width = 865),
                ImageRequest(asset, height = 4096),
                ImageRequest(asset, width = 864),
                ImageRequest(asset, 864, 4095, "cover"),
            )) {
            assertFailsWith<IllegalArgumentException> {
                ImageSizing.resolve(request, 1, Int.MAX_VALUE)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ImageSizing.resolve(ImageRequest(asset, width = 1), 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageSizing.resolve(ImageRequest(asset, height = 4095), Int.MAX_VALUE, 1)
        }
        val virtual = ImageSizing.resolve(ImageRequest(asset, 100, 100, "cover"), 864, 1)
        assertEquals(86400, virtual.scaledWidth)
        assertEquals(-43150, virtual.offsetX)
        val max = ImageSizing.resolve(ImageRequest(asset, 864, 4095, "stretch"), 1, 1)
        assertEquals(864 to 4095, max.layoutWidth to max.layoutHeight)
    }

    @Test
    fun requestIdentityIsStableAndIncludesSourceDimensionsAndFit() {
        val request = ImageRequest(asset, 11, 12, "contain")
        assertEquals(request.id, request.copy().id)
        assertTrue(request.id.matches(Regex("rafter:_sized/[0-9a-f]{64}")))
        for (different in
            listOf(
                request.copy(asset = "test:other"),
                request.copy(width = 12),
                request.copy(height = 13),
                request.copy(fit = "cover"),
            )) assertNotEquals(request.id, different.id)
    }

    @Test
    fun nativeAndCanvasUseOriginalContentRatioWithoutChangingAuthoredDocuments() {
        for (mode in listOf("native", "canvas")) {
            val document = minimal(mode, listOf(image + ("width" to 41)))
            val result =
                MenuCompiler(assets = dimensions, imageSources = sources)
                    .compile(mapOf("menu.yml" to document))
            val snapshot = snapshot(result)
            val page = snapshot.menus.getValue("test:menu").pages.getValue("main")
            val node = page.spec.nodes.single()
            val request = assertNotNull((node.content as NodeContent.Image).request)
            assertEquals(ImageRequest(asset, width = 41), request)
            assertEquals(request.id, (node.content as NodeContent.Image).asset)
            assertEquals(if (mode == "canvas") 41 to 12 else 45 to 18, node.width to node.height)
            assertEquals(document, snapshot.documents.getValue("menu.yml"))
            assertEquals(
                41,
                ((document["pages"] as Map<*, *>)["main"] as Map<*, *>).let {
                    (it[if (mode == "canvas") "elements" else "components"] as List<*>)
                        .single()
                        .let { component -> (component as Map<*, *>)["width"] }
                },
            )
            if (mode == "native") {
                assertTrue(page.scene.isEmpty())
                assertEquals(0, page.height)
            } else assertEquals(Rect(0, 0, 41, 12), page.scene.single().bounds)
            for (fit in listOf("contain", "cover", "stretch")) {
                val fitted =
                    snapshot(
                        compile(image + mapOf("width" to 11, "height" to 11, "fit" to fit), mode)
                    )
                assertEquals(fit, ImageSizing.requests(fitted).single().fit)
            }
        }
        val tall =
            snapshot(
                compile(image + mapOf("width" to 11, "height" to 901, "fit" to "contain"), "native")
            )
        assertEquals(
            909,
            tall.menus.getValue("test:menu").spec.pages.getValue("main").nodes.single().height,
        )
    }

    @Test
    fun unsizedImagesKeepOriginalAssetsAndOtherComponentsKeepTheirRules() {
        val page = snapshot(compile(image)).menus.getValue("test:menu").pages.getValue("main")
        assertEquals(NodeContent.Image(asset), page.spec.nodes.single().content)
        assertEquals(Rect(0, 0, 100, 30), page.scene.single().bounds)
        assertTrue(ImageSizing.requests(snapshot(compile(image))).isEmpty())
        for (size in listOf(0, -1, 1.5, 865)) assertNull(
            compile(image + ("width" to size)).snapshot
        )
        assertNotNull(compile(mapOf("id" to "label", "type" to "text", "width" to 11)).snapshot)
        assertNull(
            compile(mapOf("id" to "label", "type" to "text", "height" to 18), "native").snapshot
        )
        val button =
            mapOf(
                "id" to "button",
                "type" to "button",
                "asset" to asset,
                "actions" to emptyList<Any>(),
                "width" to 108,
                "height" to 36,
            )
        assertNull(compile(button).snapshot) // 原生按钮不属于画布图片或点击区。
        val hit =
            mapOf(
                "id" to "hit",
                "type" to "hit-area",
                "width" to 99,
                "height" to 36,
                "actions" to emptyList<Any>(),
            )
        assertNotNull(compile(hit).snapshot)
        assertNull(compile(hit + ("asset" to asset)).snapshot)
    }

    @Test
    fun nestedTemplateRequestsAreDeduplicatedAndLayoutIsStableAcrossDocuments() {
        val template =
            mapOf(
                "id" to "test:picture",
                "params" to mapOf("size" to 41, "top" to 0),
                "component" to
                    (image +
                        mapOf("width" to mapOf("param" to "size"), "y" to mapOf("param" to "top"))),
            )
        val elements =
            listOf("first", "second").mapIndexed { index, id ->
                mapOf(
                    "id" to id,
                    "type" to "template",
                    "key" to "test:picture",
                    "params" to mapOf("top" to index * 27),
                )
            }
        val document = minimal("canvas", elements)
        val documents =
            linkedMapOf(
                "first.yml" to document,
                "second.yml" to (document + ("id" to "test:other")),
            )
        val compiler = MenuCompiler(assets = dimensions, imageSources = sources)
        val snapshot = snapshot(compiler.compile(documents, mapOf("template.yml" to template)))
        assertEquals(setOf(ImageRequest(asset, width = 41)), ImageSizing.requests(snapshot))
        assertEquals(documents, snapshot.documents)
        snapshot.menus.values.forEach { menu ->
            val page = menu.pages.getValue("main")
            assertEquals(
                listOf(Rect(0, 0, 41, 12), Rect(0, 27, 41, 12)),
                page.scene.map { it.bounds },
            )
            assertEquals(45, page.height)
        }
        assertEquals(
            snapshot,
            snapshot(compiler.compile(documents, mapOf("template.yml" to template))),
        )
    }

    @Test
    fun effectiveCanvasStyleHeightCreatesSizingRequestWhileNativeStillRejectsIt() {
        val compiler =
            MenuCompiler(
                assets = dimensions,
                imageSources = sources,
                styles = mapOf("photo" to mapOf("height" to 18)),
            )
        val node = image + ("style" to "photo")
        val result =
            snapshot(compiler.compile(mapOf("menu.yml" to minimal("canvas", listOf(node)))))
        assertEquals(ImageRequest(asset, height = 18), ImageSizing.requests(result).single())
        assertEquals(
            Rect(0, 0, 60, 18),
            result.menus.getValue("test:menu").pages.getValue("main").scene.single().bounds,
        )
        val native = compiler.compile(mapOf("menu.yml" to minimal("native", listOf(node))))
        assertNull(native.snapshot)
        assertEquals("CAPABILITY", native.diagnostics.single().code)
    }

    @Test
    fun missingOriginalAssetsAndAuthoredInternalIdsAreRejected() {
        assertNull(compile(image + mapOf("asset" to "test:missing", "width" to 11)).snapshot)
        val generated = ImageRequest(asset, width = 11).id
        val result =
            MenuCompiler(assets = dimensions + (generated to (18 to 9)))
                .compile(
                    mapOf("menu.yml" to minimal("canvas", listOf(image + ("asset" to generated))))
                )
        assertNull(result.snapshot)
        assertEquals("ASSET", result.diagnostics.single().code)
        assertEquals("$.pages.main.elements[0].asset", result.diagnostics.single().path)
    }

    @Test
    fun assetContentDimensionsAcceptNonCellSizesAndRetainBounds() {
        val assetImage =
            mapOf(
                "width" to 108,
                "height" to 36,
                "content-width" to 100,
                "content-height" to 30,
                "source" to "photo.png",
            )
        Contract.check(assetImage, Contract.assetImage, "assets.yml", "$.images.photo")
        for (invalid in
            listOf(
                mapOf("content-width" to 0),
                mapOf("content-width" to 865),
                mapOf("content-height" to 4096),
                mapOf("content-height" to 1.5),
            )) assertFailsWith<DocumentError> {
            Contract.check(
                assetImage + invalid,
                Contract.assetImage,
                "assets.yml",
                "$.images.photo",
            )
        }
    }
}
