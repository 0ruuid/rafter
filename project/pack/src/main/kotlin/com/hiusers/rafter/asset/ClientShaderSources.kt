package com.hiusers.rafter.asset

/** 读取随包原版基线；构包期间不联网获取源码。 */
internal object ClientShaderSources {
    fun read(profile: ClientRenderProfile, name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/shaders/client/${profile.baseline}/$name")) {
                "Missing client shader baseline: ${profile.baseline}/$name"
            }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also {
                // include 继承调用程序的 GLSL 版本；26.3 的原版 include 不再声明 #version。
                require(name.startsWith("include/") || it.startsWith("#version ${profile.glsl}\n")) {
                    "Unexpected shader version: ${profile.directory}/$name"
                }
            }
}
