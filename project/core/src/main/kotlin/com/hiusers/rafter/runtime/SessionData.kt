package com.hiusers.rafter.runtime

import com.hiusers.rafter.model.*
import java.util.Collections

/** 主线程会话数据，不持有提供器；点击沿用已展示的槽位与记录键，防止重排误操作。 */
class SessionData(private val menu: MenuSpec, private val state: MutableMap<String, Any?>) {
    private data class Source(
        val items: List<Map<String, Any?>>,
        val byId: Map<String, Map<String, Any?>>,
    )

    private data class View(
        val spec: ListSpec,
        val keys: List<String>,
        val page: Int,
        val visible: Boolean,
    ) {
        val pages: Int
            get() = (keys.size + spec.pageSize - 1) / spec.pageSize

        val slots: List<String>
            get() =
                if (visible) keys.drop(page * spec.pageSize).take(spec.pageSize) else emptyList()
    }

    private val definitions = menu.lists.associateBy { it.id }
    private var sources: Map<String, Source> = emptyMap()

    private data class Resolved(val parent: Source?, val selection: Any?, val value: Source)

    private val resolved = mutableMapOf<String, Resolved>()
    private val patches = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()

    /** 仅允许修改本地文档副本，不覆盖提供器状态。 */
    fun patch(alias: String, id: String, field: String, value: Any?, toggle: Boolean = false) {
        val definition = menu.data.getValue(alias)
        require(definition.binding.type == "document") {
            "Only document sources support session-local edits"
        }
        require(field != definition.idField) { "Record identity is immutable" }
        val record = source(alias).byId[id] ?: error("Record no longer exists")
        val next =
            if (toggle) !(record[field] as? Boolean ?: error("toggle-record requires a boolean"))
            else Values.freeze(value)
        val changed = patches.getOrPut(alias) { mutableMapOf() }
        changed[id] = changed[id].orEmpty() + (field to next)
        resolved.clear()
    }

    /** 父列表选择完成后再解析子集合，不与父记录并发读取。 */
    private fun source(alias: String): Source {
        val spec = menu.data.getValue(alias)
        val binding = spec.binding
        val parent =
            if (binding.type == "record") source(binding.args.getValue("source") as String)
            else null
        val selection =
            if (parent != null) state[binding.args.getValue("state") as String] else null
        resolved[alias]
            ?.takeIf { it.parent === parent && it.selection == selection }
            ?.let {
                return it.value
            }
        val base =
            if (parent != null) {
                val selected = parent.byId[selection]
                val raw =
                    if (selected == null) emptyList<Any?>()
                    else {
                        require(selected.containsKey(binding.key)) {
                            "Record collection ${binding.key} is missing"
                        }
                        selected[binding.key]
                    }
                checked(alias, raw)
            } else sources.getValue(alias)
        val changes = patches[alias]
        val value =
            if (changes == null) base
            else {
                val items =
                    base.items.map { record ->
                        Values.map(record + changes[record[spec.idField]].orEmpty())
                    }
                Source(
                    Values.list(items),
                    Collections.unmodifiableMap(
                        items.associateBy { it.getValue(spec.idField) as String }
                    ),
                )
            }
        // 同帧行绑定共用冻结索引，不为每个标签深拷贝。
        resolved[alias] = Resolved(parent, selection, value)
        return value
    }

    private fun checked(alias: String, raw: Any?): Source {
        val definition = menu.data.getValue(alias)
        @Suppress("UNCHECKED_CAST")
        val frozen =
            Values.freeze(raw) as? List<Any?>
                ?: error("Data source $alias must return a record list")
        val records = frozen.mapIndexed { index, item ->
            @Suppress("UNCHECKED_CAST")
            val record =
                item as? Map<String, Any?>
                    ?: error("Data source $alias item $index must be an object")
            require(
                record[definition.idField] is String &&
                    (record[definition.idField] as String).isNotEmpty()
            ) {
                "Data source $alias item $index requires a nonempty string ${definition.idField}"
            }
            record
        }
        val indexed = records.associateBy { it.getValue(definition.idField) as String }
        require(indexed.size == records.size) { "Duplicate record key in data source $alias" }
        return Source(Values.list(records), Collections.unmodifiableMap(indexed))
    }

