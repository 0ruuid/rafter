package com.hiusers.rafter.compile

import com.hiusers.rafter.asset.CanvasItems
import com.hiusers.rafter.asset.ClientRenderProfiles
import com.hiusers.rafter.asset.FontRequest
import com.hiusers.rafter.asset.InputAppearances
import com.hiusers.rafter.model.ButtonRole
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.ModalCellRole
import com.hiusers.rafter.model.NativeBodyPosition
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.SourcePath
import com.hiusers.rafter.model.Values
import java.math.BigDecimal

/**
 * 字段约束共用于解码、Schema 和创作描述。 范围包含端点，倍数按十进制计算；字符串长度按 Unicode 码点。 [items] 指向子定义，default 为 null
 * 表示未指定，不代表计算默认值。
 */
data class Field(
    val type: String,
    val required: Boolean = false,
    val items: String? = null,
    val minimum: Number? = if (type == "integer") Int.MIN_VALUE else null,
    val maximum: Number? = if (type == "integer") Int.MAX_VALUE else null,
    val multipleOf: Number? = null,
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val minProperties: Int? = null,
    val maxProperties: Int? = null,
    val propertyPattern: String? = null,
    val values: List<Any> = emptyList(),
    val default: Any? = null,
    val code: String = "RANGE",
    val uniqueItems: Boolean = false,
) {
    internal fun keywords(): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
            multipleOf?.let { put("multipleOf", it) }
            pattern?.let { put("pattern", it) }
            minLength?.let { put("minLength", it) }
            maxLength?.let { put("maxLength", it) }
            minItems?.let { put("minItems", it) }
            maxItems?.let { put("maxItems", it) }
            if (uniqueItems) put("uniqueItems", true)
            minProperties?.let { put("minProperties", it) }
            maxProperties?.let { put("maxProperties", it) }
            propertyPattern?.let { put("propertyNames", mapOf("pattern" to it)) }
            if (this@Field.values.size == 1) put("const", this@Field.values.single())
            if (this@Field.values.size > 1) put("enum", this@Field.values)
            default?.let { put("default", it) }
        }

    internal fun schema(): Map<String, Any?> {
        val shape =
            when (type) {
                "text",
                "binding",
                "condition",
                "frame",
                "modal",
                "button-skin",
                "input-appearance",
                "list-filter" -> mapOf("\$ref" to "#/\$defs/$type")
                "actions" ->
                    mapOf("type" to "array", "items" to mapOf("\$ref" to "#/\$defs/action"))
                "array" ->
                    mapOf("type" to "array") +
                        (items?.let {
                            mapOf(
                                "items" to
                                    if (it == "string") mapOf("type" to "string")
                                    else mapOf("\$ref" to "#/\$defs/$it")
                            )
                        } ?: emptyMap())
                "object" ->
                    mapOf("type" to "object") +
                        (items?.let {
                            mapOf("additionalProperties" to mapOf("\$ref" to "#/\$defs/$it"))
                        } ?: emptyMap())
                "any" -> emptyMap()
                else -> mapOf("type" to type)
            }
        return shape + keywords()
    }
}

/** 共享字段定义不得修改；按页面模式派生约束和默认值。 */
object Contract {
    // 不同于 $，此结尾断言不会在末尾换行前匹配。
    private const val ID = "^[a-z0-9][a-z0-9_.:/-]*(?![\\s\\S])"
    private const val COMPONENT_ID = "^[a-z0-9][a-z0-9_.-]*(?![\\s\\S])"
    private const val COLOR = "^#[0-9a-fA-F]{6}(?![\\s\\S])"
    private const val ASSET_ID =
        "^rafter:(?!\\.{1,2}(?:/|$))(?!.*?/\\.{1,2}(?:/|$))[a-z0-9_./-]+(?![\\s\\S])"
    private val color =
        Field("string", pattern = COLOR, minLength = 7, maxLength = 7, code = "COLOR")
    private val spacing =
        Field("integer", minimum = 0, maximum = 864, multipleOf = 9, code = "LAYOUT")

    val modes =
        linkedMapOf(
            "native" to
                setOf(
                    "text",
                    "image",
                    "item",
                    "button",
                    "input",
                    "checkbox",
                    "select",
                    "slider",
                    "list",
                    "template",
                ),
            "canvas" to
                setOf("text", "image", "meter", "hit-area", "item", "group", "list", "template"),
        )
    val settings =
        linkedMapOf(
            "default-menu" to Field("string", true),
            "idle-timeout-seconds" to
                Field("integer", minimum = 30, maximum = 86400, default = 600),
            "resource-pack" to Field("object"),
            "runtime" to Field("object"),
        )
    val runtime =
        linkedMapOf(
            "render-timeout-seconds" to Field("integer", minimum = 1, maximum = 120, default = 10),
            "action-timeout-seconds" to Field("integer", minimum = 1, maximum = 300, default = 30),
            "read-concurrency" to Field("integer", minimum = 1, maximum = 16, default = 4),
        )
    val asset =
        linkedMapOf(
            "theme" to Field("object"),
            "images" to Field("object", true, "assetImage", propertyPattern = ASSET_ID),
            "hide-dialog-warning" to Field("boolean"),
            "hide-canvas-focus-outline" to Field("boolean"),
            "hide-native-focus-outline" to Field("boolean"),
        )
    val theme =
        linkedMapOf(
            "defaults" to Field("object", true),
            "styles" to Field("object"),
            "oversample" to Field("number", minimum = 1, maximum = 8, default = 1),
            "palette" to Field("object", true),
            "controls" to Field("object", true),
            "hide-dialog-warning" to Field("boolean", true),
            "hide-canvas-focus-outline" to Field("boolean", true),
            "hide-native-focus-outline" to Field("boolean", true),
        )
    private val fontFamily = Field("string", minLength = 1)
    private val fontSize =
        Field("integer", minimum = FontRequest.MIN_SIZE, maximum = FontRequest.MAX_SIZE)
    val themeDefaults =
        linkedMapOf(
            "font" to fontFamily.copy(required = true),
            "font-size" to fontSize.copy(required = true),
        )
    val style =
        linkedMapOf(
            "font" to fontFamily,
            "font-size" to fontSize,
            "height" to Field("integer", minimum = 1, maximum = LayoutCompiler.MAX_HEIGHT),
        )
    val themePalette = listOf("error").associateWith { color.copy(required = true) }
    val themeControls = listOf("close", "form-error").associateWith { Field("text", true) }

    val assetImage =
        linkedMapOf(
            "content-width" to Field("integer", minimum = 1, maximum = 864),
            "content-height" to Field("integer", minimum = 1, maximum = LayoutCompiler.MAX_HEIGHT),
            "width" to Field("integer", true, minimum = 9, maximum = 864, multipleOf = 9),
            "height" to
                Field(
                    "integer",
                    true,
                    minimum = 9,
                    maximum = LayoutCompiler.MAX_HEIGHT,
                    multipleOf = 9,
                ),
            "color" to color,
            "source" to Field("string"),
            "scale" to Field("integer", minimum = 1, maximum = 8, default = 1),
        )
    val menu =
        linkedMapOf(
            "id" to Field("string", true, pattern = ID, minLength = 1, code = "ID"),
            "entry" to Field("string", true),
            "permission" to Field("string"),
            "state" to Field("object"),
            "data" to Field("object", items = "dataSource", propertyPattern = COMPONENT_ID),
            "pages" to Field("object", true, "page", propertyPattern = ID),
        )

