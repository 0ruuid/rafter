package com.hiusers.rafter

import com.google.gson.JsonParser
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.pack.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackPipelineTest {
    @TempDir lateinit var temp: Path

    private fun pack(name: String): Path =
        temp.resolve(name).also {
            Json.write(
                it.resolve("pack.mcmeta"),
                mapOf(
                    "pack" to
                        mapOf(
                            "pack_format" to 88,
                            "min_format" to listOf(88, 0),
                            "max_format" to listOf(97, 1),
                        )
                ),
            )
        }

    private fun write(root: Path, name: String, text: String) {
        val p = root.resolve(name)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private fun assets(): AssetCatalog {
        val ui = temp.resolve("core/ui")
        copyLegacyUiTo(ui)
        return AssetCompiler.compile(ui, legacyUiDocument())
    }

    @Test
    fun settingsAreStrictAndHostingUsesLocalFallbackOrExplicitPublicUrl() {
        assertNull(PackSettings.read(emptyMap()).host)
        assertFails { PackSettings.read(mapOf("automatic" to true)) }
        assertFails { PackSettings.read(mapOf("compression-level" to 10)) }
        assertEquals(
            "http://127.0.0.1:8187",
            assertNotNull(PackSettings.read(mapOf("self-host" to mapOf("enabled" to true))).host)
                .publicUrl,
        )
        assertFails {
            PackSettings.read(
                mapOf(
                    "self-host" to mapOf("enabled" to true, "public-url" to "file:///tmp/private")
                )
            )
        }
        val settings =
            PackSettings.read(
                mapOf(
                    "self-host" to mapOf("enabled" to true, "public-url" to "http://127.0.0.1:8187")
                )
            )
        val host = assertNotNull(settings.host)
        assertTrue(settings.archive)
        assertFalse(host.required)
        assertEquals(8187, host.port)
    }

    @Test
    fun fontsLanguagesAndOrderedAtlasesMergeWhileOrdinaryModelsUseLastFile() {
        val a = pack("a")
        val stage = pack("stage")
        val font = "assets/test/font/default.json"
        val lang = "assets/test/lang/en_us.json"
        val atlas = "assets/test/atlases/blocks.json"
        write(a, font, "{\"providers\":[{\"type\":\"space\",\"advances\":{\"a\":1}}]}")
        write(stage, font, "{\"providers\":[{\"type\":\"space\",\"advances\":{\"a\":2}}]}")
        write(a, lang, "{\"ce.name\":\"Gem\",\"warning\":\"old\"}")
        write(stage, lang, "{\"warning\":\"\"}")
        write(
            a,
            atlas,
            "{\"sources\":[{\"type\":\"single\",\"resource\":\"test:a\"},{\"type\":\"filter\",\"pattern\":{\"path\":\"a\"}}]}",
        )
        write(stage, atlas, "{\"sources\":[{\"type\":\"single\",\"resource\":\"test:a\"}]}")
        write(a, "assets/test/models/item/a.json", "{\"parent\":\"old\"}")
        write(stage, "assets/test/models/item/a.json", "{\"parent\":\"new\"}")
        val verify = PackMerge.merge(temp, stage, listOf("a"))
        verify()
        val f =
            JsonParser.parseString(Files.readString(stage.resolve(font)))
                .asJsonObject
                .getAsJsonArray("providers")
        assertEquals(2, f[0].asJsonObject.getAsJsonObject("advances").get("a").asInt)
        assertEquals("Gem", Json.map(stage.resolve(lang))["ce.name"])
        assertEquals("", Json.map(stage.resolve(lang))["warning"])
        assertEquals(
            3,
            JsonParser.parseString(Files.readString(stage.resolve(atlas)))
                .asJsonObject
                .getAsJsonArray("sources")
                .size(),
        )
        assertEquals("new", Json.map(stage.resolve("assets/test/models/item/a.json"))["parent"])
        write(a, lang, "{}")
        assertFails { verify() }
    }

    @Test
    fun lowerPackOverlaysCannotOverrideLaterRootFilesAndRafterOverlaysStayLast() {
        val low = pack("low")
        val higher = pack("higher")
        val stage = pack("stage")
        val path = "assets/test/textures/a.png"
        write(low, path, "low-root")
        write(low, "same/$path", "low-overlay")
        Json.write(
            low.resolve("pack.mcmeta"),
            mapOf(
                "pack" to mapOf("min_format" to listOf(88, 0), "max_format" to listOf(97, 1)),
                "overlays" to
                    mapOf(
                        "entries" to
                            listOf(
                                mapOf(
                                    "directory" to "same",
                                    "min_format" to listOf(88, 0),
                                    "max_format" to listOf(88, 0),
                                )
                            )
                    ),
            ),
        )
        write(higher, path, "higher-root")
        write(stage, "same/$path", "rafter-overlay")
        Json.write(
            stage.resolve("pack.mcmeta"),
            mapOf(
                "pack" to mapOf("min_format" to listOf(88, 0), "max_format" to listOf(97, 1)),
                "overlays" to
                    mapOf(
                        "entries" to
                            listOf(
                                mapOf(
                                    "directory" to "same",
                                    "min_format" to listOf(97, 1),
                                    "max_format" to listOf(97, 1),
                                )
                            )
                    ),
            ),
        )
        PackMerge.merge(temp, stage, listOf("low", "higher"))()
        assertEquals("higher-root", Files.readString(stage.resolve(path)))
        val metadata =
            JsonParser.parseString(Files.readString(stage.resolve("pack.mcmeta"))).asJsonObject
        val entries = metadata.getAsJsonObject("overlays").getAsJsonArray("entries")
        fun effective(major: Int, minor: Int): String {
            var data = Files.readString(stage.resolve(path))
            val v = (major.toLong() shl 32) + minor
            for (e in entries) {
                val o = e.asJsonObject
                fun key(k: String) =
                    o.getAsJsonArray(k).let { (it[0].asLong shl 32) + it[1].asLong }
                if (v in key("min_format")..key("max_format")) {
                    val p = stage.resolve(o.get("directory").asString).resolve(path)
                    if (Files.exists(p)) data = Files.readString(p)
                }
            }
            return data
        }
        assertEquals("higher-root", effective(88, 0))
        assertEquals("rafter-overlay", effective(97, 1))
    }

    @Test
    fun unsafeSourcesAndUnsupportedFiltersFailRatherThanGuessing() {
        val stage = pack("stage")
        val zip = temp.resolve("bad.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("../secret"))
            out.write(1)
            out.closeEntry()
        }
        assertFails { PackMerge.merge(temp, stage, listOf("bad.zip")) }
        assertFails { PackMerge.merge(temp, stage, listOf("resource-pack.zip")) }
        val source = pack("source")
        Json.write(
            source.resolve("pack.mcmeta"),
            mapOf(
                "pack" to mapOf("pack_format" to 88),
                "filter" to mapOf("block" to listOf(mapOf("namespace" to "minecraft"))),
            ),
        )
        assertFails { PackMerge.merge(temp, stage, listOf("source")) }
    }

    @Test
    fun archivesAreDeterministicAndKeepTheAllocationLedgerPrivate() {
        val assets = assets()
        val settings = PackSettings(archive = true)
        PackBuild.prepare(temp, assets, settings).use { a ->
            PackBuild.prepare(temp, assets, settings).use { b ->
                assertEquals(a.hash, b.hash)
                assertContentEquals(a.sha1, b.sha1)
                ZipFile(a.archive!!.toFile()).use { z ->
                    assertNotNull(z.getEntry("pack.mcmeta"))
                    assertNull(z.getEntry("manifest.json"))
                    assertNull(z.getEntry("merge-report.json"))
                    assertTrue(
                        z.entries().asSequence().none { it.name.startsWith("resource-pack/") }
                    )
                }
                a.commit(temp)
                assertTrue(Files.exists(temp.resolve("generate/resource-pack.zip")))
                assertTrue(Files.exists(temp.resolve("generate/resource-pack/manifest.json")))
            }
        }
    }

    @Test
    fun aFailedTwoOutputCommitRestoresBothPreviouslyPublishedArtifacts() {
        val a = assets()
        Files.createDirectories(temp.resolve("generate/resource-pack"))
        Files.writeString(temp.resolve("generate/resource-pack/keep"), "previous-folder")
        Files.writeString(temp.resolve("generate/resource-pack.zip"), "previous-zip")
        PackBuild.prepare(temp, a, PackSettings(archive = true)).use { prepared ->
            Files.delete(prepared.archive!!)
            assertFails { prepared.commit(temp) }
            assertEquals("previous-folder", Files.readString(temp.resolve("generate/resource-pack/keep")))
            assertEquals(
                "previous-zip",
                Files.readString(temp.resolve("generate/resource-pack.zip")),
            )
        }
    }

    @Test
    fun lightweightLoopbackHttpServesOnlyImmutableHashMatchedZipBytes() {
        val assets = assets()
        PackBuild.prepare(temp, assets, PackSettings(archive = true)).use { pack ->
            PackHost("127.0.0.1", 0, temp.resolve("host")).use { host ->
                val file = host.prepare(pack)
                host.activate(file)
                host.activate(file)
                val base = "http://127.0.0.1:${host.localPort}"
                fun request(path: String, method: String = "GET"): Pair<Int, ByteArray> {
                    val c = URI(base + path).toURL().openConnection() as HttpURLConnection
                    c.connectTimeout = 3000
                    c.readTimeout = 3000
                    c.requestMethod = method
                    try {
                        val status = c.responseCode
                        return status to
                            if (status == 200 && method == "GET")
                                c.inputStream.use { it.readBytes() }
                            else ByteArray(0)
                    } finally {
                        c.disconnect()
                    }
                }
                val path = "/rafter/packs/${pack.hash}/resource-pack.zip"
                val (status, bytes) = request(path)
                assertEquals(200, status)
                assertContentEquals(Files.readAllBytes(pack.archive!!), bytes)
                assertContentEquals(pack.sha1, MessageDigest.getInstance("SHA-1").digest(bytes))
                assertEquals(200, request(path, "HEAD").first)
                assertEquals(405, request(path, "POST").first)
                assertEquals(404, request("/rafter/packs/manifest.json").first)
                assertEquals(404, request("/rafter/packs/../config.yml").first)
            }
        }
    }
}
