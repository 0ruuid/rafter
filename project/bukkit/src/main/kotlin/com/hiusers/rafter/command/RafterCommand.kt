package com.hiusers.rafter.command

import com.hiusers.rafter.Rafter
import com.hiusers.rafter.api.UiFailureCode
import com.hiusers.rafter.api.UiOperationException
import com.hiusers.rafter.runtime.main
import com.hiusers.rafter.runtime.sendUiFailure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.module.lang.Language
import taboolib.module.lang.sendLang
import taboolib.platform.util.sendLang

/** TabooLib 命令树只读取当前引擎，不缓存菜单定义。 */
@CommandHeader(
    name = "rafter",
    permission = "rafter.use",
    permissionDefault = PermissionDefault.TRUE,
)
object RafterCommand {
    private const val ADMIN = "rafter.admin"

    @CommandBody
    val main = mainCommand {
        incorrectSender { sender, _ -> sender.sendLang("command-player-required") }
        incorrectCommand { sender, context, _, _ ->
            sender.sendLang("command-incorrect", context.name)
        }
        execute<ProxyCommandSender> { sender, context, _ ->
            sender.sendLang("command-help-header", context.name)
            val admin = sender.hasPermission(ADMIN)
            sender.sendLang(
                if (admin) "command-help-open" else "command-help-open-self",
                context.name,
            )
            sender.sendLang(
                if (admin) "command-help-close" else "command-help-close-self",
                context.name,
            )
            if (admin) {
                sender.sendLang("command-help-reload", context.name)
                sender.sendLang("command-help-pack", context.name)
                sender.sendLang("command-help-pack-send", context.name)
                sender.sendLang("command-help-export", context.name)
            }
        }
    }

