package com.hiusers.rafter.api

/** 按 [code] 处理失败；message、cause 仅供诊断，不直接展示给玩家。 */
class UiOperationException(
    val code: UiFailureCode,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    constructor(code: UiFailureCode) : this(code, code.name, null)

    constructor(code: UiFailureCode, message: String) : this(code, message, null)

    companion object {
        operator fun invoke(
            code: UiFailureCode,
            message: String = code.name,
            cause: Throwable? = null,
        ): UiOperationException = of(code, message, cause)

        @JvmStatic
        @JvmOverloads
        fun of(
            code: UiFailureCode,
            message: String = code.name,
            cause: Throwable? = null,
        ): UiOperationException = UiOperationException(code, message, cause)
    }
}
