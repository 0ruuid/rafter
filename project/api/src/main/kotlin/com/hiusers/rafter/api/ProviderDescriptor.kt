package com.hiusers.rafter.api

/** 可移植参数类型，不做字符串等隐式转换。 */
enum class ParameterType {
    STRING,
    BOOLEAN,
    NUMBER,
    INTEGER,
    OBJECT,
    ARRAY,
    ANY,
}

/**
 * 参数约束在注册时校验并复制。
 * required 与 nullable 独立；默认值仅补缺失的可选字段，不替换 null，不能与 required 同时启用。
 * INTEGER 接受无小数部分的有限普通数值；枚举按 JSON 值比较，范围包含端点。
 * 默认值和枚举同样校验，普通值限制见 [UiContext]。
 */
data class ParameterSpec
@JvmOverloads
constructor(
    val type: ParameterType,
    val required: Boolean = true,
    val nullable: Boolean = false,
    val hasDefault: Boolean = false,
    val defaultValue: Any? = null,
    val choices: List<Any?>? = null,
    val minimum: Number? = null,
    val maximum: Number? = null,
    val description: String = "",
)

/** 具名参数契约，拒绝未声明参数。注册时深拷贝；Schema 的 default 注解不代替运行时校验。 */
data class ProviderDescriptor
@JvmOverloads
constructor(
    val description: String,
    val parameters: Map<String, ParameterSpec> = emptyMap(),
)