    /** 模板正文保留为数据，显式替换参数后再校验。 */
    val template =
        linkedMapOf(
            "id" to Field("string", true),
            "params" to Field("object"),
            "component" to Field("object", true),
        )
    val page =
        linkedMapOf(
            "mode" to Field("string", true, values = modes.keys.toList(), code = "MODE"),
            "title" to Field("text", default = ""),
            "skin" to Field("string", minLength = 1),
            "params" to Field("object"),
            "content" to Field("object", items = "slotContent", propertyPattern = COMPONENT_ID),
            "frame" to Field("frame"),
            "modal" to Field("modal"),
            "width" to Field("integer", minimum = 90, maximum = 864, code = "LAYOUT"),
            "columns" to Field("integer", minimum = 1, default = 1, code = "LAYOUT"),
            "components" to Field("array", items = "node"),
            "elements" to Field("array", items = "node"),
            "popups" to Field("object", items = "popup", propertyPattern = COMPONENT_ID),
            "native-body-position" to
                Field(
                    "string",
                    values = NativeBodyPosition.entries.map { it.id },
                    default = NativeBodyPosition.AFTER_CANVAS.id,
                    code = "LAYOUT",
                ),
            "height" to
                Field(
                    "integer",
                    minimum = 9,
                    maximum = LayoutCompiler.MAX_HEIGHT,
                    multipleOf = 9,
                    code = "LAYOUT",
                ),
        )

    /** 先确定页面模式，再应用对应默认值和约束。 */
    fun pageFields(mode: String): Map<String, Field> {
        require(mode in modes) { "Unknown mode $mode" }
        val shared =
            page -
                setOf(
                    "components",
                    "elements",
                    "popups",
                    "height",
                    "columns",
                    "native-body-position",
                    "modal",
                )
        return shared +
            mapOf(
                "mode" to page.getValue("mode").copy(values = listOf(mode)),
                "width" to
                    page.getValue("width").copy(default = if (mode == "canvas") 432 else 360),
            ) +
            if (mode == "canvas")
                mapOf(
                    "elements" to page.getValue("elements").copy(items = "canvasNode"),
                    "components" to page.getValue("components").copy(items = "nativeNode"),
                    "popups" to page.getValue("popups"),
                    "modal" to page.getValue("modal"),
                    "columns" to page.getValue("columns"),
                    "native-body-position" to page.getValue("native-body-position"),
                    "height" to page.getValue("height"),
                )
            else
                mapOf(
                    "components" to page.getValue("components").copy(items = "nativeNode"),
                    "columns" to page.getValue("columns"),
                )
    }

    val frame =
        linkedMapOf(
            "asset" to Field("string", true, pattern = ASSET_ID, code = "ASSET"),
            "padding" to Field("integer", minimum = 0, maximum = 64, default = 12, code = "LAYOUT"),
            "inset" to Field("integer", minimum = 0, maximum = 4, default = 4, code = "LAYOUT"),
            "width" to Field("integer", minimum = 90, maximum = 2048, code = "LAYOUT"),
        )

    val modal =
        linkedMapOf(
            "asset" to Field("string", true, pattern = ASSET_ID, code = "ASSET"),
            "background" to color.copy(required = true),
            "dim" to Field("number", minimum = 0, maximum = 1, default = 0.52),
            "top" to
                Field(
                    "integer",
                    minimum = -LayoutCompiler.MAX_HEIGHT,
                    maximum = LayoutCompiler.MAX_HEIGHT,
                    default = 1,
                    code = "LAYOUT",
                ),
            "content-top" to
                Field(
                    "integer",
                    minimum = 0,
                    maximum = LayoutCompiler.MAX_HEIGHT,
                    default = 35,
                    code = "LAYOUT",
                ),
            "button-gap" to
                Field("integer", minimum = 8, maximum = 128, default = 24, code = "LAYOUT"),
            "feedback" to Field("string", pattern = COMPONENT_ID, code = "REFERENCE"),
            "clear" to Field("array", items = "rect"),
        )

    val rect =
        linkedMapOf(
            "x" to Field("integer", true, minimum = 0, code = "LAYOUT"),
            "y" to Field("integer", true, minimum = 0, code = "LAYOUT"),
            "width" to Field("integer", true, minimum = 1, code = "LAYOUT"),
            "height" to Field("integer", true, minimum = 1, code = "LAYOUT"),
        )

    val buttonSkin =
        linkedMapOf(
            "asset" to Field("string", true, pattern = ASSET_ID, code = "ASSET"),
            "inset" to Field("integer", minimum = 0, maximum = 4, default = 1, code = "LAYOUT"),
            "label-offset-x" to
                Field("integer", minimum = -432, maximum = 432, default = 0, code = "LAYOUT"),
        )

    val inputAppearance =
        linkedMapOf(
            "background" to
                Field(
                    "string",
                    values = listOf("native", "transparent"),
                    default = "native",
                ),
            "text-color" to color,
        )

    val popup =
        linkedMapOf(
            "elements" to Field("array", true, items = "canvasNode", minItems = 1),
            "hide-underlay" to
                Field(
                    "array",
                    items = "string",
                    default = emptyList<String>(),
                    code = "DUPLICATE_ID",
                    uniqueItems = true,
                ),
        )

    val common =
        linkedMapOf(
            "id" to Field("string", true, pattern = COMPONENT_ID, minLength = 1, code = "ID"),
            "style" to Field("string", minLength = 1),
            "type" to Field("string", true),
            "text" to Field("text", default = ""),
            "tooltip" to Field("text"),
            "visible" to Field("condition"),
            "enabled" to Field("condition"),
            "width" to Field("integer", minimum = 1, maximum = 864, code = "LAYOUT"),
            "height" to
                Field("integer", minimum = 1, maximum = LayoutCompiler.MAX_HEIGHT, code = "LAYOUT"),
            "x" to Field("integer", minimum = 0, maximum = 864, default = 0, code = "LAYOUT"),
            "y" to
                Field(
                    "integer",
                    minimum = 0,
                    maximum = LayoutCompiler.MAX_HEIGHT,
                    default = 0,
                    code = "LAYOUT",
                ),
        )

    private fun fields(vararg values: Pair<String, Field>) = common + values

    private fun discriminated(
        types: Map<String, Map<String, Field>>,
        key: String = "type",
        code: String = "UNKNOWN_TYPE",
    ) = types.mapValues { (value, fields) ->
        fields + (key to fields.getValue(key).copy(values = listOf(value), code = code))
    }

    val variantTypes =
        setOf(
            "text",
            "image",
            "meter",
            "button",
            "hit-area",
            "item",
            "input",
            "checkbox",
            "select",
            "slider",
        )
    private val variantKeys =
        setOf(
            "text",
            "tooltip",
            "font",
            "font-size",
            "style",
            "color",
            "asset",
            "item",
            "show-count",
            "show-tooltip",
            "actions",
            "enabled",
            "skin",
            "fill",
            "track",
        )
    val variant =
        linkedMapOf(
            "when" to Field("condition", true),
            "override" to Field("object", true),
        )

