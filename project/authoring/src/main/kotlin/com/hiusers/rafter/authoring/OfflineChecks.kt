package com.hiusers.rafter.authoring

/** 显式记录缺失的平台检查，不当作成功。 */
class OfflineChecks {
    private val scripts = linkedSetOf<String>()
    private val extensions = linkedSetOf<String>()
    val incomplete: Boolean
        get() = scripts.isNotEmpty() || extensions.isNotEmpty()

    fun script(source: String) {
        scripts += source
    }

    fun extension(type: String, id: String, @Suppress("UNUSED_PARAMETER") args: Map<String, Any?>) {
        extensions += "$type:$id"
    }

    fun report(compiled: Boolean = true): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "check" to "documents-assets-text-layout",
                "status" to if (compiled) "checked" else "not-completed",
            ),
            mapOf(
                "check" to "kether-syntax",
                "status" to if (scripts.isEmpty()) "not-required" else "unavailable",
                "scripts" to scripts.size,
                "reason" to
                    "Requires target TabooLib Kether environment; no script executed offline",
            ),
            mapOf(
                "check" to "extension-availability-and-arguments",
                "status" to if (extensions.isEmpty()) "not-required" else "unavailable",
                "extensions" to extensions.toList(),
            ),
            mapOf("check" to "business-actions-and-minecraft-client", "status" to "not-executed"),
        )
}
