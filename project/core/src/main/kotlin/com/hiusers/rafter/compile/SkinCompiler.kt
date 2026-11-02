package com.hiusers.rafter.compile

import com.hiusers.rafter.model.*

/** 按数据节点显式替换参数，不插值 YAML 字符串。 */
internal fun substituteParameters(value: Any?, params: Map<String, Any?>): Any? =
    when (value) {
        is Map<*, *> ->
            if (value.keys == setOf("param")) {
                val key = value["param"]
                require(key is String) { "param must name a parameter" }
                require(key in params) { "Unknown parameter $key" }
                params[key]
            } else value.entries.associate { it.key to substituteParameters(it.value, params) }
        is List<*> -> value.map { substituteParameters(it, params) }
        else -> value
    }

internal data class SkinLocation(val authored: String, val origin: SourceLocation)

internal data class SkinExpansion(
    val page: Map<String, Any?>,
    val locations: Map<String, SkinLocation>,
)

/** 皮肤拥有几何；内容槽只接收文本、数据和动作。 */
internal class SkinCompiler(documents: Map<String, Map<String, Any?>>) {
    private data class Skin(val file: String, val document: Map<String, Any?>)

    private val skins = linkedMapOf<String, Skin>()
    private val geometry =
        setOf(
            "id",
            "x",
            "y",
            "width",
            "height",
            "fit",
            "skin",
            "row-height",
            "page-size",
            "slots",
            "template",
            "multiline",
            "multiline-height",
            "max-lines",
            "label-visible",
        )