    val nodes =
        discriminated(
            linkedMapOf(
                    "text" to
                        fields(
                            "binding" to Field("binding"),
                            "y" to
                                Field(
                                    "integer",
                                    minimum = 0,
                                    maximum = LayoutCompiler.MAX_HEIGHT,
                                    default = 0,
                                    code = "LAYOUT",
                                ),
                        ),
                    "hit-area" to
                        fields(
                            "width" to
                                Field("integer", minimum = 1, maximum = 864, code = "LAYOUT"),
                            "height" to
                                Field(
                                    "integer",
                                    minimum = 9,
                                    maximum = LayoutCompiler.MAX_HEIGHT,
                                    multipleOf = 9,
                                    code = "LAYOUT",
                                ),
                            "target" to
                                Field(
                                    "string",
                                    minLength = 1,
                                    pattern = COMPONENT_ID,
                                    code = "REFERENCE",
                                ),
                            "actions" to Field("actions", true),
                        ),
                    "group" to
                        mapOf(
                            "id" to common.getValue("id"),
                            "type" to Field("string", true),
                            "x" to common.getValue("x"),
                            "y" to common.getValue("y").copy(multipleOf = null),
                            "visible" to Field("condition"),
                            "enabled" to Field("condition"),
                            "elements" to Field("array", true, items = "canvasNode", minItems = 1),
                        ),
                    "list" to
                        mapOf(
                            "id" to common.getValue("id"),
                            "type" to Field("string", true),
                            "x" to common.getValue("x"),
                            "y" to common.getValue("y"),
                            "source" to Field("string", true, minLength = 1),
                            "template" to Field("string", true, minLength = 1),
                            "params" to Field("object"),
                            "page-state" to Field("string", true),
                            "selection-state" to Field("string"),
                            "page-size" to
                                Field(
                                    "integer",
                                    minimum = 1,
                                    maximum = 64,
                                    default = 6,
                                    code = "LAYOUT",
                                ),
                            "row-height" to
                                Field(
                                    "integer",
                                    minimum = 1,
                                    maximum = LayoutCompiler.MAX_HEIGHT,
                                    code = "LAYOUT",
                                ),
                            "slots" to
                                Field("array", items = "list-slot", minItems = 1, maxItems = 64),
                            "filter" to Field("list-filter"),
                            "visible" to Field("condition"),
                        ),
                    "image" to
                        fields(
                            "asset" to Field("string", true),
                            "width" to
                                Field("integer", minimum = 1, maximum = 864, code = "LAYOUT"),
                            "height" to
                                Field(
                                    "integer",
                                    minimum = 1,
                                    maximum = LayoutCompiler.MAX_HEIGHT,
                                    code = "LAYOUT",
                                ),
                            "fit" to
                                Field(
                                    "string",
                                    values = listOf("contain", "cover", "stretch"),
                                    code = "LAYOUT",
                                ),
                        ),
                    "meter" to
                        fields(
                            "width" to common.getValue("width").copy(required = true),
                            "height" to common.getValue("height").copy(required = true),
                            "value" to Field("binding", true),
                            "max" to Field("binding", true),
                            "fill" to color.copy(default = "#FFFFFF"),
                            "track" to color.copy(default = "#333333"),
                        ),
                    "item" to
                        fields(
                            "item" to Field("string", true),
                            "show-tooltip" to Field("boolean", default = true),
                        ),
                    "button" to
                        fields(
                            "actions" to Field("actions", true),
                            "skin" to Field("button-skin"),
                            "role" to
                                Field(
                                    "string",
                                    values = ButtonRole.entries.map { it.id },
                                    default = ButtonRole.ACTION.id,
                                ),
                        ),
                    "input" to
                        fields(
                            "state" to Field("string", true),
                            "max-length" to
                                Field("integer", minimum = 1, maximum = 4096, default = 64),
                            "multiline" to Field("boolean", default = false),
                            "label-visible" to Field("boolean", default = true),
                            "multiline-height" to
                                Field("integer", minimum = 20, maximum = 512, default = 48),
                            "max-lines" to Field("integer", minimum = 1, maximum = 64, default = 3),
                            "appearance" to Field("input-appearance"),
                        ),
                    "checkbox" to fields("state" to Field("string", true)),
                    "select" to
                        fields(
                            "state" to Field("string", true),
                            "options" to
                                Field(
                                    "object",
                                    true,
                                    "text",
                                    minProperties = 1,
                                    maxProperties = 64,
                                    code = "OPTIONS",
                                ),
                        ),
                    "slider" to
                        fields(
                            "state" to Field("string", true),
                            "min" to Field("number", default = 0.0),
                            "max" to Field("number", default = 100.0),
                            "step" to Field("number", default = 1.0),
                        ),
                    "template" to
                        mapOf(
                            "id" to common.getValue("id"),
                            "style" to common.getValue("style"),
                            "type" to Field("string", true),
                            "key" to Field("string", true),
                            "params" to Field("object"),
                            "visible" to Field("condition"),
                            "enabled" to Field("condition"),
                        ),
                )
                .mapValues { (type, fields) ->
                    val withVariants =
                        if (type in variantTypes)
                            fields +
                                ("variants" to Field("array", items = "variant", maxItems = 32))
                        else fields
                    val withoutIgnoredTooltip =
                        if (type in setOf("button")) withVariants else withVariants - "tooltip"
                    if (type in setOf("image", "hit-area")) withoutIgnoredTooltip - "text"
                    else if ("text" in withoutIgnoredTooltip) {
                        withoutIgnoredTooltip + mapOf("font" to fontFamily, "font-size" to fontSize)
                    } else withoutIgnoredTooltip
                }
        )

    fun fields(type: String, mode: String): Map<String, Field> {
        require(mode in modes) { "Unknown mode $mode" }
        val fields = nodes.getValue(type)
        if (type == "list" && mode == "native")
            return fields - setOf("x", "y", "slots", "row-height")
        if (type in setOf("group", "list")) return fields
        if (type == "template")
            return if (mode == "canvas")
                fields + mapOf("x" to common.getValue("x"), "y" to common.getValue("y"))
            else fields
        if (mode == "native") {
            val ignored =
                setOf("x", "y", "color", "label-offset") +
                    (if (type == "image") emptySet() else setOf("height")) +
                    (if (type == "button") setOf("asset") else emptySet()) +
                    (if (type == "item") setOf("show-count") else emptySet())
            return fields - ignored
        }
        val canvas =
            fields +
                mapOf(
                    "actions" to Field("actions", required = type == "hit-area"),
                    "tooltip" to Field("text"),
                )
        return when (type) {
            "text" ->
                canvas +
                    mapOf(
                        "align" to
                            Field(
                                "string",
                                values = listOf("left", "center", "right"),
                                default = "left",
                            ),
                        "overflow" to
                            Field("string", values = listOf("error", "clip"), default = "error"),
                    )
            "hit-area" -> canvas - setOf("text", "style")
            "item" ->
                (canvas +
                    mapOf(
                        "show-count" to Field("boolean", default = true),
                        "size" to
                            Field(
                                "integer",
                                minimum = 18,
                                maximum = 864,
                                multipleOf = 18,
                                default = 18,
                                code = "LAYOUT",
                            ),
                    )) - setOf("text", "font", "font-size", "style", "width", "height")
            else -> canvas
        }
    }

    /** 变体只替换允许字段，身份和布局继承原组件。 */
    fun variantFields(type: String, mode: String): Map<String, Field> =
        fields(type, mode)
            .filterKeys { it in variantKeys }
            .mapValues { (_, field) ->
                field.copy(required = false, default = null)
            }

