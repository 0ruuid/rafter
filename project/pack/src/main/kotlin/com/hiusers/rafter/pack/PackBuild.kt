package com.hiusers.rafter.pack

import com.hiusers.rafter.asset.AssetCatalog
import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.model.ProjectPaths
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 管理一次临时构建。准备在后台，提交由主线程串行执行；放弃后释放，恢复失败时保留现场。 */
class PackBuild
private constructor(
    val work: Path,
    val folder: Path,
    val archive: Path?,
    val sha1: ByteArray,
    val hash: String,
) : AutoCloseable {
    val id: UUID = UUID.nameUUIDFromBytes(("rafter:" + hash).toByteArray())
    private var committed = false
    private var recoveryRequired = false

    /** 目录与 ZIP 整组提交，失败时恢复旧产物。 */
    fun commit(root: Path): Path {
        check(!committed) { "Pack already committed" }
        require(!Files.isSymbolicLink(root.resolve(ProjectPaths.GENERATE)))
        Files.createDirectories(root.resolve(ProjectPaths.GENERATE))
        val out = root.resolve(ProjectPaths.PACK)
        val zip = root.resolve(ProjectPaths.ARCHIVE)
        require(!Files.isSymbolicLink(out) && !Files.isSymbolicLink(zip))
        val old = work.resolve("old-folder")
        val oldZip = work.resolve("old.zip")
        var moved = false
        var movedZip = false
        var placed = false
        var placedZip = false
        try {
            if (Files.exists(out)) {
                Files.move(out, old, StandardCopyOption.ATOMIC_MOVE)
                moved = true
            }
            if (Files.exists(zip)) {
                Files.move(zip, oldZip, StandardCopyOption.ATOMIC_MOVE)
                movedZip = true
            }
            Files.move(folder, out, StandardCopyOption.ATOMIC_MOVE)
            placed = true
            if (archive != null) {
                Files.move(archive, zip, StandardCopyOption.ATOMIC_MOVE)
                placedZip = true
            }
            committed = true
            return out
        } catch (e: Exception) {
            try {
                if (placedZip)
                    Files.move(zip, work.resolve("failed.zip"), StandardCopyOption.ATOMIC_MOVE)
                if (placed)
                    Files.move(out, work.resolve("failed-folder"), StandardCopyOption.ATOMIC_MOVE)
                if (moved) Files.move(old, out, StandardCopyOption.ATOMIC_MOVE)
                if (movedZip) Files.move(oldZip, zip, StandardCopyOption.ATOMIC_MOVE)
            } catch (restore: Exception) {
                e.addSuppressed(restore)
                recoveryRequired = true
            }
            throw e
        }
    }

    /** 提交结束后清理临时目录，不与提交并行。 */
    override fun close() {
        if (!recoveryRequired) work.toFile().deleteRecursively()
    }

    companion object {
        /** 后台生成候选、归档与摘要，返回前复核来源。 */
        fun prepare(root: Path, catalog: AssetCatalog, settings: PackSettings): PackBuild {
            val generated = root.resolve(ProjectPaths.GENERATE)
            require(!Files.isSymbolicLink(generated)) {
                "Generated directory must not be a symbolic link"
            }
            Files.createDirectories(generated)
            val work = Files.createTempDirectory(generated, ".pack-job-")
            try {
                val folder = AssetCompiler.build(root.resolve(ProjectPaths.UI), catalog, work)
                val verify = PackMerge.merge(root, folder, settings.sources)
                val zip = if (settings.archive) work.resolve("resource-pack.zip") else null
                if (zip != null) {
                    writeArchive(folder, zip, settings.compression)
                    require(Files.size(zip) <= 128L * 1024 * 1024) {
                        "Compressed resource pack exceeds 128 MiB"
                    }
                }
                verify()
                val sha1 = MessageDigest.getInstance("SHA-1")
                val sha256 = MessageDigest.getInstance("SHA-256")
                if (zip != null) {
                    Files.newInputStream(zip).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sha1.update(buffer, 0, count)
                            sha256.update(buffer, 0, count)
                        }
                    }
                }
                return PackBuild(
                    work,
                    folder,
                    zip,
                    sha1.digest(),
                    sha256.digest().joinToString("") { "%02x".format(it) },
                )
            } catch (e: Throwable) {
                work.toFile().deleteRecursively()
                throw e
            }
        }

        private fun writeArchive(folder: Path, zip: Path, compression: Int) {
            ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                out.setLevel(compression)
                val paths =
                    Files.walk(folder).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                            .sorted()
                            .toList()
                    }
                require(paths.size <= PackMerge.MAX_FILES)
                var total = 0L
                paths.forEach { path ->
                    check(!Thread.currentThread().isInterrupted) { "Pack build interrupted" }
                    val name = folder.relativize(path).toString().replace('\\', '/')
                    PackMerge.safe(name)
                    if (name !in setOf("manifest.json", "merge-report.json", "allocation.json")) {
                        require(!Files.isSymbolicLink(path))
                        total += Files.size(path)
                        require(total <= PackMerge.MAX_BYTES)
                        // 固定时间戳，使相同内容生成相同归档摘要。
                        out.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                        Files.newInputStream(path).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }
}
