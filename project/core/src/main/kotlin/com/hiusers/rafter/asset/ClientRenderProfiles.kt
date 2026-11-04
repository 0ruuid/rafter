package com.hiusers.rafter.asset

import com.hiusers.rafter.model.Values
import java.util.Collections

/** 客户端协议、资源格式与 Shader 接口共用一份声明。 */
data class ClientRenderProfile(
    val directory: String,
    val baseline: String,
    val clients: List<String>,
    val protocols: List<Int>,
    val min: List<Int>,
    val max: List<Int>,
    val glsl: Int,
    val lineWidth: Boolean = false,
    val vulkanInterface: Boolean = false,
    val unifiedText: Boolean = false,
    val canvasItemsRuntimeTested: Boolean = false,
) {
    val textPrograms: List<String>
        get() =
            if (unifiedText) listOf("text")
            else
                listOf(
                    "rendertype_text",
                    "rendertype_text_intensity",
                    "rendertype_text_see_through",
                    "rendertype_text_intensity_see_through",
                )

    fun overlay(): Map<String, Any?> =
        mapOf(
            "directory" to directory,
            "formats" to listOf(min[0], max[0]),
            "min_format" to min,
            "max_format" to max,
        )

    fun manifest(): Map<String, Any?> =
        overlay() +
            mapOf(
                "clients" to clients,
                "protocols" to protocols,
                "baseline" to baseline,
                "glsl" to glsl,
                "line-width-uniform" to lineWidth,
                "explicit-interface" to vulkanInterface,
                "text-programs" to textPrograms,
                "canvas-items-runtime-tested" to canvasItemsRuntimeTested,
            )
}

/** 只匹配已核对的正式协议；快照和未来协议不自动继承相邻版本能力。 */
object ClientRenderProfiles {
    val all: List<ClientRenderProfile> =
        Values.list(
            listOf(
                ClientRenderProfile(
                    "rafter_v1_21_6",
                    "1_21_6",
                    listOf("1.21.6", "1.21.7", "1.21.8"),
                    listOf(771, 772),
                    listOf(63, 0),
                    listOf(64, 0),
                    150,
                    lineWidth = true,
                ),
                ClientRenderProfile(
                    "rafter_v1_21_9",
                    "1_21_9",
                    listOf("1.21.9", "1.21.10"),
                    listOf(773),
                    listOf(69, 0),
                    listOf(69, 0),
                    330,
                    lineWidth = true,
                ),
                ClientRenderProfile(
                    "rafter_v1_21_11",
                    "1_21_11",
                    listOf("1.21.11"),
                    listOf(774),
                    listOf(75, 0),
                    listOf(75, 0),
                    330,
                ),
                ClientRenderProfile(
                    "rafter_v260100",
                    "26_1",
                    listOf("26.1", "26.1.1", "26.1.2"),
                    listOf(775),
                    listOf(84, 0),
                    listOf(84, 0),
                    330,
                ),
                ClientRenderProfile(
                    "rafter_v260200",
                    "26_2",
                    listOf("26.2"),
                    listOf(776),
                    listOf(88, 0),
                    listOf(88, 0),
                    330,
                    unifiedText = true,
                    canvasItemsRuntimeTested = true,
                ),
                ClientRenderProfile(
                    "rafter_v260300",
                    "26_3",
                    listOf("26.3"),
                    listOf(777),
                    listOf(97, 1),
                    listOf(97, 1),
                    330,
                    vulkanInterface = true,
                    unifiedText = true,
                ),
            )
        )

    val packFormats: Set<Pair<Int, Int>> =
        Collections.unmodifiableSet(
            all.flatMap { profile ->
                    (profile.min[0]..profile.max[0]).map { it to profile.min[1] }
                }
                .toSet()
        )

    fun forProtocol(protocol: Int): ClientRenderProfile? = all.firstOrNull {
        protocol in it.protocols
    }

    fun supportsPackFormat(major: Int, minor: Int): Boolean = (major to minor) in packFormats
}
