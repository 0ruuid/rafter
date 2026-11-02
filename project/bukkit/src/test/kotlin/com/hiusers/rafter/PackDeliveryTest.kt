package com.hiusers.rafter

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.pack.*
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito

class PackDeliveryTest {
    @TempDir lateinit var temp: Path

    @Test
    fun stoppingBeforeMainThreadPublicationStillClosesThePreparedListener() {
        val ui = temp.resolve("core/ui")
        copyLegacyUiTo(ui)
        val assets = AssetCompiler.compile(ui, legacyUiDocument())
        val runtime = PackRuntime(temp)
        val settings =
            PackSettings(
                true,
                6,
                emptyList(),
                PackSettings.Host("127.0.0.1", 0, "http://127.0.0.1", false, false, false),
            )
        PackBuild.prepare(temp, assets, settings).use { build ->
            val pending = runtime.prepare(build, settings, "pending")
            val port = pending.server!!.localPort
            runtime.close()
            assertFails {
                java.net.Socket().use {
                    it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                }
            }
        }
    }

    @Test
    fun staleStatusesCannotUnlockNewSnapshotsAndOnlyOwnedPacksAreRemoved() {
        val ui = temp.resolve("core/ui")
        copyLegacyUiTo(ui)
        val assets = AssetCompiler.compile(ui, legacyUiDocument())
        val player = Mockito.mock(Player::class.java)
        val playerId = UUID.randomUUID()
        Mockito.`when`(player.uniqueId).thenReturn(playerId)
        Mockito.`when`(player.isOnline).thenReturn(true)
        val sent = mutableListOf<UUID>()
        val foreign = UUID.randomUUID()
        val runtime =
            PackRuntime(temp) { _, id, url, sha1, required ->
                assertEquals(20, sha1.size)
                assertTrue(url.endsWith("/resource-pack.zip"))
                assertFalse(required)
                sent += id
            }
        val config =
            PackSettings(
                true,
                6,
                emptyList(),
                PackSettings.Host("127.0.0.1", 0, "http://127.0.0.1", false, false, false),
            )
        Mockito.mockStatic(Bukkit::class.java).use { mock ->
            mock.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            mock
                .`when`<java.util.logging.Logger> { Bukkit.getLogger() }
                .thenReturn(java.util.logging.Logger.getAnonymousLogger())
            mock.`when`<Player?> { Bukkit.getPlayer(playerId) }.thenReturn(player)
            PackBuild.prepare(temp, assets, config).use { a ->
                val ready = runtime.prepare(a, config, "A")
                runtime.expect(config, "A")
                runtime.activate(ready, false)
                assertFalse(runtime.canOpen(player))
                runtime.send(player)
                runtime.send(player)
                assertEquals(1, sent.size)
                runtime.status(
                    PlayerResourcePackStatusEvent(
                        player,
                        foreign,
                        PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
                    )
                )
                assertFalse(runtime.canOpen(player))
                runtime.status(
                    PlayerResourcePackStatusEvent(
                        player,
                        a.id,
                        PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
                    )
                )
                assertTrue(runtime.canOpen(player))
                PackBuild.prepare(temp, assets, config.copy(compression = 1)).use { b ->
                    assertNotEquals(a.id, b.id)
                    val next = runtime.prepare(b, config, "B")
                    runtime.expect(config, "B")
                    runtime.activate(next, false)
                    assertFalse(runtime.canOpen(player))
                    runtime.send(player)
                    assertEquals(2, sent.size)
                    runtime.status(
                        PlayerResourcePackStatusEvent(
                            player,
                            a.id,
                            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
                        )
                    )
                    assertFalse(runtime.canOpen(player))
                    runtime.status(
                        PlayerResourcePackStatusEvent(
                            player,
                            b.id,
                            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
                        )
                    )
                    assertTrue(runtime.canOpen(player))
                    Mockito.verify(player).removeResourcePack(a.id)
                    Mockito.verify(player, Mockito.never()).removeResourcePack(foreign)
                    runtime.status(
                        PlayerResourcePackStatusEvent(
                            player,
                            b.id,
                            PlayerResourcePackStatusEvent.Status.DISCARDED,
                        )
                    )
                    assertFalse(runtime.canOpen(player))
                }
            }
            runtime.close()
            Mockito.verify(player, Mockito.never()).removeResourcePacks()
        }
    }
}
