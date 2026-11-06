package com.hiusers.rafter

import com.hiusers.rafter.api.*
import com.hiusers.rafter.asset.AssetCatalog
import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.AutoAssets
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.render.DialogView
import com.hiusers.rafter.runtime.RafterEngine
import com.hiusers.rafter.text.CompositeText
import com.hiusers.rafter.transport.DialogTransport
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.test.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito

class RuntimeVariantTest {
    @TempDir lateinit var root: Path

    private class Capture : DialogTransport() {
        val views = mutableMapOf<UUID, DialogView>()
        var sends = 0

        override fun supported(player: Player) = true

        override fun show(
            player: Player,
            view: DialogView,
            text: CompositeText,
            values: Map<BindingSpec, Any?>,
            items: Map<String, ItemStack>,
        ): CompletableFuture<Void> {
            views[player.uniqueId] = view
            sends++
            return CompletableFuture.completedFuture(null)
        }

        override fun clear(player: Player) {
            views.remove(player.uniqueId)
        }

        fun token(player: Player): String {
            val view = views.getValue(player.uniqueId)
            return view.buttons.firstOrNull()?.handle
                ?: view.messages.flatMap { it.parts }.first { it.handle != null }.handle!!
        }

        fun plain(player: Player): String {
            val view = views.getValue(player.uniqueId)
            return view.buttons.firstOrNull()?.label?.toPlainText()
                ?: view.messages.flatMap { it.parts }.joinToString("") { it.text.toPlainText() }
        }
    }

    private class Fixture(
        val engine: RafterEngine,
        val owner: Plugin,
        val players: List<Player>,
        val assets: AssetCatalog,
        val wire: Capture,
    ) {
        fun install(node: Map<String, Any?>, mode: String = "native") {
            val result =
                MenuCompiler(assets.dimensions(), styles = assets.theme.styles)
                    .compile(mapOf("variants.yml" to minimal(mode, listOf(node))))
            engine.install(
                Candidate(
                    assertNotNull(result.snapshot, result.diagnostics.toString()),
                    assets,
                    "test:menu",
                    600_000,
                )
            )
        }
    }

