package com.hiusers.rafter

import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.Frame
import com.hiusers.rafter.runtime.UiSession

/** 仅预览本地文档；不支持的读取必须提供 stub。 */
internal object OfflineFrame {
    fun resolve(session: UiSession): Frame {
        fun binding(spec: BindingSpec): Any? =
            when (spec.type) {
                "state" -> session.state[spec.key]
                "args" -> session.args[spec.key]
                "document",
                "item",
                "record",
                "source",
                "list" -> session.data.resolve(spec)
                else -> error("Offline preview requires an explicit stub for ${spec.type}")
            }
        fun condition(spec: ConditionSpec?): Boolean =
            when (spec?.type) {
                null -> true
                "constant" -> spec.value as Boolean
                "state-equals" -> Values.equal(session.state[spec.key], spec.value)
                "at-least" ->
                    Values.atLeast(
                        binding(requireNotNull(spec.binding)),
                        spec.compare?.let(::binding) ?: spec.value,
                    )
                "equals" ->
                    Values.equal(
                        binding(requireNotNull(spec.binding)),
                        spec.compare?.let(::binding) ?: spec.value,
                    )
                "all" -> spec.children.all(::condition)
                "any" -> spec.children.any(::condition)
                "not" -> !condition(spec.children.single())
                else -> error("Offline condition requires a stub: ${spec.type}")
            }
        val page = session.current().spec
        val aliases = session.data.required(page, session.displayedNodes(), session.activeLists())
        session.data.install(
            aliases.associateWith { binding(session.menu.spec.data.getValue(it).binding) },
            false,
        )
        session.data.projectionOrder(session.activeLists()).forEach { list ->
            session.data.project(
                listOf(list),
                mapOf(list.id to condition(list.filter?.condition)),
                mapOf(list.id to condition(list.visible)),
            )
        }
        val visible = session.displayedNodes().associate { it.id to condition(it.visible) }
        val resolved =
            session.displayedNodes().associate { node ->
                node.id to
                    (if (visible[node.id] == true)
                        node.variants.firstOrNull { condition(it.condition) }?.node ?: node
                    else node)
            }
        val enabled = resolved.mapValues { (_, node) ->
            visible[node.id] == true && condition(node.enabled)
        }
        val values = linkedMapOf<BindingSpec, Any?>()
        fun text(spec: TextSpec) {
            spec.binding?.let { values[it] = binding(it) }
            spec.inlineParts.forEach { it.binding?.let { key -> values[key] = binding(key) } }
            spec.parts.forEach(::text)
            spec.hover?.let(::text)
        }
        text(page.title)
        resolved.values
            .filter { visible[it.id] == true }
            .forEach { node ->
                text(node.text)
                node.tooltip?.let(::text)
                (node.content as? NodeContent.Meter)?.let {
                    values[it.value] = binding(it.value)
                    values[it.maximum] = binding(it.maximum)
                }
                (node.content as? NodeContent.Select)?.options?.values?.forEach(::text)
            }
        return Frame(values, visible, enabled, resolved)
    }
}
