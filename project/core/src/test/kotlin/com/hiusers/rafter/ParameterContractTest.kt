package com.hiusers.rafter

import com.hiusers.rafter.api.ParameterSpec
import com.hiusers.rafter.api.ParameterType
import com.hiusers.rafter.api.ProviderDescriptor
import com.hiusers.rafter.runtime.Registry
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParameterContractTest {
    @Test
    fun portableTypesValidateWithoutCoercion() {
        val cases =
            listOf(
                Triple(ParameterType.STRING, "text", 1),
                Triple(ParameterType.BOOLEAN, true, "true"),
                Triple(ParameterType.NUMBER, 1.25, "1.25"),
                Triple(ParameterType.INTEGER, 2.0, 2.5),
                Triple(ParameterType.OBJECT, mapOf("nested" to listOf(1, null)), listOf(1)),
                Triple(ParameterType.ARRAY, listOf("text", false), mapOf("key" to "value")),
                Triple(ParameterType.ANY, mapOf("nested" to listOf(null, 1)), Any()),
            )
        cases.forEach { (type, valid, invalid) ->
            val registry = registry("value" to ParameterSpec(type))
            assertEquals(valid, registry.resolve(ID, mapOf("value" to valid))["value"])
            assertFails { registry.resolve(ID, mapOf("value" to invalid)) }
            assertFails { registry.check(ID, mapOf("value" to null)) }
        }
    }

    @Test
    fun requiredOptionalNullableAndDefaultsHaveDistinctMeaning() {
        val registry =
            registry(
                "required" to ParameterSpec(ParameterType.STRING, nullable = true),
                "omitted" to ParameterSpec(ParameterType.BOOLEAN, required = false),
                "fallback" to
                    ParameterSpec(
                        ParameterType.STRING,
                        required = false,
                        nullable = true,
                        hasDefault = true,
                        defaultValue = "fallback",
                    ),
                "null-default" to
                    ParameterSpec(
                        ParameterType.ANY,
                        required = false,
                        nullable = true,
                        hasDefault = true,
                    ),
            )
        assertFails { registry.resolve(ID, emptyMap()) }
        assertFails { registry.resolve(ID, mapOf("required" to "present", "unknown" to true)) }
        val resolved = registry.resolve(ID, mapOf("required" to null))
        assertEquals(
            mapOf("required" to null, "fallback" to "fallback", "null-default" to null),
            resolved,
        )
        assertFalse("omitted" in resolved)
        assertTrue("null-default" in resolved)
        assertEquals(
            null,
            registry.resolve(ID, mapOf("required" to "present", "fallback" to null))["fallback"],
        )
        registry.check(ID, mapOf("required" to null))

        val nonNullableDefault =
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.STRING,
                        required = false,
                        hasDefault = true,
                        defaultValue = "default",
                    )
            )
        assertEquals("default", nonNullableDefault.resolve(ID, emptyMap())["value"])
        assertFails { nonNullableDefault.resolve(ID, mapOf("value" to null)) }
    }

    @Test
    fun rejectsInvalidDescriptorsBeforePublishingAnything() {
        val invalid =
            listOf(
                ProviderDescriptor("blank", mapOf(" " to ParameterSpec(ParameterType.STRING))),
                descriptor(
                    ParameterSpec(
                        ParameterType.STRING,
                        hasDefault = true,
                        defaultValue = "required",
                    )
                ),
                descriptor(
                    ParameterSpec(ParameterType.STRING, required = false, defaultValue = "hidden")
                ),
                descriptor(ParameterSpec(ParameterType.STRING, choices = emptyList())),
                descriptor(ParameterSpec(ParameterType.NUMBER, choices = listOf(1, 1.0))),
                descriptor(
                    ParameterSpec(
                        ParameterType.OBJECT,
                        choices = listOf(mapOf("n" to listOf(1)), mapOf("n" to listOf(1.0))),
                    )
                ),
                descriptor(ParameterSpec(ParameterType.STRING, choices = listOf(1))),
                descriptor(ParameterSpec(ParameterType.STRING, choices = listOf(null))),
                descriptor(ParameterSpec(ParameterType.NUMBER, choices = listOf(0), minimum = 1)),
                descriptor(ParameterSpec(ParameterType.STRING, minimum = 1)),
                descriptor(ParameterSpec(ParameterType.ANY, maximum = 2)),
                descriptor(ParameterSpec(ParameterType.NUMBER, minimum = 3, maximum = 2)),
                descriptor(ParameterSpec(ParameterType.NUMBER, minimum = Double.NaN)),
                descriptor(ParameterSpec(ParameterType.NUMBER, maximum = Double.POSITIVE_INFINITY)),
                descriptor(ParameterSpec(ParameterType.INTEGER, minimum = Float.NEGATIVE_INFINITY)),
                descriptor(ParameterSpec(ParameterType.NUMBER, minimum = AtomicInteger(1))),
            )
        val registry = Registry<String>()
        invalid.forEach { invalidDescriptor ->
            assertFails { registry.register(Any(), ID, invalidDescriptor, "provider") }
            assertFalse(registry.contains(ID))
            assertTrue(registry.descriptors().isEmpty())
        }
        registry.register(Any(), ID, descriptor(ParameterSpec(ParameterType.STRING)), "valid")
        assertEquals("valid", registry.get(ID))
    }

    @Test
    fun defaultsMustPassTheSameTypeEnumRangeAndOrdinaryValueChecks() {
        val optional = ParameterSpec(ParameterType.INTEGER, required = false, hasDefault = true)
        val invalid =
            listOf(
                optional,
                optional.copy(defaultValue = "1"),
                optional.copy(defaultValue = 1.5),
                optional.copy(defaultValue = 0, minimum = 1),
                optional.copy(defaultValue = 4, maximum = 3),
                optional.copy(defaultValue = 2, choices = listOf(1, 3)),
                optional.copy(defaultValue = Double.NaN),
                optional.copy(defaultValue = Double.POSITIVE_INFINITY),
                optional.copy(defaultValue = AtomicInteger(1)),
                optional.copy(type = ParameterType.ANY, defaultValue = Any()),
                optional.copy(
                    type = ParameterType.OBJECT,
                    defaultValue = mapOf(1 to "not a string key"),
                ),
                optional.copy(
                    type = ParameterType.ARRAY,
                    defaultValue = listOf(Double.NEGATIVE_INFINITY),
                ),
                optional.copy(nullable = true, choices = listOf(1)),
            )
        invalid.forEach { spec ->
            val registry = Registry<String>()
            assertFails { registry.register(Any(), ID, descriptor(spec), "provider") }
            assertFalse(registry.contains(ID))
        }
        val valid =
            registry(
                "value" to
                    optional.copy(
                        defaultValue = 2.0,
                        minimum = 1,
                        maximum = 3,
                        choices = listOf(1, 2, 3),
                    )
            )
        assertEquals(2.0, valid.resolve(ID, emptyMap())["value"])
    }

    @Test
    fun enumsUseDeepJsonNumericEqualityAndStillCheckNullability() {
        val numeric =
            registry("value" to ParameterSpec(ParameterType.INTEGER, choices = listOf(1, 2.0)))
        assertEquals(
            BigDecimal("1.00"),
            numeric.resolve(ID, mapOf("value" to BigDecimal("1.00")))["value"],
        )
        assertEquals(
            BigInteger("2"),
            numeric.resolve(ID, mapOf("value" to BigInteger("2")))["value"],
        )
        assertFails { numeric.resolve(ID, mapOf("value" to 3)) }
        val objects =
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.OBJECT,
                        choices = listOf(mapOf("nested" to listOf(1))),
                    )
            )
        val equivalent = mapOf("nested" to listOf(1.0))
        assertEquals(equivalent, objects.resolve(ID, mapOf("value" to equivalent))["value"])
        assertFails { objects.resolve(ID, mapOf("value" to mapOf("nested" to listOf(2)))) }
        val nullable =
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.STRING,
                        nullable = true,
                        choices = listOf(null, "allowed"),
                    )
            )
        assertTrue("value" in nullable.resolve(ID, mapOf("value" to null)))
        val restricted =
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.STRING,
                        nullable = true,
                        choices = listOf("allowed"),
                    )
            )
        assertFails { restricted.resolve(ID, mapOf("value" to null)) }
    }

    @Test
    fun numericBoundsAreInclusiveAndDoNotRoundLargeIntegersToDouble() {
        val registry =
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.INTEGER,
                        minimum = BigInteger("9007199254740993"),
                        maximum = BigDecimal("9007199254740995"),
                    )
            )
        listOf(9007199254740993L, BigInteger("9007199254740995"), BigDecimal("9007199254740994.00"))
            .forEach { value ->
                assertEquals(value, registry.resolve(ID, mapOf("value" to value))["value"])
            }
        listOf(9007199254740992L, 9007199254740996L, BigDecimal("9007199254740993.5")).forEach {
            value ->
            assertFails { registry.resolve(ID, mapOf("value" to value)) }
        }
        val fractionalBounds =
            registry("value" to ParameterSpec(ParameterType.INTEGER, minimum = 1.5, maximum = 2.5))
        assertEquals(2, fractionalBounds.resolve(ID, mapOf("value" to 2))["value"])
        assertFails { fractionalBounds.resolve(ID, mapOf("value" to 1)) }
        assertFails { fractionalBounds.resolve(ID, mapOf("value" to 3)) }
    }

    @Test
    fun nonfiniteMutableAndForeignValuesAreRejectedEvenInsideAnyContainers() {
        val nonfinite =
            listOf(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )
        listOf(ParameterType.NUMBER, ParameterType.INTEGER, ParameterType.ANY).forEach { type ->
            val registry = registry("value" to ParameterSpec(type))
            nonfinite.forEach { value ->
                assertFails { registry.resolve(ID, mapOf("value" to value)) }
            }
        }
        val any = registry("value" to ParameterSpec(ParameterType.ANY))
        listOf(
                listOf(Double.NaN),
                mapOf("nested" to Float.POSITIVE_INFINITY),
                AtomicInteger(1),
                listOf(AtomicInteger(2)),
                mapOf("nested" to Any()),
                mapOf(1 to "bad key"),
                arrayOf(1),
            )
            .forEach { value ->
                assertFails { any.resolve(ID, mapOf("value" to value)) }
            }
        nonfinite.forEach { value ->
            assertFails {
                registry("value" to ParameterSpec(ParameterType.ANY, choices = listOf(value)))
            }
        }
        assertFails {
            registry("value" to ParameterSpec(ParameterType.ANY, choices = listOf(Any())))
        }
    }

    @Test
    fun registeredDefaultsChoicesAndResolvedArgumentsAreDeepImmutableSnapshots() {
        val items = mutableListOf<Any?>(1)
        val payload = linkedMapOf<String, Any?>("items" to items)
        val choices = mutableListOf<Any?>(payload)
        val parameters =
            linkedMapOf(
                "value" to
                    ParameterSpec(
                        ParameterType.OBJECT,
                        required = false,
                        hasDefault = true,
                        defaultValue = payload,
                        choices = choices,
                    )
            )
        val registry = Registry<String>()
        registry.register(Any(), ID, ProviderDescriptor("snapshot", parameters), "provider")
        val exported = registry.descriptors()
        items[0] = 99
        payload["extra"] = true
        choices.clear()
        parameters.clear()
        val expected = mapOf("value" to mapOf("items" to listOf(1)))
        assertEquals(expected, registry.resolve(ID, emptyMap()))
        assertEquals(exported, registry.descriptors())
        assertDeeplyImmutable(exported)
        assertDeeplyImmutable(registry.resolve(ID, emptyMap()))

        val suppliedItems = mutableListOf<Any?>(1)
        val suppliedPayload = linkedMapOf<String, Any?>("items" to suppliedItems)
        val supplied = linkedMapOf<String, Any?>("value" to suppliedPayload)
        val resolved = registry.resolve(ID, supplied)
        suppliedItems[0] = 7
        suppliedPayload["extra"] = false
        supplied.clear()
        assertEquals(expected, resolved)
        assertDeeplyImmutable(resolved)
        assertEquals(expected, registry.resolve(ID, emptyMap()))
    }

    @Test
    fun descriptorsExportTheExactValidationConstraintsAsJsonSchema() {
        val registry =
            registry(
                "count" to
                    ParameterSpec(
                        ParameterType.INTEGER,
                        minimum = 1,
                        maximum = 3,
                        description = "Count",
                    ),
                "mode" to
                    ParameterSpec(
                        ParameterType.STRING,
                        required = false,
                        hasDefault = true,
                        defaultValue = "safe",
                        choices = listOf("safe", "fast"),
                        description = "Mode",
                    ),
                "tag" to
                    ParameterSpec(
                        ParameterType.STRING,
                        required = false,
                        nullable = true,
                        hasDefault = true,
                        choices = listOf(null, "tag"),
                    ),
                "optional" to ParameterSpec(ParameterType.BOOLEAN, required = false),
                "any" to ParameterSpec(ParameterType.ANY, required = false),
                "nullable-any" to
                    ParameterSpec(ParameterType.ANY, required = false, nullable = true),
            )
        assertEquals(
            mapOf(
                ID to
                    mapOf(
                        "description" to "parameters",
                        "parameters" to
                            mapOf(
                                "type" to "object",
                                "additionalProperties" to false,
                                "required" to listOf("count"),
                                "properties" to
                                    mapOf(
                                        "count" to
                                            mapOf(
                                                "type" to "integer",
                                                "description" to "Count",
                                                "minimum" to 1,
                                                "maximum" to 3,
                                            ),
                                        "mode" to
                                            mapOf(
                                                "type" to "string",
                                                "description" to "Mode",
                                                "default" to "safe",
                                                "enum" to listOf("safe", "fast"),
                                            ),
                                        "tag" to
                                            mapOf(
                                                "type" to listOf("string", "null"),
                                                "description" to "",
                                                "default" to null,
                                                "enum" to listOf(null, "tag"),
                                            ),
                                        "optional" to
                                            mapOf("type" to "boolean", "description" to ""),
                                        "any" to
                                            mapOf(
                                                "type" to
                                                    listOf(
                                                        "string",
                                                        "boolean",
                                                        "number",
                                                        "object",
                                                        "array",
                                                    ),
                                                "description" to "",
                                            ),
                                        "nullable-any" to
                                            mapOf(
                                                "type" to
                                                    listOf(
                                                        "string",
                                                        "boolean",
                                                        "number",
                                                        "object",
                                                        "array",
                                                        "null",
                                                    ),
                                                "description" to "",
                                            ),
                                    ),
                            ),
                    )
            ),
            registry.descriptors(),
        )
        assertEquals(
            mapOf("count" to 1, "mode" to "safe", "tag" to null),
            registry.resolve(ID, mapOf("count" to 1)),
        )
        assertFails { registry.resolve(ID, emptyMap()) }
        assertFails { registry.resolve(ID, mapOf("count" to 0)) }
        assertFails { registry.resolve(ID, mapOf("count" to 2, "mode" to "other")) }
        assertFails { registry.resolve(ID, mapOf("count" to 2, "unknown" to true)) }
        assertDeeplyImmutable(registry.descriptors())
    }

    @Test
    fun defaultsAndArgumentsShareTheNestingLimit() {
        var nested: Any? = "leaf"
        repeat(31) { nested = listOf(nested) }
        val valid =
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.ANY,
                        required = false,
                        hasDefault = true,
                        defaultValue = nested,
                    )
            )
        assertEquals(nested, valid.resolve(ID, emptyMap())["value"])
        assertEquals(nested, valid.resolve(ID, mapOf("value" to nested))["value"])
        assertDeeplyImmutable(valid.descriptors())
        assertFails { valid.resolve(ID, mapOf("value" to listOf(nested))) }
        assertFails {
            registry(
                "value" to
                    ParameterSpec(
                        ParameterType.ANY,
                        required = false,
                        hasDefault = true,
                        defaultValue = listOf(nested),
                    )
            )
        }
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        assertFails { valid.resolve(ID, mapOf("value" to cycle)) }
    }

    @Test
    fun ownerIdentityAndOldHandlesCannotRemoveAnIdenticalReplacement() {
        data class Owner(val name: String)
        val owner = Owner("owner")
        val equalButDifferentOwner = Owner("owner")
        val registry = Registry<String>()
        val descriptor = descriptor(ParameterSpec(ParameterType.STRING))
        assertFails { registry.register(owner, "not-namespaced", descriptor, "same") }
        val first = registry.register(owner, ID, descriptor, "same")
        assertFails { registry.register(equalButDifferentOwner, ID, descriptor, "same") }
        assertSame(owner, registry.owner(ID))
        first.close()
        val replacement = registry.register(owner, ID, descriptor, "same")
        first.close()
        first.close()
        assertEquals("same", registry.get(ID))
        assertTrue(registry.removeOwner(equalButDifferentOwner).isEmpty())
        val exported = registry.descriptors()
        val removed = registry.removeOwner(owner)
        assertEquals(setOf(ID), removed)
        assertFailsWith<UnsupportedOperationException> {
            (removed as MutableSet<String>).add("test:other")
        }
        assertFails { registry.resolve(ID, mapOf("value" to "missing")) }
        registry.register(equalButDifferentOwner, ID, descriptor, "same")
        replacement.close()
        assertSame(equalButDifferentOwner, registry.owner(ID))
        registry.clear()
        replacement.close()
        assertTrue(registry.descriptors().isEmpty())
        assertFalse(registry.contains(ID))
        assertEquals(null, registry.owner(ID))
        assertFails { registry.get(ID) }
        assertTrue(ID in exported)
        assertDeeplyImmutable(exported)
    }

    private fun descriptor(spec: ParameterSpec) =
        ProviderDescriptor("parameters", mapOf("value" to spec))

    private fun registry(vararg parameters: Pair<String, ParameterSpec>): Registry<String> =
        Registry<String>().also {
            it.register(Any(), ID, ProviderDescriptor("parameters", mapOf(*parameters)), "provider")
        }

    @Suppress("UNCHECKED_CAST")
    private fun assertDeeplyImmutable(value: Any?) {
        when (value) {
            is Map<*, *> -> {
                assertFailsWith<UnsupportedOperationException> {
                    (value as MutableMap<Any?, Any?>)["mutation"] = true
                }
                value.values.forEach(::assertDeeplyImmutable)
            }
            is List<*> -> {
                assertFailsWith<UnsupportedOperationException> {
                    (value as MutableList<Any?>).add("mutation")
                }
                value.forEach(::assertDeeplyImmutable)
            }
        }
    }

    private companion object {
        const val ID = "test:parameters"
    }

    @Test
    fun leasesAreIdentityScopedAndRemovalOnlyReportsTheExactRegistration() {
        val registry = Registry<String>()
        val owner = Any()
        val descriptor = ProviderDescriptor("leased")
        val first = registry.register(owner, "test:lease", descriptor, "same")
        val lease = registry.acquire("test:lease", emptyMap())
        assertEquals("same", lease.value)
        assertTrue(lease.isCurrent())
        assertTrue(first.remove())
        assertFalse(first.remove())
        assertFalse(lease.isCurrent())
        registry.register(owner, "test:lease", descriptor, "same")
        assertFalse(first.remove())
        assertFalse(lease.isCurrent())
        assertTrue(registry.acquire("test:lease", emptyMap()).isCurrent())
    }
}
