package com.hiusers.rafter.asset

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.Locale

/** 发现 TTF 字体族及可选同名许可。 */
internal object FontDiscovery {
    fun discover(root: Path): Map<String, Path> {
        val directory = root.resolve("fonts").toAbsolutePath().normalize()
        checked(root, directory)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            "Missing UI font directory: $directory"
        }
        val families = linkedMapOf<String, Path>()
        Files.walk(directory).use { paths ->
            paths.sorted().forEach { path ->
                checked(root, path)
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) return@forEach
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    "Font entry must be a regular file: $path"
                }
                if (
                    path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) ==
                        "ttf"
                ) {
                    val family = directory.relativize(path).joinToString("/").dropLast(4)
                    FontRequest(family, 1.0)
                    require(families.putIfAbsent(family, path) == null) {
                        "Duplicate font family: $family"
                    }
                }
            }
        }
        return Collections.unmodifiableMap(families)
    }

    fun notice(root: Path, source: Path): Path? {
        val path =
            source.resolveSibling(source.fileName.toString().substringBeforeLast('.') + ".license")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        checked(root, path)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            "Font license must be a regular file: $path"
        }
        return path
    }

    /** 逐级检查路径，不跟随字体目录符号链接。 */
    fun checked(root: Path, source: Path): Path {
        val base = root.toAbsolutePath().normalize()
        val path = source.toAbsolutePath().normalize()
        require(path.startsWith(base.resolve("fonts"))) { "Font path escapes ui/fonts: $source" }
        var component = base
        require(!Files.isSymbolicLink(component)) { "Symbolic UI directory: $component" }
        for (part in base.relativize(path)) {
            component = component.resolve(part)
            require(!Files.isSymbolicLink(component)) { "Symbolic font path: $component" }
        }
        require(path.toRealPath().startsWith(base.toRealPath().resolve("fonts"))) {
            "Font path escapes ui/fonts: $source"
        }
        return path
    }
}
