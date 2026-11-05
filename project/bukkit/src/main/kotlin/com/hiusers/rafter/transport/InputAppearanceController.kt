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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.floor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/** Experimental native-input appearance marker; inactive players receive no altered time packets. */
internal class InputAppearanceController(private val plugin: Plugin) :
    PacketListenerAbstract(),
    AutoCloseable {
    private companion object {
        const val PROTOCOL_26_2 = 776
        const val MARKER_UNIT = 256L
        const val LEASE_NANOS = 60_000_000_000L
        const val REFRESH_TICKS = 5L
    }

    private data class ClockSample(
        val serverAge: Long,
        val state: ClockNetworkState,
    )

    private data class ActiveAppearance(
        val appearanceId: Int,
        val generation: Long,
        val deadlineNanos: Long,
    )

    private val active = ConcurrentHashMap<UUID, ActiveAppearance>()
    private val generations = ConcurrentHashMap<UUID, Long>()
    private val pendingRestores = ConcurrentHashMap<UUID, Long>()
    private val deferredForgets = ConcurrentHashMap.newKeySet<UUID>()
    private val clockSamples = ConcurrentHashMap<UUID, ConcurrentHashMap<WorldClock, ClockSample>>()
    private val stateLock = Any()

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
            PacketEvents.getAPI().playerManager.getClientVersion(player).protocolVersion ==
                PROTOCOL_26_2
        }.getOrDefault(false)

    fun show(player: Player, layoutId: Int) {
        require(layoutId in 0..64) { "Input appearance id must be 0..64" }
        if (layoutId == 0) {
            clear(player.uniqueId)
            return
        }
        check(started && !closed) { "Input appearance controller is not running" }
        check(supports(player)) { "Input appearance requires protocol $PROTOCOL_26_2" }
        check(!clockSamples[player.uniqueId].isNullOrEmpty()) {
            "Input appearance requires a normal TIME_UPDATE clock baseline"
        }
        val generation = activate(player.uniqueId, layoutId)
        if (!sendWorldAge(player, marker(layoutId), generation)) {
            throw IllegalStateException("Failed to send input appearance marker")
        }
    }

    /** Extends an existing marker lease without reviving a marker cleared by a packet. */
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
            nextGenerationLocked(id)
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
        val ids = (active.keys + pendingRestores.keys).distinct()
        ids.forEach { id ->
            synchronized(stateLock) {
                active.remove(id)
                nextGenerationLocked(id)
            }
            val player = Bukkit.getPlayer(id)
            if (player != null && player.isOnline) sendNormalWorldAge(player)
        }
        active.clear()
        pendingRestores.clear()
        deferredForgets.clear()
        clockSamples.clear()
        generations.clear()
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val id = event.user.uuid ?: return
        if (!isProtocol26_2(event.user.clientVersion)) return
        try {
            when {
                event.packetType == PacketType.Play.Server.TIME_UPDATE -> {
                    val wrapper = WrapperPlayServerTimeUpdate(event)
                    val originalAge = wrapper.worldAge
                    if (originalAge >= 0L) {
                        cacheClocks(id, originalAge, wrapper.clockUpdates)
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
                isServerResetPacket(event.packetType) -> clear(id)
            }
        } catch (error: Throwable) {
            logError("Failed to rewrite TIME_UPDATE for $id", error)
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
            if (
                deferredForgets.remove(id) &&
                    active[id] == null &&
                    generations[id] == generation
            ) {
                clockSamples.remove(id)
            }
        }
    }

    private fun activate(id: UUID, appearanceId: Int): Long =
        synchronized(stateLock) {
            pendingRestores.remove(id)
            deferredForgets.remove(id)
            val generation = nextGenerationLocked(id)
            active[id] =
                ActiveAppearance(
                    appearanceId,
                    generation,
                    System.nanoTime() + LEASE_NANOS,
                )
            generation
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

    private fun sendWorldAge(player: Player, worldAge: Long, expectedGeneration: Long? = null): Boolean {
        return try {
            val projected =
                projectClocks(player.uniqueId, player.world.gameTime)
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
            samples[clock] = ClockSample(serverAge, state)
        }
    }

    private fun projectClocks(id: UUID, actualAge: Long): Map<WorldClock, ClockNetworkState>? {
        val samples = clockSamples[id].takeUnless { it.isNullOrEmpty() } ?: return null
        val projected = HashMap<WorldClock, ClockNetworkState>(samples.size)
        for ((clock, sample) in samples) {
            val state = sample.state
            val rate = state.rate.toDouble()
            val delta = (actualAge.toDouble() - sample.serverAge.toDouble()) * rate + state.partialTick
            if (!rate.isFinite() || !delta.isFinite()) {
                throw IllegalStateException("Non-finite clock projection for $id clock=$clock")
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

    private fun nextGenerationLocked(id: UUID): Long {
        val next = (generations[id] ?: 0L) + 1L
        generations[id] = next
        return next
    }

    private fun isProtocol26_2(version: ClientVersion): Boolean =
        version.protocolVersion == PROTOCOL_26_2

    private fun marker(appearanceId: Int): Long = -MARKER_UNIT * appearanceId

    private fun isServerResetPacket(packetType: Any): Boolean =
        packetType == PacketType.Play.Server.SHOW_DIALOG ||
            packetType == PacketType.Play.Server.CLEAR_DIALOG ||
            packetType == PacketType.Play.Server.OPEN_WINDOW ||
            packetType == PacketType.Play.Server.RESPAWN

    private fun logError(message: String, error: Throwable) {
        plugin.logger.log(Level.SEVERE, message, error)
    }
}
