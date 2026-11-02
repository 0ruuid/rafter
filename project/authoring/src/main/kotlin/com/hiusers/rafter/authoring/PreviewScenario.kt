package com.hiusers.rafter.authoring

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.Field
import com.hiusers.rafter.compile.YamlSource
import com.hiusers.rafter.model.Values
import java.nio.file.Path

/** 本地预览数据；stub 不是脚本或业务实现。 */
data class PreviewScenario(
    val args: Map<String, Any?> = emptyMap(),
    val state: Map<String, Any?> = emptyMap(),
    val form: Map<String, Any?> = emptyMap(),
    val stubs: Map<String, Any?> = emptyMap(),
    val errors: Map<String, Any?> = emptyMap(),
    val origin: String? = null,
    val originPopup: String? = null,
    val popup: String? = null,
) {
    companion object {
        private val fields =
            linkedMapOf(
                "args" to Field("object"),
                "state" to Field("object"),
                "form" to Field("object"),
                "stubs" to Field("object"),
                "errors" to Field("object"),
                "origin" to Field("string"),
                "origin-popup" to Field("string"),
                "popup" to Field("string"),
            )

        fun schema(): Map<String, Any?> = Contract.objectSchema(fields)

        fun read(root: Path, relative: String): PreviewScenario {
            val source = YamlSource.read(root, root.resolve(relative))
            val raw = Values.map(YamlSource.decode(source.text))
            Contract.check(raw, fields, relative, "$")
            @Suppress("UNCHECKED_CAST")
            fun map(name: String): Map<String, Any?> =
                Values.map(raw[name] as? Map<String, Any?> ?: emptyMap())
            return PreviewScenario(
                map("args"),
                map("state"),
                map("form"),
                map("stubs"),
                map("errors"),
                raw["origin"] as? String,
                raw["origin-popup"] as? String,
                raw["popup"] as? String,
            )
        }
    }
}
