package com.hiusers.rafter.runtime

import com.hiusers.rafter.api.ParameterSpec
import com.hiusers.rafter.api.ParameterType
import com.hiusers.rafter.api.ProviderDescriptor
import com.hiusers.rafter.model.Values
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

/** 同步注册表，不依赖平台；复制参数时调用方不得并发修改输入，不在此执行提供器。 */
class Registry<T> {
    // 按身份而非内容比较，旧句柄不能删除内容相同的新注册。
    private class Entry<T>(
        val owner: Any,
        val descriptor: ProviderDescriptor,
        val value: T,
        val token: Any = Any(),
    )

    private val entries = linkedMapOf<String, Entry<T>>()

    /** 先校验并复制描述再注册；失败不占 ID，句柄仅删除本代。 */
    class Registration internal constructor(private val removeEntry: () -> Boolean) :
        AutoCloseable {
        /** 仅删除本代注册时返回 true。 */
        fun remove(): Boolean = removeEntry()

        override fun close() {
            remove()
        }
    }

    class Lease<T>
    internal constructor(
        val value: T,
        val args: Map<String, Any?>,
        private val valid: () -> Boolean,
    ) {
        fun isCurrent(): Boolean = valid()

        /** 完成检查只保留注册代标记，不保留已移除的 owner 或回调。 */
        fun validity(): () -> Boolean = valid
    }

    @Synchronized
    fun register(owner: Any, id: String, descriptor: ProviderDescriptor, value: T): Registration {
        require(id.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
            "Provider IDs must be namespaced"
        }
        require(id !in entries) { "Duplicate provider $id" }
        val entry = Entry(owner, snapshot(descriptor), value)
        entries[id] = entry
        return Registration { synchronized(this) { entries.remove(id, entry) } }
    }

    @Synchronized
    fun acquire(id: String, args: Map<String, Any?>): Lease<T> {
        val entry = entries[id] ?: throw NoSuchElementException("Unavailable provider $id")
        val token = entry.token
        return Lease(entry.value, resolve(id, args)) {
            synchronized(this) { entries[id]?.token === token }
        }
    }

    @Synchronized
    fun get(id: String): T =
        entries[id]?.value ?: throw NoSuchElementException("Unavailable provider $id")

    @Synchronized fun contains(id: String) = id in entries

    @Synchronized fun owner(id: String): Any? = entries[id]?.owner

    /** 按 owner 身份移除注册，不取消已启动的回调。 */
    @Synchronized
    fun removeOwner(owner: Any): Set<String> {
        val ids = entries.filterValues { it.owner === owner }.keys.toSet()
        ids.forEach(entries::remove)
        return Collections.unmodifiableSet(ids)
    }

    /**
     * 拒绝未知、缺失或非法参数，返回深度不可变普通值。
     * 仅缺失的可选字段补默认值；显式 null 仍校验，不做类型转换或执行回调。
     */
    @Synchronized
    fun resolve(id: String, args: Map<String, Any?>): Map<String, Any?> {
        val descriptor =
            entries[id]?.descriptor ?: throw NoSuchElementException("Unavailable provider $id")
        require((args.keys - descriptor.parameters.keys).isEmpty()) {
            "Unknown provider arguments for $id"
        }
        val resolved = linkedMapOf<String, Any?>()
        descriptor.parameters.forEach { (name, spec) ->
            when {
                name in args -> resolved[name] = freezeValue(args[name])
                spec.hasDefault -> resolved[name] = spec.defaultValue
                else -> require(!spec.required) { "Missing provider argument $name" }
            }
            if (name in resolved) validateValue("Argument $name", spec, resolved[name])
        }
        return Collections.unmodifiableMap(resolved)
    }

    /** 仅作编译期检查，调用提供器时须使用 [resolve] 返回值。 */
    fun check(id: String, args: Map<String, Any?>) {
        resolve(id, args)
    }

    /** 描述来自同一份注册快照；Schema 的 default 仅为注解。 */
    @Synchronized
    fun descriptors(): Map<String, Any?> =
        Collections.unmodifiableMap(
            entries.mapValues { (_, entry) ->
                val parameters = entry.descriptor.parameters
                mapOf(
                        "description" to entry.descriptor.description,
                        "parameters" to
                            Collections.unmodifiableMap(
                                linkedMapOf<String, Any?>(
                                    "type" to "object",
                                    "properties" to
                                        Collections.unmodifiableMap(
                                            parameters.mapValues { (_, spec) -> schema(spec) }
                                        ),
                                    "required" to
                                        Values.list(
                                            parameters.filterValues { it.required }.keys.toList()
                                        ),
                                    "additionalProperties" to false,
                                )
                            ),
                    )
                    .let { Collections.unmodifiableMap(it) }
            }
        )

