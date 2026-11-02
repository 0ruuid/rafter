package com.hiusers.rafter.runtime

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.compile.LoadResult
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.ProjectPaths
import com.hiusers.rafter.pack.PackBuild
import com.hiusers.rafter.pack.PackRuntime
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import taboolib.module.kether.parseKetherScript

/** 管理候选与资源生命周期，不持有会话。发现和构建在后台，检查与发布在主线程；代数隔离迟到结果。 */
internal class ResourceCoordinator(
    private val root: Path,
    private val logger: Logger,
    extensionCheck: (String, String, Map<String, Any?>) -> Unit,
    private val onInstall: () -> Unit,
) : AutoCloseable {
    private val repository =
        MenuRepository(
            root,
            { source ->
                val wrapped =
                    if (source.trimStart().startsWith("def ")) source else "def main = { $source }"
                wrapped.parseKetherScript(listOf("rafter"))
            },
            extensionCheck,
        )
    private val packs = PackRuntime(root)
    private val packEpoch = AtomicLong()
    private var packJob: CompletableFuture<Path>? = null
    private val loads = mutableSetOf<CompletableFuture<LoadResult>>()

    @Volatile private var stopped = false

    var candidate: Candidate? = null
        private set

    private data class PreparedPack(
        val candidate: Candidate,
        val build: PackBuild,
        val ready: PackRuntime.Ready,
    )

    fun reload(): CompletableFuture<LoadResult> {
        cancelPack()
        return loadCandidate()
    }

    private fun loadCandidate(): CompletableFuture<LoadResult> {
        check(Bukkit.isPrimaryThread())
        check(!stopped)
        val token = packEpoch.get()
        val previous =
            try {
                allocationForCandidate()
            } catch (failure: Exception) {
                val diagnostic =
                    Diagnostic(
                        ProjectPaths.ALLOCATION,
                        "$",
                        "LEDGER",
                        failure.message ?: "Invalid allocation ledger",
                    )
                return CompletableFuture.completedFuture(LoadResult(null, listOf(diagnostic)))
            }
        val result = CompletableFuture<LoadResult>()
        loads += result
        CompletableFuture.supplyAsync(
                {
                    check(!stopped && token == packEpoch.get()) { "Stale asset discovery" }
                    repository.assets(previous)
                },
                packs.worker,
            )
            .thenCompose { assets ->
                main<LoadResult> {
                    check(!stopped && token == packEpoch.get()) { "Stale asset discovery" }
                    repository.load(previous, assets).also { loaded ->
                        loaded.candidate?.let(::install)
                    }
                }
            }
            .whenComplete { value, error ->
                main {
                    loads.remove(result)
                    val failure = error?.let(::unwrap)
                    val diagnostic =
                        if (failure is DocumentError) {
                            failure.diagnostic
                        } else {
                            Diagnostic(
                                "project",
                                "$",
                                "LOAD",
                                failure?.message ?: "Asset discovery failed",
                            )
                        }
                    result.complete(value ?: LoadResult(null, listOf(diagnostic)))
                }
            }
        return result
    }

    /** 先发布候选，再关闭旧会话，避免延续读取旧快照。 */
    fun install(next: Candidate) {
        check(Bukkit.isPrimaryThread())
        check(!stopped)
        candidate = next
        packEpoch.incrementAndGet()
        packs.expect(next.pack, next.assets.fingerprint)
        onInstall()
    }

    private fun allocationForCandidate(): Map<String, Int> {
        val disk =
            try {
                AssetCompiler.readAllocation(root)
            } catch (failure: Exception) {
                throw IllegalArgumentException(
                    "Invalid allocation ledger; refusing to remap glyphs",
                    failure,
                )
            }
        val live = candidate?.assets?.allocation.orEmpty()
        require(live.all { (id, code) -> id !in disk || disk[id] == code }) {
            "Allocation ledger conflicts with active snapshot"
        }
        return live + disk
    }

    fun pack(startup: Boolean = false): CompletableFuture<Path> {
        check(Bukkit.isPrimaryThread())
        check(!stopped)
        packJob?.let {
            return it
        }
        val result = CompletableFuture<Path>()
        packJob = result
        val token = packEpoch.incrementAndGet()
        val previous =
            try {
                allocationForCandidate()
            } catch (failure: Exception) {
                packJob = null
                result.completeExceptionally(failure)
                return result
            }
        CompletableFuture.supplyAsync(
                {
                    check(!stopped && packEpoch.get() == token)
                    repository.assets(previous)
                },
                packs.worker,
            )
            .thenCompose { assets ->
                main<Candidate> {
                    check(!stopped && packEpoch.get() == token) { "Stale pack build" }
                    val loaded = repository.load(previous, assets)
                    loaded.candidate ?: error(loaded.diagnostics.joinToString("\n"))
                }
            }
            .thenApplyAsync(
                { next ->
                    preparePack(next, token)
                },
                packs.worker,
            )
            .thenCompose { prepared ->
                publishPack(prepared, token, startup)
            }
            .whenComplete { path, failure ->
                main {
                    // 旧完成回调不能清空新构建的 Future。
                    if (packJob === result) packJob = null
                    if (failure == null) result.complete(path)
                    else result.completeExceptionally(unwrap(failure))
                }
            }
        return result
    }

    private fun preparePack(next: Candidate, token: Long): PreparedPack {
        check(!stopped && packEpoch.get() == token) { "Stale pack build" }
        val build = PackBuild.prepare(root, next.assets, next.pack)
        return try {
            PreparedPack(next, build, packs.prepare(build, next.pack, next.assets.fingerprint))
        } catch (failure: Throwable) {
            build.close()
            throw failure
        }
    }

    /** 先提交文件和候选，再激活托管；已发布内容不因后续失败回滚。 */
    private fun publishPack(
        prepared: PreparedPack,
        token: Long,
        startup: Boolean,
    ): CompletableFuture<Path> =
        main<Path> {
                check(!stopped && packEpoch.get() == token) { "Stale pack build" }
                val path = prepared.build.commit(root)
                install(prepared.candidate)
                packs.activate(prepared.ready, startup)
                path
            }
            .whenComplete { _, failure ->
                if (failure != null) packs.discard(prepared.ready)
                runCatching { packs.worker.execute { prepared.build.close() } }
            }

    fun cancelPack() {
        packEpoch.incrementAndGet()
        packJob?.completeExceptionally(CancellationException("Pack build superseded"))
        packJob = null
    }

    fun startHostedPack() {
        if (candidate?.pack?.host != null) {
            pack(startup = true).whenComplete { _, failure ->
                if (failure != null) logger.warning("Hosted pack: ${unwrap(failure).message}")
            }
        }
    }

    /** 需要时请求目标资源包，本次打开仍返回加载中。 */
    fun ensurePackReady(player: Player): Boolean {
        if (packs.canOpen(player)) return true
        if (!packs.send(player) && packJob == null) pack()
        return false
    }

    fun sendPack(player: Player): Boolean = packs.send(player)

    fun packJoin(player: Player) {
        if (!stopped) packs.join(player)
    }

    fun packStatus(event: PlayerResourcePackStatusEvent) {
        main {
            if (!stopped) packs.status(event)
        }
    }

    fun forget(id: UUID) {
        packs.quit(id)
    }

    /** 先停止接收工作，再完成可能重入的 Future。 */
    override fun close() {
        stopped = true
        cancelPack()
        loads.toList().forEach { it.completeExceptionally(CancellationException("Rafter stopped")) }
        loads.clear()
        packs.close()
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) unwrap(error.cause!!) else error
}
