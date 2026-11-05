package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class UiDesignTest {
    @TempDir lateinit var temp: Path

    private fun source(): Path {
        val root = temp.resolve("core/ui")
        copyLegacyUiTo(root)
        return root
    }

    @Test
    fun emittedFontAdvancesMatchTheApprovedMonoGridIncludingBoldSpaces() {
        val catalog =
            AssetCompiler.compile(
                source(),
                legacyUiDocument(),
                requests = setOf(FontRequest("rafter-mono", 20.0)),
            )
        assertEquals(10.0, catalog.textWidth("中"))
        assertEquals(5.0, catalog.textWidth("i"))
        assertEquals(5.0, catalog.textWidth("W"))
        assertEquals(5.0, catalog.textWidth(" "))
        assertEquals(6.0, catalog.textWidth(" ", bold = true))
        assertEquals(20.0, catalog.textWidth("中", font = FontRequest("rafter-mono", 20.0).id))
        assertEquals(10.0, catalog.textWidth("A", font = FontRequest("rafter-mono", 20.0).id))
        assertTrue(
            catalog.faces.values.all { face -> face.values.all { it == it.toInt().toDouble() } }
        )
    }

    @Test
    fun canvasRowsHaveAStablePositiveWidthAndStartWithTheHitPlane() {
        taboolib.module.chat.Components.useAdventure = true
        val catalog = AssetCompiler.compile(source(), legacyUiDocument())
        val text = com.hiusers.rafter.text.CompositeText(catalog)
        val document =
            minimal(
                "canvas",
                listOf(
                    mapOf("id" to "back-image", "type" to "image", "asset" to "rafter:mark"),
                    mapOf(
                        "id" to "back",
                        "type" to "text",
                        "x" to 45,
                        "align" to "center",
                        "width" to 90,
                        "height" to 18,
                        "text" to "返回",
                        "actions" to listOf(mapOf("type" to "close")),
                    ),
                ),
            )
        val compiled =
            com.hiusers.rafter.compile
                .MenuCompiler(
                    catalog.dimensions(),
                    textCheck = text::validate,
                    canvasTextCheck = text::validateCanvas,
                )
                .compile(mapOf("menu.yml" to document))
                .snapshot!!
                .menus
                .getValue("test:menu")
        val session =
            com.hiusers.rafter.runtime.UiSession(java.util.UUID.randomUUID(), compiled, emptyMap())
        val frame =
            com.hiusers.rafter.render.Frame(
                emptyMap(),
                mapOf("back" to true, "back-image" to true),
                mapOf("back" to true),
            )
        val handles = session.ready(session.beginRender(), listOf("back", "__close"))
        val message =
            com.hiusers.rafter.render.CanvasRenderer.render(session, frame, text, handles)
                .messages
                .single()
        var advance = 0.0
        var rows = 0
        var anchors = 0
        for (part in message.parts) {
            if (part.text.toPlainText() == "\n") {
                assertEquals(compiled.pages.getValue("main").spec.width + 2.0, advance, 0.00001)
                advance = 0.0
                rows++
                assertEquals(
                    4,
                    anchors,
                    "Hit and paint planes each need one pair of CPU bounds anchors",
                )
                anchors = 0
            } else {
                anchors += part.text.toPlainText().count { it == '\ue001' }
                advance += text.width(part.text)
                assertTrue(
                    advance >= 0 && advance <= compiled.pages.getValue("main").spec.width + 2.001
                )
            }
        }
        assertEquals(compiled.pages.getValue("main").spec.width + 2.0, advance, 0.00001)
        assertEquals(
            4,
            anchors,
            "Hit and paint planes each need one pair of CPU bounds anchors",
        )
        assertEquals(compiled.pages.getValue("main").height / 9 - 1, rows)
        assertEquals(compiled.pages.getValue("main").spec.width + 10, message.width)
        val firstHit = message.parts.indexOfFirst { it.handle != null }
        val firstPaint =
            message.parts.indexOfFirst {
                "rafter:images" in it.text.toRawMessage()
            }
        assertTrue(
            firstHit >= 0 && firstHit < firstPaint,
            "Monotonic hit regions must precede decorative glyphs",
        )
        assertTrue("rafter:space" in message.parts[firstHit].text.toRawMessage())
        assertTrue(
            message.parts
                .filter { it.handle != null }
                .all { "rafter:space" in it.text.toRawMessage() },
            "CUSTOM handles belong only to the rectangular hit plane, never glyph ink",
        )
    }

    @Test
    fun taboolibRawSupportsTheVanillaNoShadowAttribute() {
        taboolib.module.chat.Components.useAdventure = true
        val component =
            taboolib.module.chat.Components.parseRaw("""{"text":"Pixel","shadow_color":0}""")
        assertTrue(
            component.toRawMessage().contains("\"shadow_color\":0"),
            component.toRawMessage(),
        )
    }

    @Test
    fun textImagesAndLiteralLabelsDoNotInheritClientShadows() {
        taboolib.module.chat.Components.useAdventure = true
        val text =
            com.hiusers.rafter.text.CompositeText(
                AssetCompiler.compile(source(), legacyUiDocument())
            )
        val parts =
            listOf(
                text.render(
                    com.hiusers.rafter.model.TextSpec(
                        value = "清晰文本",
                        hover = com.hiusers.rafter.model.TextSpec(value = "悬停文字"),
                    )
                ),
                text.literal("返回游戏"),
                text.tile(text.imageTiles("rafter:mark").first()),
                text.spaces(18),
            )
        fun check(e: com.google.gson.JsonElement, inherited: Int?) {
            if (e.isJsonArray) {
                e.asJsonArray.forEach { check(it, inherited) }
                return
            }
            if (e.isJsonPrimitive) {
                if (e.asString.isNotEmpty())
                    assertEquals(
                        0,
                        inherited,
                        "Every Rafter text run needs explicit transparent shadow",
                    )
                return
            }
            val o = e.asJsonObject
            val shadow = o.get("shadow_color")?.asInt ?: inherited
            o.get("text")?.let { check(it, shadow) }
            o.getAsJsonArray("extra")?.forEach { check(it, shadow) }
        }
        parts.forEach { check(com.google.gson.JsonParser.parseString(it.toRawMessage()), null) }
    }

    @Test
    fun pixelFontUsesItsNativeGridRatherThanSupersamplingIt() {
        val root = source()
        val catalog = AssetCompiler.compile(root, legacyUiDocument())
        val pack = AssetCompiler.build(root, catalog, temp)
        for (font in catalog.fontFaces.keys) {
            @Suppress("UNCHECKED_CAST")
            val providers =
                Json.map(pack.resolve("assets/rafter/font/${font.substringAfter(':')}.json"))[
                        "providers"]
                    as List<Map<String, Any?>>
            assertEquals(
                1.0,
                (providers.first { it["type"] == "ttf" }["oversample"] as Number).toDouble(),
                "Pixel outlines must not be downsampled from 5x",
            )
        }
    }

    @Test
    fun canvasCompilesGenuineLongContentWithoutScalingOrPageSplitting() {
        val document =
            minimal(
                "canvas",
                List(40) { i ->
                    mapOf(
                        "id" to "section-$i",
                        "type" to "text",
                        "y" to i * 45,
                        "height" to 36,
                        "text" to "Section $i",
                    )
                },
            )
        val result =
            com.hiusers.rafter.compile.MenuCompiler().compile(mapOf("long.yml" to document))
        val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
        assertTrue(snapshot.menus.getValue("test:menu").pages.getValue("main").height > 1440)
    }

    @Test
    fun centeredCanvasLabelsAreSnappedToWholePixels() {
        taboolib.module.chat.Components.useAdventure = true
        val assets = AssetCompiler.compile(source(), legacyUiDocument())
        val text = com.hiusers.rafter.text.CompositeText(assets)
        val doc =
            minimal(
                "canvas",
                listOf(
                    mapOf(
                        "id" to "label",
                        "type" to "text",
                        "x" to 50,
                        "align" to "center",
                        "text" to "中",
                        "actions" to listOf(mapOf("type" to "close")),
                    )
                ),
            )
        val menu =
            com.hiusers.rafter.compile
                .MenuCompiler(assets.dimensions())
                .compile(mapOf("test.yml" to doc))
                .snapshot!!
                .menus
                .getValue("test:menu")
        val session =
            com.hiusers.rafter.runtime.UiSession(java.util.UUID.randomUUID(), menu, emptyMap())
        val frame =
            com.hiusers.rafter.render.Frame(
                emptyMap(),
                mapOf("label" to true),
                mapOf("label" to true),
            )
        val handles = session.ready(session.beginRender(), listOf("label", "__close"))
        var x = 0.0
        var checked = false
        com.hiusers.rafter.render.CanvasRenderer.render(session, frame, text, handles)
            .messages
            .single()
            .parts
            .forEach {
                if (it.text.toPlainText() == "\n") x = 0.0
                else {
                    if (it.text.toPlainText() == "中") {
                        assertEquals(x.toInt().toDouble(), x)
                        checked = true
                    }
                    x += text.width(it.text)
                }
            }
        assertTrue(checked)
    }

    @Test
    fun bodyAndHeadingUseNativePixelFonts() {
        val root = source()
        val catalog =
            AssetCompiler.compile(
                root,
                legacyUiDocument(),
                requests = setOf(FontRequest("rafter-mono", 20.0)),
            )
        val pack = AssetCompiler.build(root, catalog, temp)
        @Suppress("UNCHECKED_CAST")
        val providers =
            Json.map(
                    pack.resolve(
                        "assets/rafter/font/${FontRequest("rafter-mono",10.0).id.substringAfter(':')}.json"
                    )
                )["providers"]
                as List<Map<String, Any?>>
        val native = providers.firstOrNull { it["type"] == "ttf" }
        assertNotNull(native, "Body font must not enlarge a 9px bitmap atlas")
        assertEquals(1.0, (native["oversample"] as Number).toDouble())
        assertTrue(
            Files.exists(
                pack.resolve(
                    "assets/rafter/font/${FontRequest("rafter-mono",20.0).id.substringAfter(':')}.json"
                )
            )
        )
    }

    @Test
    fun canvasBitmapAscentMatchesTheNativeClickRowBaseline() {
        val root = source()
        val pack = AssetCompiler.build(root, AssetCompiler.compile(root, legacyUiDocument()), temp)
        for (name in listOf("images", "fill")) {
            @Suppress("UNCHECKED_CAST")
            val providers =
                Json.map(pack.resolve("assets/rafter/font/$name.json"))["providers"]
                    as List<Map<String, Any?>>
            assertTrue(
                providers.all { (it["ascent"] as Number).toInt() == 7 },
                "Bitmap top must coincide with the vanilla empty-area hit plane",
            )
        }
    }

    @Test
    fun typographyIsAttachedToActualTextNotAnEmptySibling() {
        taboolib.module.chat.Components.useAdventure = true
        val catalog =
            AssetCompiler.compile(
                source(),
                legacyUiDocument(),
                requests = setOf(FontRequest("rafter-mono", 20.0)),
            )
        val component =
            com.hiusers.rafter.text
                .CompositeText(catalog)
                .render(
                    com.hiusers.rafter.model.TextSpec(
                        value = "下一段冒险",
                        font = "rafter-mono",
                        fontSize = 20.0,
                        color = "#f0f6f9",
                    )
                )
        var actualFont: String? = null
        var actualColor: String? = null
        fun visit(e: com.google.gson.JsonElement, font: String?, color: String?) {
            if (e.isJsonPrimitive) {
                if (e.asString == "下一段冒险") {
                    actualFont = font
                    actualColor = color
                }
                return
            }
            if (e.isJsonArray) {
                e.asJsonArray.forEach { visit(it, font, color) }
                return
            }
            val obj = e.asJsonObject
            val nextFont = obj.get("font")?.asString ?: font
            val nextColor = obj.get("color")?.asString ?: color
            obj.get("text")?.let { visit(it, nextFont, nextColor) }
            obj.getAsJsonArray("extra")?.forEach { visit(it, nextFont, nextColor) }
        }
        visit(com.google.gson.JsonParser.parseString(component.toRawMessage()), null, null)
        assertEquals(FontRequest("rafter-mono", 20.0).id, actualFont)
        assertEquals("#f0f6f9", actualColor?.lowercase())
    }

    @Test
    fun highResolutionTilesFitTheNativeGlyphAtlasAndFractionalSpacesStayExact() {
        taboolib.module.chat.Components.useAdventure = true
        val root = source()
        val catalog = AssetCompiler.compile(root, legacyUiDocument())
        catalog.imageSpecs.forEach { image ->
            catalog.images.getValue(image.id).tiles.forEach { tile ->
                assertEquals(9, tile.height, "Bitmap glyphs must not cross native paint rows")
                assertTrue(tile.width * image.scale <= 252 && tile.height * image.scale <= 252)
            }
        }
        val text = com.hiusers.rafter.text.CompositeText(catalog)
        assertEquals(42.125, text.width(text.spaces(42.125)), 0.00001)
        assertEquals(-42.125, text.width(text.spaces(-42.125)), 0.00001)
    }

    @Test
    fun legacyExampleUpgradeNeverOverwritesAdministratorChanges() {
        val root = temp.resolve("defaults")
        Files.createDirectories(root)
        val old = "shipped-v1".toByteArray()
        val newer = "shipped-v2".toByteArray()
        val digest =
            java.security.MessageDigest.getInstance("SHA-256").digest(old).joinToString("") {
                "%02x".format(it)
            }
        val previous = mapOf("demo.yml" to listOf(digest))
        val originals = mapOf("demo.yml" to newer)
        Files.write(root.resolve("demo.yml"), old)
        assertTrue(com.hiusers.rafter.compile.DefaultBundle.canUpgrade(root, previous, originals))
        Files.writeString(root.resolve("demo.yml"), "administrator-customized")
        assertFalse(com.hiusers.rafter.compile.DefaultBundle.canUpgrade(root, previous, originals))
    }

    @Test
    fun knownRetiredFontDoesNotBlockMigrationButCustomizedRetiredFontDoes() {
        val root = temp.resolve("retired")
        Files.createDirectories(root)
        val old = "original-retired-font".toByteArray()
        val digest =
            java.security.MessageDigest.getInstance("SHA-256").digest(old).joinToString("") {
                "%02x".format(it)
            }
        val previous = mapOf("retired.ttf" to listOf(digest))
        val originals = mapOf("replacement.ttf" to "new-font".toByteArray())
        assertTrue(com.hiusers.rafter.compile.DefaultBundle.canUpgrade(root, previous, originals))
        Files.write(root.resolve("retired.ttf"), old)
        assertTrue(com.hiusers.rafter.compile.DefaultBundle.canUpgrade(root, previous, originals))
        Files.writeString(root.resolve("retired.ttf"), "custom-font")
        assertFalse(com.hiusers.rafter.compile.DefaultBundle.canUpgrade(root, previous, originals))
    }

    @Test
    fun canvasRejectsUnknownMetricsButNativeAllowsDefaultFont() {
        taboolib.module.chat.Components.useAdventure = true
        val text =
            com.hiusers.rafter.text.CompositeText(
                AssetCompiler.compile(source(), legacyUiDocument())
            )
        val spec = com.hiusers.rafter.model.TextSpec(value = "Literal", font = "minecraft:default")
        text.validate(spec)
        assertFails {
            text.validateCanvas(
                com.hiusers.rafter.model.TextSpec(value = String(Character.toChars(0x10ffff)))
            )
        }
        assertFails { text.validateCanvas(spec) }
        assertFails {
            text.validateCanvas(
                com.hiusers.rafter.model.TextSpec(
                    format = "simple",
                    value = "[X](font=minecraft:default)",
                )
            )
        }
    }

    @Test
    fun warningSuppressionIsLimitedToThePreviousDialogSprites() {
        val root = source()
        val catalog = AssetCompiler.compile(root, legacyUiDocument())
        val pack = AssetCompiler.build(root, catalog, temp)
        assertTrue(
            Files.exists(
                pack.resolve("assets/minecraft/textures/gui/sprites/dialog/warning_button.png")
            )
        )
        assertFalse(Files.exists(pack.resolve("assets/minecraft/textures/gui/sprites/widget")))
    }
}
