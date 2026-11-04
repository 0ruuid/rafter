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
                require(it.startsWith("#version ${profile.glsl}\n")) {
                    "Unexpected shader version: ${profile.directory}/$name"
                }
            }
}
