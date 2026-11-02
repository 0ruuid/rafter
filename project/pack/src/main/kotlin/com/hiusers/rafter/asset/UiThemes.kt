package com.hiusers.rafter.asset

import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.TextSpecDecoder
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.ProjectPaths
import com.hiusers.rafter.model.SourcePath
import com.hiusers.rafter.model.Values
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** 主题仅使用显式 YAML，不提供 JSON 回退或隐式迁移。 */
object UiThemes {
    const val FILE = "theme.yml"
    private const val DIAGNOSTIC_FILE = ProjectPaths.THEME
    private const val MAX_BYTES = 1_048_576

    fun document(root: Path): Map<String, Any?> =
        try {
            require(!Files.exists(root.resolve("theme.json"), NOFOLLOW_LINKS)) {
                "core/ui/theme.json is no longer supported; move your settings to core/ui/theme.yml and remove the old file"
            }
            val file = root.resolve(FILE)
            val stream =
                if (Files.exists(file, NOFOLLOW_LINKS)) {
                    require(!Files.isSymbolicLink(file) && Files.isRegularFile(file)) {
                        "Theme must be a regular file"
                    }
                    require(file.toRealPath().startsWith(root.toRealPath())) {
                        "Theme escapes the UI directory"
                    }
                    Files.newInputStream(file, NOFOLLOW_LINKS)
                } else {
                    // 宿主提供默认主题，独立构包者需提供自己的主题。
                    requireNotNull(javaClass.getResourceAsStream("/$DIAGNOSTIC_FILE")) {
                        "Missing $DIAGNOSTIC_FILE"
                    }
                }
            val bytes = stream.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES) { "Theme exceeds 1 MiB" }
            val options =
                LoaderOptions().apply {
                    isAllowDuplicateKeys = false
                    nestingDepthLimit = 32
                    maxAliasesForCollections = 32
                    codePointLimit = MAX_BYTES
                }
            val value = Yaml(SafeConstructor(options)).load<Any?>(bytes.toString(Charsets.UTF_8))
            require(value is Map<*, *> && value.keys.all { it is String }) {
                "Theme must be an object"
            }
            @Suppress("UNCHECKED_CAST") Values.map(value as Map<String, Any?>)
        } catch (failure: Exception) {
            throw DocumentError(
                Diagnostic(DIAGNOSTIC_FILE, "$", "THEME_READ", failure.message ?: "Invalid theme")
            )
        }

    fun compile(document: Map<String, Any?>): UiTheme {
        Contract.check(document, Contract.theme, DIAGNOSTIC_FILE, "$")
        @Suppress("UNCHECKED_CAST")
        fun section(name: String) = document.getValue(name) as Map<String, Any?>
        val defaults = section("defaults")
        val palette = section("palette")
        val controls = section("controls")
        Contract.check(defaults, Contract.themeDefaults, DIAGNOSTIC_FILE, "$.defaults")
        Contract.check(palette, Contract.themePalette, DIAGNOSTIC_FILE, "$.palette")
        Contract.check(controls, Contract.themeControls, DIAGNOSTIC_FILE, "$.controls")
        val family = defaults.getValue("font") as String
        val size = (defaults.getValue("font-size") as Number).toDouble()
        try {
            FontRequest(family, size)
        } catch (failure: IllegalArgumentException) {
            fail("$.defaults", failure.message ?: "Invalid default font")
        }
        @Suppress("UNCHECKED_CAST")
        val styles =
            (document["styles"] as? Map<String, Any?>).orEmpty().mapValues { (name, value) ->
                val path = SourcePath.field("$.styles", name)
                if (!name.matches(Regex("[a-z0-9][a-z0-9_.-]*"))) fail(path, "Invalid style name")
                if (value !is Map<*, *> || value.keys.any { it !is String })
                    fail(path, "Style must be an object")
                val fields = value as Map<String, Any?>
                Contract.check(fields, Contract.style, DIAGNOSTIC_FILE, path)
                try {
                    FontRequest(
                        fields["font"] as? String ?: family,
                        (fields["font-size"] as? Number)?.toDouble() ?: size,
                    )
                } catch (failure: IllegalArgumentException) {
                    fail(path, failure.message ?: "Invalid style font")
                }
                Values.map(fields)
            }
        val decoder = TextSpecDecoder(DIAGNOSTIC_FILE)
        fun text(key: String) = decoder.decode(controls.getValue(key), "$.controls.$key")
        return UiTheme(
            defaultFamily = family,
            defaultSize = size,
            oversample = (document["oversample"] as? Number)?.toDouble() ?: 1.0,
            styles = java.util.Collections.unmodifiableMap(styles),
            palette = UiTheme.Palette(palette.getValue("error") as String),
            closeLabel = text("close"),
            formError = text("form-error"),
            hideWarning = document.getValue("hide-dialog-warning") as Boolean,
            hideCanvasFocus = document.getValue("hide-canvas-focus-outline") as Boolean,
            hideNativeFocus = document.getValue("hide-native-focus-outline") as Boolean,
        )
    }

    private fun fail(path: String, message: String): Nothing =
        throw DocumentError(Diagnostic(DIAGNOSTIC_FILE, path, "THEME", message))
}
