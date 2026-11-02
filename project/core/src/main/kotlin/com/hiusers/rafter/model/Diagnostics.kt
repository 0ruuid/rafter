package com.hiusers.rafter.model

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

/**
 * 源码行列从 1 开始，列按 Unicode 码点，结束位置不包含在范围内。
 * 缺失或派生字段定位到作者节点，不伪造 token。
 */
data class SourceRange(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

data class SourceLocation(
    val file: String,
    val path: String,
    val component: String? = null,
    val range: SourceRange? = null,
)

data class Diagnostic(
    val file: String,
    val path: String,
    val code: String,
    val message: String,
    val component: String? = null,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val range: SourceRange? = null,
    val origins: List<SourceLocation> = emptyList(),
) {
    override fun toString(): String {
        fun position(file: String, path: String, range: SourceRange?) =
            if (range == null) "$file:$path"
            else "$file:${range.startLine}:${range.startColumn}:$path"
        val label = if (severity == DiagnosticSeverity.ERROR) code else "$severity/$code"
        return buildString {
            append("${position(file, path, range)} [$label] $message")
            for (origin in origins) append(
                "\n  from ${position(origin.file, origin.path, origin.range)}"
            )
        }
    }
}

class DocumentError(val diagnostic: Diagnostic) : IllegalArgumentException(diagnostic.toString())
