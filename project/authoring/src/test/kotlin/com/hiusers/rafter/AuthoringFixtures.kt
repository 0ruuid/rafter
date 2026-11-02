package com.hiusers.rafter

import java.nio.file.Files
import java.nio.file.Path

/** 仅复制当前源工程，不启动平台或执行默认安装。 */
object AuthoringFixtures {
    fun copy(root: Path, uiOnly: Boolean = false) {
        val source = Path.of(requireNotNull(javaClass.getResource("/config.yml")).toURI()).parent
        val start = if (uiOnly) source.resolve("core/ui") else source
        Files.walk(start).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) &&
                        (if (uiOnly)
                            it.startsWith(source.resolve("core/ui/images")) ||
                                it.startsWith(source.resolve("core/ui/fonts")) ||
                                it == source.resolve("core/ui/theme.yml")
                        else
                            it.startsWith(source.resolve("core")) ||
                                it.fileName.toString() == "config.yml")
                }
                .forEach { file ->
                    val target = root.resolve(source.relativize(file).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(file, target)
                }
        }
    }
}
