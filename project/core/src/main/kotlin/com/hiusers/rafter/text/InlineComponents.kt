package com.hiusers.rafter.text

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.TextSpec

/** 只解析一次作者文本，不把运行时结果当作模板继续解释。 */
internal object InlineComponents {
    private val marker = Regex("${InlineText.START_MARKER}([0-9]+)${InlineText.END_MARKER}")

    fun hasMarker(value: String): Boolean =
        InlineText.START_MARKER in value || InlineText.END_MARKER in value

    fun replace(raw: JsonElement, spec: TextSpec, values: Map<BindingSpec, Any?>): JsonElement {
        fun literal(value: String) = JsonObject().apply { addProperty("text", value) }
        fun rejectMarkers(element: JsonElement) {
            when {
                element.isJsonObject ->
                    element.asJsonObject.entrySet().forEach { (key, value) ->
                        require(!hasMarker(key)) {
                            "Inline bindings are only allowed in text content"
                        }
                        rejectMarkers(value)
                    }
                element.isJsonArray -> element.asJsonArray.forEach(::rejectMarkers)
                element.isJsonPrimitive ->
                    require(!hasMarker(element.asString)) {
                        "Inline bindings are only allowed in text content"
                    }
            }
        }
        fun fragments(source: String): JsonArray {
            val parts = JsonArray()
            fun static(value: String) {
                require(!hasMarker(value)) { "Invalid inline text marker" }
                if (value.isNotEmpty()) parts.add(literal(value))
            }
            var offset = 0
            marker.findAll(source).forEach { match ->
                static(source.substring(offset, match.range.first))
                val index = match.groupValues[1].toIntOrNull()
                val binding = index?.let { spec.inlineParts.getOrNull(it)?.binding }
                requireNotNull(binding) { "Unknown inline text marker" }
                parts.add(literal(values[binding]?.toString().orEmpty()))
                offset = match.range.last + 1
            }
            static(source.substring(offset))
            return parts
        }
        fun visit(element: JsonElement): JsonElement {
            if (element.isJsonPrimitive) {
                val source = element.asString
                return if (!hasMarker(source)) element
                else
                    JsonObject().apply {
                        addProperty("text", "")
                        add("extra", fragments(source))
                    }
            }
            if (element.isJsonArray)
                return JsonArray().apply { element.asJsonArray.forEach { add(visit(it)) } }
            if (!element.isJsonObject) return element
            val obj = element.asJsonObject
            // 先保存作者字段，新插入的数据不递归解析。
            obj.entrySet().toList().forEach { (key, value) ->
                require(!hasMarker(key)) { "Inline bindings are only allowed in text content" }
                when (key) {
                    "text" -> Unit
                    "extra",
                    "with",
                    "separator" -> obj.add(key, visit(value))
                    "hoverEvent",
                    "hover_event" -> {
                        val event = value.takeIf { it.isJsonObject }?.asJsonObject
                        if (event?.get("action")?.asString == "show_text") {
                            event.entrySet().toList().forEach { (eventKey, contents) ->
                                require(!hasMarker(eventKey)) { "Invalid hover attribute" }
                                if (eventKey in setOf("contents", "value"))
                                    event.add(eventKey, visit(contents))
                                else rejectMarkers(contents)
                            }
                        } else rejectMarkers(value)
                    }
                    else -> rejectMarkers(value)
                }
            }
            val source = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return obj
            if (hasMarker(source)) {
                val parts = fragments(source)
                obj.getAsJsonArray("extra")?.forEach { parts.add(it) }
                obj.addProperty("text", "")
                obj.add("extra", parts)
            }
            return obj
        }
        return visit(raw)
    }
}