    init {
        documents.forEach { (file, doc) ->
            Contract.check(doc, Contract.skinDocument, file, "$")
            val id = doc.getValue("id") as String
            val page = obj(doc["page"], file, "$.page")
            val mode =
                page["mode"] as? String
                    ?: throw DocumentError(
                        Diagnostic(file, "$.page.mode", "MODE", "A skin needs an explicit mode")
                    )
            if (mode !in Contract.modes)
                throw DocumentError(
                    Diagnostic(
                        file,
                        "$.page.mode",
                        "MODE",
                        "A skin needs an explicit supported mode",
                    )
                )
            val unknown =
                page.keys - (Contract.pageFields(mode).keys - setOf("skin", "params", "content"))
            if (unknown.isNotEmpty())
                throw DocumentError(
                    Diagnostic(
                        file,
                        "$.page",
                        "UNKNOWN_FIELD",
                        "Unknown skin page fields: $unknown",
                    )
                )
            if (skins.putIfAbsent(id, Skin(file, doc)) != null)
                throw DocumentError(Diagnostic(file, "$.id", "DUPLICATE_ID", "Duplicate skin $id"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?, file: String, path: String): Map<String, Any?> {
        if (value !is Map<*, *> || value.keys.any { it !is String })
            throw DocumentError(
                Diagnostic(file, path, "FIELD_TYPE", "Expected string-keyed object")
            )
        return value as Map<String, Any?>
    }

    fun expand(page: Map<String, Any?>, file: String, path: String): SkinExpansion {
        val key =
            page["skin"] as? String
                ?: run {
                    for (field in listOf("params", "content")) if (field in page)
                        throw DocumentError(
                            Diagnostic(
                                file,
                                "$path.$field",
                                "CAPABILITY",
                                "$field requires a page skin",
                            )
                        )
                    return SkinExpansion(page, emptyMap())
                }
        val skin =
            skins[key]
                ?: throw DocumentError(
                    Diagnostic(file, "$path.skin", "REFERENCE", "Unknown skin $key")
                )
        for (field in page.keys - setOf("mode", "skin", "params", "content")) throw DocumentError(
            Diagnostic(
                file,
                SourcePath.field(path, field),
                "CAPABILITY",
                "Skin geometry is authored in ${skin.file}, not in the menu",
            )
        )
        val defaults =
            obj(skin.document["params"] ?: emptyMap<String, Any?>(), skin.file, "$.params")
        val passed = obj(page["params"] ?: emptyMap<String, Any?>(), file, "$path.params")
        if ((passed.keys - defaults.keys).isNotEmpty())
            throw DocumentError(
                Diagnostic(
                    file,
                    "$path.params",
                    "SKIN_PARAM",
                    "Unknown skin parameters: ${passed.keys - defaults.keys}",
                )
            )
        val prototype =
            try {
                obj(
                    substituteParameters(skin.document.getValue("page"), defaults + passed),
                    skin.file,
                    "$.page",
                )
            } catch (failure: IllegalArgumentException) {
                throw DocumentError(
                    Diagnostic(
                        file,
                        "$path.params",
                        "SKIN_PARAM",
                        failure.message ?: "Invalid skin parameters",
                    )
                )
            }
        val mode =
            page["mode"] as? String
                ?: throw DocumentError(
                    Diagnostic(file, "$path.mode", "REQUIRED_FIELD", "Missing explicit page mode")
                )
        if (prototype["mode"] != mode)
            throw DocumentError(
                Diagnostic(file, "$path.mode", "MODE", "Page mode must match skin $key")
            )
        if (listOf("skin", "params", "content").any { it in prototype })
            throw DocumentError(
                Diagnostic(skin.file, "$.page", "CAPABILITY", "Skins cannot inherit other skins")
            )
        Contract.check(prototype, Contract.pageFields(mode), skin.file, "$.page")
        val content = obj(page["content"] ?: emptyMap<String, Any?>(), file, "$path.content")
        val used = mutableSetOf<String>()
        val locations =
            linkedMapOf(path to SkinLocation("$path.skin", SourceLocation(skin.file, "$.page")))
        fun node(
            raw: Any?,
            effectivePath: String,
            skinPath: String,
            authored: String = "$path.skin",
        ): Map<String, Any?> {
            var value = obj(raw, skin.file, skinPath)
            var authorPath = authored
            var sourcePath = skinPath
            if (value["type"] == "slot") {
                Contract.check(value, Contract.skinSlot, skin.file, skinPath)
                val id = value.getValue("id") as String
                if (!used.add(id))
                    throw DocumentError(
                        Diagnostic(
                            skin.file,
                            "$skinPath.id",
                            "DUPLICATE_ID",
                            "Duplicate content slot $id",
                            id,
                        )
                    )
                val supplied =
                    content[id]?.let { obj(it, file, SourcePath.field("$path.content", id)) }
                if (supplied == null && value["required"] == true)
                    throw DocumentError(
                        Diagnostic(
                            file,
                            SourcePath.field("$path.content", id),
                            "REQUIRED_FIELD",
                            "Missing content slot $id",
                            id,
                        )
                    )
                val base = obj(value["component"], skin.file, "$skinPath.component")
                if ("id" in base)
                    throw DocumentError(
                        Diagnostic(
                            skin.file,
                            "$skinPath.component.id",
                            "CAPABILITY",
                            "The slot owns its component ID",
                        )
                    )
                if (supplied != null) {
                    val locked = supplied.keys.intersect(geometry)
                    if (locked.isNotEmpty())
                        throw DocumentError(
                            Diagnostic(
                                file,
                                SourcePath.field("$path.content", id),
                                "CAPABILITY",
                                "Layout fields belong to the skin: $locked",
                                id,
                            )
                        )
                    if ("type" in supplied && supplied["type"] != base["type"])
                        throw DocumentError(
                            Diagnostic(
                                file,
                                SourcePath.field("$path.content", id),
                                "CAPABILITY",
                                "Content cannot change the slot component type",
                                id,
                            )
                        )
                    authorPath = SourcePath.field("$path.content", id)
                }
                val textStyle =
                    obj(
                        value["text-style"] ?: emptyMap<String, Any?>(),
                        skin.file,
                        "$skinPath.text-style",
                    )
                Contract.check(
                    textStyle,
                    Contract.text
                        .filterKeys {
                            it in setOf("font", "font-size", "color", "bold", "italic", "offset-y")
                        }
                        .mapValues { it.value.copy(required = false) },
                    skin.file,
                    "$skinPath.text-style",
                )
                value = base + supplied.orEmpty() + ("id" to id)
                if (textStyle.isNotEmpty()) {
                    if ("text" in value && value["text"] == null)
                        throw DocumentError(
                            Diagnostic(
                                file,
                                "$authorPath.text",
                                "FIELD_TYPE",
                                "Text must be a string or text specification",
                            )
                        )
                    val text = value["text"] ?: ""
                    val spec =
                        if (text is String) mapOf("format" to "colored", "value" to text)
                        else obj(text, file, "$authorPath.text")
                    value = value + ("text" to (textStyle + spec))
                }
                sourcePath = "$skinPath.component"
            }
            locations[effectivePath] =
                SkinLocation(
                    authorPath,
                    SourceLocation(skin.file, sourcePath, value["id"] as? String),
                )
            if (value["type"] == "group") {
                val children =
                    value["elements"] as? List<*>
                        ?: throw DocumentError(
                            Diagnostic(
                                skin.file,
                                "$sourcePath.elements",
                                "FIELD_TYPE",
                                "Expected array",
                            )
                        )
                value =
                    value +
                        ("elements" to
                            children.mapIndexed { index, child ->
                                node(
                                    child,
                                    "$effectivePath.elements[$index]",
                                    "$sourcePath.elements[$index]",
                                    authorPath,
                                )
                            })
            }
            return value
        }
        var expanded = prototype
        for (collection in listOf("elements", "components")) {
            val nodes = prototype[collection] as? List<*> ?: continue
            expanded =
                expanded +
                    (collection to
                        nodes.mapIndexed { index, item ->
                            node(item, "$path.$collection[$index]", "$.page.$collection[$index]")
                        })
        }
        val popups = prototype["popups"]?.let { obj(it, skin.file, "$.page.popups") }.orEmpty()
        if (popups.isNotEmpty())
            expanded =
                expanded +
                    ("popups" to
                        popups.mapValues { (id, raw) ->
                            val popup = obj(raw, skin.file, SourcePath.field("$.page.popups", id))
                            Contract.check(
                                popup,
                                Contract.popup,
                                skin.file,
                                SourcePath.field("$.page.popups", id),
                            )
                            val elements =
                                popup["elements"] as? List<*>
                                    ?: throw DocumentError(
                                        Diagnostic(
                                            skin.file,
                                            SourcePath.field("$.page.popups", id) + ".elements",
                                            "FIELD_TYPE",
                                            "Expected array",
                                        )
                                    )
                            popup +
                                ("elements" to
                                    elements.mapIndexed { index, item ->
                                        node(
                                            item,
                                            SourcePath.field("$path.popups", id) +
                                                ".elements[$index]",
                                            SourcePath.field("$.page.popups", id) +
                                                ".elements[$index]",
                                        )
                                    })
                        })
        if ((content.keys - used).isNotEmpty())
            throw DocumentError(
                Diagnostic(
                    file,
                    "$path.content",
                    "REFERENCE",
                    "Unknown skin slots: ${content.keys - used}",
                )
            )
        return SkinExpansion(expanded, locations)
    }
}
