package com.hiusers.rafter.api

/** 界面操作失败码，不携带平台对象或内部异常。 */
enum class UiFailureCode {
    ENGINE_STOPPED,
    PLAYER_UNAVAILABLE,
    UNSUPPORTED_CLIENT,
    NO_VALID_SNAPSHOT,
    UNKNOWN_MENU,
    NO_PERMISSION,
    RESOURCE_PACK_LOADING,
    SUPERSEDED,
    TIMED_OUT,
    PROVIDER_UNAVAILABLE,
    ACTION_DENIED,
    INVALID_FORM,
    INVALID_ARGUMENT,
    INTERNAL_ERROR,
}

/** 按 [code] 处理失败；message、cause 仅供诊断，不直接展示给玩家。 */
class UiOperationException
@JvmOverloads
constructor(
    val code: UiFailureCode,
    message: String = code.name,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
