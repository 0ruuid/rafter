package com.hiusers.rafter.compile

import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.SourceLocation
import com.hiusers.rafter.model.SourcePath
import com.hiusers.rafter.model.SourceRange
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionEndEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import taboolib.common.env.RuntimeDependency

/** 解码前预检 YAML，区分显式 null 与缺失字段。 */
@RuntimeDependency(
    value = "!org.yaml:snakeyaml:2.6",
    test = "!org.yaml.snakeyaml_2_6.Yaml",
    transitive = false,
    relocate = ["!org.yaml.snakeyaml", "!org.yaml.snakeyaml_2_6"],
)
internal data class YamlSource(val text: String, val index: YamlSourceIndex) {
    companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_DEPTH = 32 // 与文档普通值的嵌套限制一致。
        const val MAX_ALIASES = 32
        const val MAX_NODES = 50_000
        private val tags = setOf(Tag.MAP, Tag.SEQ, Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT)

        fun read(root: Path, path: Path): YamlSource {
            val file = root.relativize(path).toString()
            try {
                require(
                    path
                        .toAbsolutePath()
                        .normalize()
                        .startsWith(root.toAbsolutePath().normalize()) &&
                        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                        path.toRealPath().startsWith(root.toRealPath())
                ) {
                    "Unsafe configuration path"
                }
                // 同时限制实际读取量，防止文件增长绕过大小检查。
                require(Files.size(path) <= MAX_BYTES) { "Configuration exceeds 1 MiB" }
                val bytes =
                    Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
                require(bytes.size <= MAX_BYTES) { "Configuration exceeds 1 MiB" }
                val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                return YamlSource(text, index(file, text))
            } catch (failure: DocumentError) {
                throw failure
            } catch (failure: Exception) {
                throw DocumentError(
                    Diagnostic(file, "$", "READ", failure.message ?: "Invalid document")
                )
            }
        }

        fun index(file: String, text: String): YamlSourceIndex {
            fun fail(code: String, message: String, range: SourceRange? = null): Nothing =
                throw DocumentError(Diagnostic(file, "$", code, message, range = range))
            if (text.toByteArray(UTF_8).size > MAX_BYTES)
                fail("READ", "Configuration exceeds 1 MiB")
            val yaml = Yaml(SafeConstructor(loaderOptions()))
            try {
                var depth = 0
                var aliases = 0
                var nodes = 0
                // 构造递归 AST 前检查事件深度及别名。
                for (event in yaml.parse(StringReader(text))) {
                    if (event is CollectionStartEvent) {
                        depth++
                        if (depth > MAX_DEPTH + 1)
                            fail(
                                "YAML_DEPTH",
                                "YAML nesting exceeds $MAX_DEPTH",
                                span(event.startMark, event.endMark),
                            )
                    } else if (event is CollectionEndEvent) depth--
                    if (event is AliasEvent && ++aliases > MAX_ALIASES) {
                        fail(
                            "YAML_ALIAS",
                            "YAML aliases exceed $MAX_ALIASES",
                            span(event.startMark, event.endMark),
                        )
                    }
                    if (
                        event is CollectionStartEvent || event is ScalarEvent || event is AliasEvent
                    ) {
                        if (++nodes > MAX_NODES)
                            fail(
                                "YAML_NODES",
                                "YAML nodes exceed $MAX_NODES",
                                span(event.startMark, event.endMark),
                            )
                    }
                    val tag =
                        when (event) {
                            is ScalarEvent -> event.tag
                            is CollectionStartEvent -> event.tag
                            else -> null
                        }
                    if (tag != null && tags.none { it.value == tag }) {
                        fail(
                            "YAML_TAG",
                            "Only document value tags are supported",
                            span(event.startMark, event.endMark),
                        )
                    }
                }
                val document = yaml.compose(StringReader(text))
                if (document !is MappingNode)
                    fail(
                        "YAML_ROOT",
                        "Expected a YAML mapping",
                        document?.let { span(it.startMark, it.endMark) },
                    )
                return YamlSourceIndex.build(file, document)
            } catch (failure: YAMLException) {
                val marked = failure as? MarkedYAMLException
                val mark = marked?.problemMark ?: marked?.contextMark
                fail("YAML_SYNTAX", marked?.problem ?: "Invalid YAML", mark?.let { span(it, it) })
            }
        }

        /** 只解码预检文本，避免 ConfigurationSection 写入时吞掉 null。 */
        fun decode(text: String): Map<String, Any?> =
            Yaml(SafeConstructor(loaderOptions())).load(text)

        private fun loaderOptions() =
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                nestingDepthLimit = MAX_DEPTH + 1
                maxAliasesForCollections = MAX_ALIASES
                codePointLimit = MAX_BYTES
            }

        private fun span(start: Mark, end: Mark): SourceRange =
            SourceRange(start.line + 1, start.column + 1, end.line + 1, end.column + 1)
    }
}