    private val views = mutableMapOf<String, View>()
    private var presented: Map<String, List<String>> = emptyMap()
    private var clicking = false

    /** 显式集合源每帧读取一次，不按行重复调用。 */
    fun required(
        page: PageSpec,
        nodes: List<NodeSpec>,
        lists: List<ListSpec> = page.lists,
    ): Set<String> {
        val aliases = linkedSetOf<String>()
        fun binding(value: BindingSpec) {
            when (value.type) {
                "source",
                "record" -> aliases += value.args.getValue("source") as String
                "item",
                "list" ->
                    aliases += definitions.getValue(value.args.getValue("list") as String).source
            }
        }
        fun text(value: TextSpec) {
            value.binding?.let(::binding)
            value.inlineParts.forEach { it.binding?.let(::binding) }
            value.parts.forEach(::text)
            value.hover?.let(::text)
        }
        fun condition(value: ConditionSpec?) {
            value ?: return
            value.binding?.let(::binding)
            value.compare?.let(::binding)
            value.children.forEach(::condition)
        }
        fun node(value: NodeSpec) {
            text(value.text)
            value.tooltip?.let(::text)
            condition(value.visible)
            condition(value.enabled)
            value.variants.forEach {
                condition(it.condition)
                node(it.node)
            }
            value.actions.forEach { action ->
                action.binding?.let(::binding)
                // 记录编辑按钮可能是页面唯一的数据消费者。
                if (action.type in setOf("set-record", "toggle-record")) aliases += action.key
            }
            (value.content as? NodeContent.Meter)?.let {
                binding(it.value)
                binding(it.maximum)
            }
            (value.content as? NodeContent.Select)?.options?.values?.forEach(::text)
        }
        lists.forEach {
            aliases += it.source
            condition(it.filter?.condition)
            condition(it.visible)
        }
        text(page.title)
        nodes.forEach(::node)
        val roots = linkedSetOf<String>()
        fun root(alias: String) {
            val spec = menu.data.getValue(alias).binding
            if (spec.type == "record") root(spec.args.getValue("source") as String)
            else roots += alias
        }
        aliases.forEach(::root)
        return roots
    }

    fun document(key: String): List<Map<String, Any?>> = menu.documents.getValue(key)

    fun install(values: Map<String, Any?>, click: Boolean) {
        val next = values.mapValues { (alias, raw) -> checked(alias, raw) }
        sources = Collections.unmodifiableMap(sources + next)
        resolved.clear()
        clicking = click
    }

    fun projectionOrder(specs: List<ListSpec>): List<ListSpec> =
        ListProjectionOrder.resolve(menu.data, specs)

    fun project(
        specs: List<ListSpec>,
        filters: Map<String, Boolean>,
        shown: Map<String, Boolean> = emptyMap(),
    ) {
        projectionOrder(specs).forEach { spec ->
            val source = source(spec.source)
            val items =
                if (spec.filter != null && filters[spec.id] == true)
                    source.items.filter {
                        Values.equal(
                            it[spec.filter.field],
                            spec.filter.state?.let { key -> state[key] } ?: spec.filter.value,
                        )
                    }
                else source.items
            val key = menu.data.getValue(spec.source).idField
            val keys = Values.list(items.map { it.getValue(key) as String })
            val rawPage =
                state[spec.pageState] as? Number ?: error("List page state must be numeric")
            require(
                Values.isOrdinaryNumber(rawPage) &&
                    rawPage.toDouble().isFinite() &&
                    rawPage.toDouble() % 1.0 == 0.0
            ) {
                "List page state must be an integer"
            }
            val last = ((keys.size + spec.pageSize - 1) / spec.pageSize - 1).coerceAtLeast(0)
            val page = rawPage.toDouble().coerceIn(0.0, last.toDouble()).toInt()
            views[spec.id] = View(spec, keys, page, shown[spec.id] ?: true)
            if (
                !clicking &&
                    views.getValue(spec.id).visible &&
                    spec.selectionState != null &&
                    state[spec.selectionState] !in keys
            ) {
                state[spec.selectionState] = keys.firstOrNull()
            }
        }
    }

