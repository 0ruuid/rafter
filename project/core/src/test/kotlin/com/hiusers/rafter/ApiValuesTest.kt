package com.hiusers.rafter

import com.hiusers.rafter.api.*
import java.util.UUID
import kotlin.test.*

class ApiValuesTest {
    @Test
    fun simpleAndNamedCreationRetainTheirDefaults() {
        assertTrue(UiActionResult().statePatch.isEmpty())
        assertTrue(UiActionResult.empty().statePatch.isEmpty())
        assertTrue(ProviderDescriptor("Example").parameters.isEmpty())
        val required = ParameterSpec(ParameterType.STRING)
        assertTrue(required.required)
        assertFalse(required.nullable)
        assertFalse(required.hasDefault)
        assertNull(required.defaultValue)
        assertNull(required.choices)
        assertEquals("", required.description)
        assertEquals(required, ParameterSpec.of(ParameterType.STRING))
        assertFalse(ParameterSpec.optional(ParameterType.INTEGER).required)
        val named =
            ParameterSpec(
                type = ParameterType.STRING,
                required = false,
                nullable = true,
                hasDefault = true,
                defaultValue = null,
                choices = listOf(null, "example"),
                description = "Example",
            )
        assertFalse(named.required)
        assertTrue(named.nullable)
        assertTrue(named.hasDefault)
        assertEquals(listOf(null, "example"), named.choices)
        assertEquals("Changed", named.copy(description = "Changed").description)
        assertEquals("Example", named.description)
        assertEquals(
            named,
            ParameterSpec.of(
                type = ParameterType.STRING,
                required = false,
                nullable = true,
                hasDefault = true,
                defaultValue = null,
                choices = listOf(null, "example"),
                description = "Example",
            ),
        )
    }

    @Test
    fun optionsResultsAndErrorsKeepTheirPublicMeaning() {
        assertEquals(UiOpenOptions(emptyMap(), null), UiOpenOptions())
        assertEquals("edit", UiOpenOptions(initialPage = "edit").initialPage)
        assertEquals(
            mapOf("name" to "Example"),
            UiOpenOptions(initialState = mapOf("name" to "Example")).initialState,
        )
        val id = UUID.randomUUID()
        val opened = OpenResult(opened = true, sessionId = id)
        assertTrue(opened.opened)
        assertEquals(id, opened.sessionId)
        assertNull(opened.failure)
        assertEquals(opened, OpenResult.of(true, sessionId = id))
        val denied = OpenResult(false, UiFailureCode.NO_PERMISSION)
        assertEquals(UiFailureCode.NO_PERMISSION, denied.failure)
        assertNull(denied.sessionId)
        val cause = IllegalStateException("Example")
        val failure = UiOperationException(UiFailureCode.INVALID_ARGUMENT, cause = cause)
        assertSame(cause, failure.cause)
        assertEquals("INVALID_ARGUMENT", failure.message)
        assertSame(
            cause,
            UiOperationException.of(UiFailureCode.INVALID_ARGUMENT, cause = cause).cause,
        )
        assertEquals("Example", UiOperationException(UiFailureCode.INVALID_FORM, "Example").message)
    }

    @Test
    fun dtoConstructorsAndEnumsDoNotExposeKotlinRuntimeTypes() {
        for (type in
            listOf(
                UiOpenOptions::class.java,
                UiActionResult::class.java,
                ProviderDescriptor::class.java,
                ParameterSpec::class.java,
                OpenResult::class.java,
                UiOperationException::class.java,
            )) {
            assertTrue(
                type.constructors.none { constructor ->
                    constructor.parameterTypes.any { it.name.startsWith("kotlin") }
                },
                type.name,
            )
        }
        for (type in listOf(ParameterType::class.java, UiFailureCode::class.java)) {
            assertTrue(type.methods.none { it.returnType.name.startsWith("kotlin") }, type.name)
        }
    }
}
