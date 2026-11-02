package com.hiusers.rafter

import com.hiusers.rafter.asset.AutoAssets
import com.hiusers.rafter.compile.MenuCompiler
import com.hiusers.rafter.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.yaml.snakeyaml.Yaml

fun minimal(
    mode: String = "native",
    components: List<Map<String, Any?>> =
        listOf(mapOf("id" to "label", "type" to "text", "text" to "Hello")),
): Map<String, Any?> =
    mapOf(
        "id" to "test:menu",
        "entry" to "main",
        "state" to mapOf("name" to "old", "enabled" to true, "choice" to "a", "number" to 5),
        "pages" to
            mapOf(
                "main" to
                    mapOf(
                        "mode" to mode,
                        "title" to "Title",
                        (if (mode == "canvas") "elements" else "components") to components,
                    )
            ),
    )

fun compiled(document: Map<String, Any?> = minimal()): CompiledMenu =
    MenuCompiler().compile(mapOf("test.yml" to document)).snapshot!!.menus.getValue("test:menu")

@Suppress("UNCHECKED_CAST")
fun yaml(name: String): Map<String, Any?> =
    Yaml().load(Fixtures::class.java.classLoader.getResourceAsStream(name)) as Map<String, Any?>

private object Fixtures

// 历史界面仅供机制回归，不作为默认配置。
private val legacyDefaults: Path
    get() =
        Path.of(
            requireNotNull(Fixtures::class.java.classLoader.getResource("fixtures/legacy-defaults"))
                .toURI()
        )
private const val sharedFont = "core/ui/fonts/rafter-mono.ttf"

fun legacyYaml(name: String): Map<String, Any?> = yaml("fixtures/legacy-defaults/$name")

fun legacyUiDocument(): Map<String, Any?> = AutoAssets.document(legacyDefaults.resolve("core/ui"))

fun copyLegacyResourceTo(name: String, target: Path) {
    // 复用唯一的默认字体，测试资源不重复打包 TTF。
    val resource = if (name == sharedFont) name else "fixtures/legacy-defaults/$name"
    Files.createDirectories(target.parent)
    requireNotNull(Fixtures::class.java.classLoader.getResourceAsStream(resource)) {
            "Missing test resource: $resource"
        }
        .use {
            Files.copy(it, target, REPLACE_EXISTING)
        }
}

private fun copyLegacyTreeTo(directory: String, target: Path) {
    val source = legacyDefaults.resolve(directory)
    Files.walk(source).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .forEach { file ->
                copyLegacyResourceTo(
                    legacyDefaults.relativize(file).toString().replace('\\', '/'),
                    target.resolve(source.relativize(file)),
                )
            }
    }
}

fun copyLegacyUiTo(root: Path) {
    copyLegacyTreeTo("ui", root)
    copyLegacyResourceTo(sharedFont, root.resolve("fonts/rafter-mono.ttf"))
}

fun installLegacyDefaults(root: Path) {
    copyLegacyTreeTo("", root)
    copyLegacyResourceTo(sharedFont, root.resolve(sharedFont))
}
