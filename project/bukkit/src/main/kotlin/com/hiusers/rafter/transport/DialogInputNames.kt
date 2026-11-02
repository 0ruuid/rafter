package com.hiusers.rafter.transport

/** 原生输入键仅允许字母、数字和下划线，需转换配置 ID。 */
internal object DialogInputNames {
    fun key(index: Int): String = "input_$index"

    /** 字段顺序属于已发布帧，过期动作仍由会话拒绝。 */
    fun decode(values: Map<String, Any?>, inputIds: List<String>): Map<String, Any?>? {
        val names = inputIds.mapIndexed { index, id -> key(index) to id }.toMap()
        val decoded = linkedMapOf<String, Any?>()
        values.forEach { (key, value) -> decoded[names[key] ?: return null] = value }
        return decoded
    }
}