/** 定位缺失或派生字段时使用最近的作者节点，不伪造精确位置。 */
internal class YamlSourceIndex
private constructor(
    private val paths: Map<String, List<SourceLocation>>,
    private val components: Map<String, List<SourceLocation>>,
) {
    fun range(path: String, component: String? = null): SourceRange? {
        val matches = paths[path].orEmpty().distinct()
        if (matches.isNotEmpty()) {
            return (matches.singleOrNull()
                    ?: matches
                        .filter { component != null && it.component == component }
                        .singleOrNull())
                ?.range
        }
        val enclosing =
            paths.keys
                .filter { path.startsWith("$it.") || path.startsWith("$it[") }
                .maxByOrNull { it.length }
        if (enclosing != null) return paths.getValue(enclosing).singleOrNull()?.range
        return component?.let { components[it].orEmpty().singleOrNull()?.range }
    }

    fun fieldRange(path: String, key: String, component: String? = null): SourceRange? {
        val child = SourcePath.field(path, key)
        return if (child in paths) range(child, component) else range(path, component)
    }

    companion object {
        fun build(file: String, root: MappingNode): YamlSourceIndex {
            val paths = linkedMapOf<String, MutableList<SourceLocation>>()
            val components = linkedMapOf<String, MutableList<SourceLocation>>()
            val seen = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
            val active = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
            var nodes = 0
            fun span(node: Node) =
                SourceRange(
                    node.startMark.line + 1,
                    node.startMark.column + 1,
                    node.endMark.line + 1,
                    node.endMark.column + 1,
                )
            fun walk(
                node: Node,
                path: String,
                depth: Int,
                inherited: String?,
                keyRange: SourceRange? = null,
            ) {
                val authored = seen.add(node)
                val range = keyRange ?: if (authored) span(node) else null
                fun fail(code: String, message: String): Nothing =
                    throw DocumentError(
                        Diagnostic(file, path, code, message, inherited, range = range)
                    )
                if (depth > YamlSource.MAX_DEPTH)
                    fail("YAML_DEPTH", "YAML nesting exceeds ${YamlSource.MAX_DEPTH}")
                if (++nodes > YamlSource.MAX_NODES)
                    fail("YAML_NODES", "Expanded YAML nodes exceed ${YamlSource.MAX_NODES}")
                if (!active.add(node))
                    fail("YAML_ALIAS", "Recursive YAML aliases are not supported")
                val ownId =
                    (node as? MappingNode)
                        ?.value
                        ?.firstOrNull {
                            (it.keyNode as? ScalarNode)?.value == "id"
                        }
                        ?.valueNode as? ScalarNode
                val component = ownId?.takeIf { it.tag == Tag.STR }?.value ?: inherited
                if (range != null) {
                    val location = SourceLocation(file, path, component, range)
                    paths.getOrPut(path) { mutableListOf() }.add(location)
                }
                if (node is MappingNode) {
                    if (ownId?.tag == Tag.STR) {
                        components
                            .getOrPut(ownId.value) { mutableListOf() }
                            .add(
                                SourceLocation(
                                    file,
                                    path,
                                    ownId.value,
                                    if (authored) span(node) else null,
                                )
                            )
                    }
                    val keys = linkedMapOf<String, Node>()
                    for (tuple in node.value) {
                        val key =
                            tuple.keyNode as? ScalarNode
                                ?: fail("YAML_KEY", "Mapping keys must be strings")
                        if (key.tag != Tag.STR)
                            throw DocumentError(
                                Diagnostic(
                                    file,
                                    path,
                                    "YAML_KEY",
                                    "Mapping keys must be strings; YAML merge keys are not supported",
                                    component,
                                    range = if (authored) span(key) else null,
                                )
                            )
                        val child = SourcePath.field(path, key.value)
                        val previous = keys.putIfAbsent(key.value, key)
                        if (previous != null)
                            throw DocumentError(
                                Diagnostic(
                                    file,
                                    child,
                                    "YAML_DUPLICATE_KEY",
                                    "Duplicate mapping key: ${key.value}",
                                    component,
                                    range = if (authored) span(key) else null,
                                    origins =
                                        listOf(
                                            SourceLocation(file, child, component, span(previous))
                                        ),
                                )
                            )
                        walk(
                            tuple.valueNode,
                            child,
                            depth + 1,
                            component,
                            if (authored) span(key) else null,
                        )
                    }
                } else if (node is SequenceNode) {
                    node.value.forEachIndexed { index, value ->
                        walk(value, SourcePath.index(path, index), depth + 1, component)
                    }
                }
                active.remove(node)
            }
            walk(root, "$", 0, null)
            return YamlSourceIndex(
                paths.mapValues { it.value.toList() },
                components.mapValues { it.value.toList() },
            )
        }
    }
}

internal fun Diagnostic.withSources(sources: Map<String, YamlSourceIndex>): Diagnostic =
    copy(
        range = range ?: sources[file]?.range(path, component),
        origins =
            origins.map { origin ->
                origin.copy(
                    range =
                        origin.range ?: sources[origin.file]?.range(origin.path, origin.component)
                )
            },
    )