    /** 仅发送成功后更新已展示身份，失败帧不替换映射。 */
    fun presented() {
        presented = views.mapValues { Values.list(it.value.slots) }
    }

    private fun key(scope: RowScope): String? =
        if (clicking) presented[scope.list]?.getOrNull(scope.slot)
        else views[scope.list]?.slots?.getOrNull(scope.slot)

    fun item(scope: RowScope): Map<String, Any?>? {
        val view = views[scope.list] ?: return null
        if (!view.visible) return null
        return source(view.spec.source).byId[key(scope)]
    }

    fun accepts(scope: RowScope): Boolean {
        val key = key(scope) ?: return false
        return views[scope.list]?.visible == true && key in views[scope.list]?.keys.orEmpty()
    }

    fun select(scope: RowScope): Pair<String, String> {
        require(accepts(scope)) { "Presented list record no longer exists or is filtered out" }
        val state =
            requireNotNull(definitions.getValue(scope.list).selectionState) {
                "List has no selection state"
            }
        return state to requireNotNull(key(scope))
    }

    fun page(list: String, step: Int): Pair<String, Int> {
        val view = views.getValue(list)
        return view.spec.pageState to
            (view.page.toLong() + step)
                .coerceIn(0, (view.pages - 1).coerceAtLeast(0).toLong())
                .toInt()
    }

    /** 只读取直接字段，点号是字面键，不是隐式查询路径。 */
    fun resolve(binding: BindingSpec): Any? =
        when (binding.type) {
            "document" -> document(binding.key)
            "source" -> {
                val source = source(binding.args.getValue("source") as String)
                when (binding.key) {
                    "size" -> source.items.size
                    "count" ->
                        source.items.count {
                            Values.equal(
                                it[binding.args.getValue("field") as String],
                                binding.args["value"],
                            )
                        }
                    else -> error("Unknown source statistic ${binding.key}")
                }
            }
            "record" -> {
                val source = source(binding.args.getValue("source") as String)
                source.byId[state[binding.args.getValue("state") as String]]?.get(binding.key)
            }
            "item" -> {
                val scope = scope(binding)
                item(scope)?.get(binding.key)
            }
            "list" -> {
                val view = views[binding.args.getValue("list") as String]
                when (binding.key) {
                    "count" ->
                        view?.let { v ->
                            val source = source(v.spec.source)
                            v.keys.count { id ->
                                Values.equal(
                                    source.byId
                                        .getValue(id)[binding.args.getValue("field") as String],
                                    binding.args["value"],
                                )
                            }
                        } ?: 0
                    "total" -> view?.keys?.size ?: 0
                    "page" -> if (view == null || view.keys.isEmpty()) 0 else view.page + 1
                    "pages" -> view?.pages ?: 0
                    "has-next" -> view != null && view.page + 1 < view.pages
                    "has-previous" -> view != null && view.page > 0
                    "selected" ->
                        view?.spec?.selectionState?.let { state[it] == key(scope(binding)) }
                            ?: false
                    else -> error("Unknown list statistic ${binding.key}")
                }
            }
            else -> error("Not a session data binding ${binding.type}")
        }

    private fun scope(binding: BindingSpec) =
        RowScope(
            binding.args.getValue("list") as String,
            (binding.args.getValue("slot") as Number).toInt(),
        )
}
