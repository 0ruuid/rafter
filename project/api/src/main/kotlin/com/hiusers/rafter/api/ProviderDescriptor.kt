package com.hiusers.rafter.api

/**
 * 参数约束在注册时校验并复制。required 与 nullable 独立；默认值仅补缺失的可选字段，
 * 不替换 null，不能与 required 同时启用。
 *
 * [ParameterType.INTEGER] 接受无小数部分的有限普通数值；枚举按 JSON 值比较，范围包含端点。
 * 默认值和枚举同样校验，普通值限制见 [UiContext]。
 */
data class ParameterSpec(
    val type: ParameterType,
    val required: Boolean,
    val nullable: Boolean,
    val hasDefault: Boolean,
    val defaultValue: Any?,
    val choices: List<Any?>?,
    val minimum: Number?,
    val maximum: Number?,
    val description: String,
) {
    companion object {
        /** 可省略但不接受 null，不设置默认值。 */
        @JvmStatic fun optional(type: ParameterType): ParameterSpec = invoke(type, required = false)

        /** Kotlin 支持命名和默认参数；Java 使用 of()。 */
        operator fun invoke(
            type: ParameterType,
            required: Boolean = true,
            nullable: Boolean = false,
            hasDefault: Boolean = false,
            defaultValue: Any? = null,
            choices: List<Any?>? = null,
            minimum: Number? = null,
            maximum: Number? = null,
            description: String = "",
        ): ParameterSpec =
            of(
                type,
                required,
                nullable,
                hasDefault,
                defaultValue,
                choices,
                minimum,
                maximum,
                description,
            )

        /** 创建参数描述，约束在提供器注册时校验；Java 可按顺序使用重载。 */
        @JvmStatic
        @JvmOverloads
        fun of(
            type: ParameterType,
            required: Boolean = true,
            nullable: Boolean = false,
            hasDefault: Boolean = false,
            defaultValue: Any? = null,
            choices: List<Any?>? = null,
            minimum: Number? = null,
            maximum: Number? = null,
            description: String = "",
        ): ParameterSpec =
            ParameterSpec(
                type,
                required,
                nullable,
                hasDefault,
                defaultValue,
                choices,
                minimum,
                maximum,
                description,
            )
    }
}

/** 具名参数契约，拒绝未声明参数。注册时深拷贝；Schema 的 default 注解不代替运行时校验。 */
data class ProviderDescriptor(
    val description: String,
    val parameters: Map<String, ParameterSpec>,
) {
    constructor(description: String) : this(description, emptyMap())
}
