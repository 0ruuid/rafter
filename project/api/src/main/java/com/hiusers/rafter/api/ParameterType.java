package com.hiusers.rafter.api;

/** 可移植参数类型，不做字符串等隐式转换。 */
public enum ParameterType {
    /** 字符串，不将其他值转换为文本。 */
    STRING,
    /** 布尔值。 */
    BOOLEAN,
    /** 有限普通数值。 */
    NUMBER,
    /** 没有小数部分的有限普通数值。 */
    INTEGER,
    /** 键为字符串、内容为普通值的映射。 */
    OBJECT,
    /** 内容为普通值的列表。 */
    ARRAY,
    /** 任意普通值；是否允许 null 仍由参数的 nullable 约束决定。 */
    ANY
}
