package com.hiusers.rafter

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.runtime.RuntimePolicy
import kotlin.test.*

class RuntimePolicyTest {
    @Test
    fun defaultsAndOverridesUseTheSharedFieldDescription() {
        assertEquals(RuntimePolicy(), RuntimePolicy.read(emptyMap()))
        assertEquals(
            RuntimePolicy(2_000, 5_000, 2),
            RuntimePolicy.read(
                mapOf(
                    "render-timeout-seconds" to 2,
                    "action-timeout-seconds" to 5,
                    "read-concurrency" to 2,
                )
            ),
        )
        assertEquals(4, Contract.runtime.getValue("read-concurrency").default)
        assertTrue("runtime-fields" in Contract.manifest())
    }

    @Test
    fun idleTimeoutIsOptionalWithValidatedOverrides() {
        val minimal = mapOf<String, Any?>("default-menu" to "test:menu")
        Contract.check(minimal, Contract.settings, "config.yml", "$")
        assertEquals(
            600,
            Contract.withDefaults(minimal, Contract.settings).getValue("idle-timeout-seconds"),
        )
        assertFalse(Contract.settings.getValue("idle-timeout-seconds").required)
        for (value in listOf(30, 120, 86400)) {
            val override = minimal + ("idle-timeout-seconds" to value)
            Contract.check(override, Contract.settings, "config.yml", "$")
            assertEquals(
                value,
                Contract.withDefaults(override, Contract.settings).getValue("idle-timeout-seconds"),
            )
        }
        for (value in listOf(29, 86401, 1.5, "600", null)) {
            val error =
                assertFailsWith<DocumentError> {
                    Contract.check(
                        minimal + ("idle-timeout-seconds" to value),
                        Contract.settings,
                        "config.yml",
                        "$",
                    )
                }
            assertEquals("config.yml", error.diagnostic.file)
            assertEquals("$.idle-timeout-seconds", error.diagnostic.path)
        }
        assertEquals(
            RuntimePolicy(readConcurrency = 2),
            RuntimePolicy.read(mapOf("read-concurrency" to 2)),
        )
    }

    @Test
    fun unknownInvalidAndUnboundedPoliciesAreRejectedWithSourcePaths() {
        for (raw in
            listOf(
                mapOf("unknown" to true),
                mapOf("render-timeout-seconds" to 0),
                mapOf("action-timeout-seconds" to 301),
                mapOf("read-concurrency" to 17),
                mapOf("read-concurrency" to 1.5),
            )) {
            val error = assertFailsWith<DocumentError> { RuntimePolicy.read(raw) }
            assertEquals("config.yml", error.diagnostic.file)
            assertTrue(error.diagnostic.path.startsWith("$.runtime."))
        }
    }
}
