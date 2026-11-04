package com.hiusers.rafter

import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.compile.*
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuthoringExportSmokeTest {
    @TempDir lateinit var root: Path

    @Test
    fun exportsCurrentAuthoringContractAndRepresentativeLayouts() {
        taboolib.module.chat.Components.useAdventure = true
        DefaultBundle.install(root)
        val loaded =
            MenuRepository(root, { error("No scripts") }, ::checkStaticItemReference)
                .load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.toString())
        val authoring = AuthoringContract.manifest("0.0.2", emptyMap())
        assertEquals(AuthoringContract.VERSION, authoring["contract-version"])
        assertEquals(
            false,
            (authoring["authoring-capabilities"] as Map<*, *>)["native-free-position"],
        )
        assertEquals(20, (authoring["native-geometry"] as Map<*, *>)["button-height"])
        val out = Path.of("build/authoring-contract")
        val documents =
            com.hiusers.rafter.authoring.AuthoringExport.documents(root, authoring, candidate)
        documents.forEach { (name, value) -> Json.writeContract(out.resolve(name), value) }
        val search = candidate.snapshot.menus.getValue("rafter:friends").pages.getValue("search")
        assertNotNull(search.modalLayout)
        assertEquals(listOf("input"), search.modalLayout!!.inputs.map { it.id })
        assertTrue(candidate.snapshot.menus.getValue("rafter:tasks").pages.isNotEmpty())
    }
}
