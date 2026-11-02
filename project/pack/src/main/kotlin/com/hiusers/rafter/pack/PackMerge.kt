package com.hiusers.rafter.pack

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.model.ProjectPaths
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 后台按格式区间合包，后列来源优先，Rafter 候选最后合入。
 * 先解析各来源自己的覆盖层，不修改来源或直接发布。
 */
object PackMerge {
    const val MAX_FILES = 65536
    const val MAX_FILE = 16 * 1024 * 1024
    const val MAX_BYTES = 256L * 1024 * 1024
    private const val END = (Int.MAX_VALUE.toLong() shl 32) or Int.MAX_VALUE.toLong()

    private data class Range(val min: Long, val max: Long) {
        fun contains(version: Long) = version in min..max
    }

    private data class Overlay(val directory: String, val range: Range)

    private data class Source(
        val files: Map<String, ByteArray>,
        val meta: JsonObject,
        val overlays: List<Overlay>,
        val range: Range,
        val verify: () -> Unit,
    ) {
        val base = files.filterKeys { name ->
            overlays.none { name.startsWith(it.directory + "/") }
        }

        fun effectiveFiles(version: Long) =
            base.toMutableMap().also { result ->
                overlays
                    .filter { it.range.contains(version) }
                    .forEach { overlay ->
                        files
                            .filterKeys { it.startsWith(overlay.directory + "/") }
                            .forEach { (path, bytes) ->
                                result[path.removePrefix(overlay.directory + "/")] = bytes
                            }
                    }
            }
    }

    fun safe(name: String) {
        require(
            name.isNotEmpty() &&
                !name.startsWith('/') &&
                name.matches(Regex("[A-Za-z0-9_./-]+")) &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." }
        ) {
            "Unsafe pack path: $name"
        }
    }

    private fun isAllowedPath(name: String) =
        name.startsWith("assets/") ||
            Regex("[^/]+/assets/.+").matches(name) ||
            name == "pack.mcmeta" ||
            name == "pack.png" ||
            name.startsWith("licenses/") ||
            name.substringBefore('/').startsWith("LICENSE")

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readDirectory(root: Path): Map<String, ByteArray> {
        require(!Files.isSymbolicLink(root))
        val base = root.toRealPath()
        var total = 0L
        val paths =
            Files.walk(root).use { stream ->
                stream
                    .filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .limit((MAX_FILES + 1).toLong())
                    .toList()
            }
        require(paths.size <= MAX_FILES) { "Too many pack files" }
        return paths
            .mapNotNull { path ->
                check(!Thread.currentThread().isInterrupted) { "Pack merge interrupted" }
                val name = root.relativize(path).toString().replace('\\', '/')
                safe(name)
                require(
                    !Files.isSymbolicLink(path) &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.toRealPath().startsWith(base)
                ) {
                    "Pack symlink/special file rejected"
                }
                if (!isAllowedPath(name)) {
                    null
                } else {
                    require(Files.size(path) <= MAX_FILE)
                    val data = Files.readAllBytes(path)
                    total += data.size
                    require(total <= MAX_BYTES)
                    name to data
                }
            }
            .toMap()
    }

