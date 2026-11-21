package com.hiusers.rafter

import com.github.retrooper.packetevents.PacketEvents
import com.hiusers.rafter.api.RafterService
import com.hiusers.rafter.compile.DefaultBundle
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.runtime.RafterEngine
import com.hiusers.rafter.transport.DialogListener
import com.hiusers.rafter.transport.InputAppearanceController
import org.bukkit.Bukkit
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault as BukkitPermissionDefault
import org.bukkit.plugin.ServicePriority
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor.PlatformTask
import taboolib.module.chat.Components
import taboolib.platform.BukkitPlugin
import taboolib.platform.util.sendLang

/** Bukkit 生命周期入口，命令由独立命令树处理。 */
object Rafter {
    internal lateinit var engine: RafterEngine
        private set

    private var ticker: PlatformTask? = null
    private var startupLoad: PlatformTask? = null
    private var inputAppearance: InputAppearanceController? = null
    private val listener = DialogListener({ if (::engine.isInitialized) engine else null })

    @Awake(LifeCycle.ENABLE)
    fun enable() {
        // 统一使用 TabooLib 的组件后端。
        Components.useAdventure = true
        val projectRoot = getDataFolder().toPath()
        if (MenuRepository.shouldInstallDefaults(projectRoot)) DefaultBundle.install(projectRoot)
        if (Bukkit.getPluginManager().getPermission("rafter.admin") == null) {
            Bukkit.getPluginManager()
                .addPermission(Permission("rafter.admin", BukkitPermissionDefault.OP))
        }
        val appearance = InputAppearanceController(BukkitPlugin.getInstance())
        appearance.start()
        inputAppearance = appearance
        engine =
            RafterEngine(
                BukkitPlugin.getInstance(),
                projectRoot,
                inputAppearance = appearance,
            )
        Bukkit.getServicesManager()
            .register(
                RafterService::class.java,
                engine,
                BukkitPlugin.getInstance(),
                ServicePriority.Normal,
            )
        PacketEvents.getAPI().eventManager.registerListener(listener)
        ticker = submit(period = 20) { engine.tick() }
    }

    @Awake(LifeCycle.ACTIVE)
    fun activate() {
        if (::engine.isInitialized) scheduleStartupLoad(1, retry = true)
    }

    private fun scheduleStartupLoad(delay: Long, retry: Boolean) {
        startupLoad = submit(delay = delay) {
            startupLoad = null
            engine.reload().thenAccept { result ->
                submit report@{
                    if (!BukkitPlugin.getInstance().isEnabled) return@report
                    if (retry && result.candidate == null &&
                        result.diagnostics.isNotEmpty() && result.diagnostics.all { it.code == "PROVIDER" }
                    ) {
                        // 依赖插件可能在首个服务端 tick 注册数据和动作。
                        scheduleStartupLoad(20, retry = false)
                        return@report
                    }
                    val console = Bukkit.getConsoleSender()
                    result.diagnostics.forEach { diagnostic ->
                        console.sendLang(
                            "command-diagnostic",
                            diagnostic.file,
                            diagnostic.path,
                            diagnostic.code,
                            diagnostic.message,
                        )
                    }
                    val candidate = result.candidate
                    if (candidate != null) {
                        console.sendLang(
                            "menu-load-success",
                            candidate.snapshot.menus.size,
                            candidate.assets.images.size,
                        )
                        engine.startHostedPack()
                    } else console.sendLang("menu-load-failed", result.diagnostics.size)
                }
            }
        }
    }

    @SubscribeEvent
    fun join(event: PlayerJoinEvent) {
        submit(delay = 40) {
            if (::engine.isInitialized && event.player.isOnline) engine.packJoin(event.player)
        }
    }

    @SubscribeEvent
    fun packStatus(event: PlayerResourcePackStatusEvent) {
        if (::engine.isInitialized) engine.packStatus(event)
    }

    @SubscribeEvent
    fun quit(event: PlayerQuitEvent) {
        inputAppearance?.forget(event.player.uniqueId)
        if (::engine.isInitialized) engine.forget(event.player.uniqueId)
    }

    @SubscribeEvent
    fun disabled(event: PluginDisableEvent) {
        if (::engine.isInitialized) engine.removeOwner(event.plugin)
    }

    @Awake(LifeCycle.DISABLE)
    fun disable() {
        startupLoad?.cancel()
        startupLoad = null
        ticker?.cancel()
        ticker = null
        val appearance = inputAppearance
        inputAppearance = null
        if (::engine.isInitialized) {
            PacketEvents.getAPI().eventManager.unregisterListener(listener)
            engine.close()
            Bukkit.getServicesManager().unregister(RafterService::class.java, engine)
        }
        appearance?.close()
    }
}
