package com.hiusers.rafter.transport

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.world.clock.ClockNetworkState
import com.github.retrooper.packetevents.protocol.world.clock.WorldClocks
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.util.mappings.IRegistryHolder
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTimeUpdate
import io.netty.buffer.Unpooled
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class InputAppearanceControllerTest {

    @Test
    fun supportsOnlyDeclaredClientProfiles() {
        assertEquals(
            (771..777).toSet(),
            (771..777).filter(::supportsInputAppearanceProtocol).toSet(),
        )
        assertFalse(supportsInputAppearanceProtocol(770))
        assertFalse(supportsInputAppearanceProtocol(778))
        assertFalse(supportsInputAppearanceProtocol(Int.MAX_VALUE))
    }

    @Test
    fun projectsAllClocksAndLeavesPausedClockStopped() = withPacketEventsServer(ServerVersion.V_26_1) {
        val projected =
            assertNotNull(
                projectInputAppearanceClocks(
                    mapOf(
                        WorldClocks.OVERWORLD to
                            InputAppearanceClockSample(
                                100L,
                                ClockNetworkState(1_000L, 0.25f, 1.0f),
                            ),
                        WorldClocks.THE_END to
                            InputAppearanceClockSample(
                                100L,
                                ClockNetworkState(2_000L, 0.75f, 0.0f),
                            ),
                    ),
                    104L,
                )
            )

        assertEquals(2, projected.size)
        assertEquals(ClockNetworkState(1_004L, 0.25f, 1.0f), projected[WorldClocks.OVERWORLD])
        assertEquals(ClockNetworkState(2_000L, 0.75f, 0.0f), projected[WorldClocks.THE_END])
        assertEquals(
            ClockNetworkState(2_000L, 0.75f, 0.0f),
            projected.entries.single { it.value.rate == 0.0f }.value,
        )
    }

    @Test
    fun legacyWrapperSerializesDaytimeAndPausedState() = withPacketEventsServer(ServerVersion.V_1_21_6) {
        val buffer = Unpooled.buffer()
        try {
            val writer =
                WrapperPlayServerTimeUpdate(
                    -256L,
                    mapOf(WorldClocks.OVERWORLD to ClockNetworkState(1_234L, 0.5f, 0.0f)),
                )
            writer.setServerVersion(ServerVersion.V_1_21_6)
            writer.setRegistryHolder(IRegistryHolder { _, _ -> WorldClocks.getRegistry() })
            writer.setBuffer(buffer)
            writer.write()

            val reader = WrapperPlayServerTimeUpdate(0L, emptyMap())
            reader.setServerVersion(ServerVersion.V_1_21_6)
            reader.setRegistryHolder(IRegistryHolder { _, _ -> WorldClocks.getRegistry() })
            reader.setBuffer(buffer)
            reader.read()

            assertEquals(-256L, reader.worldAge)
            assertEquals(1_234L, reader.timeOfDay)
            assertFalse(reader.isTickTime)
            assertEquals(1_234L, reader.clockState.totalTicks)
        } finally {
            buffer.release()
        }
    }

    @Test
    fun modernWrapperRetainsEveryClockEntry() = withPacketEventsServer(ServerVersion.V_26_1) {
        val expected =
            mapOf(
                WorldClocks.OVERWORLD to ClockNetworkState(1_000L, 0.25f, 1.0f),
                WorldClocks.THE_END to ClockNetworkState(2_000L, 0.5f, 0.5f),
            )
        val buffer = Unpooled.buffer()
        try {
            val writer = WrapperPlayServerTimeUpdate(100L, expected)
            writer.setServerVersion(ServerVersion.V_26_1)
            writer.setRegistryHolder(IRegistryHolder { _, _ -> WorldClocks.getRegistry() })
            writer.setBuffer(buffer)
            writer.write()

            val reader = WrapperPlayServerTimeUpdate(0L, emptyMap())
            reader.setServerVersion(ServerVersion.V_26_1)
            reader.setRegistryHolder(IRegistryHolder { _, _ -> WorldClocks.getRegistry() })
            reader.setBuffer(buffer)
            reader.read()

            assertEquals(100L, reader.worldAge)
            assertEquals(expected, reader.clockUpdates)
            assertTrue(reader.clockUpdates.containsKey(WorldClocks.OVERWORLD))
        } finally {
            buffer.release()
        }
    }

    private fun withPacketEventsServer(serverVersion: ServerVersion, block: () -> Unit) {
        // 映射初始化逐字节访问 API；仅提供返回值，避免记录几十万次无关调用。
        val api = Mockito.mock(PacketEventsAPI::class.java, Mockito.withSettings().stubOnly()) as PacketEventsAPI<*>
        val serverManager = Mockito.mock(ServerManager::class.java, Mockito.withSettings().stubOnly())
        Mockito.`when`(api.serverManager).thenReturn(serverManager)
        Mockito.`when`(api.nettyManager).thenReturn(NettyManagerImpl())
        Mockito.`when`(api.settings).thenReturn(PacketEventsSettings())
        Mockito.`when`(serverManager.version).thenReturn(serverVersion)
        Mockito.mockStatic(PacketEvents::class.java, Mockito.withSettings().stubOnly()).use { packets ->
            packets.`when`<PacketEventsAPI<*>> { PacketEvents.getAPI() }.thenReturn(api)
            block()
        }
    }
}