    val text =
        linkedMapOf(
            "format" to
                Field(
                    "string",
                    true,
                    values = listOf("literal", "colored", "simple", "parts"),
                    code = "TEXT_FORMAT",
                ),
            "value" to Field("string"),
            "parts" to Field("array", items = "text"),
            "binding" to Field("binding"),
            "font" to Field("string"),
            "font-size" to fontSize,
            "color" to color,
            "bold" to Field("boolean", default = false),
            "italic" to Field("boolean", default = false),
            "offset-y" to Field("integer", minimum = -8, maximum = 8, default = 0),
            "hover" to Field("text"),
        )
    val textFormats =
        discriminated(
            linkedMapOf(
                "literal" to (text - "parts"),
                "colored" to
                    ((text - "parts" - "binding") +
                        ("value" to text.getValue("value").copy(required = true))),
                "simple" to
                    ((text - "parts" - "binding") +
                        ("value" to text.getValue("value").copy(required = true))),
                "parts" to
                    ((text - "value" - "binding") +
                        ("parts" to text.getValue("parts").copy(required = true))),
            ),
            "format",
            "TEXT_FORMAT",
        )
    val bindings =
        discriminated(
            linkedMapOf(
                "document" to
                    mapOf("type" to Field("string", true), "key" to Field("string", true)),
                "item" to
                    mapOf(
                        "type" to Field("string", true),
                        "key" to Field("string", true, minLength = 1),
                    ),
                "source" to
                    mapOf(
                        "type" to Field("string", true),
                        "source" to Field("string", true),
                        "key" to Field("string", true, values = listOf("size", "count")),
                        "field" to Field("string"),
                        "value" to Field("any"),
                    ),
                "record" to
                    mapOf(
                        "type" to Field("string", true),
                        "source" to Field("string", true),
                        "state" to Field("string", true),
                        "key" to Field("string", true, minLength = 1),
                    ),
                "list" to
                    mapOf(
                        "type" to Field("string", true),
                        "list" to Field("string"),
                        "key" to
                            Field(
                                "string",
                                true,
                                values =
                                    listOf(
                                        "total",
                                        "page",
                                        "pages",
                                        "has-next",
                                        "has-previous",
                                        "selected",
                                        "count",
                                    ),
                            ),
                        "field" to Field("string"),
                        "value" to Field("any"),
                    ),
                "args" to mapOf("type" to Field("string", true), "key" to Field("string", true)),
                "state" to mapOf("type" to Field("string", true), "key" to Field("string", true)),
                "data" to
                    mapOf(
                        "type" to Field("string", true),
                        "key" to Field("string", true),
                        "args" to Field("object"),
                    ),
                "papi" to mapOf("type" to Field("string", true), "key" to Field("string", true)),
                "kether" to
                    mapOf("type" to Field("string", true), "script" to Field("string", true)),
            )
        )
    val dataDocument =
        linkedMapOf(
            "id" to Field("string", true, pattern = ID, code = "ID"),
            "items" to Field("array", true, items = "record"),
        )
    val skinDocument =
        linkedMapOf(
            "id" to Field("string", true, pattern = ID, code = "ID"),
            "params" to Field("object"),
            "page" to Field("object", true),
        )
    val skinSlot =
        linkedMapOf(
            "id" to common.getValue("id"),
            "type" to Field("string", true, values = listOf("slot")),
            "component" to Field("object", true),
            "required" to Field("boolean", default = false),
            "text-style" to Field("object"),
        )
    val listSlot =
        linkedMapOf(
            "x" to common.getValue("x"),
            "y" to common.getValue("y").copy(required = true),
            "params" to Field("object"),
        )
    val listFilter =
        linkedMapOf(
            "field" to Field("string", true, minLength = 1),
            "value" to Field("any"),
            "state" to Field("string", minLength = 1),
            "when" to Field("condition"),
        )

    fun dataSourceFields(type: String): Map<String, Field> {
        require(type in setOf("document", "data", "args", "state", "record")) {
            "Unsupported collection source $type"
        }
        return bindings.getValue(type) +
            ("id-field" to Field("string", minLength = 1, default = "id"))
    }

    val actions =
        discriminated(
            linkedMapOf(
                "set-record" to
                    mapOf(
                        "type" to Field("string", true),
                        "source" to Field("string", true),
                        "state" to Field("string"),
                        "field" to Field("string", true),
                        "value" to Field("any", true),
                    ),
                "toggle-record" to
                    mapOf(
                        "type" to Field("string", true),
                        "source" to Field("string", true),
                        "state" to Field("string"),
                        "field" to Field("string", true),
                    ),
                "select-item" to mapOf("type" to Field("string", true)),
                "page-list" to
                    mapOf(
                        "type" to Field("string", true),
                        "list" to Field("string", true),
                        "step" to Field("integer", true, values = listOf(-1, 1)),
                    ),
                "open-page" to
                    mapOf("type" to Field("string", true), "page" to Field("string", true)),
                "open-popup" to
                    mapOf("type" to Field("string", true), "popup" to Field("string", true)),
                "close-popup" to mapOf("type" to Field("string", true)),
                "back" to mapOf("type" to Field("string", true)),
                "close" to mapOf("type" to Field("string", true)),
                "submit" to mapOf("type" to Field("string", true)),
                "set-state" to
                    mapOf(
                        "type" to Field("string", true),
                        "state" to Field("string", true),
                        "value" to Field("any"),
                        "binding" to Field("binding"),
                    ),
                "toggle-state" to
                    mapOf("type" to Field("string", true), "state" to Field("string", true)),
                "provider" to
                    mapOf(
                        "type" to Field("string", true),
                        "provider" to Field("string", true),
                        "args" to Field("object"),
                    ),
                "kether" to
                    mapOf("type" to Field("string", true), "script" to Field("string", true)),
            )
        )
    val conditions =
        discriminated(
            linkedMapOf(
                "at-least" to
                    mapOf(
                        "type" to Field("string", true),
                        "binding" to Field("binding", true),
                        "value" to Field("number"),
                        "compare" to Field("binding"),
                    ),
                "equals" to
                    mapOf(
                        "type" to Field("string", true),
                        "binding" to Field("binding", true),
                        "value" to Field("any"),
                        "compare" to Field("binding"),
                    ),
                "constant" to
                    mapOf("type" to Field("string", true), "value" to Field("boolean", true)),
                "permission" to
                    mapOf("type" to Field("string", true), "permission" to Field("string", true)),
                "state-equals" to
                    mapOf(
                        "type" to Field("string", true),
                        "state" to Field("string", true),
                        "value" to Field("any", true),
                    ),
                "provider" to
                    mapOf(
                        "type" to Field("string", true),
                        "provider" to Field("string", true),
                        "args" to Field("object"),
                    ),
                "kether" to
                    mapOf("type" to Field("string", true), "script" to Field("string", true)),
                "all" to
                    mapOf(
                        "type" to Field("string", true),
                        "conditions" to
                            Field("array", true, "condition", minItems = 1, code = "CONDITION"),
                    ),
                "any" to
                    mapOf(
                        "type" to Field("string", true),
                        "conditions" to
                            Field("array", true, "condition", minItems = 1, code = "CONDITION"),
                    ),
                "not" to
                    mapOf(
                        "type" to Field("string", true),
                        "condition" to Field("condition", true),
                    ),
            )
        )

    /** 校验后应用与 Schema 同源的字面默认值。 */
    fun withDefaults(map: Map<String, Any?>, fields: Map<String, Field>): Map<String, Any?> {
        val result = LinkedHashMap(map)
        fields.forEach { (name, field) ->
            if (name !in result && field.default != null)
                result[name] = Values.freeze(field.default)
        }
        return result
    }

