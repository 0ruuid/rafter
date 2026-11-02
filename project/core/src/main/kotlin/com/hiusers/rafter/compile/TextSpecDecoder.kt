package com.hiusers.rafter.compile

import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.Values
import com.hiusers.rafter.text.InlineText

/** 作者文本支持 TabooLib 颜色；动态结果保持字面数据，此处不执行绑定。 */
class TextSpecDecoder(
    private val file: String,
    private val validate: (TextSpec) -> Unit = {},
    private val binding: ((Map<String, Any?>, String) -> BindingSpec)? = null,
) {
    fun decode(value: Any?, path: String, depth: Int = 0): TextSpec {
        if (depth > 16) fail(path, "TEXT_DEPTH", "Text nesting exceeds 16")
        val result =
            if (value is String)
                TextSpec(
                    format = "colored",
                    value = value,
                    inlineParts = InlineText.compile(value, file, path, binding),
                )
            else {
                val fields = objectValue(value, path)
                Contract.check(fields, Contract.text, file, path)
                val format = fields["format"] as String
                if (format !in Contract.textFormats)
                    fail(path, "TEXT_FORMAT", "Unknown text format")
                if (
                    format == "parts" && "parts" !in fields ||
                        format != "parts" && "parts" in fields ||
                        format == "parts" && "value" in fields
                ) {
                    fail(path, "TEXT_FORMAT", "parts and value are mutually exclusive")
                }
                if (format != "literal" && "binding" in fields)
                    fail(path, "TEXT_FORMAT", "Only literal text can bind dynamic data")
                if ("binding" in fields && "value" in fields)
                    fail(path, "TEXT_FORMAT", "value and binding are mutually exclusive")
                if (format in setOf("colored", "simple") && "value" !in fields)
                    fail(path, "TEXT_FORMAT", "$format requires a value")
                val color = fields["color"] as? String
                if (color != null && !color.matches(Regex("#[0-9a-fA-F]{6}")))
                    fail(path, "COLOR", "Expected #RRGGBB")
                val text = fields["value"] as? String ?: ""
                TextSpec(
                    format = format,
                    value = text,
                    parts =
                        Values.list(
                            (fields["parts"] as? List<*>)
                                ?.mapIndexed { index, part ->
                                    decode(part, "$path.parts[$index]", depth + 1)
                                }
                                .orEmpty()
                        ),
                    binding =
                        fields["binding"]?.let {
                            val parser =
                                binding
                                    ?: fail(
                                        "$path.binding",
                                        "TEXT_BINDING",
                                        "This text cannot bind session data",
                                    )
                            parser(objectValue(it, "$path.binding"), "$path.binding")
                        },
                    font = fields["font"] as? String,
                    fontSize = (fields["font-size"] as? Number)?.toDouble(),
                    color = color,
                    bold = fields["bold"] as? Boolean ?: false,
                    italic = fields["italic"] as? Boolean ?: false,
                    offsetY = (fields["offset-y"] as? Number)?.toInt() ?: 0,
                    hover = fields["hover"]?.let { decode(it, "$path.hover", depth + 1) },
                    inlineParts =
                        if (format == "colored" || format == "simple")
                            InlineText.compile(text, file, "$path.value", binding)
                        else emptyList(),
                )
            }
        try {
            validate(result)
        } catch (failure: DocumentError) {
            throw failure
        } catch (failure: Exception) {
            fail(path, "TEXT", failure.message ?: "Invalid text")
        }
        return result
    }

    private fun objectValue(value: Any?, path: String): Map<String, Any?> {
        if (value !is Map<*, *> || value.keys.any { it !is String })
            fail(path, "FIELD_TYPE", "Expected an object")
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun fail(path: String, code: String, message: String): Nothing =
        throw DocumentError(Diagnostic(file, path, code, message))
}
