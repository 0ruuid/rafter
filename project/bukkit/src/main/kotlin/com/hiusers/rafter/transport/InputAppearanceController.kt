package com.hiusers.rafter.transport

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.world.clock.ClockNetworkState
import com.github.retrooper.packetevents.protocol.world.clock.WorldClock
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTimeUpdate
import com.hiusers.rafter.asset.ClientRenderProfiles
import com.hiusers.rafter.asset.InputAppearances
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.floor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

internal data class InputAppearanceClockSample(
    val serverAge: Long,
    val state: ClockNetworkState,
)

internal fun supportsInputAppearanceProtocol(protocol: Int): Boolean =
    ClientRenderProfiles.forProtocol(protocol) != null

internal fun projectInputAppearanceClocks(
    samples: Map<WorldClock, InputAppearanceClockSample>,
    actualAge: Long,
): Map<WorldClock, ClockNetworkState>? {
    val usableSamples = samples.takeUnless { it.isEmpty() } ?: return null
    val projected = HashMap<WorldClock, ClockNetworkState>(usableSamples.size)
    for ((clock, sample) in usableSamples) {
        val state = sample.state
        val rate = state.rate.toDouble()
        val delta = (actualAge.toDouble() - sample.serverAge.toDouble()) * rate + state.partialTick
        if (!rate.isFinite() || !delta.isFinite()) {
            throw IllegalStateException("Non-finite clock projection for clock=$clock")
        }
        val floorDelta = floor(delta).toLong()
        projected[clock] =
            ClockNetworkState(
                state.totalTicks + floorDelta,
                (delta - floorDelta).toFloat(),
                state.rate,
            )
    }
    return projected
}

/**
 * 用观看者客户端的 GameTime 选择输入布局，单独外推正常世界时钟。
 * 刷新标记可能使该客户端其他依赖 GameTime 的动画重新开始。
 * 未启用输入外观的玩家不接收改写后的时间包。
 */
