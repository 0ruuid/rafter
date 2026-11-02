package com.hiusers.rafter

import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.asset.manifest
import com.hiusers.rafter.compile.*
import com.hiusers.rafter.model.PageMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

class IndependentDefaultsContractTest {
    @TempDir lateinit var root: Path

    @Test
    fun defaultsCompileWithIndependentElementsAndCanonicalEditorExport() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val loaded =
            MenuRepository(
                    root,
                    { error("Static preview must not execute Kether") },
                    { _, _, _ -> error("Static preview must not call providers") },
                    { Yaml(SafeConstructor(LoaderOptions())).load(it) },
                )
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        assertEquals("rafter:friends", candidate.defaultMenu)
        assertEquals(setOf("rafter:friends", "rafter:tasks"), candidate.snapshot.menus.keys)
        val menu = candidate.snapshot.menus.getValue("rafter:friends")
        assertEquals(setOf("friends", "add", "search", "chat", "memo", "remove"), menu.pages.keys)
        assertTrue(menu.pages.values.all { it.spec.skin != null })
        assertEquals(PageMode.CANVAS, menu.pages.getValue("add").spec.mode)
        assertEquals(12, menu.spec.documents.getValue("preview:friends").size)
        assertEquals(2, menu.spec.data.size)
        val preview = AuthoringContract.previewData(candidate.snapshot)
        val data =
            assertIs<Map<*, *>>(
                assertIs<Map<*, *>>(assertIs<Map<*, *>>(preview["menus"])["rafter:friends"])["data"]
            )
        assertEquals(12, assertIs<List<*>>(data["friends"]).size)
        assertEquals(AuthoringContract.VERSION, preview["contract-version"])
        val canvas = Contract.manifest()["canvas"] as Map<*, *>
        assertEquals(1, canvas["image-y-step"])
        assertEquals(9, canvas["hit-area-y-step"])
        System.getProperty("rafter.editor.export")?.let { target ->
            val directory = Path.of(target)
            Files.createDirectories(directory)
            mapOf(
                    "menu.schema.json" to Contract.schema(),
                    "template.schema.json" to Contract.templateSchema(),
                    "data.schema.json" to Contract.dataSchema(),
                    "skin.schema.json" to Contract.skinSchema(),
                    "assets.schema.json" to Contract.assetSchema(),
                    "theme.schema.json" to Contract.themeSchema(),
                    "authoring.json" to AuthoringContract.manifest("0.0.2", emptyMap()),
                    "assets.json" to candidate.assets.manifest(),
                    "preview-data.json" to preview,
                    "layouts.json" to AuthoringContract.layouts(candidate.snapshot),
                )
                .forEach { (name, value) -> Json.writeContract(directory.resolve(name), value) }
        }
    }
}