    /** 只检查局部字段，引用和跨字段语义由编译器处理。 */
    fun check(map: Map<String, Any?>, fields: Map<String, Field>, file: String, path: String) {
        fun fail(key: String, code: String, message: String): Nothing =
            throw DocumentError(
                Diagnostic(file, SourcePath.field(path, key), code, message, map["id"] as? String)
            )
        (map.keys - fields.keys).firstOrNull()?.let {
            fail(it, "UNKNOWN_FIELD", "Unknown field: $it")
        }
        for ((key, field) in fields) {
            if (!map.containsKey(key)) {
                if (field.required) fail(key, "REQUIRED_FIELD", "Missing $key")
                continue
            }
            val value = map[key]
            val number = (value as? Number)?.let(::decimal)
            val valid =
                when (field.type) {
                    "any" -> true
                    "string" -> value is String
                    "boolean" -> value is Boolean
                    "number" -> number != null
                    "integer" ->
                        number != null &&
                            number.stripTrailingZeros().scale() <= 0 &&
                            number >= BigDecimal.valueOf(Int.MIN_VALUE.toLong()) &&
                            number <= BigDecimal.valueOf(Int.MAX_VALUE.toLong())
                    "object",
                    "binding",
                    "condition",
                    "frame",
                    "modal",
                    "button-skin",
                    "input-appearance",
                    "list-filter" -> value is Map<*, *> && value.keys.all { it is String }
                    "array",
                    "actions" -> value is List<*>
                    "text" ->
                        value is String || value is Map<*, *> && value.keys.all { it is String }
                    else -> false
                }
            if (!valid) fail(key, "FIELD_TYPE", "$key must be ${field.type}")
            fun invalid(message: String): Nothing = fail(key, field.code, message)
            if (field.values.isNotEmpty() && field.values.none { sameValue(value, it) })
                invalid("Unsupported $key")
            if (number != null) {
                field.minimum?.let {
                    if (number < decimal(it)!!) invalid("$key must be at least $it")
                }
                field.maximum?.let {
                    if (number > decimal(it)!!) invalid("$key must be at most $it")
                }
                field.multipleOf?.let {
                    if (number.remainder(decimal(it)!!).signum() != 0)
                        invalid("$key must be a multiple of $it")
                }
            }
            if (value is String) {
                val length = value.codePointCount(0, value.length)
                field.minLength?.let {
                    if (length < it) invalid("$key must contain at least $it characters")
                }
                field.maxLength?.let {
                    if (length > it) invalid("$key must contain at most $it characters")
                }
                field.pattern?.let {
                    if (!Regex(it).containsMatchIn(value)) invalid("Invalid $key")
                }
            }
            if (value is List<*>) {
                field.minItems?.let {
                    if (value.size < it) invalid("$key requires at least $it items")
                }
                field.maxItems?.let {
                    if (value.size > it) invalid("$key allows at most $it items")
                }
                if (field.uniqueItems && value.distinct().size != value.size)
                    invalid("$key items must be unique")
            }
            if (value is Map<*, *>) {
                field.minProperties?.let {
                    if (value.size < it) invalid("$key requires at least $it entries")
                }
                field.maxProperties?.let {
                    if (value.size > it) invalid("$key allows at most $it entries")
                }
                field.propertyPattern?.let { pattern ->
                    value.keys
                        .firstOrNull { !Regex(pattern).containsMatchIn(it as String) }
                        ?.let {
                            throw DocumentError(
                                Diagnostic(
                                    file,
                                    SourcePath.field(SourcePath.field(path, key), it as String),
                                    "ID",
                                    "Invalid identifier $it",
                                    map["id"] as? String,
                                )
                            )
                        }
                }
            }
        }
    }

    private fun decimal(value: Number): BigDecimal? =
        if (value.toDouble().isFinite()) value.toString().toBigDecimalOrNull() else null

    private fun sameValue(left: Any?, right: Any): Boolean {
        if (left !is Number || right !is Number) return left == right
        val first = decimal(left) ?: return false
        val second = decimal(right) ?: return false
        return first.compareTo(second) == 0
    }

    /** 工具文档复用字段规则，不再维护一套 Schema。 */
    fun objectSchema(fields: Map<String, Field>): Map<String, Any?> = obj(fields)

