package com.hiusers.rafter

import com.google.gson.JsonObject
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.model.DocumentError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FontDiscoveryTest {
    @TempDir lateinit var temp: Path

    @Test
    fun aliasSourcesKeepFamiliesSizesAndProviderReferencesIndependent() {
        val root = temp.resolve("ui")
        // 两个别名使用同一字体夹具，不证明不同字体的实际外观。
        for (family in listOf("first/mono", "second/mono")) {
            val source = root.resolve("fonts/$family.ttf")
            Files.createDirectories(source.parent)
            requireNotNull(javaClass.getResourceAsStream("/core/ui/fonts/rafter-mono.ttf")).use {
                Files.copy(it, source)
            }
        }
        val unused = root.resolve("fonts/unused.ttf")
        Files.copy(root.resolve("fonts/first/mono.ttf"), unused)
        Files.writeString(
            root.resolve(UiThemes.FILE),
            """
            defaults:
              font: first/mono
              font-size: 10
            styles:
              larger:
                font-size: 20
              alternate:
                font: second/mono
            oversample: 1
            palette:
              error: '#ff6666'
            controls:
              close: Close
              form-error: Error
            hide-dialog-warning: false
            hide-canvas-focus-outline: false
            hide-native-focus-outline: false
            """
                .trimIndent(),
        )
        val first = FontRequest("first/mono", 10.0)
        val firstLarge = FontRequest("first/mono", 20.0)
        val second = FontRequest("second/mono", 10.0)
        val secondLarge = FontRequest("second/mono", 20.0)
        val document = mapOf<String, Any?>("images" to emptyMap<String, Any?>())
        val catalog =
            AssetCompiler.compile(root, document, requests = linkedSetOf(secondLarge, first))
        assertEquals(
            setOf(first.id, firstLarge.id, second.id, secondLarge.id),
            catalog.fontFaces.keys,
        )
        assertEquals(setOf("first/mono", "second/mono", "unused"), catalog.fontFamilies.keys)
        assertFalse(unused in catalog.sourceHashes)
        assertEquals(
            catalog.fontFaces.getValue(first.id).source,
            catalog.fontFaces.getValue(firstLarge.id).source,
        )
        assertNotEquals(
            catalog.fontFaces.getValue(first.id).source,
            catalog.fontFaces.getValue(second.id).source,
        )
        assertEquals(catalog.faces.getValue(first.id), catalog.faces.getValue(second.id))
        assertEquals(
            catalog.faces.getValue(first.id).getValue(65) * 2,
            catalog.faces.getValue(firstLarge.id).getValue(65),
        )
        assertEquals(
            catalog.fingerprint,
            AssetCompiler.compile(root, document, requests = linkedSetOf(first, secondLarge))
                .fingerprint,
        )

        val pack = AssetCompiler.build(root, catalog, temp.resolve("output"))
        fun provider(request: FontRequest): JsonObject =
            Json.gson
                .fromJson(
                    Files.readString(
                        pack.resolve("assets/rafter/font/${request.id.substringAfter(':')}.json")
                    ),
                    JsonObject::class.java,
                )
                .getAsJsonArray("providers")[0]
                .asJsonObject
        assertEquals(provider(first)["file"], provider(firstLarge)["file"])
        assertNotEquals(provider(first)["file"], provider(second)["file"])
        assertEquals(20.0, provider(secondLarge)["size"].asDouble)
        for (family in listOf("first/mono", "second/mono")) {
            assertTrue(Files.isRegularFile(pack.resolve("assets/rafter/font/sources/$family.ttf")))
        }
        assertFalse(Files.exists(pack.resolve("assets/rafter/font/sources/unused.ttf")))
        assertFalse(Files.exists(root.resolve("fonts/OFL.txt")))
        assertFalse(Files.exists(pack.resolve("licenses")))
        val manifest = Json.map(pack.resolve("manifest.json"))["fonts"] as Map<*, *>
        val entry = manifest[secondLarge.id] as Map<*, *>
        assertEquals("second/mono", entry["family"])
        assertEquals("fonts/second/mono.ttf", entry["source"])
        assertEquals(20.0, entry["size"])
        // 未请求字体不打包，但发现阶段仍须校验其度量。
        Files.writeString(unused, "Not a TTF")
        assertFailsWith<IllegalArgumentException> {
            AssetCompiler.compile(root, document, requests = linkedSetOf(secondLarge, first))
        }
    }

    @Test
    fun fontSizeBoundsAreSharedByRequestsDocumentsAndMeasurement() {
        assertEquals(1, FontRequest.MIN_SIZE)
        assertEquals(128, FontRequest.MAX_SIZE)
        val field = Contract.text.getValue("font-size")
        for (fields in
            listOf(
                Contract.themeDefaults,
                Contract.style,
                Contract.fields("text", "native"),
                Contract.fields("text", "canvas"),
            )) {
            val declared = fields.getValue("font-size")
            assertEquals(
                FontRequest.MIN_SIZE.toDouble(),
                assertNotNull(declared.minimum).toDouble(),
            )
            assertEquals(
                FontRequest.MAX_SIZE.toDouble(),
                assertNotNull(declared.maximum).toDouble(),
            )
        }
        for (size in listOf(FontRequest.MIN_SIZE.toDouble(), FontRequest.MAX_SIZE.toDouble())) {
            assertTrue(FontRequest.isValidSize(size))
            assertEquals(size, FontRequest("mono", size).size)
            Contract.check(mapOf("font-size" to size), mapOf("font-size" to field), "font.yml", "$")
        }
        for (size in listOf(0.0, 129.0, 1.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(FontRequest.isValidSize(size))
            assertFailsWith<IllegalArgumentException> { FontRequest("mono", size) }
            assertFailsWith<IllegalArgumentException> {
                NativeFonts.widths(temp.resolve("must-not-read.ttf"), mapOf("invalid" to size))
            }
            assertFailsWith<DocumentError> {
                Contract.check(
                    mapOf("font-size" to size),
                    mapOf("font-size" to field),
                    "font.yml",
                    "$",
                )
            }
        }
        val source = temp.resolve("mono.ttf")
        requireNotNull(javaClass.getResourceAsStream("/core/ui/fonts/rafter-mono.ttf")).use {
            Files.copy(it, source)
        }
        val sizes = NativeFonts.supportedSizes(source)
        assertTrue(sizes.isNotEmpty())
        assertTrue(sizes.all { FontRequest.isValidSize(it.toDouble()) })
    }
}
