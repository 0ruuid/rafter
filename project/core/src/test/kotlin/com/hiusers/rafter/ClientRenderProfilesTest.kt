package com.hiusers.rafter

import com.hiusers.rafter.asset.ClientRenderProfiles
import com.hiusers.rafter.compile.Contract
import kotlin.test.*

class ClientRenderProfilesTest {
    @Test
    fun releasedProtocolsAndPackFormatsAreClosedAndUnambiguous() {
        val profiles = ClientRenderProfiles.all
        assertEquals((771..777).toList(), profiles.flatMap { it.protocols })
        assertEquals(11, profiles.sumOf { it.clients.size })
        assertEquals(6, profiles.map { it.directory }.distinct().size)
        for (profile in profiles) for (protocol in profile.protocols) {
            assertSame(profile, ClientRenderProfiles.forProtocol(protocol))
        }
        for (unknown in listOf(-1, 0, 770, 778, 1073742169, Int.MAX_VALUE)) {
            assertNull(ClientRenderProfiles.forProtocol(unknown))
        }
        assertEquals(
            setOf(63 to 0, 64 to 0, 69 to 0, 75 to 0, 84 to 0, 88 to 0, 97 to 1),
            ClientRenderProfiles.packFormats,
        )
        assertFalse(ClientRenderProfiles.supportsPackFormat(97, 0))
        assertFalse(ClientRenderProfiles.supportsPackFormat(88, 1))
    }

    @Test
    fun exportedItemCapabilitiesUseTheRuntimeProfileTable() {
        val canvas = Contract.manifest()["canvas"] as Map<*, *>
        val items = canvas["items"] as Map<*, *>
        assertEquals(ClientRenderProfiles.all.map { it.manifest() }, items["profiles"])
        val modal = Contract.manifest()["native-modal"] as Map<*, *>
        assertEquals(items["profiles"], modal["profiles"])
        assertEquals("matching-client-profile-required", items["resource-pack"])
        assertEquals(
            listOf("26_2"),
            ClientRenderProfiles.all
                .filter {
                    it.manifest()["canvas-items-runtime-tested"] == true
                }
                .map { it.baseline },
        )
    }
}