    private fun obj(fields: Map<String, Field>): Map<String, Any?> =
        mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to fields.filterValues { it.required }.keys.toList(),
            "properties" to fields.mapValues { (_, field) -> field.schema() },
        )

    private fun tagged(types: Map<String, Map<String, Field>>): Map<String, Any?> =
        mapOf("oneOf" to types.values.map(::obj))

    /** 跨字段语义校验时允许样式补充图片高度。 */
    private fun componentSchemas(types: Map<String, Map<String, Field>>): Map<String, Any?> =
        mapOf(
            "oneOf" to
                types.map { (type, fields) ->
                    obj(fields) +
                        when (type) {
                            "image" ->
                                mapOf(
                                    "allOf" to
                                        listOf(
                                            mapOf(
                                                "if" to
                                                    mapOf("required" to listOf("width", "height")),
                                                "then" to mapOf("required" to listOf("fit")),
                                            ),
                                            mapOf(
                                                "if" to mapOf("required" to listOf("fit")),
                                                "then" to
                                                    mapOf(
                                                        "required" to listOf("width"),
                                                        "anyOf" to
                                                            listOf(
                                                                mapOf(
                                                                    "required" to listOf("height")
                                                                ),
                                                                mapOf(
                                                                    "required" to listOf("style")
                                                                ),
                                                            ),
                                                    ),
                                            ),
                                        )
                                )
                            "list" ->
                                mapOf(
                                    "oneOf" to
                                        listOf(
                                            mapOf(
                                                "required" to listOf("row-height"),
                                                "not" to mapOf("required" to listOf("slots")),
                                            ),
                                            mapOf(
                                                "required" to listOf("slots"),
                                                "not" to
                                                    mapOf(
                                                        "anyOf" to
                                                            listOf("row-height", "page-size").map {
                                                                mapOf("required" to listOf(it))
                                                            }
                                                    ),
                                            ),
                                        )
                                )
                            "hit-area" ->
                                mapOf(
                                    "if" to mapOf("required" to listOf("target")),
                                    "then" to
                                        mapOf(
                                            "not" to
                                                mapOf(
                                                    "anyOf" to
                                                        listOf("x", "y", "width", "height").map {
                                                            mapOf("required" to listOf(it))
                                                        }
                                                )
                                        ),
                                    "else" to mapOf("required" to listOf("width", "height")),
                                )
                            "input" ->
                                mapOf(
                                    "dependentSchemas" to
                                        listOf("multiline-height", "max-lines").associateWith {
                                            mapOf(
                                                "required" to listOf("multiline"),
                                                "properties" to
                                                    mapOf("multiline" to mapOf("const" to true)),
                                            )
                                        }
                                )
                            else -> emptyMap()
                        }
                }
        )

    private fun exactlyOne(first: String, second: String): Map<String, Any?> =
        mapOf(
            "oneOf" to
                listOf(mapOf("required" to listOf(first)), mapOf("required" to listOf(second)))
        )

    fun schema(): Map<String, Any?> {
        val textVariants = textFormats.map { (format, fields) ->
            obj(fields) +
                if (format == "literal")
                    mapOf("not" to mapOf("required" to listOf("value", "binding")))
                else emptyMap()
        }
        val actionVariants = actions.map { (type, fields) ->
            obj(fields) + if (type == "set-state") exactlyOne("value", "binding") else emptyMap()
        }
        val definitions =
            linkedMapOf<String, Any?>(
                "text" to
                    mapOf(
                        "oneOf" to listOf(mapOf("type" to "string"), mapOf("oneOf" to textVariants))
                    ),
                "binding" to tagged(bindings),
                "action" to mapOf("oneOf" to actionVariants),
                "condition" to
                    mapOf(
                        "oneOf" to
                            conditions.map { (type, fields) ->
                                obj(fields) +
                                    if (type in setOf("equals", "at-least"))
                                        mapOf(
                                            "oneOf" to
                                                listOf(
                                                    mapOf(
                                                        "required" to listOf("value"),
                                                        "not" to
                                                            mapOf("required" to listOf("compare")),
                                                    ),
                                                    mapOf(
                                                        "required" to listOf("compare"),
                                                        "not" to
                                                            mapOf("required" to listOf("value")),
                                                    ),
                                                )
                                        )
                                    else emptyMap()
                            }
                    ),
                "variant" to
                    (obj(variant) +
                        mapOf(
                            "properties" to
                                mapOf(
                                    "when" to variant.getValue("when").schema(),
                                    "override" to
                                        obj(
                                            variantTypes
                                                .flatMap { type ->
                                                    modes.keys
                                                        .filter { type in modes.getValue(it) }
                                                        .flatMap { mode ->
                                                            variantFields(type, mode).entries
                                                        }
                                                }
                                                .associate { it.key to it.value }
                                        ),
                                )
                        )),
                "node" to componentSchemas(nodes),
                "popup" to obj(popup),
                "frame" to obj(frame),
                "modal" to obj(modal),
                "input-appearance" to obj(inputAppearance),
                "list-filter" to (obj(listFilter) + exactlyOne("value", "state")),
                "list-slot" to obj(listSlot),
                "record" to mapOf("type" to "object"),
                "slotContent" to mapOf("type" to "object"),
                "dataSource" to
                    tagged(
                        setOf("document", "data", "args", "state", "record")
                            .associateWith(::dataSourceFields)
                    ),
                "rect" to obj(rect),
                "button-skin" to obj(buttonSkin),
            )
        modes.forEach { (mode, allowed) ->
            definitions["${mode}Node"] =
                componentSchemas(
                    nodes
                        .filterKeys { it in allowed }
                        .mapValues { (type, _) ->
                            fields(type, mode)
                        }
                )
        }
        val skinOwnership =
            mapOf(
                "if" to mapOf("required" to listOf("skin")),
                "then" to
                    mapOf(
                        "not" to
                            mapOf(
                                "anyOf" to
                                    (page.keys - setOf("mode", "skin", "params", "content")).map {
                                        mapOf("required" to listOf(it))
                                    }
                            )
                    ),
            )
        definitions["page"] =
            mapOf(
                "oneOf" to
                    modes.keys.map { mode ->
                        obj(pageFields(mode)) +
                            if (mode == "canvas")
                                mapOf(
                                    "allOf" to
                                        listOf(
                                            mapOf(
                                                "if" to
                                                    mapOf(
                                                        "not" to mapOf("required" to listOf("skin"))
                                                    ),
                                                "then" to mapOf("required" to listOf("elements")),
                                            ),
                                            skinOwnership,
                                            mapOf(
                                                "if" to
                                                    mapOf(
                                                        "required" to listOf("popups"),
                                                        "properties" to
                                                            mapOf(
                                                                "popups" to
                                                                    mapOf("minProperties" to 1)
                                                            ),
                                                    ),
                                                "then" to
                                                    mapOf(
                                                        "properties" to
                                                            mapOf(
                                                                "components" to
                                                                    mapOf("maxItems" to 0)
                                                            )
                                                    ),
                                            ),
                                            mapOf(
                                                "if" to mapOf("required" to listOf("modal")),
                                                "then" to
                                                    mapOf(
                                                        "required" to listOf("components"),
                                                        "not" to
                                                            mapOf(
                                                                "anyOf" to
                                                                    listOf(
                                                                        mapOf(
                                                                            "required" to
                                                                                listOf("frame")
                                                                        ),
                                                                        mapOf(
                                                                            "required" to
                                                                                listOf("height")
                                                                        ),
                                                                    )
                                                            ),
                                                        "properties" to
                                                            mapOf(
                                                                "popups" to
                                                                    mapOf("maxProperties" to 0),
                                                                "native-body-position" to
                                                                    mapOf(
                                                                        "const" to
                                                                            NativeBodyPosition
                                                                                .AFTER_CANVAS
                                                                                .id
                                                                    ),
                                                                "components" to
                                                                    mapOf("minItems" to 1),
                                                            ),
                                                    ),
                                            ),
                                        )
                                )
                            else
                                mapOf(
                                    "allOf" to
                                        listOf(
                                            mapOf(
                                                "if" to
                                                    mapOf(
                                                        "not" to mapOf("required" to listOf("skin"))
                                                    ),
                                                "then" to mapOf("required" to listOf("components")),
                                            ),
                                            skinOwnership,
                                        )
                                )
                    }
            )
        return obj(menu) +
            mapOf(
                "\$schema" to "https://json-schema.org/draft/2020-12/schema",
                "\$defs" to definitions,
            )
    }

    fun dataSchema(): Map<String, Any?> =
        obj(dataDocument) +
            mapOf(
                "\$schema" to "https://json-schema.org/draft/2020-12/schema",
                "\$defs" to mapOf("record" to mapOf("type" to "object")),
            )

    /** 替换内容后再校验皮肤原型。 */
    fun skinSchema(): Map<String, Any?> =
        obj(skinDocument) + mapOf("\$schema" to "https://json-schema.org/draft/2020-12/schema")

    fun assetSchema(): Map<String, Any?> =
        obj(asset) +
            mapOf(
                "\$schema" to "https://json-schema.org/draft/2020-12/schema",
                "\$defs" to
                    mapOf("assetImage" to (obj(assetImage) + exactlyOne("source", "color"))),
            )

    /** 仅描述参数替换前的模板结构。 */
    fun templateSchema(): Map<String, Any?> =
        obj(template) + mapOf("\$schema" to "https://json-schema.org/draft/2020-12/schema")

    /** 主题复用文本语法，但不允许会话绑定。 */
    fun themeSchema(): Map<String, Any?> {
        val properties = theme.mapValues { (_, field) -> field.schema() }.toMutableMap()
        properties["defaults"] = obj(themeDefaults)
        properties["styles"] =
            mapOf(
                "type" to "object",
                "propertyNames" to mapOf("pattern" to COMPONENT_ID),
                "additionalProperties" to obj(style),
            )
        properties["palette"] = obj(themePalette)
        properties["controls"] = obj(themeControls)
        return obj(theme) +
            mapOf(
                "\$schema" to "https://json-schema.org/draft/2020-12/schema",
                "properties" to properties,
                "\$defs" to
                    mapOf(
                        "text" to
                            mapOf(
                                "oneOf" to
                                    listOf(mapOf("type" to "string")) +
                                        textFormats.values.map { fields -> obj(fields - "binding") }
                            )
                    ),
            )
    }

    private fun describe(fields: Map<String, Field>) = fields.mapValues { (_, field) ->
        mapOf("type" to field.type, "required" to field.required) +
            (field.items?.let { mapOf("items" to it) } ?: emptyMap()) +
            field.keywords()
    }

    fun manifest(): Map<String, Any?> =
        mapOf(
            "asset-authoring" to "filesystem-discovery-no-assets-yaml",
            "image-density" to "@1x..@8x",
            "merge-sources" to
                "resource-pack.merge.sources only; [] disables external pack merging",
            "project-paths" to
                mapOf(
                    "menus" to com.hiusers.rafter.model.ProjectPaths.MENUS,
                    "data" to com.hiusers.rafter.model.ProjectPaths.DATA,
                    "templates" to com.hiusers.rafter.model.ProjectPaths.TEMPLATES,
                    "skins" to com.hiusers.rafter.model.ProjectPaths.SKINS,
                    "ui" to com.hiusers.rafter.model.ProjectPaths.UI,
                    "generated" to com.hiusers.rafter.model.ProjectPaths.GENERATE,
                ),
            "runtime-fields" to describe(runtime),
            "input-appearance" to
                mapOf(
                    "supported-clients" to ClientRenderProfiles.all.flatMap { it.clients },
                    "profiles" to ClientRenderProfiles.all.map { it.manifest() },
                    "compiled-layouts" to InputAppearances.MAX_LAYOUTS,
                    "scope" to "modal",
                    "selection" to "per-input-appearance",
                    "requires-loaded-resource-pack" to true,
                    "requires-client-gametime-marker" to true,
                ),
            "theme-schema" to themeSchema(),
            "style-fields" to describe(style),
            "font-authoring" to "auto-discovered-family-and-integer-size",
            "font-licenses" to "optional-same-stem-.license-companions",
            "asset-fields" to describe(asset),
            "asset-image-fields" to describe(assetImage),
            "menu-fields" to describe(menu),
            "template-fields" to describe(template),
            "frame-fields" to describe(frame),
            "modal-fields" to describe(modal),
            "input-appearance-fields" to describe(inputAppearance),
            "rect-fields" to describe(rect),
            "button-skin-fields" to describe(buttonSkin),
            "native-button-skin" to
                mapOf(
                    "source" to "image-asset",
                    "text" to "independent-measured-component",
                    "height" to NativeGeometry.BUTTON_HEIGHT,
                    "inset" to "native-hover-focus-edge",
                    "fit" to "stretch",
                    "submission" to "native-dynamic-custom-action",
                    "exit-role" to "replaces-generated-close",
                ),
            "native-text-input" to
                mapOf(
                    "multiline-only-fields" to listOf("multiline-height", "max-lines"),
                    "single-line-height" to NativeGeometry.BUTTON_HEIGHT,
                    "multiline-height" to "client-widget-height",
                    "max-lines" to "client-line-limit",
                    "server-validation" to "max-length-without-visual-line-estimation",
                    "draft-owner" to "native-client-until-submit",
                    "keystroke-events" to false,
                    "counter" to "native-multiline-only-outside-widget",
                    "counter-region" to "reserved-line-not-string-ink-bounds",
                ),
            "native-modal" to
                mapOf(
                    "profiles" to ClientRenderProfiles.all.map { it.manifest() },
                    "placement" to "frozen-canvas-backdrop",
                    "source" to "original-image-asset",
                    "opener" to
                        "pure-canvas-with-items-or-without-items-without-frame-or-current-modal",
                    "opener-title" to "measured-canvas-text",
                    "entry" to "forbidden",
                    "replacement" to "same-layer-reuse-backdrop-no-history-push",
                    "snapshot" to
                        mapOf(
                            "required" to true,
                            "items" to "independent-item-stack-snapshot",
                            "provider-reread" to false,
                        ),
                    "background-interaction" to "paint-only-no-hover-or-click",
                    "panel-clipping" to "frozen-model-routes-only",
                    "native-body-position" to NativeBodyPosition.AFTER_CANVAS.id,
                    "columns" to "positive-count-bounded-by-actions-and-layout",
                    "header-y-origin" to "panel-top",
                    "height" to "derived-from-top-content-top-and-header",
                    "top" to "minimum-body-offset-snapped-to-transport-rows",
                    "controls" to listOf("input", "checkbox", "select", "slider", "button"),
                    "actions" to "authored-submit-explicit-no-fixed-back-or-confirm-role",
                    "geometry" to "compiled-modal-layout-plan",
                    "input-regions" to listOf("flow-slot", "control", "label", "counter-region"),
                    "action-cell-roles" to ModalCellRole.entries.map { it.id },
                    "transport-cells" to "not-author-elements-not-business-actions",
                    "keyboard-focus" to "native-client-owned",
                    "feedback" to "explicit-existing-text-target-fixed-geometry-required-for-forms",
                    "counter-clearance" to "one-transport-row-only-after-final-multiline-input",
                    "multiline-placement" to "last-input-only-counter-overlap-guard",
                    "checkbox-width" to "reserved-by-literal-label-advance-no-protocol-width-field",
                    "input-position" to "native-vertical-flow-not-arbitrary-canvas-coordinates",
                    "input-height" to
                        "compiled-native-slot-including-visible-label-or-checkbox-height",
                    "description" to
                        mapOf(
                            "count" to "0..n",
                            "type" to "text",
                            "placement" to "before-inputs",
                            "width" to "authored-or-page-width",
                            "body-height" to
                                NativeGeometry.LINE_HEIGHT + NativeGeometry.TEXT_PADDING * 2,
                            "gap-after" to NativeGeometry.BODY_GAP,
                            "text" to "single-line-measured",
                            "visibility" to "unconditional",
                            "variants" to "fixed-geometry",
                            "bindings" to
                                "declared-state-or-readonly-records-with-runtime-line-clipping",
                        ),
                    "control-visibility" to "fixed",
                    "input-enabled" to "unconditional",
                    "button-widths" to "independent-of-input-centered-native-grid",
                    "panel-width" to "at-least-max-canvas-body-input-and-button-row",
                    "panel-height" to "covers-native-body-flow",
                    "error-style" to "theme-form-error-clipped-to-feedback-region-with-full-hover",
                ),
            "dialog-frame" to
                mapOf(
                    "placement" to "viewport-backdrop",
                    "native-controls" to "unchanged-client-widgets",
                    "source" to "image-asset",
                    "sampling" to "nearest",
                    "scroll" to "stationary-behind-content",
                    "width" to "at-least-rendered-content-plus-padding",
                    "height" to "viewport-minus-inset",
                ),
            "modes" to modes.mapValues { it.value.toList() },
            "canvas-composition" to
                mapOf(
                    "group" to "compile-time-local-coordinates-and-dot-namespaced-leaves",
                    "depth-limit" to 16,
                    "paint-order" to "authored-depth-first-order",
                    "hit-target" to "same-plane-image-or-fixed-bounds-text",
                    "hit-target-rows" to "covering-9px-rows-exported-as-actual-bounds",
                    "native-widgets" to "not-group-children",
                ),
            "data-documents" to mapOf("schema" to dataSchema(), "fields" to describe(dataDocument)),
            "page-skins" to
                mapOf(
                    "schema" to skinSchema(),
                    "slot-fields" to describe(skinSlot),
                    "geometry-owner" to "skin",
                    "composition" to "explicit-content-no-inheritance",
                ),
            "data-lists" to
                mapOf(
                    "layout" to "fixed-compiled-slots",
                    "key" to "nonempty-unique-string",
                    "identity" to "presented-record-key-revalidated-before-action",
                    "nested" to false,
                    "providers" to "page-dependencies-read-once-per-frame",
                    "script-item" to "readonly-record",
                ),
            "native-items" to
                mapOf(
                    "placement" to "native-body-flow",
                    "canvas-coordinates" to false,
                    "components" to "server-codec-snapshot",
                    "count-min" to NativeGeometry.ITEM_MIN_COUNT,
                    "count-max" to NativeGeometry.ITEM_MAX_COUNT,
                    "tooltip" to "native-item-stack",
                    "resource-pack" to "client-loaded-assets-required-for-custom-models",
                ),
            "components" to nodes.mapValues { (_, fields) -> describe(fields) },
            "capabilities" to
                modes.mapValues { (mode, allowed) ->
                    mapOf(
                        "page" to describe(pageFields(mode)),
                        "collections" to
                            if (mode == "canvas")
                                mapOf(
                                    "elements" to
                                        mapOf(
                                            "mode" to "canvas",
                                            "layout-owner" to "rafter-compiler",
                                        ),
                                    "components" to
                                        mapOf(
                                            "mode" to "native",
                                            "layout-owner" to "native-client",
                                        ),
                                    "popups" to
                                        mapOf(
                                            "mode" to "canvas",
                                            "layout-owner" to "rafter-compiler",
                                            "modal" to true,
                                        ),
                                )
                            else
                                mapOf(
                                    "components" to
                                        mapOf("mode" to "native", "layout-owner" to "native-client")
                                ),
                        "components" to
                            nodes
                                .filterKeys { it in allowed }
                                .mapValues { (type, _) ->
                                    describe(fields(type, mode))
                                },
                    )
                },
            "canvas" to
                mapOf(
                    "cell-height" to CanvasGeometry.ROW_HEIGHT,
                    "x-step" to 1,
                    "y-step" to CanvasGeometry.ROW_HEIGHT,
                    "text-y-step" to 1,
                    "image-y-step" to 1,
                    "meter-y-step" to 1,
                    "hit-area-y-step" to CanvasGeometry.ROW_HEIGHT,
                    "interaction-height-step" to CanvasGeometry.ROW_HEIGHT,
                    "element-types" to
                        modes.getValue("canvas").filter {
                            it !in setOf("template", "group", "list")
                        },
                    "authoring-types" to listOf("group", "list", "template"),
                    "items" to
                        mapOf(
                            "resource-pack" to "matching-client-profile-required",
                            "profiles" to ClientRenderProfiles.all.map { it.manifest() },
                            "page" to "canvas-title-paint-with-native-item-flow",
                            "placement" to "static-x-and-row-aligned-y",
                            "bounds" to "static-size-by-size-frame-including-count-reservation",
                            "data" to "independent-item-stack-snapshot",
                            "layout" to "CanvasFlow-title-paint-native-hit-bands",
                            "carrier" to
                                mapOf(
                                    "height" to "1..256",
                                    "gap" to NativeGeometry.BODY_GAP,
                                    "filler" to "node-null-empty-model",
                                    "allocation" to "interactive-outside-blank-bands-first",
                                    "inset" to "compiler-adds-9px-leading-inset-on-shortfall",
                                ),
                            "channel-limit" to CanvasItems.MAX_CHANNELS,
                            "channel-scope" to
                                "overlapping-source-corners;reused-on-disjoint-native-y",
                            "carrier-centering" to "even-native-body-width",
                            "modal-scissor" to "contains-frozen-canvas-width-and-height",
                            "viewport" to "finite-complete-fit-no-scroll",
                            "layout-budget" to
                                mapOf(
                                    "source" to "item-layouts.json",
                                    "routes" to "routes",
                                    "flows" to "flows",
                                    "modal-flows" to "modal-flows",
                                    "minimum-gui-width" to "routes[*].minimum-gui-width",
                                    "minimum-gui-height" to "routes[*].minimum-gui-height",
                                ),
                            "minimum-gui-width" to "item-layouts.json.routes[*].minimum-gui-width",
                            "minimum-gui-height" to
                                "item-layouts.json.routes[*].minimum-gui-height",
                            "containers" to
                                mapOf(
                                    "main-scene" to "supported",
                                    "popup" to
                                        "item-not-hosted;covering-overlay-requires-hide-underlay",
                                    "modal" to "native-modal-from-pure-canvas-flow",
                                    "modal-items" to "not-supported-new-item-nodes",
                                ),
                            "count-overlay" to "read-only-actual-stack-count",
                            "durability-bar" to false,
                            "cooldown-overlay" to false,
                            "tooltip" to "native-show-item-through-flow-hit-text",
                            "hover-appearance" to "native-tooltip-only-no-modal-background-hover",
                            "layering" to
                                "no-arbitrary-item-z-or-mask-or-scroll;frozen-route-panel-clip-only",
                            "static" to
                                listOf(
                                    "x",
                                    "y",
                                    "size",
                                    "layout",
                                    "capacity",
                                    "flow",
                                    "inset",
                                    "carrier",
                                ),
                            "dynamic" to
                                listOf(
                                    "item-provider-result",
                                    "visible",
                                    "enabled",
                                    "variant",
                                    "page",
                                    "selection",
                                ),
                            "actions" to listOf("single-click"),
                        ),
                    "native-component-types" to modes.getValue("native").toList(),
                    "native-layout-owner" to "native-client",
                    "native-layout-order" to listOf("body", "inputs", "actions"),
                    "element-order" to "back-to-front",
                    "popups" to
                        mapOf(
                            "fields" to describe(popup),
                            "coordinates" to "page-absolute",
                            "activation" to "session-only-single-popup",
                            "outside-click" to "dismiss",
                            "underlay-input" to "blocked",
                            "native-components" to "separate-input-page",
                            "underlay-visibility" to
                                "explicit-hide-underlay-page-regions-or-element-ids",
                        ),
                    "natural-text-bounds" to true,
                    "max-content-height" to LayoutCompiler.MAX_HEIGHT,
                    "scroll-owner" to "native-dialog",
                    "pixel-aligned" to true,
                    "shadow-color" to 0,
                ),
            "inline-kether" to
                mapOf(
                    "delimiters" to listOf("{{", "}}"),
                    "formats" to listOf("colored", "simple"),
                    "evaluation" to "ordered-on-render",
                    "result" to "literal-component",
                    "recursive" to false,
                    "max-expressions" to 64,
                    "theme-controls" to false,
                ),
            "text-formats" to textFormats.keys.toList(),
            "text-fields" to textFormats.mapValues { (_, fields) -> describe(fields) },
            "bindings" to bindings.keys.toList(),
            "binding-fields" to bindings.mapValues { (_, fields) -> describe(fields) },
            "actions" to actions.keys.toList(),
            "action-fields" to actions.mapValues { (_, fields) -> describe(fields) },
            "variant-fields" to describe(variant),
            "variant-components" to variantTypes.toList(),
            "variant-overrides" to
                modes.mapValues { (mode, allowed) ->
                    variantTypes
                        .filter { it in allowed }
                        .associateWith { describe(variantFields(it, mode)) }
                },
            "conditions" to conditions.keys.toList(),
            "condition-fields" to conditions.mapValues { (_, fields) -> describe(fields) },
        )
}
