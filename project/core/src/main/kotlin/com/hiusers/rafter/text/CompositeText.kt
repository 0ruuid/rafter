package com.hiusers.rafter.text

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.AssetCatalog
import com.hiusers.rafter.asset.FontRequest
import com.hiusers.rafter.asset.ImageTile
import com.hiusers.rafter.asset.TextOffsets
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.ButtonSkinSpec
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.TextSpec
import java.awt.Color
import kotlin.math.abs
import kotlin.math.round
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.module.chat.colored
import taboolib.module.chat.impl.DefaultSimpleComponent
import taboolib.module.chat.impl.TextBlock

/** 每次渲染独立构造 TabooLib 组件，不修改共享定义。 */
class CompositeText(internal val assets: AssetCatalog) {
    val theme
        get() = assets.theme

    private val textFonts
        get() = assets.fontFamilies.keys + "minecraft:default"

    fun validate(spec: TextSpec) {
        require(spec.font == null || spec.font in textFonts) { "Unknown font ${spec.font}" }
        require(spec.font != "minecraft:default" || spec.fontSize == null) {
            "minecraft:default does not support a configured font-size"
        }
        if (spec.format == "colored" && spec.inlineParts.isNotEmpty()) {
            val raw =
                Components.text(InlineText.source(spec).colored(), color = true).toRawMessage()
            InlineComponents.replace(JsonParser.parseString(raw), spec, emptyMap())
        }
        if (spec.format != "simple") return

        // parseSimpleToRaw 遇到非法输入会返回错误组件，必须先校验源串。
        val source = InlineText.source(spec)
        val parsed = DefaultSimpleComponent(source)
        fun check(block: TextBlock) {
            require(
                block.properties.keys.none {
                    it in
                        setOf("url", "file", "cmd", "command", "suggest", "page", "copy", "insert")
                }
            ) {
                "Text cannot bypass menu actions with click attributes"
            }
            require(
                block.properties.none { (key, value) ->
                    InlineComponents.hasMarker(key) ||
                        key !in setOf("h", "hover") &&
                            InlineComponents.hasMarker(value?.toString().orEmpty())
                }
            ) {
                "Inline bindings are only allowed in text content"
            }
            block.subBlocks.forEach(::check)
        }
        parsed.root.forEach(::check)
        parsed.linkData.values.forEach { linked -> linked.root.forEach(::check) }
        val raw =
            InlineComponents.replace(
                JsonParser.parseString(Components.parseSimpleToRaw(source)),
                spec,
                emptyMap(),
            )
        fun checkWire(element: JsonElement) {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                require(!obj.has("clickEvent") && !obj.has("click_event")) {
                    "Text click attributes must use structured menu actions"
                }
                obj.get("font")?.let {
                    require(FontRequest.authoredFamily(it.asString) in textFonts) {
                        "Unknown text font ${it.asString}"
                    }
                }
                obj.entrySet().forEach { checkWire(it.value) }
            } else if (element.isJsonArray) {
                element.asJsonArray.forEach(::checkWire)
            }
        }
        checkWire(raw)
    }

    fun validateCanvas(spec: TextSpec) {
        val component = render(spec)
        require('\n' !in component.toPlainText() && '\r' !in component.toPlainText()) {
            "Canvas text must be single line"
        }
        fun check(element: JsonElement) {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                require(obj.get("font")?.asString != "minecraft:default") {
                    "Canvas cannot assume metrics for a client-replaceable default font"
                }
                require(
                    obj.keySet().none {
                        it in setOf("translate", "score", "selector", "keybind", "object")
                    }
                ) {
                    "Client-resolved text cannot be measured on a canvas"
                }
                obj.entrySet().forEach { check(it.value) }
            } else if (element.isJsonArray) {
                element.asJsonArray.forEach(::check)
            }
        }
        check(JsonParser.parseString(component.toRawMessage()))
        width(component)
    }

    fun render(
        spec: TextSpec,
        values: Map<BindingSpec, Any?> = emptyMap(),
        inheritedFont: String = theme.defaultFamily,
        inheritedSize: Double = theme.defaultSize,
    ): ComponentText {
        val family = spec.font ?: inheritedFont
        val size = spec.fontSize ?: inheritedSize
        val font = wireFont(family, size)
        val body =
            when (spec.format) {
                "parts" ->
                    Components.empty().apply {
                        spec.parts.forEach { append(render(it, values, family, size)) }
                    }
                "colored" -> {
                    val parsed = Components.text(InlineText.source(spec).colored(), color = true)
                    if (spec.inlineParts.isEmpty()) parsed
                    else {
                        val raw =
                            InlineComponents.replace(
                                JsonParser.parseString(parsed.toRawMessage()),
                                spec,
                                values,
                            )
                        Components.parseRaw(raw.toString())
                    }
                }
                "simple" -> {
                    val raw =
                        InlineComponents.replace(
                            JsonParser.parseString(
                                Components.parseSimpleToRaw(InlineText.source(spec))
                            ),
                            spec,
                            values,
                        )
                    fun resolve(element: JsonElement) {
                        if (element.isJsonObject) {
                            val obj = element.asJsonObject
                            obj.get("font")?.asString?.let {
                                obj.addProperty(
                                    "font",
                                    wireFont(FontRequest.authoredFamily(it), size),
                                )
                            }
                            obj.entrySet().forEach { resolve(it.value) }
                        } else if (element.isJsonArray) element.asJsonArray.forEach(::resolve)
                    }
                    resolve(raw)
                    Components.parseRaw(raw.toString())
                }
                else ->
                    Components.text(
                        if (spec.binding != null) values[spec.binding]?.toString() ?: ""
                        else spec.value,
                        color = false,
                    )
            }
        // 样式作用于独立父组件，不误改前一兄弟或显式字体。
        val component = shadowRoot(body)
        component.font(font)
        spec.color?.let { component.color(Color.decode(it)) }
        if (spec.bold) component.bold()
        if (spec.italic) component.italic()
        spec.hover?.let { component.hoverText(render(it, values, family, size)) }
        return if (spec.offsetY == 0) component else offsetY(component, spec.offsetY)
    }

    private fun wireFont(family: String, size: Double): String {
        if (family == "minecraft:default") return family
        val id = FontRequest(family, size).id
        require(id in assets.fontFaces) {
            "Font face was not compiled: $family at $size; reload and rebuild resources"
        }
        return id
    }

    /** 独立构建器仅变更可见文本字体，不触碰悬停和事件载荷。 */
    fun offsetY(component: ComponentText, offset: Int): ComponentText {
        require(offset in -512..512) { "Text Y offset is outside the provider coordinate domain" }
        val tree = JsonParser.parseString(component.toRawMessage())
        if (offset == 0) return Components.empty().append(Components.parseRaw(tree.toString()))
        fun visit(element: JsonElement) {
            if (element.isJsonArray) element.asJsonArray.forEach(::visit)
            if (!element.isJsonObject) return
            val obj = element.asJsonObject
            obj.get("font")?.asString?.let { font ->
                if (TextOffsets.baseFont(font) in assets.fontFaces)
                    obj.addProperty("font", TextOffsets.font(font, offset))
            }
            listOf("extra", "with", "separator").forEach { key -> obj.get(key)?.let(::visit) }
        }
        visit(tree)
        val parent =
            JsonObject().apply {
                addProperty("text", "")
                addProperty("font", TextOffsets.font(theme.defaultFont, offset))
                add("extra", JsonArray().apply { add(tree) })
            }
        return Components.empty().append(Components.parseRaw(parent.toString()))
    }

    /** 固定版本无阴影设置方法，通过原始组件属性保留原生语义。 */
    private fun shadowRoot(body: ComponentText): ComponentText {
        val parent =
            JsonObject().apply {
                addProperty("text", "")
                addProperty("shadow_color", 0)
                add("extra", JsonArray().apply { add(JsonParser.parseString(body.toRawMessage())) })
            }
        // parseRaw 会结束文本块，先追加再设置新块样式。
        return Components.empty().append(Components.parseRaw(parent.toString()))
    }

    fun glyph(value: String, font: String) =
        shadowRoot(Components.text(value, color = false).font(font))

    fun literal(value: String) = glyph(value, theme.defaultFont)

    /** 颜色字节仅由专用背景字体解释，不影响普通标题。 */
    fun frameTitle(asset: String, width: Int, inset: Int, title: ComponentText): ComponentText {
        require(width in 1..65535 && inset in 0..4)
        val frame = assets.dialogFrames.getValue(asset)
        require(frame.characters.size == frame.advances.size)
        val output = Components.empty()
        val color = Color(width and 255, (width ushr 8) and 255, inset)
        frame.characters.forEachIndexed { index, character ->
            // 原生排序使用 CPU 包围盒，先居中逻辑字形，再由 Shader 定位。
            val before = frame.advances[index] / 2
            output.append(spaces(-before))
            output.append(
                shadowRoot(Components.text(character, color = false).font(frame.font).color(color))
            )
            output.append(spaces(before - frame.advances[index]))
        }
        output.append(title)
        return output
    }

    /** 原生文字滚动会同时移动并裁剪按钮皮肤，图片标签不能使用它。 */
    fun validateButtonLabel(spec: TextSpec, buttonWidth: Int) {
        validateCanvas(spec)
        buttonLabelWidth(render(spec), buttonWidth)
    }

    private fun buttonLabelWidth(label: ComponentText, buttonWidth: Int): Int {
        require('\n' !in label.toPlainText() && '\r' !in label.toPlainText()) {
            "Button labels must be single line"
        }
        val measured = kotlin.math.ceil(width(label)).toInt()
        require(measured <= buttonWidth - NativeGeometry.BUTTON_TEXT_MARGIN * 2) {
            "Button label is $measured pixels wide; skinned button allows ${buttonWidth - NativeGeometry.BUTTON_TEXT_MARGIN * 2}"
        }
        return measured
    }

    /** 按真实字宽绘制标签背景后恢复光标，交互仍由原生控件负责。 */
    fun buttonLabel(skin: ButtonSkinSpec, buttonWidth: Int, label: ComponentText): ComponentText {
        val measured = buttonLabelWidth(label, buttonWidth)
        val request = skin.request(buttonWidth)
        require(assets.buttonSkins[request.id] == skin.inset) {
            "Button skin was not compiled; reload and rebuild resources"
        }
        val output = Components.empty()
        // 原生居中使用整数半宽，不使用小数 advance。
        val before = measured / 2 - buttonWidth / 2 + skin.inset
        output.append(spaces(before))
        var cursor = 0
        imageTiles(request.id).forEach { tile ->
            output.append(spaces(tile.x - cursor))
            output.append(glyph(tile.character, "rafter:button_skins/${skin.inset}"))
            cursor = tile.x + tile.advance
        }
        output.append(spaces(-cursor - before))
        require(
            measured + kotlin.math.abs(skin.labelOffsetX) * 2 <=
                buttonWidth - NativeGeometry.BUTTON_TEXT_MARGIN * 2
        ) {
            "Skinned button label offset would paint outside the native button"
        }
        output.append(spaces(skin.labelOffsetX))
        output.append(label)
        output.append(spaces(-skin.labelOffsetX))
        return output
    }

    fun imageHeight(id: String) = assets.images.getValue(id).height

    fun imageTiles(id: String) = assets.images.getValue(id).tiles

    fun tile(tile: ImageTile) = glyph(tile.character, "rafter:images")

    fun image(id: String): ComponentText {
        val output = Components.empty()
        val rows = imageTiles(id).groupBy { it.y }.toSortedMap()
        var previousY = 0
        rows.forEach { (y, tiles) ->
            if (y > previousY) output.append(literal("\n".repeat((y - previousY) / 9)))
            var advance = 0
            tiles.forEach { tile ->
                output.append(spaces(tile.x - advance))
                output.append(tile(tile))
                advance = tile.x + tile.advance
            }
            output.append(spaces(-advance))
            previousY = y
        }
        return output
    }

    fun spaces(offset: Number): ComponentText {
        val value = offset.toDouble()
        require(value.isFinite() && value in -8191.0..8191.0)
        val code = if (value >= 0) 0xe100 else 0xe140
        val amount = round(abs(value) * 64).toInt()
        return glyph(
            buildString {
                for (bit in 0..18) if (amount and (1 shl bit) != 0)
                    append(Character.toString(code + bit))
            },
            "rafter:space",
        )
    }

    /** 按真实字形步进裁切，保留样式，不重新解析绑定数据。 */
    fun fitLine(component: ComponentText, limit: Int): ComponentText {
        require(limit >= 0)
        if (limit == 0) return literal("")
        var remaining = limit.toDouble()
        var stopped = false
        fun clip(value: String, font: String, bold: Boolean): String = buildString {
            value.codePoints().toArray().forEach { code ->
                if (!stopped) {
                    val glyph = Character.toString(code)
                    if (code == 10 || code == 13) stopped = true
                    else {
                        val advance = assets.textWidth(glyph, bold, font)
                        if (advance > remaining) stopped = true
                        else {
                            append(glyph)
                            remaining -= advance
                        }
                    }
                }
            }
        }
        fun visit(element: JsonElement, font: String, bold: Boolean): JsonElement {
            if (element.isJsonPrimitive)
                return com.google.gson.JsonPrimitive(clip(element.asString, font, bold))
            if (element.isJsonArray)
                return JsonArray().apply {
                    element.asJsonArray.forEach { add(visit(it, font, bold)) }
                }
            val obj = element.asJsonObject.deepCopy()
            val actualFont = obj.get("font")?.asString ?: font
            val actualBold = obj.get("bold")?.asBoolean ?: bold
            obj.get("text")?.let {
                obj.addProperty("text", clip(it.asString, actualFont, actualBold))
            }
            obj.getAsJsonArray("extra")?.let { obj.add("extra", visit(it, actualFont, actualBold)) }
            return obj
        }
        return Components.empty()
            .append(
                Components.parseRaw(
                    visit(
                            JsonParser.parseString(component.toRawMessage()),
                            theme.defaultFont,
                            false,
                        )
                        .toString()
                )
            )
    }

    /** 按实际组件树测量自然行框，悬停内容不计入正文。 */
    fun bounds(component: ComponentText): Pair<Int, Int> {
        fun size(element: JsonElement, inherited: String): Double {
            if (element.isJsonPrimitive)
                return if (element.asString.isEmpty()) 0.0
                else
                    (assets.fontFace(inherited)?.request?.size ?: 10.0) +
                        TextOffsets.offset(inherited).coerceAtLeast(0)
            if (element.isJsonArray)
                return element.asJsonArray.maxOfOrNull { size(it, inherited) } ?: 0.0
            val obj = element.asJsonObject
            val font = obj.get("font")?.asString ?: inherited
            var result =
                if (obj.get("text")?.asString?.isNotEmpty() == true)
                    (assets.fontFace(font)?.request?.size ?: 10.0) +
                        TextOffsets.offset(font).coerceAtLeast(0)
                else 0.0
            obj.getAsJsonArray("extra")?.forEach { result = maxOf(result, size(it, font)) }
            return result
        }
        return kotlin.math.ceil(width(component)).toInt().coerceAtLeast(1) to
            kotlin.math
                .ceil(size(JsonParser.parseString(component.toRawMessage()), theme.defaultFont))
                .toInt()
                .coerceAtLeast(1)
    }

    /** 测量实际发送的字体和样式，不降级为传统文本。 */
    fun width(component: ComponentText): Double {
        fun measure(element: JsonElement, font: String, bold: Boolean): Double {
            if (element.isJsonPrimitive) return assets.textWidth(element.asString, bold, font)
            if (element.isJsonArray) return element.asJsonArray.sumOf { measure(it, font, bold) }
            val obj = element.asJsonObject
            val actualFont = obj.get("font")?.asString ?: font
            val actualBold = obj.get("bold")?.asBoolean ?: bold
            var width =
                obj.get("text")?.let { assets.textWidth(it.asString, actualBold, actualFont) }
                    ?: 0.0
            obj.getAsJsonArray("extra")?.forEach { width += measure(it, actualFont, actualBold) }
            return width
        }
        return measure(JsonParser.parseString(component.toRawMessage()), theme.defaultFont, false)
    }
}
