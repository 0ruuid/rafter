package com.hiusers.rafter.authoring

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.model.*
import java.security.MessageDigest

/** 标识包含参数、出现位置与行作用域；表达式变化后旧 stub 失效。 */
object PreviewInputs {
    fun key(kind: String, value: Any): String {
        fun ordered(element: JsonElement): JsonElement =
            when {
                element.isJsonObject ->
                    JsonObject().apply {
                        element.asJsonObject
                            .entrySet()
                            .sortedBy { it.key }
                            .forEach { add(it.key, ordered(it.value)) }
                    }
                element.isJsonArray ->
                    com.google.gson.JsonArray().apply {
                        element.asJsonArray.forEach { add(ordered(it)) }
                    }
                else -> element
            }
        val json = ordered(JsonParser.parseString(Json.gson.toJson(value))).toString()
        return "$kind:" +
            MessageDigest.getInstance("SHA-256")
                .digest(json.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    fun describe(snapshot: MenuSnapshot): Map<String, Any?> {
        val requests = linkedMapOf<String, Any?>()
        fun binding(value: BindingSpec) {
            if (
                value.type !in
                    setOf("state", "args", "document", "item", "record", "source", "list")
            )
                requests[key("binding", value)] =
                    mapOf(
                        "kind" to "binding",
                        "spec" to Json.gson.fromJson(Json.gson.toJson(value), Map::class.java),
                    )
        }
        fun condition(value: ConditionSpec?) {
            if (value == null) return
            value.binding?.let(::binding)
            value.compare?.let(::binding)
            if (
                value.type !in
                    setOf("constant", "state-equals", "at-least", "equals", "all", "any", "not")
            )
                requests[key("condition", value)] =
                    mapOf(
                        "kind" to "condition",
                        "spec" to Json.gson.fromJson(Json.gson.toJson(value), Map::class.java),
                    )
            value.children.forEach(::condition)
        }
        fun text(value: TextSpec) {
            value.binding?.let(::binding)
            value.inlineParts.forEach { it.binding?.let(::binding) }
            value.parts.forEach(::text)
            value.hover?.let(::text)
        }
        fun node(value: NodeSpec) {
            text(value.text)
            value.tooltip?.let(::text)
            condition(value.visible)
            condition(value.enabled)
            (value.content as? NodeContent.Meter)?.let {
                binding(it.value)
                binding(it.maximum)
            }
            (value.content as? NodeContent.Select)?.options?.values?.forEach(::text)
            value.variants.forEach {
                condition(it.condition)
                node(it.node)
            }
        }
        snapshot.menus.values.forEach { menu ->
            menu.spec.data.values.forEach { binding(it.binding) }
            menu.pages.values.forEach { page ->
                text(page.spec.title)
                page.spec.nodes.forEach(::node)
                page.spec.lists.forEach {
                    condition(it.visible)
                    condition(it.filter?.condition)
                }
            }
        }
        return Values.contract(mapOf("requests" to requests, "execution" to "disabled"))
    }
}