    private fun run(block: Fixture.() -> Unit) {
        taboolib.module.chat.Components.useAdventure = true
        val players =
            List(2) { index ->
                Mockito.mock(Player::class.java).also {
                    Mockito.`when`(it.uniqueId).thenReturn(UUID.randomUUID())
                    Mockito.`when`(it.isOnline).thenReturn(true)
                    Mockito.`when`(it.name).thenReturn("Player$index")
                }
            }
        val owner =
            Mockito.mock(Plugin::class.java).also {
                Mockito.`when`(it.isEnabled).thenReturn(true)
                Mockito.`when`(it.name).thenReturn("VariantTest")
                Mockito.`when`(it.logger).thenReturn(Logger.getLogger("RafterVariantTest"))
            }
        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            players.forEach { player ->
                bukkit.`when`<Player> { Bukkit.getPlayer(player.uniqueId) }.thenReturn(player)
            }
            val ui = root.resolve("core/ui")
            copyLegacyUiTo(ui)
            val assets = AssetCompiler.compile(ui, AutoAssets.document(ui))
            val wire = Capture()
            val engine = RafterEngine(owner, root, wire, post = { it() })
            try {
                Fixture(engine, owner, players, assets, wire).block()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun firstMatchIsPlayerLocalAndActionsReplaceRatherThanAppend() = run {
        var baseCalls = 0
        var firstCalls = 0
        var secondCalls = 0
        var secondReads = 0
        engine.registerCondition(
            owner,
            "test:first-player",
            ProviderDescriptor("first"),
            ConditionProvider { context, _ ->
                CompletableFuture.completedFuture(
                    context.player.uniqueId == players.first().uniqueId
                )
            },
        )
        engine.registerData(
            owner,
            "test:second-label",
            ProviderDescriptor("label"),
            DataProvider { _, _ ->
                secondReads++
                CompletableFuture.completedFuture("Second")
            },
        )
        fun action(key: String, execute: () -> Unit) {
            engine.registerAction(
                owner,
                key,
                ProviderDescriptor(key),
                ActionProvider { _, _ ->
                    execute()
                    CompletableFuture.completedFuture(UiActionResult.empty())
                },
            )
        }
        action("test:base") { baseCalls++ }
        action("test:first") { firstCalls++ }
        action("test:second") { secondCalls++ }
        fun actions(id: String) = listOf(mapOf("type" to "provider", "provider" to id))
        install(
            mapOf(
                "id" to "button",
                "type" to "button",
                "text" to "Base",
                "actions" to actions("test:base"),
                "variants" to
                    listOf(
                        mapOf(
                            "when" to
                                mapOf("type" to "provider", "provider" to "test:first-player"),
                            "override" to
                                mapOf("text" to "First", "actions" to actions("test:first")),
                        ),
                        mapOf(
                            "when" to mapOf("type" to "constant", "value" to true),
                            "override" to
                                mapOf(
                                    "text" to
                                        mapOf(
                                            "format" to "literal",
                                            "binding" to
                                                mapOf(
                                                    "type" to "data",
                                                    "key" to "test:second-label",
                                                ),
                                        ),
                                    "actions" to actions("test:second"),
                                ),
                        ),
                    ),
            )
        )
        assertTrue(engine.open(players[0], "test:menu").getNow(null).opened)
        assertEquals("First", wire.plain(players[0]))
        assertEquals(0, secondReads, "Inactive branch data must not be read")
        assertTrue(engine.open(players[1], "test:menu").getNow(null).opened)
        assertEquals("Second", wire.plain(players[1]))
        assertEquals(1, secondReads)
        engine.click(players[0], wire.token(players[0]), emptyMap())
        engine.click(players[1], wire.token(players[1]), emptyMap())
        assertEquals(0, baseCalls)
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
        assertEquals("First", wire.plain(players[0]))
    }

    @Test
    fun changedCanvasBranchRejectsOldClickWithoutExecutingEitherAction() = run {
        val player = players.first()
        var eligible = true
        var baseCalls = 0
        var variantCalls = 0
        engine.registerCondition(
            owner,
            "test:eligible",
            ProviderDescriptor("eligible"),
            ConditionProvider { _, _ ->
                CompletableFuture.completedFuture(eligible)
            },
        )
        engine.registerAction(
            owner,
            "test:base",
            ProviderDescriptor("base"),
            ActionProvider { _, _ ->
                baseCalls++
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        engine.registerAction(
            owner,
            "test:variant",
            ProviderDescriptor("variant"),
            ActionProvider { _, _ ->
                variantCalls++
                CompletableFuture.completedFuture(UiActionResult.empty())
            },
        )
        install(
            mapOf(
                "id" to "button",
                "type" to "text",
                "width" to 180,
                "height" to 18,
                "text" to "Base",
                "actions" to listOf(mapOf("type" to "provider", "provider" to "test:base")),
                "variants" to
                    listOf(
                        mapOf(
                            "when" to mapOf("type" to "provider", "provider" to "test:eligible"),
                            "override" to
                                mapOf(
                                    "text" to "Ready",
                                    "actions" to
                                        listOf(
                                            mapOf(
                                                "type" to "provider",
                                                "provider" to "test:variant",
                                            )
                                        ),
                                ),
                        )
                    ),
            ),
            "canvas",
        )
        assertTrue(engine.open(player, "test:menu").getNow(null).opened)
        assertTrue("Ready" in wire.plain(player))
        val old = wire.token(player)
        eligible = false
        engine.click(player, old, emptyMap())
        assertEquals(0, baseCalls)
        assertEquals(0, variantCalls)
        assertTrue("Base" in wire.plain(player))
        val fresh = wire.token(player)
        assertNotEquals(old, fresh)
        engine.click(player, fresh, emptyMap())
        assertEquals(1, baseCalls)
        engine.click(player, old, emptyMap())
        engine.click(player, fresh, emptyMap())
        assertEquals(1, baseCalls, "Both consumed and stale handles must remain unusable")
    }

    @Test
    fun pendingBranchResultCannotReopenAClosedSession() = run {
        val player = players.first()
        val pending = CompletableFuture<Boolean>()
        lateinit var sessionId: UUID
        engine.registerCondition(
            owner,
            "test:pending",
            ProviderDescriptor("pending"),
            ConditionProvider { context, _ ->
                sessionId = context.sessionId
                pending
            },
        )
        install(
            mapOf(
                "id" to "button",
                "type" to "button",
                "text" to "Base",
                "actions" to listOf(mapOf("type" to "close")),
                "variants" to
                    listOf(
                        mapOf(
                            "when" to mapOf("type" to "provider", "provider" to "test:pending"),
                            "override" to mapOf("text" to "Late"),
                        )
                    ),
            )
        )
        val opened = engine.open(player, "test:menu")
        assertFalse(opened.isDone)
        engine.close(player, sessionId).getNow(null)
        pending.complete(true)
        assertTrue(opened.isDone)
        assertEquals(0, wire.sends)
        assertTrue(wire.views.isEmpty())
    }
}
