package com.hiusers.rafter.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按摘要托管不可变 ZIP，实例拥有监听器、线程和缓存。
 * 后台准备，提交后激活；读取计数受锁保护，所有者负责关闭。
 */
class PackHost(val bind: String, val port: Int, private val storage: Path) : AutoCloseable {
    data class File(
        val path: Path,
        val hash: String,
        val sha1: ByteArray,
        val id: UUID,
        var readers: Int = 0,
    )

    private val lock = Any()
    private val files = linkedMapOf<String, File>()
    private val sequence = AtomicInteger()
    private val workers =
        ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(16),
            { runnable ->
                Thread(runnable, "Rafter-pack-http-${sequence.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val cache = run {
        require(!Files.isSymbolicLink(storage))
        Files.createDirectories(storage)
        Files.createTempDirectory(storage, "listener-")
    }
    private val server =
        try {
            HttpServer.create(InetSocketAddress(bind, port), 16).apply {
                executor = workers
                createContext("/rafter/packs/", ::servePack)
                start()
            }
        } catch (e: Throwable) {
            workers.shutdownNow()
            cache.toFile().deleteRecursively()
            throw e
        }

    val localPort
        get() = server.address.port

    /** 仅复制到私有缓存，放弃时使用 [discard]，不删除构建产物。 */
    fun prepare(build: PackBuild): File {
        val source = requireNotNull(build.archive)
        val dir = cache.resolve(build.hash)
        require(!Files.isSymbolicLink(dir))
        Files.createDirectories(dir)
        val target = dir.resolve("resource-pack.zip")
        require(!Files.isSymbolicLink(target))
        val existing = if (Files.isRegularFile(target)) hashFile(target) else null
        if (existing != build.hash) {
            val temp = Files.createTempFile(dir, ".pack-", ".tmp")
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temp)
            }
        }
        return File(target, build.hash, build.sha1.copyOf(), build.id)
    }

    /** 登记已提交候选；同摘要复用条目，保留进行中的读取计数。 */
    fun activate(file: File) {
        synchronized(lock) {
            val old = files.remove(file.hash)
            files[file.hash] = old ?: file
            trimInactiveFiles()
        }
    }

    fun discard(file: File) {
        synchronized(lock) {
            if (file.hash !in files) {
                runCatching {
                    Files.deleteIfExists(file.path)
                    Files.deleteIfExists(file.path.parent)
                }
            }
        }
    }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trimInactiveFiles() {
        while (files.size > 3) {
            // 保留传输中和最后发布的文件，避免截断响应。
            val entry =
                files.entries.firstOrNull {
                    it.value.readers == 0 && it.key != files.keys.last()
                } ?: break
            files.remove(entry.key)
            runCatching {
                Files.deleteIfExists(entry.value.path)
                Files.deleteIfExists(entry.value.path.parent)
            }
        }
    }

    private fun servePack(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod !in setOf("GET", "HEAD")) {
                exchange.sendResponseHeaders(405, -1)
                return
            }
            val match =
                Regex("/rafter/packs/([0-9a-f]{64})/resource-pack\\.zip")
                    .matchEntire(exchange.requestURI.rawPath)
            val file =
                synchronized(lock) {
                    match?.groupValues?.get(1)?.let(files::get)?.also { it.readers++ }
                }
            if (file == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            try {
                val etag = "\"${file.hash}\""
                exchange.responseHeaders.set("Content-Type", "application/zip")
                exchange.responseHeaders.set("Cache-Control", "public, max-age=31536000, immutable")
                exchange.responseHeaders.set("ETag", etag)
                val unchanged =
                    exchange.requestHeaders["If-None-Match"]?.any { header ->
                        header.split(',').any { tag ->
                            val value = tag.trim()
                            value == "*" || value.removePrefix("W/") == etag
                        }
                    } == true
                if (unchanged) {
                    exchange.sendResponseHeaders(304, -1)
                    return
                }
                val size = Files.size(file.path)
                exchange.responseHeaders.set("Content-Length", size.toString())
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    Files.newInputStream(file.path).use { input ->
                        exchange.sendResponseHeaders(200, size)
                        exchange.responseBody.use { input.copyTo(it) }
                    }
                }
            } finally {
                synchronized(lock) {
                    file.readers--
                    trimInactiveFiles()
                }
            }
        } finally {
            exchange.close()
        }
    }

    /** 停止监听与线程后释放自有缓存，不删除其他实例文件。 */
    override fun close() {
        server.stop(0)
        workers.shutdownNow()
        synchronized(lock) { files.clear() }
        cache.toFile().deleteRecursively()
    }
}
