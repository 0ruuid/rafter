package com.hiusers.rafter.model

/** 数据源定义不持有玩家或提供器实例。 */
data class DataSourceSpec(val binding: BindingSpec, val idField: String = "id")

data class ListFilter(
    val field: String,
    val value: Any?,
    val condition: ConditionSpec? = null,
    val state: String? = null,
)

data class ListPlacement(val x: Int, val y: Int, val params: Map<String, Any?> = emptyMap())

data class ListSpec(
    val id: String,
    val source: String,
    val pageState: String,
    val selectionState: String?,
    val placements: List<ListPlacement>,
    val filter: ListFilter? = null,
    val visible: ConditionSpec? = null,
    val popup: String? = null,
) {
    val pageSize: Int
        get() = placements.size
}

/** 槽位仅是传输身份，实际展示的稳定记录键由会话保存。 */
data class RowScope(val list: String, val slot: Int)

/** 数据投影按依赖排序，不依赖绘制或 YAML 声明顺序。 */
object ListProjectionOrder {
    fun resolve(sources: Map<String, DataSourceSpec>, lists: List<ListSpec>): List<ListSpec> {
        val owners = lists.filter { it.selectionState != null }.groupBy { it.selectionState!! }
        fun sourceStates(alias: String): Set<String> {
            val binding = sources.getValue(alias).binding
            return if (binding.type == "record")
                sourceStates(binding.args.getValue("source") as String) +
                    (binding.args.getValue("state") as String)
            else emptySet()
        }
        fun bindingStates(binding: BindingSpec?): Set<String> =
            when (binding?.type) {
                "state" -> setOf(binding.key)
                "record" ->
                    sourceStates(binding.args.getValue("source") as String) +
                        (binding.args.getValue("state") as String)
                "source" -> sourceStates(binding.args.getValue("source") as String)
                else -> emptySet()
            }
        fun conditionStates(condition: ConditionSpec?): Set<String> {
            condition ?: return emptySet()
            return bindingStates(condition.binding) +
                bindingStates(condition.compare) +
                (if (condition.type == "state-equals") setOf(condition.key) else emptySet()) +
                condition.children.flatMap(::conditionStates)
        }
        val done = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val ordered = mutableListOf<ListSpec>()
        fun visit(list: ListSpec) {
            if (list.id in done) return
            require(visiting.add(list.id)) { "List selection dependency cycle at ${list.id}" }
            val states =
                sourceStates(list.source) +
                    listOfNotNull(list.filter?.state) +
                    conditionStates(list.filter?.condition) +
                    conditionStates(list.visible)
            states.flatMap { owners[it].orEmpty() }.forEach(::visit)
            visiting.remove(list.id)
            done += list.id
            ordered += list
        }
        lists.forEach(::visit)
        return ordered
    }
}
