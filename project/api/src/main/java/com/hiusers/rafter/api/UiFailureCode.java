package com.hiusers.rafter.api;

/** 界面操作失败码，不携带平台对象或内部异常。 */
public enum UiFailureCode {
    /** 引擎已停止，无法接受操作。 */
    ENGINE_STOPPED,
    /** 玩家已离线或无法继续接收界面。 */
    PLAYER_UNAVAILABLE,
    /** 客户端版本不支持当前页面所需的能力。 */
    UNSUPPORTED_CLIENT,
    /** 尚无成功加载的菜单快照。 */
    NO_VALID_SNAPSHOT,
    /** 请求的菜单不存在。 */
    UNKNOWN_MENU,
    /** 玩家不具备打开菜单所需的权限。 */
    NO_PERMISSION,
    /** 目标资源包尚未确认加载完成。 */
    RESOURCE_PACK_LOADING,
    /** 会话或操作已被替换，旧结果不再适用。 */
    SUPERSEDED,
    /** 操作超过配置的完成时限。 */
    TIMED_OUT,
    /** 所需提供器不可用，包括所属插件已停用或注册已撤销。 */
    PROVIDER_UNAVAILABLE,
    /** 动作未通过权限、条件或当前视图校验。 */
    ACTION_DENIED,
    /** 提交的表单未通过校验。 */
    INVALID_FORM,
    /** 调用参数或提供器数据不符合契约。 */
    INVALID_ARGUMENT,
    /** 未归类的内部错误。 */
    INTERNAL_ERROR
}
