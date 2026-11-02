package com.hiusers.rafter.compile

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hiusers.rafter.asset.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Collections

/** 按发布清单安装默认文件。仅整组原件可升级，自定义文件保留；退役删除需匹配历史摘要。 */
object DefaultBundle {
    private data class Manifest(val files: List<String>, val backupDirectory: String)

    private val loader = DefaultBundle::class.java.classLoader
    private val manifest by lazy {
        val document = jsonResource("defaults/bundle.json")
        require(document["schema-version"]?.toString() == "1") {
            "Unsupported default bundle schema-version"
        }
        val backupDirectory = string(document["backup-directory"], "backup-directory")
        val backupPath = relativePath(backupDirectory)
        val entries = document["files"]
        require(entries != null && entries.isJsonArray) { "Default bundle files must be an array" }
        val files = entries.asJsonArray.map { string(it, "files entry") }
        require(files.isNotEmpty() && files.distinct().size == files.size) {
            "Default bundle files must be nonempty and unique"
        }
        val filePaths = files.map(::relativePath)
        require(filePaths.none { it.startsWith(backupPath) || backupPath.startsWith(it) }) {
            "Default bundle files must not overlap the backup directory"
        }
        require(
            filePaths.none { parent -> filePaths.any { it != parent && it.startsWith(parent) } }
        ) {
            "Default bundle file paths must not contain other files"
        }
        files.forEach {
            resource(it).use { /* 发布资源不完整时，在写入前失败。 */ }
        }
        Manifest(Collections.unmodifiableList(files), backupDirectory)
    }

    /** 文件来自显式清单，不扫描目录或 JAR 自动补入。 */
    val paths: List<String>
        get() = manifest.files

    /** 任一已修改文件都会阻止整组覆盖；缺失文件仍可补齐。 */
    internal fun canUpgrade(
        root: Path,
        previous: Map<String, List<String>>,
        originals: Map<String, ByteArray>,
    ): Boolean {
        val targets = (previous.keys + originals.keys).associateWith { resolve(root, it) }
        return targets.all { (path, target) ->
            !Files.exists(target, NOFOLLOW_LINKS) ||
                (Files.isRegularFile(target, NOFOLLOW_LINKS) &&
                    hash(readInstalled(target)!!) in
                        previous[path].orEmpty() + listOfNotNull(originals[path]?.let(::hash)))
        }
    }

    /** 仅可删除摘要匹配的历史原件。 */
    internal fun retire(root: Path, previous: Map<String, List<String>>, active: Set<String>) {
        previous.keys.forEach { resolve(root, it) }
        previous
            .filterKeys { it !in active }
            .forEach { (path, hashes) ->
                val target = resolve(root, path)
                if (
                    Files.isRegularFile(target, NOFOLLOW_LINKS) &&
                        hash(readInstalled(target)!!) in hashes
                ) {
                    Files.delete(target)
                }
            }
    }

    /** 只写入指定目录。安装前校验清单，拒绝符号链接；备份按内容寻址，不覆盖旧备份。 */
    fun install(root: Path) {
        val originals = paths.associateWith { resource(it).use { input -> input.readBytes() } }
        val previous =
            jsonResource("defaults/previous.json").entrySet().associate { (path, values) ->
                relativePath(path)
                require(values.isJsonArray) {
                    "Default bundle history must contain digest arrays: $path"
                }
                path to values.asJsonArray.map { string(it, "digest for $path") }
            }
        val backupDirectory = relativePath(manifest.backupDirectory)
        require(
            previous.keys.none {
                val path = relativePath(it)
                path.startsWith(backupDirectory) || backupDirectory.startsWith(path)
            }
        ) {
            "Default bundle history must not overlap the backup directory"
        }
        val backupRoot = resolve(root, manifest.backupDirectory)
        require(
            !Files.exists(backupRoot, NOFOLLOW_LINKS) ||
                Files.isDirectory(backupRoot, NOFOLLOW_LINKS)
        ) {
            "Default bundle backup destination must be a directory: $backupRoot"
        }
        val upgrade = canUpgrade(root, previous, originals)
        originals.forEach { (path, bytes) ->
            val current = readInstalled(resolve(root, path))
            if (current == null) {
                write(root, path, bytes, replace = false)
            } else if (
                upgrade &&
                    hash(current) in previous[path].orEmpty() &&
                    !current.contentEquals(bytes)
            ) {
                val backupPath = "${manifest.backupDirectory}/${hash(current)}/$path"
                val backup = readInstalled(resolve(root, backupPath))
                if (backup == null) {
                    write(root, backupPath, current, replace = false)
                } else {
                    require(backup.contentEquals(current)) {
                        "Default bundle backup has been modified: $backupPath"
                    }
                }
                write(root, path, bytes, replace = true)
            }
        }
        // 整组替换成功后再清理旧依赖，避免作者文件仍引用它们。
        if (upgrade) retire(root, previous, originals.keys)
    }

    private fun resource(path: String) =
        requireNotNull(loader.getResourceAsStream(path)) {
            "Missing default bundle resource: $path"
        }

    private fun jsonResource(path: String): JsonObject =
        resource(path).bufferedReader(Charsets.UTF_8).use {
            requireNotNull(Json.gson.fromJson(it, JsonObject::class.java)) {
                "Missing default bundle document: $path"
            }
        }

    private fun string(value: JsonElement?, field: String): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Default bundle $field must be a string"
        }
        return value.asString
    }

    private fun relativePath(name: String): Path {
        require(
            name.isNotBlank() &&
                '\\' !in name &&
                ':' !in name &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." }
        ) {
            "Default bundle path must be a normalized relative path: $name"
        }
        return Path.of(name).also {
            require(!it.isAbsolute) { "Absolute default bundle path: $name" }
        }
    }

    /** 拒绝根目录和子路径中的符号链接，包括失效链接和备份目录。 */
    private fun resolve(root: Path, name: String): Path {
        var target = root.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(target)) { "Symbolic default bundle root: $target" }
        for (part in relativePath(name)) {
            require(
                !Files.exists(target, NOFOLLOW_LINKS) || Files.isDirectory(target, NOFOLLOW_LINKS)
            ) {
                "Default bundle parent must be a directory: $target"
            }
            target = target.resolve(part)
            require(!Files.isSymbolicLink(target)) { "Symbolic default bundle path: $target" }
        }
        return target
    }

    private fun readInstalled(target: Path): ByteArray? {
        if (!Files.exists(target, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            "Default bundle target must be a regular file: $target"
        }
        return Files.newInputStream(target, NOFOLLOW_LINKS).use { it.readBytes() }
    }

    private fun write(root: Path, path: String, bytes: ByteArray, replace: Boolean) {
        val target = resolve(root, path)
        Files.createDirectories(target.parent)
        resolve(root, path)
        Files.write(
            target,
            bytes,
            WRITE,
            if (replace) TRUNCATE_EXISTING else CREATE_NEW,
            NOFOLLOW_LINKS,
        )
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
