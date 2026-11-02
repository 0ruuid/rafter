package com.hiusers.rafter.model

/** 路径相对插件数据目录，根配置和语言位置保持不变。 */
object ProjectPaths {
    const val CORE = "core"
    const val MENUS = "$CORE/menus"
    const val DATA = "$CORE/data"
    const val UI = "$CORE/ui"
    const val TEMPLATES = "$UI/templates"
    const val SKINS = "$UI/skins"
    const val THEME = "$UI/theme.yml"
    const val GENERATE = "generate"
    const val PACK = "$GENERATE/resource-pack"
    const val ARCHIVE = "$GENERATE/resource-pack.zip"
    const val ALLOCATION = "$PACK/allocation.json"
    const val CONTRACT = "$GENERATE/contract"
    const val HOST_CACHE = "$GENERATE/.hosted-packs"
}
