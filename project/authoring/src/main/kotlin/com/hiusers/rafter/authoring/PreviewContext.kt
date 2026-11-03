package com.hiusers.rafter.authoring

import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.model.*
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.FrozenCanvas
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.util.UUID

/** 复用真实会话投影与渲染，不签发点击句柄或执行业务。 */
class PreviewContext(
    candidate: Candidate,
    menuId: String,
    pageId: String,
    val scenario: PreviewScenario,
) {
    val menu = candidate.snapshot.menus[menuId] ?: error("Unknown preview menu $menuId")
    val page = menu.pages[pageId] ?: error("Unknown preview page $pageId")
    val text = CompositeText(candidate.assets)
    val usedStubs = linkedSetOf<String>()
    val session: UiSession
    val backdrop: Pair<CompiledPage, Frame>?
    val frame: Frame
    val view: DialogView

    init {
        val available = PreviewInputs.describe(candidate.snapshot).getValue("requests") as Map<*, *>
        require((scenario.stubs.keys - available.keys).isEmpty()) {
            "Unknown or stale preview stub IDs"
        }
        val start = if (page.spec.modal == null) pageId else scenario.origin ?: menu.spec.entry
        require(start in menu.pages) { "Unknown modal preview origin $start" }
        session =
            UiSession(UUID(0, 0), menu.copy(spec = menu.spec.copy(entry = start)), scenario.args)
        session.patchState(scenario.state)
        if (page.spec.modal != null) {
            require(start != pageId) { "Modal preview requires a Canvas origin" }
            scenario.originPopup?.let(session::openPopup)
            val origin = session.current()
            require(
                origin.spec.mode == PageMode.CANVAS &&
                    origin.spec.components.isEmpty() &&
                    origin.spec.frame == null &&
                    origin.spec.modal == null
            ) {
                "Modal preview origin must be a Canvas without native controls or a dialog frame"
            }
            val originFrame = resolve()
            val generation = session.beginRender()
            val originView = CanvasRenderer.render(session, originFrame, text)
            session.ready(generation, emptyList())
            session.rememberCanvas(FrozenCanvas.capture(session, originView, text))
            backdrop = origin to originFrame
            session.navigate(pageId)
        } else backdrop = null
        scenario.popup?.let(session::openPopup)
        scenario.form.forEach { (id, value) ->
            val node =
                page.spec.components.singleOrNull { it.id == id }
                    ?: error("Unknown preview form field $id")
            val content = node.content as? FormContent ?: error("Not a preview form field $id")
            require(content.accepts(value)) { "Invalid preview form value for $id" }
            session.form[id] = Values.freeze(value)
        }
        require(scenario.errors.keys.all { key -> page.spec.nodes.any { it.id == key } }) {
            "Unknown preview error target"
        }
        frame = resolve()
        session.beginRender()
        view =
            if (page.spec.mode == PageMode.CANVAS)
                CanvasRenderer.render(session, frame, text, errors = scenario.errors)
            else NativeRenderer.render(session, frame, text, scenario.errors)
    }

    private fun stub(kind: String, spec: Any): Any? {
        val key = PreviewInputs.key(kind, spec)
        require(scenario.stubs.containsKey(key)) {
            "Explicit preview stub required: $key; spec=${com.hiusers.rafter.asset.Json.gson.toJson(spec)}"
        }
        usedStubs += key
        return scenario.stubs[key]
    }

    private fun binding(spec: BindingSpec): Any? =
        when (spec.type) {
            "state" -> session.state[spec.key]
            "args" -> session.args[spec.key]
            "document",
            "item",
            "record",
            "source",
            "list" -> session.data.resolve(spec)
            else -> stub("binding", spec)
        }

    private fun condition(spec: ConditionSpec?): Boolean =
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
            else -> stub("condition", spec) as? Boolean ?: error("Condition stub must be boolean")
        }

    private fun resolve(): Frame {
        val page = session.current().spec
        val aliases = session.data.required(page, session.displayedNodes(), session.activeLists())
        session.data.install(
            aliases.associateWith { binding(menu.spec.data.getValue(it).binding) },
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
