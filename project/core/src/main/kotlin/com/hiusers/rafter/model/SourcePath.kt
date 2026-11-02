package com.hiusers.rafter.model

/** 编译与源码索引共用路径写法，引号键中的点和括号不拆分。 */
object SourcePath {
    private val simpleKey = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    fun field(parent: String, key: String): String {
        if (simpleKey.matches(key)) return "$parent.$key"
        val escaped =
            key.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        return "$parent['$escaped']"
    }

    fun index(parent: String, index: Int): String = "$parent[$index]"
}
