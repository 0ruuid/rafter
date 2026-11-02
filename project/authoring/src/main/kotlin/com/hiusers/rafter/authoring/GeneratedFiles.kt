package com.hiusers.rafter.authoring

import com.hiusers.rafter.model.ProjectPaths
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Comparator

/** 整组发布派生目录，不覆盖作者源文件。 */
object GeneratedFiles {
    @Synchronized
    fun publish(root: Path, name: String, verify: () -> Unit = {}, write: (Path) -> Unit): Path {
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid generated directory name" }
        val generated = root.resolve(ProjectPaths.GENERATE)
        require(!Files.isSymbolicLink(generated)) {
            "Generated directory must not be a symbolic link"
        }
        Files.createDirectories(generated)
        require(generated.toRealPath().startsWith(root.toRealPath())) {
            "Generated directory escapes project"
        }
        val target = generated.resolve(name)
        require(
            !Files.exists(target, NOFOLLOW_LINKS) || Files.isDirectory(target, NOFOLLOW_LINKS)
        ) {
            "Generated target must be a directory, not a file or symbolic link"
        }
        val work = Files.createTempDirectory(generated, ".$name-job-")
        val candidate = Files.createDirectory(work.resolve("candidate"))
        val previous = work.resolve("previous")
        var moved = false
        var recoveryRequired = false
        try {
            write(candidate)
            verify()
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                Files.move(target, previous, ATOMIC_MOVE)
                moved = true
            }
            try {
                Files.move(candidate, target, ATOMIC_MOVE)
            } catch (failure: Exception) {
                if (moved)
                    try {
                        Files.move(previous, target, ATOMIC_MOVE)
                    } catch (restore: Exception) {
                        recoveryRequired = true
                        failure.addSuppressed(restore)
                    }
                throw failure
            }
            return target
        } finally {
            // 恢复失败时保留旧产物和现场。
            if (!recoveryRequired)
                Files.walk(work).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
        }
    }
}
