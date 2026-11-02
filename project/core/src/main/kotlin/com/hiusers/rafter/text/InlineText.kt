package com.hiusers.rafter.text

import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.InlineTextPart
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.Values
import taboolib.common.util.VariableReader

/** 只编译模板，不执行脚本或将结果解析为格式。 */
object InlineText {
    const val START_MARKER: Char = '\ufdd0'
    const val END_MARKER: Char = '\ufdd1'

    fun marker(index: Int): String = "$START_MARKER$index$END_MARKER"

    fun source(spec: TextSpec): String =
        if (spec.inlineParts.isEmpty()) spec.value
        else
            buildString {
                spec.inlineParts.forEachIndexed { index, part ->
                    append(if (part.binding == null) part.text else marker(index))
                }
            }

    fun compile(
        source: String,
        file: String,
        path: String,
        binding: ((Map<String, Any?>, String) -> BindingSpec)?,
    ): List<InlineTextPart> {
        fun fail(code: String, message: String, offset: Int): Nothing =
            throw DocumentError(Diagnostic(file, path, code, "$message at character ${offset + 1}"))

        var opening = -1
        var expressions = 0
        var escaped = false
        var offset = 0
        // 此处只检查分隔符，拆分和转义交给 VariableReader。
        while (offset < source.length) {
            if (source[offset] == START_MARKER || source[offset] == END_MARKER) {
                fail("TEXT_TEMPLATE", "Reserved inline text marker is not allowed", offset)
            }
            val start = source.startsWith("{{", offset)
            val end = source.startsWith("}}", offset)
            if (!start && !end) {
                offset++
                continue
            }
            // VariableReader 仅检查紧邻的反斜线，不按奇偶数量处理。
            if (offset > 0 && source[offset - 1] == '\\') {
                escaped = true
            } else if (start) {
                if (opening >= 0)
                    fail("TEXT_DEPTH", "Nested inline expressions are not allowed", offset)
                if (++expressions > 64)
                    fail("TEXT_DEPTH", "Inline text exceeds 64 expressions", offset)
                opening = offset
            } else {
                if (opening < 0) fail("TEXT_TEMPLATE", "Closing }} has no matching {{", offset)
                if (source.substring(opening + 2, offset).isBlank()) {
                    fail("TEXT_TEMPLATE", "Inline expression must not be empty", opening)
                }
                opening = -1
            }
            offset += 2
        }
        if (opening >= 0) fail("TEXT_TEMPLATE", "Inline expression is missing closing }}", opening)
        if (expressions == 0 && !escaped) return emptyList()

        return Values.list(
            VariableReader().readToFlatten(source).mapIndexed { index, part ->
                if (!part.isVariable) InlineTextPart(text = part.text)
                else {
                    val parser =
                        binding
                            ?: throw DocumentError(
                                Diagnostic(
                                    file,
                                    path,
                                    "TEXT_BINDING",
                                    "This static text cannot use inline Kether bindings",
                                )
                            )
                    InlineTextPart(
                        binding =
                            parser(mapOf("type" to "kether", "script" to part.text.trim()), path)
                                .copy(occurrence = "$file:$path:$index")
                    )
                }
            }
        )
    }
}
