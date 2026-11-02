package com.hiusers.rafter.pack

import com.hiusers.rafter.model.ProjectPaths
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import taboolib.platform.util.sendLang

/** 管理资源构建与分发。准备和文件 I/O 在后台，发布与玩家状态在主线程；提交成功后接管托管候选。 */
class PackRuntime(
    private val root: Path,
    private val dispatch: (Player, UUID, String, ByteArray, Boolean) -> Unit =
        { player, id, url, hash, required ->
            Player::class
                .java
                .getMethod(
                    "addResourcePack",
                    UUID::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(player, id, url, hash, "Rafter resource pack", required)
        },
) : AutoCloseable {
    val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Rafter-pack-build").apply { isDaemon = true }
    }
    @Volatile private var host: PackHost? = null
    @Volatile private var closed = false
    private val preparing = ConcurrentHashMap.newKeySet<Ready>()
    private var current: Ready? = null
    private var expected: String? = null
    private val pending = mutableMapOf<UUID, UUID>()
    private val loaded = mutableMapOf<UUID, UUID>()

    /** 候选拥有未发布的监听器或缓存；放弃时释放，激活后交由运行时管理。 */
    data class Ready(
        val settings: PackSettings,
        val server: PackHost?,
        val file: PackHost.File?,
        val ownsServer: Boolean,
        val stamp: String,
    ) {
        fun discard() {
            if (ownsServer) server?.close() else file?.let { server?.discard(it) }
        }
    }

    fun discard(ready: Ready) {
        preparing.remove(ready)
        ready.discard()
    }

    fun expect(settings: PackSettings, stamp: String) {
        expected = if (settings.host != null) stamp else null
    }

    /** 后台准备缓存，仅复用地址与端口一致的监听器。 */
    fun prepare(build: PackBuild, settings: PackSettings, stamp: String): Ready {
        check(!closed) { "Pack runtime stopped" }
        val config = settings.host ?: return Ready(settings, null, null, false, stamp)
        val active = host
        val reuse = active != null && active.bind == config.bind && active.port == config.port
        require(!java.nio.file.Files.isSymbolicLink(root.resolve(ProjectPaths.GENERATE))) {
            "Generated directory must not be a symbolic link"
        }
        val next =
            if (reuse) active!!
            else PackHost(config.bind, config.port, root.resolve(ProjectPaths.HOST_CACHE))
        return try {
            Ready(settings, next, next.prepare(build), !reuse, stamp).also { ready ->
                preparing.add(ready)
                if (closed) {
                    discard(ready)
                    error("Pack runtime stopped")
                }
            }
        } catch (e: Throwable) {
            if (!reuse) next.close()
            throw e
        }
    }

    /** 主线程接管已提交候选，关闭旧监听器并按配置分发。 */
    fun activate(ready: Ready, startup: Boolean) {
        check(Bukkit.isPrimaryThread())
        check(!closed)
        preparing.remove(ready)
        val old = host
        ready.file?.let { ready.server!!.activate(it) }
        host = ready.server
        current = ready
        if (old !== host) runCatching { old?.close() }
        val config = ready.settings.host
        if (config != null) {
            Bukkit.getLogger()
                .info(
                    "[Rafter] Pack ready: ${config.publicUrl}/rafter/packs/${ready.file!!.hash}/resource-pack.zip"
                )
        }
        if (config != null && (if (startup) config.join else config.afterBuild)) {
            Bukkit.getOnlinePlayers().forEach { player ->
                runCatching { send(player) }
                    .onFailure {
                        Bukkit.getLogger().warning("[Rafter] Pack send: ${it.message}")
                    }
            }
        }
    }

    fun enabled() = expected != null

    fun join(player: Player) {
        if (current?.settings?.host?.join == true) send(player)
    }

    fun canOpen(player: Player): Boolean =
        !enabled() ||
            (current?.stamp == expected &&
                current?.file?.id != null &&
                current?.file?.id == loaded[player.uniqueId])

    fun send(player: Player): Boolean {
        check(Bukkit.isPrimaryThread())
        val ready = current?.takeIf { it.stamp == expected } ?: return false
        val config = ready.settings.host ?: return false
        val pack = ready.file ?: return false
        if (!player.isOnline) return false
        if (pending[player.uniqueId] == pack.id || loaded[player.uniqueId] == pack.id) return true
        pending.remove(player.uniqueId)?.let {
            if (it != loaded[player.uniqueId]) player.removeResourcePack(it)
        }
        val url = config.publicUrl + "/rafter/packs/${pack.hash}/resource-pack.zip"
        dispatch(player, pack.id, url, pack.sha1.copyOf(), config.required)
        pending[player.uniqueId] = pack.id
        return true
    }

    fun status(event: PlayerResourcePackStatusEvent) {
        val id = event.getID()
        val player = event.player
        if (pending[player.uniqueId] != id) {
            if (event.status.name == "DISCARDED" && loaded[player.uniqueId] == id) {
                loaded.remove(player.uniqueId)
            }
            return
        }
        when (event.status.name) {
            "SUCCESSFULLY_LOADED" -> {
                pending.remove(player.uniqueId)
                loaded
                    .put(player.uniqueId, id)
                    ?.takeIf { it != id }
                    ?.let(player::removeResourcePack)
                player.sendLang("pack-load-success")
            }
            "DECLINED",
            "FAILED_DOWNLOAD",
            "INVALID_URL",
            "FAILED_RELOAD",
            "DISCARDED" -> {
                pending.remove(player.uniqueId)
                player.removeResourcePack(id)
                player.sendLang(
                    "pack-load-failed-${event.status.name.lowercase(java.util.Locale.ROOT).replace('_', '-')}"
                )
                Bukkit.getLogger().warning("[Rafter] ${player.name}: ${event.status.name}")
            }
        }
    }

    fun quit(id: UUID) {
        pending.remove(id)
        loaded.remove(id)
    }

    /** 先停止接收工作，再释放资源；迟到候选自行回收。 */
    override fun close() {
        closed = true
        preparing.toList().forEach(::discard)
        preparing.clear()
        (pending.keys + loaded.keys).forEach { id ->
            Bukkit.getPlayer(id)?.let { player ->
                listOfNotNull(pending[id], loaded[id]).distinct().forEach { pack ->
                    runCatching { player.removeResourcePack(pack) }
                }
            }
        }
        host?.close()
        host = null
        current = null
        worker.shutdownNow()
        pending.clear()
        loaded.clear()
    }
}
