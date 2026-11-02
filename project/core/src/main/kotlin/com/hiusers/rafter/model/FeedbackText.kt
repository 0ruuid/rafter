package com.hiusers.rafter.model

/** 资源发现与渲染共用错误样式，替换提示不移动基线。 */
fun feedbackText(style: TextSpec, message: TextSpec, color: String): TextSpec =
    style.copy(
        format = "parts",
        value = "",
        parts = listOf(message),
        binding = null,
        inlineParts = emptyList(),
        color = color,
        hover = null,
    )
