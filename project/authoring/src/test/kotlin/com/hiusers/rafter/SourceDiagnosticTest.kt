package com.hiusers.rafter

import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.*
import com.hiusers.rafter.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** 验证预检先于解码，并保留源码位置。 */
class SourceDiagnosticTest {
    @TempDir lateinit var root: Path

    @Test
    fun duplicateKeysHaveTheSecondPositionAndTheOriginalAsRelatedLocation() {
        val failure =
            assertFailsWith<DocumentError> {
                    YamlSource.index("duplicate.yml", "state:\n  name: one\n  name: two\n")
                }
                .diagnostic
        assertEquals("YAML_DUPLICATE_KEY", failure.code)
        assertEquals("$.state.name", failure.path)
        assertEquals(3, failure.range?.startLine)
        assertEquals(2, failure.origins.single().range?.startLine)
    }

    @Test
    fun arraysQuotedKeysAndComponentIdsKeepUnambiguousAuthoredPositions() {
        val text =
            "pages:\n  'a.b':\n    components:\n      - id: label\n        type: text\n        text: hello\n"
        val index = YamlSource.index("quoted.yml", text)
        val range = assertNotNull(index.range("$.pages['a.b'].components[0].text"))
        assertEquals(6, range.startLine)
        assertEquals(9, range.startColumn)
        assertNotNull(index.range("unknown-generated-path", "label"))
        val duplicate = YamlSource.index("dupe-ids.yml", "a: {id: same}\nb: {id: same}\n")
        assertNull(
            duplicate.range("unknown", "same"),
            "An ambiguous ID must not invent an exact location",
        )
    }

    @Test
    fun sourceColumnsCountUnicodeCodePointsRatherThanUtf16Units() {
        val index = YamlSource.index("unicode.yml", "'😀': value\n")
        val range = assertNotNull(index.range("$['😀']"))
        assertEquals(1, range.startColumn)
        assertEquals(4, range.endColumn)
    }

    @Test
    fun canonicalPathsDoNotConfuseLiteralDottedKeysWithNestedMappings() {
        val index = YamlSource.index("paths.yml", "'a.b': literal\na: {b: nested}\n")
        assertEquals(1, index.range("$['a.b']")?.startLine)
        assertEquals(2, index.range("$.a.b")?.startLine)
        assertEquals(2, index.range("$.a.b.generated")?.startLine)
    }

    @Test
    fun malformedYamlAndUnsafeTagsAreRejectedBeforeConstruction() {
        assertEquals(
            "YAML_SYNTAX",
            assertFailsWith<DocumentError> {
                    YamlSource.index("syntax.yml", "value: [not closed\n")
                }
                .diagnostic
                .code,
        )
        assertEquals(
            "YAML_TAG",
            assertFailsWith<DocumentError> {
                    YamlSource.index(
                        "tag.yml",
                        "value: !!java/object:java.lang.ProcessBuilder {}\n",
                    )
                }
                .diagnostic
                .code,
        )
        assertEquals(
            "YAML_ROOT",
            assertFailsWith<DocumentError> {
                    YamlSource.index("root.yml", "- a\n- b\n")
                }
                .diagnostic
                .code,
        )
    }

    @Test
    fun depthAliasExpansionCyclesAndByteLimitsAreBounded() {
        val deep = (0..34).joinToString("") { "  ".repeat(it) + "key$it:\n" }
        assertEquals(
            "YAML_DEPTH",
            assertFailsWith<DocumentError> { YamlSource.index("deep.yml", deep) }.diagnostic.code,
        )
        assertEquals(
            "YAML_ALIAS",
            assertFailsWith<DocumentError> {
                    YamlSource.index("cycle.yml", "value: &loop [*loop]\n")
                }
                .diagnostic
                .code,
        )
        val aliases =
            "anchor: &value x\nvalues: [" + List(33) { "*value" }.joinToString(", ") + "]\n"
        assertEquals(
            "YAML_ALIAS",
            assertFailsWith<DocumentError> { YamlSource.index("alias.yml", aliases) }
                .diagnostic
                .code,
        )
        assertFailsWith<DocumentError> {
            YamlSource.index("large.yml", "#".repeat(YamlSource.MAX_BYTES + 1))
        }
    }