internal class InputAppearanceController(private val plugin: Plugin) :
    PacketListenerAbstract(),
    AutoCloseable {
    private companion object {
        const val LEASE_NANOS = 60_000_000_000L
        const val REFRESH_TICKS = 5L
    }

    private data class ActiveAppearance(
        val appearanceId: Int,
        val generation: Long,
        val deadlineNanos: Long,
        val worldUid: UUID,
    )

    private data class RespawnRestore(
        val id: UUID,
        val generation: Long,
        val worldUid: UUID,
        val samples: Map<WorldClock, InputAppearanceClockSample>,
    )

    private val active = ConcurrentHashMap<UUID, ActiveAppearance>()
    private val generations = ConcurrentHashMap<UUID, Long>()
    private val pendingRestores = ConcurrentHashMap<UUID, Long>()
    private val respawnRestores = ConcurrentHashMap<UUID, RespawnRestore>()
    private val deferredForgets = ConcurrentHashMap.newKeySet<UUID>()
    private val clockSamples =
        ConcurrentHashMap<UUID, ConcurrentHashMap<WorldClock, InputAppearanceClockSample>>()
    private val stateLock = Any()
    // 由 stateLock 保护，全局递增避免清理玩家记录后重连复用旧代数。
    private var generationCounter = 0L

    @Volatile private var started = false
    @Volatile private var closed = false
    private var markerTask: BukkitTask? = null

    @Synchronized
    fun start() {
        if (started) return
        closed = false
        PacketEvents.getAPI().eventManager.registerListener(this)
        markerTask =
            Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable { refreshMarkers() },
                0L,
                REFRESH_TICKS,
            )
        started = true
    }

    fun supports(player: Player): Boolean =
        runCatching {
            supportsInputAppearanceProtocol(
                PacketEvents.getAPI().playerManager.getClientVersion(player).protocolVersion
            )
        }.getOrDefault(false)

    fun show(player: Player, layoutId: Int) {
        require(layoutId in 0..InputAppearances.MAX_LAYOUTS) {
            "Input appearance id must be 0..${InputAppearances.MAX_LAYOUTS}"
        }
        if (layoutId == 0) {
            clear(player.uniqueId)
            return
        }
        check(started && !closed) { "Input appearance controller is not running" }
        check(supports(player)) { "Input appearance requires a supported client profile" }
        check(!clockSamples[player.uniqueId].isNullOrEmpty()) {
            "Input appearance requires a normal TIME_UPDATE clock baseline"
        }
        val generation = activate(player.uniqueId, layoutId, player.world.uid)
        if (!sendWorldAge(player, marker(layoutId), generation)) {
            throw IllegalStateException("Failed to send input appearance marker")
        }
    }

    /** 延长已有标记租约，协议事件清理后不重新激活。 */
    fun keepAlive(id: UUID) {
        if (closed) return
        synchronized(stateLock) {
            val current = active[id] ?: return
            active[id] = current.copy(deadlineNanos = System.nanoTime() + LEASE_NANOS)
        }
    }

    fun clear(id: UUID) {
        val generation = clearActive(id) ?: return
        if (closed) return
        if (Bukkit.isPrimaryThread()) restore(id, generation) else dispatchRestore(id, generation)
    }

    fun forget(id: UUID) {
        synchronized(stateLock) {
            active.remove(id)
            if (pendingRestores.containsKey(id)) {
                deferredForgets += id
                return
            }
            clockSamples.remove(id)
            generations.remove(id)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        markerTask?.cancel()
        markerTask = null
        if (started) {
            PacketEvents.getAPI().eventManager.unregisterListener(this)
            started = false
        }
        val respawnRecords = synchronized(stateLock) { respawnRestores.toMap() }
        val ids = (active.keys + pendingRestores.keys + respawnRecords.keys).distinct()
        ids.forEach { id ->
            synchronized(stateLock) {
                active.remove(id)
                nextGenerationLocked(id)
            }
            val player = Bukkit.getPlayer(id)
            if (player != null && player.isOnline) {
                val respawnRestore = respawnRecords[id]
                if (respawnRestore == null) sendNormalWorldAge(player)
                else sendCloseRespawnRestore(player, respawnRestore)
            }
        }
        active.clear()
        pendingRestores.clear()
        respawnRestores.clear()
        deferredForgets.clear()
        clockSamples.clear()
        generations.clear()
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val id = event.user.uuid ?: return
        try {
            when {
                event.packetType == PacketType.Play.Server.RESPAWN -> resetForRespawn(id)
                event.packetType == PacketType.Play.Server.TIME_UPDATE -> {
                    if (!isSupported(event.user.clientVersion)) return
                    val wrapper = WrapperPlayServerTimeUpdate(event)
                    val originalAge = wrapper.worldAge
                    if (originalAge >= 0L) {
                        cacheClocks(id, originalAge, wrapper.clockUpdates)
                        cancelRespawnRestoreOnTimeUpdate(id)
                    }
                    val appearance = active[id] ?: return
                    if (originalAge < 0L) return
                    val projected =
                        projectClocks(id, originalAge)
                            ?: run {
                                clear(id)
                                return
                            }
                    wrapper.worldAge = marker(appearance.appearanceId)
                    wrapper.clockUpdates = projected
                    event.markForReEncode(true)
                }
                isClearResetPacket(event.packetType) -> {
                    if (!isSupported(event.user.clientVersion)) return
                    clear(id)
                }
            }
        } catch (error: Throwable) {
            logError("Failed to update input appearance for $id", error)
            clear(id)
        }
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (
            event.packetType != PacketType.Play.Client.CUSTOM_CLICK_ACTION &&
                event.packetType != PacketType.Play.Client.CLOSE_WINDOW
        ) {
            return
        }
        event.user.uuid?.let(::clear)
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        event.user.uuid?.let(::forget)
    }

    private fun refreshMarkers() {
        if (closed) return
        val now = System.nanoTime()
        for ((id, appearance) in active) {
            val player = Bukkit.getPlayer(id)
            if (player == null || !player.isOnline) {
                forget(id)
                continue
            }
            if (now >= appearance.deadlineNanos) {
                expire(id, appearance.generation)
                continue
            }
            sendWorldAge(player, marker(appearance.appearanceId), appearance.generation)
        }
    }

    private fun expire(id: UUID, generation: Long) {
        val removed =
            synchronized(stateLock) {
                val current = active[id]
                if (
                    current == null ||
                        current.generation != generation ||
                        System.nanoTime() < current.deadlineNanos
                ) {
                    return
                }
                active.remove(id)
                nextGenerationLocked(id)
                true
            }
        if (!removed || closed) return
        val player = Bukkit.getPlayer(id)
        if (player != null && player.isOnline) sendNormalWorldAge(player)
    }

    private fun dispatchRestore(id: UUID, generation: Long) {
        synchronized(stateLock) {
            if (closed || active[id] != null || generations[id] != generation) return
            pendingRestores[id] = generation
        }
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                restore(id, generation)
            })
        } catch (error: Throwable) {
            logError("Failed to schedule input appearance restore for $id", error)
            finishRestore(id, generation)
        }
    }

    private fun restore(id: UUID, generation: Long) {
        val shouldRestore =
            synchronized(stateLock) {
                !closed &&
                    active[id] == null &&
                    generations[id] == generation &&
                    (pendingRestores[id] == generation || Bukkit.isPrimaryThread())
            }
        if (!shouldRestore) {
            finishRestore(id, generation)
            return
        }
        val player = Bukkit.getPlayer(id)
        if (player != null && player.isOnline) sendNormalWorldAge(player)
        finishRestore(id, generation)
    }

    private fun finishRestore(id: UUID, generation: Long) {
        synchronized(stateLock) {
            pendingRestores.remove(id, generation)
            if (respawnRestores[id]?.generation == generation) respawnRestores.remove(id)
            if (
                active[id] == null &&
                    generations[id] == generation &&
                    deferredForgets.remove(id)
            ) {
                clockSamples.remove(id)
                generations.remove(id, generation)
            }
        }
    }

    /**
     * 同维度死亡可能复用客户端世界。先分离旧时钟基线，
     * 主线程任务仅在玩家仍处于原世界时恢复。
     */
    private fun resetForRespawn(id: UUID) {
        val restore =
            synchronized(stateLock) {
                val appearance = active.remove(id)
                val detachedSamples = clockSamples.remove(id)
                pendingRestores.remove(id)
                respawnRestores.remove(id)
                deferredForgets.remove(id)
                val generation = nextGenerationLocked(id)
                if (appearance == null || detachedSamples == null || detachedSamples.isEmpty()) {
                    null
                } else {
                    pendingRestores[id] = generation
                    val restore = RespawnRestore(
                        id,
                        generation,
                        appearance.worldUid,
                        HashMap(detachedSamples),
                    )
                    respawnRestores[id] = restore
                    restore
                }
            }
        if (restore != null) dispatchRespawnRestore(restore)
    }

    private fun dispatchRespawnRestore(restore: RespawnRestore) {
        if (closed) {
            finishRestore(restore.id, restore.generation)
            return
        }
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable { restoreAfterRespawn(restore) })
        } catch (error: Throwable) {
            logError("Failed to schedule input appearance respawn restore for ${restore.id}", error)
            finishRestore(restore.id, restore.generation)
        }
    }

    private fun restoreAfterRespawn(restore: RespawnRestore) {
        val shouldRestore =
            synchronized(stateLock) {
                !closed &&
                    active[restore.id] == null &&
                    generations[restore.id] == restore.generation &&
                    pendingRestores[restore.id] == restore.generation &&
                    respawnRestores[restore.id]?.generation == restore.generation
            }
        if (!shouldRestore) {
            finishRestore(restore.id, restore.generation)
            return
        }
        val player = Bukkit.getPlayer(restore.id)
        if (
            player == null ||
                !player.isOnline ||
                player.world.uid != restore.worldUid
        ) {
            finishRestore(restore.id, restore.generation)
            return
        }
        val shouldSendDetached =
            synchronized(stateLock) {
                generations[restore.id] == restore.generation &&
                    pendingRestores[restore.id] == restore.generation &&
                    respawnRestores[restore.id]?.generation == restore.generation &&
                    clockSamples[restore.id].isNullOrEmpty()
            }
        if (shouldSendDetached) {
            sendWorldAge(player, player.world.gameTime, restore.samples)
        }
        finishRestore(restore.id, restore.generation)
    }

    private fun cancelRespawnRestoreOnTimeUpdate(id: UUID) {
        synchronized(stateLock) {
            val restore = respawnRestores.remove(id) ?: return
            pendingRestores.remove(id, restore.generation)
            if (generations[id] == restore.generation) nextGenerationLocked(id)
        }
    }

    private fun activate(id: UUID, appearanceId: Int, worldUid: UUID): Long =
        synchronized(stateLock) {
            pendingRestores.remove(id)
            respawnRestores.remove(id)
            deferredForgets.remove(id)
            val generation = nextGenerationLocked(id)
            active[id] =
                ActiveAppearance(
                    appearanceId,
                    generation,
                    System.nanoTime() + LEASE_NANOS,
                    worldUid,
                )
            generation
        }

    private fun sendCloseRespawnRestore(player: Player, restore: RespawnRestore) {
        val samples =
            synchronized(stateLock) {
                clockSamples[player.uniqueId]?.takeUnless { it.isNullOrEmpty() }?.let(::HashMap)
                    ?: if (player.world.uid == restore.worldUid) restore.samples else null
            }
                ?: return
        sendWorldAge(player, player.world.gameTime, samples)
    }

    private fun clearActive(id: UUID): Long? =
        synchronized(stateLock) {
            if (active.remove(id) == null) null else nextGenerationLocked(id)
        }

    private fun clearActiveIfGeneration(id: UUID, generation: Long) {
        synchronized(stateLock) {
            if (active[id]?.generation == generation) {
                active.remove(id)
                nextGenerationLocked(id)
            }
        }
    }

    private fun sendNormalWorldAge(player: Player) {
        sendWorldAge(player, player.world.gameTime)
    }

    private fun sendWorldAge(
        player: Player,
        worldAge: Long,
        expectedGeneration: Long? = null,
    ): Boolean =
        sendWorldAge(
            player,
            worldAge,
            clockSamples[player.uniqueId].orEmpty(),
            expectedGeneration,
        )

    private fun sendWorldAge(
        player: Player,
        worldAge: Long,
        samples: Map<WorldClock, InputAppearanceClockSample>,
        expectedGeneration: Long? = null,
    ): Boolean {
        return try {
            val projected =
                projectInputAppearanceClocks(samples, player.world.gameTime)
                    ?: throw IllegalStateException("No cached TIME_UPDATE clock baseline")
            PacketEvents.getAPI()
                .playerManager
                .sendPacket(player, WrapperPlayServerTimeUpdate(worldAge, projected))
            true
        } catch (error: Throwable) {
            logError("Failed to send input appearance time update to ${player.uniqueId}", error)
            expectedGeneration?.let { clearActiveIfGeneration(player.uniqueId, it) }
            false
        }
    }

    private fun cacheClocks(
        id: UUID,
        serverAge: Long,
        updates: Map<WorldClock, ClockNetworkState>,
    ) {
        if (serverAge < 0L || updates.isEmpty()) return
        val samples = clockSamples.computeIfAbsent(id) { ConcurrentHashMap() }
        updates.forEach { (clock, state) ->
            samples[clock] = InputAppearanceClockSample(serverAge, state)
        }
    }

    private fun projectClocks(id: UUID, actualAge: Long): Map<WorldClock, ClockNetworkState>? {
        return projectInputAppearanceClocks(clockSamples[id].orEmpty(), actualAge)
    }

    private fun nextGenerationLocked(id: UUID): Long {
        val next = ++generationCounter
        generations[id] = next
        return next
    }

    private fun isSupported(version: ClientVersion): Boolean =
        supportsInputAppearanceProtocol(version.protocolVersion)

    private fun marker(appearanceId: Int): Long =
        -InputAppearances.MARKER_UNIT_TICKS.toLong() * appearanceId

    private fun isClearResetPacket(packetType: Any): Boolean =
        packetType == PacketType.Play.Server.SHOW_DIALOG ||
            packetType == PacketType.Play.Server.CLEAR_DIALOG ||
            packetType == PacketType.Play.Server.OPEN_WINDOW

    private fun logError(message: String, error: Throwable) {
        plugin.logger.log(Level.SEVERE, message, error)
    }
}