    private fun readSourceFiles(path: Path): Map<String, ByteArray> {
        if (Files.isDirectory(path)) return readDirectory(path)
        return ZipFile(path.toFile()).use { zip ->
            var total = 0L
            val seen = mutableSetOf<String>()
            val out = linkedMapOf<String, ByteArray>()
            val entries = zip.entries().asSequence().take(MAX_FILES + 1).toList()
            require(entries.size <= MAX_FILES)
            entries.forEach { entry ->
                check(!Thread.currentThread().isInterrupted) { "Pack merge interrupted" }
                val name = entry.name.removeSuffix("/")
                safe(name)
                require(seen.add(name)) { "Duplicate ZIP path: $name" }
                if (!entry.isDirectory && isAllowedPath(name)) {
                    require(entry.size in 0..MAX_FILE.toLong())
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_FILE + 1) }
                    require(bytes.size <= MAX_FILE && bytes.size.toLong() == entry.size)
                    total += bytes.size
                    require(total <= MAX_BYTES)
                    out[name] = bytes
                }
            }
            out
        }
    }

    private fun readFormatVersion(element: JsonElement, upper: Boolean = false): Long {
        val parts =
            if (element.isJsonArray) {
                element.asJsonArray.map { it.asInt }
            } else {
                listOf(element.asInt, if (upper) Int.MAX_VALUE else 0)
            }
        require(parts.size == 2 && parts.all { it >= 0 })
        return (parts[0].toLong() shl 32) or parts[1].toLong()
    }

    private fun readFormatRange(obj: JsonObject): Range {
        fun readBound(min: Boolean): Long {
            val key = if (min) "min_format" else "max_format"
            obj.get(key)?.let {
                return readFormatVersion(it, !min)
            }
            val formats =
                obj.get("formats")
                    ?: obj.get("supported_formats")
                    ?: obj.get("pack_format")
                    ?: error("Missing pack format range")
            return when {
                formats.isJsonObject ->
                    readFormatVersion(
                        formats.asJsonObject.get(if (min) "min_inclusive" else "max_inclusive"),
                        !min,
                    )
                formats.isJsonArray ->
                    readFormatVersion(
                        formats.asJsonArray[if (min) 0 else formats.asJsonArray.size() - 1],
                        !min,
                    )
                else -> readFormatVersion(formats, !min)
            }
        }
        return Range(readBound(true), readBound(false)).also {
            require(it.min <= it.max && it.max <= END)
        }
    }

    private fun readSource(files: Map<String, ByteArray>, verify: () -> Unit): Source {
        val meta =
            JsonParser.parseString(
                    files["pack.mcmeta"]?.decodeToString() ?: error("Pack lacks root pack.mcmeta")
                )
                .asJsonObject
        val filters = meta.getAsJsonObject("filter")?.getAsJsonArray("block")
        require(filters == null || filters.isEmpty) {
            "Pack filter.block cannot safely be flattened; remove or resolve filters first"
        }
        val overlays =
            meta.getAsJsonObject("overlays")?.getAsJsonArray("entries")?.map { entry ->
                val overlay = entry.asJsonObject
                val directory = overlay.get("directory").asString
                require(directory.matches(Regex("[A-Za-z0-9_-]{1,100}"))) {
                    "Unsafe overlay directory"
                }
                Overlay(directory, readFormatRange(overlay))
            } ?: emptyList()
        require(
            overlays.size <= 32 && overlays.map { it.directory }.distinct().size == overlays.size
        )
        return Source(files, meta, overlays, readFormatRange(meta.getAsJsonObject("pack")), verify)
    }

    private fun canonicalJson(element: JsonElement): String =
        when {
            element.isJsonObject ->
                element.asJsonObject
                    .entrySet()
                    .sortedBy { it.key }
                    .joinToString(",", "{", "}") {
                        JsonPrimitive(it.key).toString() + ":" + canonicalJson(it.value)
                    }
            element.isJsonArray ->
                element.asJsonArray.joinToString(",", "[", "]") { canonicalJson(it) }
            else -> element.toString()
        }

    private fun mergeObjects(a: JsonObject, b: JsonObject): JsonObject =
        a.deepCopy().also { out ->
            b.entrySet().forEach { (key, value) ->
                out.add(
                    key,
                    if (value.isJsonObject && out.get(key)?.isJsonObject == true) {
                        mergeObjects(out.getAsJsonObject(key), value.asJsonObject)
                    } else {
                        value.deepCopy()
                    },
                )
            }
        }

    private fun mergeFile(path: String, a: ByteArray, b: ByteArray): ByteArray {
        if (a.contentEquals(b)) return a
        val font = Regex("assets/[^/]+/font/.+\\.json").matches(path)
        val atlas = Regex("assets/[^/]+/atlases/.+\\.json").matches(path)
        val lang = Regex("assets/[^/]+/lang/.+\\.json").matches(path)
        if (!font && !atlas && !lang) return b
        val x = JsonParser.parseString(a.decodeToString()).asJsonObject
        val y = JsonParser.parseString(b.decodeToString()).asJsonObject
        val out = mergeObjects(x, y)
        if (font || atlas) {
            val key = if (font) "providers" else "sources"
            val xa = x.getAsJsonArray(key)
            val ya = y.getAsJsonArray(key)
            val merged = JsonArray()
            val seen = mutableSetOf<String>()
            // Atlas 的移除与重加依赖顺序，不能全局去重。
            (if (font) ya.toList() + xa.toList() else xa.toList() + ya.toList()).forEach { entry ->
                if (!font || seen.add(canonicalJson(entry))) merged.add(entry.deepCopy())
            }
            out.add(key, merged)
        }
        return out.toString().toByteArray()
    }

    /** 仅写私有候选；归档后、提交前调用返回函数复核来源。 */
    fun merge(root: Path, stage: Path, paths: List<String>): () -> Unit {
        if (paths.isEmpty()) return {}
        val inputs =
            paths.map { name ->
                val path = root.resolve(name).normalize().toAbsolutePath()
                val output = root.resolve(ProjectPaths.PACK).toAbsolutePath().normalize()
                require(
                    path != root.resolve(ProjectPaths.ARCHIVE).toAbsolutePath().normalize() &&
                        !path.startsWith(output) &&
                        !output.startsWith(path)
                ) {
                    "Cannot merge Rafter's output into itself"
                }
                require(!Files.isSymbolicLink(path))
                val files = readSourceFiles(path)
                val signatures = files.mapValues { sha256(it.value) }
                readSource(files) {
                    require(readSourceFiles(path).mapValues { sha256(it.value) } == signatures) {
                        "Merge source changed during build: $name"
                    }
                }
            } + readSource(readDirectory(stage)) {}
        val min = inputs.maxOf { it.range.min }
        val max = inputs.minOf { it.range.max }
        require(min <= max) { "Resource packs have no common supported format range" }
        val conflicts = linkedSetOf<String>()
        fun composeFiles(states: List<Map<String, ByteArray>>): Map<String, ByteArray> {
            val result = linkedMapOf<String, ByteArray>()
            states.forEach { files ->
                files.forEach { (name, bytes) ->
                    if (name != "pack.mcmeta") {
                        val old = result[name]
                        if (old != null && !old.contentEquals(bytes)) conflicts += name
                        result[name] = if (old == null) bytes else mergeFile(name, old, bytes)
                    }
                }
            }
            return result
        }
        val base = composeFiles(inputs.map { it.base })
        val points =
            (listOf(min, max + 1) +
                    inputs
                        .flatMap { it.overlays }
                        .flatMap {
                            listOf(it.range.min, it.range.max + 1)
                        })
                .filter { it in min..(max + 1) }
                .distinct()
                .sorted()
        require(points.size <= 66) { "Too many merged format intervals" }
        // 仅清理候选生成的覆盖层，外部来源只读。
        inputs.last().overlays.forEach { stage.resolve(it.directory).toFile().deleteRecursively() }
        var bytes = 0L
        var files = 0
        fun writeFile(name: String, data: ByteArray) {
            check(!Thread.currentThread().isInterrupted) { "Pack merge interrupted" }
            safe(name)
            bytes += data.size
            files++
            require(bytes <= MAX_BYTES && files <= MAX_FILES) {
                "Merged pack output budget exceeded"
            }
            val target = stage.resolve(name)
            Files.createDirectories(target.parent)
            Files.write(target, data)
        }
        base.forEach(::writeFile)
        val entries = JsonArray()
        fun formatVersionJson(version: Long) =
            JsonArray().also {
                it.add((version ushr 32).toInt())
                it.add((version and 0xffffffffL).toInt())
            }
        points.zipWithNext().forEachIndexed { index, (low, high) ->
            val effective = composeFiles(inputs.map { it.effectiveFiles(low) })
            val delta = effective.filter { (name, data) -> base[name]?.contentEquals(data) != true }
            if (delta.isNotEmpty()) {
                val dir = "rafter_merged_$index"
                delta.forEach { (name, data) -> writeFile("$dir/$name", data) }
                entries.add(
                    JsonObject().apply {
                        addProperty("directory", dir)
                        add("min_format", formatVersionJson(low))
                        add("max_format", formatVersionJson(high - 1))
                        add(
                            "formats",
                            JsonObject().apply {
                                addProperty("min_inclusive", (low ushr 32).toInt())
                                addProperty("max_inclusive", ((high - 1) ushr 32).toInt())
                            },
                        )
                    }
                )
            }
        }
        val meta =
            inputs.fold(JsonObject()) { accumulated, source ->
                mergeObjects(accumulated, source.meta)
            }
        meta.remove("overlays")
        meta.remove("filter")
        val pack = meta.getAsJsonObject("pack")
        pack.add("min_format", formatVersionJson(min))
        pack.add("max_format", formatVersionJson(max))
        pack.addProperty("pack_format", (min ushr 32).toInt())
        pack.add(
            "supported_formats",
            JsonArray().apply {
                add((min ushr 32).toInt())
                add((max ushr 32).toInt())
            },
        )
        pack.addProperty("description", "Rafter combined resource pack")
        if (!entries.isEmpty) meta.add("overlays", JsonObject().apply { add("entries", entries) })
        val features = JsonArray()
        inputs
            .flatMap {
                it.meta.getAsJsonObject("features")?.getAsJsonArray("enabled")?.toList()
                    ?: emptyList()
            }
            .distinctBy(::canonicalJson)
            .forEach { features.add(it) }
        if (!features.isEmpty) meta.add("features", JsonObject().apply { add("enabled", features) })
        Files.writeString(stage.resolve("pack.mcmeta"), meta.toString())
        Json.write(
            stage.resolve("merge-report.json"),
            mapOf(
                "sources" to paths,
                "priority" to "later sources then Rafter",
                "conflicts" to conflicts.take(512),
                "conflict-count" to conflicts.size,
                "intervals" to entries.size(),
                "json-rules" to
                    mapOf(
                        "font" to "later providers first, exact duplicates removed",
                        "atlas" to "ordered concatenation",
                        "lang" to "object merge, later keys win",
                        "other" to "later file wins (including shaders/models)",
                    ),
            ),
        )
        return { inputs.forEach { it.verify() } }
    }
}
