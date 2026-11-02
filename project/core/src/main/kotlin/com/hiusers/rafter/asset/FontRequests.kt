package com.hiusers.rafter.asset

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.NodeSpec
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.feedbackText
import com.hiusers.rafter.text.InlineText
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
            if (spec.format == "simple") {
                // 复用渲染解析器，不用正则猜测字体。
                fun visit(element: JsonElement) {
                    if (element.isJsonObject) {
                        element.asJsonObject.get("font")?.asString?.let {
                            if (it != "minecraft:default")
                                requests += FontRequest(FontRequest.authoredFamily(it), actualSize)
                        }
                        element.asJsonObject.entrySet().forEach { visit(it.value) }
                    } else if (element.isJsonArray) element.asJsonArray.forEach(::visit)
                }
                visit(JsonParser.parseString(Components.parseSimpleToRaw(InlineText.source(spec))))
            }
            spec.parts.forEach { text(it, actualFamily, actualSize) }
            spec.hover?.let { text(it, actualFamily, actualSize) }
        }
        fun node(value: NodeSpec) {
            text(value.text)
            value.tooltip?.let { text(it) }
            (value.content as? NodeContent.Select)?.options?.values?.forEach { text(it) }
            value.variants.forEach { node(it.node) }
        }
        snapshot.menus.values.forEach { menu ->
            menu.spec.pages.values.forEach { page ->
                text(page.title)
                page.nodes.forEach(::node)
                page.nodes
                    .firstOrNull { it.id == page.modal?.feedback }
                    ?.let {
                        text(feedbackText(it.text, theme.formError, theme.palette.error))
                    }
            }
        }
        listOf(theme.closeLabel, theme.formError).forEach { text(it) }
        return java.util.Collections.unmodifiableSet(requests)
    }

    /** 只收集实际文本位移，不生成全部字体与行组合。 */
    fun canvasOffsets(snapshot: com.hiusers.rafter.model.MenuSnapshot, theme: UiTheme): Set<Int> {
        val offsets = TextOffsets.OFFSETS.toMutableSet()
        fun extents(spec: TextSpec, size: Double, shift: Int): List<Pair<Int, Int>> {
            val actualSize = spec.fontSize ?: size
            val actualShift = shift + spec.offsetY
            return if (spec.format == "parts")
                spec.parts.flatMap { extents(it, actualSize, actualShift) }
            else
                listOf(
                    kotlin.math
                        .ceil(actualSize + actualShift.coerceAtLeast(0))
                        .toInt()
                        .coerceAtLeast(1) to actualShift
                )
        }
        fun visit(node: NodeSpec, height: Int) {
            if (node.content == NodeContent.Text) {
                val leaves = extents(node.text, theme.defaultSize, 0)
                leaves.forEach { (bottom, _) ->
                    val anchor = minOf((node.y + bottom - 1) / 9, height / 9 - 1) * 9
                    val lift = node.y - anchor
                    offsets += lift
                    leaves.forEach { (_, shift) -> offsets += lift + shift }
                }
            }
            node.variants.forEach { visit(it.node, height) }
        }
        snapshot.menus.values.forEach { menu ->
            menu.pages.values.forEach { page ->
                (page.spec.elements + page.spec.popups.values.flatMap { it.elements }).forEach {
                    visit(it, page.height)
                }
                page.spec.elements
                    .firstOrNull { it.id == page.spec.modal?.feedback }
                    ?.let {
                        visit(
                            it.copy(
                                text = feedbackText(it.text, theme.formError, theme.palette.error)
                            ),
                            page.height,
                        )
                    }
            }
        }
        return java.util.Collections.unmodifiableSet(offsets)
    }
}
