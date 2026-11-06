package com.hiusers.rafter.transport

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.world.clock.ClockNetworkState
import com.github.retrooper.packetevents.protocol.world.clock.WorldClocks
import com.github.retrooper.packetevents.settings.PacketEventsSettings
import com.github.retrooper.packetevents.util.mappings.IRegistryHolder
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTimeUpdate
import io.netty.buffer.Unpooled
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class InputAppearanceControllerTest {

    @Test
    fun forgetDoesNotRetainDisconnectedPlayers() {
        val controller = InputAppearanceController(Mockito.mock(Plugin::class.java))
        repeat(100) { controller.forget(UUID.randomUUID()) }
        assertTrue(generations(controller).isEmpty())
    }

    @Test
    fun reconnectDoesNotReuseAnEarlierGeneration() = withPacketEventsServer(ServerVersion.V_26_1) {
        val controller = InputAppearanceController(Mockito.mock(Plugin::class.java))
        val id = UUID.randomUUID()
        val user = Mockito.mock(User::class.java)
        Mockito.`when`(user.uuid).thenReturn(id)
        val event = Mockito.mock(PacketSendEvent::class.java)
        Mockito.`when`(event.user).thenReturn(user)
        Mockito.`when`(event.packetType).thenReturn(PacketType.Play.Server.RESPAWN)

        controller.onPacketSend(event)
        val first = generations(controller).getValue(id)
        controller.forget(id)
        assertFalse(generations(controller).containsKey(id))
        controller.onPacketSend(event)
        assertTrue(generations(controller).getValue(id) > first)
    }

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

    @Test
    fun queuedShowAndMarkerStayOrderedOnOneChannelEventLoop() =
        withPacketEventsApi(ServerVersion.V_26_1) { _, playerManager ->
            val controller = InputAppearanceController(Mockito.mock(Plugin::class.java))
            val id = UUID.randomUUID()
            val worldUid = UUID.randomUUID()
            val player = Mockito.mock(Player::class.java)
            val world = Mockito.mock(World::class.java)
            val user = Mockito.mock(User::class.java)
            val channel = Any()
            val show = Mockito.mock(WrapperPlayServerShowDialog::class.java)
            val showEvent = Mockito.mock(PacketSendEvent::class.java)
            val queue = ArrayDeque<Runnable>()
            val sent = mutableListOf<PacketWrapper<*>>()

            Mockito.`when`(player.uniqueId).thenReturn(id)
            Mockito.`when`(player.world).thenReturn(world)
            Mockito.`when`(world.uid).thenReturn(worldUid)
            Mockito.`when`(world.gameTime).thenReturn(100L)
            Mockito.`when`(playerManager.getClientVersion(player))
                .thenReturn(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_26_1)
            Mockito.`when`(playerManager.getUser(player)).thenReturn(user)
            Mockito.`when`(user.channel).thenReturn(channel)
            Mockito.`when`(user.uuid).thenReturn(id)
            Mockito.`when`(user.clientVersion)
                .thenReturn(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_26_1)
            Mockito.`when`(showEvent.user).thenReturn(user)
            Mockito.`when`(showEvent.packetType).thenReturn(PacketType.Play.Server.SHOW_DIALOG)
            Mockito.doAnswer { invocation ->
                val packet = invocation.getArgument<PacketWrapper<*>>(0)
                sent += packet
                if (packet is WrapperPlayServerShowDialog) controller.onPacketSend(showEvent)
                null
            }.`when`(user).sendPacket(Mockito.any(PacketWrapper::class.java))
            Mockito.doAnswer { invocation ->
                queue += Runnable {
                    user.sendPacket(invocation.getArgument<PacketWrapper<*>>(1))
                }
                Unit
            }.`when`(playerManager)
                .sendPacket(Mockito.eq(player), Mockito.any(PacketWrapper::class.java))
            seedRunningController(controller, id)

            Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
                bukkit
                    .`when`<Player?> { Bukkit.getPlayer(id) }
                    .thenReturn(null)
                Mockito.mockStatic(ChannelHelper::class.java).use { channels ->
                    channels
                        .`when`<Unit> {
                            ChannelHelper.runInEventLoop(
                                Mockito.eq(channel),
                                Mockito.any(Runnable::class.java),
                            )
                        }
                        .thenAnswer { invocation ->
                            queue += invocation.getArgument<Runnable>(1)
                            Unit
                        }

                    controller.show(player, 1, show)
                    while (queue.isNotEmpty()) queue.removeFirst().run()
                }
            }

            assertEquals(listOf(show), sent.take(1))
            assertEquals(-256L, (sent[1] as WrapperPlayServerTimeUpdate).worldAge)
            assertEquals(1, activeAppearanceId(controller, id))
        }

    @Test
    fun externalDialogAndIncomingClickPacketsStillClearActiveAppearance() =
        withPacketEventsApi(ServerVersion.V_26_1) { _, playerManager ->
            val controller = InputAppearanceController(Mockito.mock(Plugin::class.java))
            val id = UUID.randomUUID()
            val player = Mockito.mock(Player::class.java)
            val world = Mockito.mock(World::class.java)
            val user = Mockito.mock(User::class.java)
            val channel = Any()
            val show = Mockito.mock(WrapperPlayServerShowDialog::class.java)
            val showEvent = Mockito.mock(PacketSendEvent::class.java)
            val queue = ArrayDeque<Runnable>()

            Mockito.`when`(player.uniqueId).thenReturn(id)
            Mockito.`when`(player.world).thenReturn(world)
            Mockito.`when`(world.uid).thenReturn(UUID.randomUUID())
            Mockito.`when`(world.gameTime).thenReturn(100L)
            Mockito.`when`(playerManager.getClientVersion(player))
                .thenReturn(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_26_1)
            Mockito.`when`(playerManager.getUser(player)).thenReturn(user)
            Mockito.`when`(user.channel).thenReturn(channel)
            Mockito.`when`(user.uuid).thenReturn(id)
            Mockito.`when`(user.clientVersion)
                .thenReturn(com.github.retrooper.packetevents.protocol.player.ClientVersion.V_26_1)
            Mockito.`when`(showEvent.user).thenReturn(user)
            Mockito.`when`(showEvent.packetType).thenReturn(PacketType.Play.Server.SHOW_DIALOG)
            Mockito.doAnswer { invocation ->
                if (invocation.getArgument<Any>(0) is WrapperPlayServerShowDialog) {
                    controller.onPacketSend(showEvent)
                }
                null
            }.`when`(user).sendPacket(Mockito.any(PacketWrapper::class.java))
            seedRunningController(controller, id)

            Mockito.mockStatic(ChannelHelper::class.java).use { channels ->
                channels
                    .`when`<Unit> {
                        ChannelHelper.runInEventLoop(
                            Mockito.eq(channel),
                            Mockito.any(Runnable::class.java),
                        )
                    }
                    .thenAnswer { invocation ->
                        queue += invocation.getArgument<Runnable>(1)
                        Unit
                    }
                Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
                    bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
                    bukkit
                        .`when`<Player?> { Bukkit.getPlayer(id) }
                        .thenReturn(null)

                    for (packetType in listOf(
                        PacketType.Play.Server.SHOW_DIALOG,
                        PacketType.Play.Server.CLEAR_DIALOG,
                        PacketType.Play.Server.OPEN_WINDOW,
                    )) {
                        controller.show(player, 1, show)
                        while (queue.isNotEmpty()) queue.removeFirst().run()
                        assertEquals(1, activeAppearanceId(controller, id))
                        val event = Mockito.mock(PacketSendEvent::class.java)
                        Mockito.`when`(event.user).thenReturn(user)
                        Mockito.`when`(event.packetType).thenReturn(packetType)
                        controller.onPacketSend(event)
                        assertTrue(active(controller).isEmpty(), packetType.toString())
                    }

                    controller.show(player, 1, show)
                    while (queue.isNotEmpty()) queue.removeFirst().run()
                    val click = Mockito.mock(PacketReceiveEvent::class.java)
                    Mockito.`when`(click.user).thenReturn(user)
                    Mockito.`when`(click.packetType)
                        .thenReturn(PacketType.Play.Client.CUSTOM_CLICK_ACTION)
                    controller.onPacketReceive(click)
                    assertTrue(active(controller).isEmpty())
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun generations(controller: InputAppearanceController): Map<UUID, Long> =
        InputAppearanceController::class.java.getDeclaredField("generations").let { field ->
            field.isAccessible = true
            field.get(controller) as Map<UUID, Long>
        }

    @Suppress("UNCHECKED_CAST")
    private fun active(controller: InputAppearanceController): Map<UUID, Any> =
        InputAppearanceController::class.java.getDeclaredField("active").let { field ->
            field.isAccessible = true
            field.get(controller) as Map<UUID, Any>
        }

    private fun activeAppearanceId(controller: InputAppearanceController, id: UUID): Int? =
        active(controller)[id]?.let { value ->
            value.javaClass.getDeclaredField("appearanceId").let { field ->
                field.isAccessible = true
                field.getInt(value)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun seedRunningController(controller: InputAppearanceController, id: UUID) {
        InputAppearanceController::class.java.getDeclaredField("started").let { field ->
            field.isAccessible = true
            field.setBoolean(controller, true)
        }
        InputAppearanceController::class.java.getDeclaredField("clockSamples").let { field ->
            field.isAccessible = true
            val samples = field.get(controller) as ConcurrentHashMap<UUID, ConcurrentHashMap<*, *>>
            @Suppress("UNCHECKED_CAST")
            (samples as ConcurrentHashMap<UUID, ConcurrentHashMap<com.github.retrooper.packetevents.protocol.world.clock.WorldClock, InputAppearanceClockSample>>)[id] =
                ConcurrentHashMap(
                    mapOf(
                        WorldClocks.OVERWORLD to
                            InputAppearanceClockSample(
                                100L,
                                ClockNetworkState(1_000L, 0.25f, 1.0f),
                            ),
                    ),
                )
        }
    }

    private fun withPacketEventsApi(
        serverVersion: ServerVersion,
        block: (PacketEventsAPI<*>, PlayerManager) -> Unit,
    ) {
        val api = Mockito.mock(PacketEventsAPI::class.java, Mockito.withSettings().stubOnly()) as PacketEventsAPI<*>
        val serverManager = Mockito.mock(ServerManager::class.java, Mockito.withSettings().stubOnly())
        val playerManager = Mockito.mock(PlayerManager::class.java, Mockito.withSettings().stubOnly())
        Mockito.`when`(api.serverManager).thenReturn(serverManager)
        Mockito.`when`(api.playerManager).thenReturn(playerManager)
        Mockito.`when`(api.nettyManager).thenReturn(NettyManagerImpl())
        Mockito.`when`(api.settings).thenReturn(PacketEventsSettings())
        Mockito.`when`(serverManager.version).thenReturn(serverVersion)
        Mockito.mockStatic(PacketEvents::class.java, Mockito.withSettings().stubOnly()).use { packets ->
            packets.`when`<PacketEventsAPI<*>> { PacketEvents.getAPI() }.thenReturn(api)
            block(api, playerManager)
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