    @Synchronized fun clear() = entries.clear()

    private fun snapshot(descriptor: ProviderDescriptor): ProviderDescriptor {
        val parameters =
            descriptor.parameters.mapValues { (name, source) ->
                require(name.isNotBlank()) { "Provider parameter names must not be blank" }
                require(!source.required || !source.hasDefault) {
                    "Required parameter $name cannot have a default"
                }
                require(source.hasDefault || source.defaultValue == null) {
                    "Parameter $name has a value without hasDefault"
                }
                require(
                    source.minimum == null && source.maximum == null ||
                        source.type == ParameterType.NUMBER ||
                        source.type == ParameterType.INTEGER
                ) {
                    "Parameter $name must be numeric to have bounds"
                }
                val minimum = source.minimum?.let(::decimal)
                val maximum = source.maximum?.let(::decimal)
                require(minimum == null || maximum == null || minimum <= maximum) {
                    "Parameter $name minimum exceeds maximum"
                }
                val spec =
                    source.copy(
                        defaultValue =
                            if (source.hasDefault) freezeValue(source.defaultValue) else null,
                        choices = source.choices?.let { Values.list(it.map(::freezeValue)) },
                    )
                spec.choices?.let { choices ->
                    require(choices.isNotEmpty()) { "Parameter $name choices must not be empty" }
                    // ponytail: 枚举列表较小，直接比较；注册成本增长时再改用规范键。
                    choices.forEachIndexed { index, choice ->
                        validateValue("Choice $index for $name", spec, choice, checkChoices = false)
                        require(choices.subList(0, index).none { Values.equal(it, choice) }) {
                            "Parameter $name choices must be unique"
                        }
                    }
                }
                if (spec.hasDefault) validateValue("Default for $name", spec, spec.defaultValue)
                spec
            }
        return descriptor.copy(parameters = Collections.unmodifiableMap(parameters))
    }

    private fun validateValue(
        label: String,
        spec: ParameterSpec,
        value: Any?,
        checkChoices: Boolean = true,
    ) {
        if (value == null) {
            require(spec.nullable) { "$label must not be null" }
        } else {
            require(
                when (spec.type) {
                    ParameterType.STRING -> value is String
                    ParameterType.BOOLEAN -> value is Boolean
                    ParameterType.NUMBER -> value is Number
                    ParameterType.INTEGER ->
                        value is Number && decimal(value).stripTrailingZeros().scale() <= 0
                    ParameterType.OBJECT -> value is Map<*, *>
                    ParameterType.ARRAY -> value is List<*>
                    ParameterType.ANY -> true
                }
            ) {
                "$label must be ${spec.type.name.lowercase()}"
            }
            val minimum = spec.minimum
            val maximum = spec.maximum
            if (minimum != null || maximum != null) {
                val number = decimal(value as Number)
                require(minimum == null || number >= decimal(minimum)) { "$label is below minimum" }
                require(maximum == null || number <= decimal(maximum)) { "$label is above maximum" }
            }
        }
        val choices = spec.choices
        require(!checkChoices || choices == null || choices.any { Values.equal(it, value) }) {
            "$label must be one of the declared choices"
        }
    }

    private fun schema(spec: ParameterSpec): Map<String, Any?> {
        val types =
            if (spec.type == ParameterType.ANY) {
                listOf("string", "boolean", "number", "object", "array")
            } else listOf(spec.type.name.lowercase())
        val schema =
            linkedMapOf<String, Any?>(
                "type" to
                    if (!spec.nullable && types.size == 1) types.single()
                    else Values.list(types + if (spec.nullable) listOf("null") else emptyList()),
                "description" to spec.description,
            )
        if (spec.hasDefault) schema["default"] = spec.defaultValue
        if (spec.choices != null) schema["enum"] = spec.choices
        if (spec.minimum != null) schema["minimum"] = spec.minimum
        if (spec.maximum != null) schema["maximum"] = spec.maximum
        return Collections.unmodifiableMap(schema)
    }

    private fun freezeValue(value: Any?): Any? = Values.freeze(value, 1).also(::checkNumbers)

    private fun checkNumbers(value: Any?) {
        when (value) {
            is Number -> decimal(value)
            is Map<*, *> -> value.values.forEach(::checkNumbers)
            is List<*> -> value.forEach(::checkNumbers)
        }
    }

    private fun decimal(value: Number): BigDecimal {
        require(
            value is Byte ||
                value is Short ||
                value is Int ||
                value is Long ||
                value is Float ||
                value is Double ||
                value.javaClass == BigInteger::class.java ||
                value.javaClass == BigDecimal::class.java
        ) {
            "Numbers must be ordinary immutable JVM values"
        }
        require(value.toDouble().isFinite()) { "Non-finite number" }
        return BigDecimal(value.toString())
    }
}