    @Test
    fun diagnosticsEnrichPrimaryAndTemplateOriginWithoutChangingTheirMeaning() {
        val sources =
            mapOf(
                "menu.yml" to
                    YamlSource.index(
                        "menu.yml",
                        "components:\n  - id: instance\n    type: template\n",
                    ),
                "template.yml" to
                    YamlSource.index("template.yml", "component:\n  id: inner\n  type: text\n"),
            )
        val diagnostic =
            Diagnostic(
                    "menu.yml",
                    "$.components[0]",
                    "TEST",
                    "explanation",
                    "instance",
                    origins = listOf(SourceLocation("template.yml", "$.component", "inner")),
                )
                .withSources(sources)
        assertEquals(2, diagnostic.range?.startLine)
        assertEquals(1, diagnostic.origins.single().range?.startLine)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertTrue(diagnostic.toString().contains("menu.yml:2:"))
        assertTrue(diagnostic.toString().contains("from template.yml:1:"))
    }

    @Test
    fun repositoryUsesThePreflightedTextAndNeverRewritesBadOrGoodDocuments() {
        seed()
        val bad =
            "id: test:menu\nentry: main\npages:\n  main:\n    mode: native\n    title: Test\n    components:\n      - id: label\n        type: text\n        mystery: value\n"
        val file = root.resolve("core/menus/test.yml")
        Files.createDirectories(file.parent)
        Files.writeString(file, bad)
        val before = Files.readAllBytes(file)
        var decoded = 0
        val repo =
            MenuRepository(
                root,
                {},
                { _, _, _ -> },
                { text ->
                    decoded++
                    @Suppress("UNCHECKED_CAST")
                    (Yaml(SafeConstructor(LoaderOptions())).load<Any>(text) as Map<String, Any?>)
                },
            )
        val result = repo.load(compiledAssets = assets())
        val error = result.diagnostics.single()
        assertNull(result.candidate)
        assertEquals("UNKNOWN_FIELD", error.code, error.toString())
        assertEquals(10, error.range?.startLine)
        assertEquals("label", error.component)
        assertContentEquals(before, Files.readAllBytes(file))
        assertTrue(decoded >= 2)
        val good = bad.replace("        mystery: value\n", "        text: '[literal](cmd=no)'\n")
        Files.writeString(file, good)
        assertNotNull(repo.load(compiledAssets = assets()).candidate)
        assertEquals(good, Files.readString(file))
    }

    @Test
    fun invalidPreflightNeverCallsDecoderAndAssetConfigDiagnosticsKeepSourceMarks() {
        seed()
        val config = root.resolve("config.yml")
        val bad = "default-menu: test:menu\ndefault-menu: test:menu\n"
        Files.writeString(config, bad)
        var calls = 0
        val repo =
            MenuRepository(
                root,
                {},
                { _, _, _ -> },
                {
                    calls++
                    emptyMap()
                },
            )
        val error = assertFailsWith<DocumentError> { repo.assets(emptyMap()) }.diagnostic
        assertEquals("YAML_DUPLICATE_KEY", error.code)
        assertEquals(2, error.range?.startLine)
        assertEquals(0, calls)
        assertEquals(bad, Files.readString(config))
    }

    private fun seed() {
        taboolib.module.chat.Components.useAdventure = true
        AuthoringFixtures.copy(root, uiOnly = true)
        Files.writeString(
            root.resolve("config.yml"),
            "default-menu: test:menu\nidle-timeout-seconds: 600\n",
        )
    }

    private fun assets(): com.hiusers.rafter.asset.AssetCatalog {
        val catalog =
            AssetCompiler.compile(
                root.resolve("core/ui"),
                com.hiusers.rafter.asset.AutoAssets.document(root.resolve("core/ui")),
            )
        // 测试资源须固定与真实发现阶段一致的文档集合。
        val menu = root.resolve("core/menus/test.yml")
        return catalog.copy(
            sourceHashes = catalog.sourceHashes + mapOf(menu to AssetCompiler.sha(menu))
        )
    }
}
