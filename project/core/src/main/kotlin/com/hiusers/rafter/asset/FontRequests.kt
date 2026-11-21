package com.hiusers.rafter.asset

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.feedbackText
import com.hiusers.rafter.text.InlineText
import java.util.Collections
import taboolib.module.chat.Components

/** 模板展开后收集所需字体，不执行绑定或脚本。 */
object FontRequests {
    fun collect(snapshot: MenuSnapshot, theme: UiTheme): Set<FontRequest> {
        val requests = linkedSetOf(theme.defaultRequest)
        fun text(
            spec: TextSpec,
            family: String = theme.defaultFamily,
            size: Double = theme.defaultSize,
        ) {
            val actualFamily = spec.font ?: family
            val actualSize = spec.fontSize ?: size
            if (actualFamily != "minecraft:default")
                requests += FontRequest(actualFamily, actualSize)
            inlineFamilies(spec).filterNot { it == "minecraft:default" }.forEach {
                requests += FontRequest(it, actualSize)
            }
            spec.parts.forEach { text(it, actualFamily, actualSize) }
            spec.hover?.let { text(it, actualFamily, actualSize) }
        }
        forEachText(snapshot, theme) { text(it) }
        return java.util.Collections.unmodifiableSet(requests)
    }

    /** 原生文本及提示的显式位移仍可依赖系统回退字体。 */
    fun authoredOffsetsByFace(snapshot: MenuSnapshot, theme: UiTheme): Map<String, Set<Int>> {
        val offsets = linkedMapOf<String, MutableSet<Int>>()
        fun visit(spec: TextSpec, parentFamily: String, parentSize: Double, parentShift: Int) {
            val family = spec.font ?: parentFamily
            val size = spec.fontSize ?: parentSize
            val shift = parentShift + spec.offsetY
            offsets.getOrPut(theme.defaultFont) { linkedSetOf() } += shift
            for (face in inlineFamilies(spec) + family) {
                if (face != "minecraft:default") {
                    offsets.getOrPut(FontRequest(face, size).id) { linkedSetOf() } += shift
                }
            }
            spec.parts.forEach { visit(it, family, size, shift) }
            spec.hover?.let { visit(it, family, size, 0) }
        }
        forEachText(snapshot, theme) { visit(it, theme.defaultFamily, theme.defaultSize, 0) }
        return freezeOffsets(offsets)
    }

    /** 按字面预留所有可能的画布行偏移。默认字面也用于布局的空父组件和数量。 */
    fun canvasOffsetsByFace(snapshot: MenuSnapshot, theme: UiTheme): Map<String, Set<Int>> {
        val offsets = linkedMapOf<String, MutableSet<Int>>()
        fun forFace(id: String) = offsets.getOrPut(id) { linkedSetOf() }
        fun visit(node: NodeSpec) {
            val content = node.content
            if (content == NodeContent.Text) {
                val heights = linkedSetOf(1)
                val shiftsByFace = linkedMapOf(theme.defaultFont to linkedSetOf(0))
                fun measure(spec: TextSpec, parentFamily: String, parentSize: Double, parentShift: Int) {
                    val family = spec.font ?: parentFamily
                    val size = spec.fontSize ?: parentSize
                    val shift = parentShift + spec.offsetY
                    // offsetY 使用默认字体包裹组件，包括嵌套的 parts。
                    shiftsByFace.getValue(theme.defaultFont) += shift
                    for (face in inlineFamilies(spec) + family) {
                        if (face == "minecraft:default") {
                            heights += 10
                        } else {
                            val id = FontRequest(face, size).id
                            shiftsByFace.getOrPut(id) { linkedSetOf() } += shift
                        }
                    }
                    if (spec.format == "parts") {
                        spec.parts.forEach { measure(it, family, size, shift) }
                    } else {
                        heights += kotlin.math.ceil(size + shift.coerceAtLeast(0)).toInt()
                    }
                }
                measure(node.text, theme.defaultFamily, theme.defaultSize, 0)
                heights.forEach { height ->
                    val row = CanvasGeometry.ROW_HEIGHT
                    val anchor = (node.y + height - 1) / row * row
                    val lift = node.y - anchor
                    shiftsByFace.forEach { (face, shifts) ->
                        shifts.forEach { shift -> forFace(face) += lift + shift }
                    }
                }
            }
            if (content is NodeContent.Item && content.showCount) {
                // 数量只含数字，行高等于默认字号。物品边框由固定的 size 决定。
                val labelY = node.y + content.size - theme.defaultSize.toInt()
                forFace(theme.defaultFont) += labelY % CanvasGeometry.ROW_HEIGHT
            }
            node.variants.forEach { visit(it.node) }
        }
        snapshot.menus.values.forEach { menu ->
            menu.pages.values.forEach { page ->
                (page.spec.elements + page.spec.popups.values.flatMap { it.elements }).forEach(::visit)
                page.spec.elements
                    .firstOrNull { it.id == page.spec.modal?.feedback }
                    ?.let {
                        visit(it.copy(text = feedbackText(it.text, theme.formError, theme.palette.error)))
                    }
            }
        }
        return freezeOffsets(offsets)
    }

    private fun freezeOffsets(offsets: Map<String, Set<Int>>): Map<String, Set<Int>> =
        Collections.unmodifiableMap(
            offsets.mapValues { (_, values) -> Collections.unmodifiableSet(values) }
        )

    private fun inlineFamilies(spec: TextSpec): Set<String> {
        if (spec.format != "simple") return emptySet()
        val families = linkedSetOf<String>()
        fun visit(element: JsonElement) {
            if (element.isJsonObject) {
                element.asJsonObject.get("font")?.asString?.let {
                    families += FontRequest.authoredFamily(it)
                }
                element.asJsonObject.entrySet().forEach { visit(it.value) }
            } else if (element.isJsonArray) element.asJsonArray.forEach(::visit)
        }
        visit(JsonParser.parseString(Components.parseSimpleToRaw(InlineText.source(spec))))
        return families
    }

    private fun forEachText(snapshot: MenuSnapshot, theme: UiTheme, visit: (TextSpec) -> Unit) {
        fun node(value: NodeSpec) {
            visit(value.text)
            value.tooltip?.let(visit)
            (value.content as? NodeContent.Select)?.options?.values?.forEach(visit)
            value.variants.forEach { node(it.node) }
        }
        snapshot.menus.values.forEach { menu ->
            menu.spec.pages.values.forEach { page ->
                visit(page.title)
                page.nodes.forEach(::node)
                page.nodes
                    .firstOrNull { it.id == page.modal?.feedback }
                    ?.let { visit(feedbackText(it.text, theme.formError, theme.palette.error)) }
            }
        }
        listOf(theme.closeLabel, theme.formError).forEach(visit)
    }
}
