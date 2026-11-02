package com.hiusers.rafter

import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.NativeBodyPosition
import kotlin.test.*

class NativeBodyPositionTest {
    @Suppress("UNCHECKED_CAST")
    @Test
    fun canvasBodyPositionIsSharedByDecoderSchemaAndEditorExport() {
        val key = "native-body-position"
        val field = Contract.pageFields("canvas").getValue(key)
        assertEquals(listOf("before-canvas", "after-canvas"), field.values)
        assertEquals("after-canvas", field.default)
        assertFalse(key in Contract.pageFields("native"))
        val definitions = Contract.schema().getValue("\$defs") as Map<String, Map<String, Any?>>
        val pages = definitions.getValue("page").getValue("oneOf") as List<Map<String, Any?>>
        val schemas = pages.associate { page ->
            val properties = page.getValue("properties") as Map<String, Map<String, Any?>>
            properties.getValue("mode").getValue("const") to properties
        }
        assertEquals(field.schema(), schemas.getValue("canvas").getValue(key))
        assertFalse(key in schemas.getValue("native"))
        val capabilities =
            AuthoringContract.manifest("test", emptyMap()).getValue("capabilities")
                as Map<String, Map<String, Any?>>
        val exportedFields =
            capabilities.getValue("canvas").getValue("page") as Map<String, Map<String, Any?>>
        assertEquals(field.values, exportedFields.getValue(key)["enum"])
        assertEquals(field.default, exportedFields.getValue(key)["default"])

        fun document(mode: String, extras: Map<String, Any?>) =
            minimal() +
                ("pages" to
                    mapOf(
                        "main" to
                            (mapOf(
                                "mode" to mode,
                                "title" to "Order",
                                (if (mode == "canvas") "elements" else "components") to
                                    emptyList<Any>(),
                            ) + extras)
                    ))
        val compiler = MenuCompiler()
        for ((extras, position) in
            listOf(
                emptyMap<String, Any?>() to NativeBodyPosition.AFTER_CANVAS,
                mapOf(key to "before-canvas") to NativeBodyPosition.BEFORE_CANVAS,
                mapOf(key to "after-canvas") to NativeBodyPosition.AFTER_CANVAS,
            )) {
            val result = compiler.compile(mapOf("order.yml" to document("canvas", extras)))
            val snapshot = assertNotNull(result.snapshot, result.diagnostics.toString())
            assertEquals(
                position,
                snapshot.menus.getValue("test:menu").pages.getValue("main").spec.nativeBodyPosition,
            )
            val menus =
                AuthoringContract.layouts(snapshot).getValue("menus")
                    as Map<String, Map<String, Map<String, Any?>>>
            assertEquals(position.id, menus.getValue("test:menu").getValue("main")[key])
        }
        for (value in listOf("middle", "BEFORE_CANVAS", "", null, true)) {
            val result =
                compiler.compile(mapOf("order.yml" to document("canvas", mapOf(key to value))))
            assertNull(result.snapshot)
            assertEquals("$.pages.main.$key", result.diagnostics.single().path)
            assertEquals(
                if (value is String) "LAYOUT" else "FIELD_TYPE",
                result.diagnostics.single().code,
            )
        }
        val native =
            compiler.compile(
                mapOf("order.yml" to document("native", mapOf(key to "before-canvas")))
            )
        assertNull(native.snapshot)
        assertEquals("UNKNOWN_FIELD", native.diagnostics.single().code)
    }
}