    @CommandBody(description = "@command-description-open")
    val open = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> attempt(sender) { open(sender, null, null) } }
        dynamic("menu", optional = true) {
            suggestion<ProxyCommandSender>(uncheck = true) { sender, _ -> menuSuggestions(sender) }
            execute<ProxyCommandSender> { sender, context, _ ->
                attempt(sender) { open(sender, context["menu"], null) }
            }
            dynamic("player", optional = true, permission = ADMIN) {
                suggestion<ProxyCommandSender>(uncheck = true) { _, _ -> playerSuggestions() }
                execute<ProxyCommandSender> { sender, context, _ ->
                    attempt(sender) { open(sender, context["menu"], context["player"]) }
                }
            }
        }
    }

    @CommandBody(description = "@command-description-close")
    val close = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> attempt(sender) { close(sender, null) } }
        dynamic("player", optional = true, permission = ADMIN) {
            suggestion<ProxyCommandSender>(uncheck = true) { _, _ -> playerSuggestions() }
            execute<ProxyCommandSender> { sender, context, _ ->
                attempt(sender) { close(sender, context["player"]) }
            }
        }
    }

    @CommandBody(permission = ADMIN, description = "@command-description-reload")
    val reload = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            attempt(sender) {
                Language.reload()
                sender.sendLang("command-reload-start")
                respond(sender, Rafter.engine.reload()) { result ->
                    val candidate = result.candidate
                    if (candidate != null) {
                        sender.sendLang("command-reload-success", candidate.snapshot.menus.size)
                    } else {
                        sender.sendLang("command-reload-failed", result.diagnostics.size)
                    }
                    result.diagnostics.forEach { diagnostic ->
                        sender.sendLang(
                            "command-diagnostic",
                            diagnostic.file,
                            diagnostic.path,
                            diagnostic.code,
                            diagnostic.message,
                        )
                        if (sender.origin !== Bukkit.getConsoleSender()) {
                            Bukkit.getConsoleSender()
                                .sendLang(
                                    "command-diagnostic",
                                    diagnostic.file,
                                    diagnostic.path,
                                    diagnostic.code,
                                    diagnostic.message,
                                )
                        }
                    }
                }
            }
        }
    }

    @CommandBody(permission = ADMIN, description = "@command-description-pack")
    val pack = subCommand {
        literal("send") {
            execute<ProxyCommandSender> { sender, _, _ ->
                attempt(sender) { sendPack(sender, null) }
            }
            dynamic("player", optional = true) {
                suggestion<ProxyCommandSender>(uncheck = true) { _, _ -> playerSuggestions() }
                execute<ProxyCommandSender> { sender, context, _ ->
                    attempt(sender) { sendPack(sender, context["player"]) }
                }
            }
        }
        execute<ProxyCommandSender> { sender, _, _ ->
            attempt(sender) {
                sender.sendLang("pack-building")
                Rafter.engine.pack().whenComplete { path, error ->
                    main {
                        if (error == null) sender.sendLang("pack-success", path.toString())
                        else {
                            log(error)
                            sender.sendLang("pack-failed")
                        }
                    }
                }
            }
        }
    }

    @CommandBody(permission = ADMIN, description = "@command-description-export")
    val export = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            attempt(sender) {
                sender.sendLang("command-export-success", Rafter.engine.exportContract().toString())
            }
        }
    }

    /** 按当前菜单与请求者权限生成补全。 */
    internal fun menuSuggestions(sender: ProxyCommandSender): List<String> {
        if (!Bukkit.isPrimaryThread()) return emptyList()
        val player = (sender.origin as? Player)
        if (player == null && !sender.hasPermission(ADMIN)) return emptyList()
        return runCatching {
                Rafter.engine.menuIds(if (sender.hasPermission(ADMIN)) null else player)
            }
            .getOrDefault(emptyList())
    }

    internal fun playerSuggestions(): List<String> =
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getOnlinePlayers().filter { it.isOnline }.map { it.name }.sorted()
        } else emptyList()

    private fun target(sender: ProxyCommandSender, name: String?): Player? {
        if (name == null)
            return (sender.origin as? Player)
                ?: run {
                    sender.sendLang("command-player-required")
                    null
                }
        if (!sender.hasPermission(ADMIN)) {
            sender.sendUiFailure(UiFailureCode.NO_PERMISSION)
            return null
        }
        return Bukkit.getPlayerExact(name)?.takeIf { it.isOnline }
            ?: run {
                sender.sendLang("command-player-not-found", name)
                null
            }
    }

    private fun open(sender: ProxyCommandSender, menu: String?, playerName: String?) {
        val player = target(sender, playerName) ?: return
        val engine = Rafter.engine
        val menuId = menu ?: engine.defaultMenu()
        respond(sender, engine.open(player, menuId)) { result ->
            if (result.opened) sender.sendLang("command-open-success", menuId, player.name)
            else sender.sendUiFailure(result.failure ?: UiFailureCode.INTERNAL_ERROR)
        }
    }

    private fun close(sender: ProxyCommandSender, playerName: String?) {
        val player = target(sender, playerName) ?: return
        respond(sender, Rafter.engine.closeCurrent(player)) {
            sender.sendLang("command-close-success", player.name)
        }
    }

    private fun sendPack(sender: ProxyCommandSender, playerName: String?) {
        val player = target(sender, playerName) ?: return
        if (!Rafter.engine.sendPack(player)) sender.sendLang("pack-not-ready")
    }

    private fun <T> respond(
        sender: ProxyCommandSender,
        future: CompletableFuture<T>,
        success: (T) -> Unit,
    ) {
        future.whenComplete { value, error ->
            main {
                attempt(sender) {
                    if (error == null) success(value) else failure(sender, error)
                }
            }
        }
    }

    private inline fun attempt(sender: ProxyCommandSender, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            failure(sender, error)
        }
    }

    private fun failure(sender: ProxyCommandSender, error: Throwable) {
        val cause = unwrap(error)
        if (cause is UiOperationException) sender.sendUiFailure(cause.code)
        else {
            log(cause)
            sender.sendLang("command-failed")
        }
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) unwrap(error.cause!!) else error

    private fun log(error: Throwable) {
        Bukkit.getLogger().log(Level.WARNING, "[Rafter] Command operation failed", unwrap(error))
    }
}
