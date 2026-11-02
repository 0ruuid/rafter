package com.hiusers.rafter

import com.hiusers.rafter.api.UiFailureCode
import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.AutoAssets
import com.hiusers.rafter.command.RafterCommand
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.runtime.RafterEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.test.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.yaml.snakeyaml.Yaml
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.CommandStructure
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.SimpleCommandBody
import taboolib.common.platform.command.component.CommandBase

class CommandSystemTest {
    @TempDir lateinit var root: Path

    @Test
    fun commandTreeAndCompletionsUsePermissionsOnlinePlayersAndCurrentMenus() {
        val alice = Mockito.mock(Player::class.java)
        val bob = Mockito.mock(Player::class.java)
        Mockito.`when`(alice.name).thenReturn("Alice")
        Mockito.`when`(bob.name).thenReturn("Bob")
        Mockito.`when`(alice.isOnline).thenReturn(true)
        Mockito.`when`(bob.isOnline).thenReturn(true)
        Mockito.`when`(alice.hasPermission("menu.vip")).thenReturn(false)
        val owner = Mockito.mock(Plugin::class.java)
        Mockito.`when`(owner.logger).thenReturn(Logger.getLogger("CommandSystemTest"))
        val user = sender(alice, false)
        val admin = sender(alice, true)
        Mockito.mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<Boolean> { Bukkit.isPrimaryThread() }.thenReturn(true)
            bukkit
                .`when`<Collection<Player>> { Bukkit.getOnlinePlayers() }
                .thenReturn(listOf(alice, bob))
            val ui = root.resolve("core/ui")
            copyLegacyUiTo(ui)
            val assets = AssetCompiler.compile(ui, AutoAssets.document(ui))
            val engine = RafterEngine(owner, root)
            val field = Rafter::class.java.getDeclaredField("engine").apply { isAccessible = true }
            val previous = field.get(null)
            field.set(null, engine)
            try {
                fun install(vararg docs: Map<String, Any?>) {
                    val result =
                        MenuCompiler()
                            .compile(docs.mapIndexed { index, doc -> "$index.yml" to doc }.toMap())
                    engine.install(
                        Candidate(
                            assertNotNull(result.snapshot, result.diagnostics.toString()),
                            assets,
                            docs.first()["id"] as String,
                            600_000,
                        )
                    )
                }
                install(
                    minimal() + ("id" to "demo:public"),
                    minimal() + mapOf("id" to "demo:vip", "permission" to "menu.vip"),
                )
                val tree = tree()
                fun suggest(sender: ProxyCommandSender, vararg args: String): List<String> =
                    tree
                        .suggest(
                            CommandContext(
                                sender,
                                CommandStructure(
                                    "rafter",
                                    emptyList(),
                                    "",
                                    "",
                                    "rafter.use",
                                    "",
                                    PermissionDefault.TRUE,
                                    emptyMap(),
                                    false,
                                ),
                                "rafter",
                                tree,
                                false,
                                arrayOf(*args),
                            )
                        )
                        .orEmpty()
                assertEquals(setOf("open", "close"), suggest(user, "").toSet())
                assertEquals(
                    setOf("open", "close", "reload", "pack", "export"),
                    suggest(admin, "").toSet(),
                )
                assertTrue(suggest(admin, "check").isEmpty())
                assertTrue(suggest(admin, "contract").isEmpty())
                assertEquals(listOf("demo:public"), suggest(user, "open", ""))
                assertEquals(listOf("demo:public", "demo:vip"), suggest(admin, "open", ""))
                assertTrue(suggest(user, "open", "demo:public", "").isEmpty())
                assertEquals(listOf("Alice"), suggest(admin, "open", "demo:public", "Al"))
                assertTrue(suggest(user, "close", "").isEmpty())
                assertEquals(listOf("Alice", "Bob"), suggest(admin, "close", ""))
                assertEquals(listOf("send"), suggest(admin, "pack", ""))
                assertEquals(listOf("Bob"), suggest(admin, "pack", "send", "Bo"))
                install(minimal() + ("id" to "next:menu"))
                assertEquals(
                    listOf("next:menu"),
                    suggest(user, "open", ""),
                    "Reloaded IDs must replace old completion data",
                )
            } finally {
                engine.close()
                field.set(null, previous)
            }
        }
    }

    private fun sender(origin: Any, admin: Boolean): ProxyCommandSender =
        Mockito.mock(ProxyCommandSender::class.java).also {
            Mockito.`when`(it.origin).thenReturn(origin)
            Mockito.`when`(it.hasPermission(Mockito.anyString())).thenAnswer { invocation ->
                when (invocation.arguments[0]) {
                    "",
                    "rafter.use" -> true
                    "rafter.admin" -> admin
                    else -> false
                }
            }
        }

    /** 复用 TabooLib 注解装配，测试真实命令树。 */
    private fun tree() =
        CommandBase().apply {
            RafterCommand.main.func(this)
            RafterCommand::class.java.declaredFields.forEach { field ->
                val body = field.getAnnotation(CommandBody::class.java) ?: return@forEach
                if (field.type != SimpleCommandBody::class.java) return@forEach
                field.isAccessible = true
                val definition = field.get(null) as SimpleCommandBody
                literal(
                    field.name,
                    optional = body.optional,
                    permission = body.permission,
                    hidden = body.hidden,
                    description = body.description,
                ) {
                    definition.func(this)
                }
            }
        }

    @Test
    fun languagesHaveMatchingPlaceholdersConsistentStyleAndAllReferencedMessages() {
        fun language(name: String): Map<String, String> =
            javaClass.getResourceAsStream("/lang/$name.yml")!!.reader().use {
                Yaml().load(it)
            }
        val chinese = language("zh_CN")
        val english = language("en_US")
        assertEquals(chinese.keys, english.keys)
        val placeholder = Regex("\\{[0-9]+}")
        chinese.forEach { (key, value) ->
            assertEquals(
                placeholder.findAll(value).map { it.value }.toSet(),
                placeholder.findAll(english.getValue(key)).map { it.value }.toSet(),
                key,
            )
            if (!key.startsWith("command-description-")) {
                assertTrue(value.startsWith("&8[&fRafter&8]"), key)
                assertTrue(english.getValue(key).startsWith("&8[&fRafter&8]"), key)
            }
        }
        UiFailureCode.entries.forEach {
            assertTrue("ui-failure-${it.name.lowercase().replace('_', '-')}" in chinese, it.name)
        }
        listOf("declined", "failed-download", "invalid-url", "failed-reload", "discarded").forEach {
            assertTrue("pack-load-failed-$it" in chinese)
        }
        val production = Path.of("src/main/kotlin")
        val referenced = Regex("sendLang\\(\"([a-z0-9-]+)\"")
        Files.walk(production).use { files ->
            files
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file ->
                    referenced.findAll(Files.readString(file)).forEach { match ->
                        assertTrue(
                            match.groupValues[1] in chinese,
                            "$file: ${match.groupValues[1]}",
                        )
                    }
                }
        }
        assertFalse("check-success" in chinese)
        assertFalse("contract-success" in chinese)
    }
}
