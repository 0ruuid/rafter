package com.hiusers.rafter.compile

import com.hiusers.rafter.asset.AssetCatalog
import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.AutoAssets
import com.hiusers.rafter.asset.ButtonSkins
import com.hiusers.rafter.asset.CanvasItemShaders
import com.hiusers.rafter.asset.DialogFocusShaders
import com.hiusers.rafter.asset.FontRequests
import com.hiusers.rafter.asset.ImageSizing
import com.hiusers.rafter.asset.InputAppearances
import com.hiusers.rafter.asset.ModalAssets
import com.hiusers.rafter.asset.UiThemes
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.ProjectPaths
import com.hiusers.rafter.model.TextSpec
import com.hiusers.rafter.model.Values
import com.hiusers.rafter.model.feedbackText
import com.hiusers.rafter.pack.PackSettings
import com.hiusers.rafter.runtime.RuntimePolicy
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path

/** 已校验候选，构造时不激活菜单或发布资源。 */
data class Candidate(
    val snapshot: MenuSnapshot,
    val assets: AssetCatalog,
    val defaultMenu: String,
    val timeoutMillis: Long,
    val pack: PackSettings = PackSettings(),
    val runtime: RuntimePolicy = RuntimePolicy(),
)

data class LoadResult(val candidate: Candidate?, val diagnostics: List<Diagnostic>)

/** 只读构建候选，安装与发布由调用方负责。 */
class MenuRepository(
    private val root: Path,
    private val scriptCheck: (String) -> Unit,
    private val extensionCheck: (String, String, Map<String, Any?>) -> Unit,
    private val decode: (String) -> Map<String, Any?> = { YamlSource.decode(it) },
) {
    private fun checkSourceDirectories() {
        for (legacy in listOf("menus", "data", "templates", "ui")) {
            val directory = root.resolve(legacy)
            if (!Files.isDirectory(directory)) continue
            Files.walk(directory).use { paths ->
                val file =
                    paths
                        .filter {
                            Files.isRegularFile(it) &&
                                it.fileName.toString().substringAfterLast('.', "") in
                                    setOf("yml", "yaml", "png", "ttf")
                        }
                        .findFirst()
                        .orElse(null)
                if (file != null)
                    throw DocumentError(
                        Diagnostic(
                            root.relativize(file).toString(),
                            "$",
                            "SOURCE_DIRECTORY",
                            "Move authored sources into core/ (templates into core/ui/templates); root settings and lang stay in place",
                        )
                    )
            }
        }
    }

    private fun read(
        path: Path,
        sources: MutableMap<String, YamlSourceIndex>? = null,
    ): Map<String, Any?> {
        val source = YamlSource.read(root, path)
        val file = root.relativize(path).toString()
        sources?.put(file, source.index)
        return try {
            // 解码已预检的同一份文本，避免二次读取发生变化。
            Values.map(decode(source.text))
        } catch (failure: Exception) {
            throw DocumentError(
                Diagnostic(file, "$", "READ", failure.message ?: "Invalid document")
            )
        }
    }

    private fun documents(
        directory: String,
        sources: MutableMap<String, YamlSourceIndex>,
        hashes: MutableMap<Path, String>? = null,
    ): Map<String, Map<String, Any?>> {
        val directoryPath = root.resolve(directory)
        if (!Files.exists(directoryPath)) return emptyMap()
        return Files.walk(directoryPath).use { files ->
            files
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml") }
                .sorted()
                .toList()
                .associate { path ->
                    val hash = AssetCompiler.sha(path)
                    val document = read(path, sources)
                    require(AssetCompiler.sha(path) == hash) {
                        "$path changed during document loading; retry"
                    }
                    hashes?.put(path, hash)
                    root.relativize(path).toString() to document
                }
        }
    }

    private fun pack(settings: Map<String, Any?>): PackSettings {
        @Suppress("UNCHECKED_CAST")
        val raw = settings["resource-pack"] as? Map<String, Any?> ?: emptyMap()
        return PackSettings.read(raw)
    }

    private fun runtime(settings: Map<String, Any?>): RuntimePolicy {
        @Suppress("UNCHECKED_CAST")
        val raw = settings["runtime"] as? Map<String, Any?> ?: emptyMap()
        return RuntimePolicy.read(raw)
    }

    /** 后台发现并校验本地资源，不导入外部资源包。 */
    fun assets(previous: Map<String, Int>): AssetCatalog {
        checkSourceDirectories()
        val config = root.resolve("config.yml")
        val hash = AssetCompiler.sha(config)
        val sources = linkedMapOf<String, YamlSourceIndex>()
        return try {
            val settings = read(config, sources)
            Contract.check(settings, Contract.settings, "config.yml", "$")
            runtime(settings)
            pack(settings) // 只校验配置，不导入资源。
            val assetDocument = AutoAssets.document(root.resolve(ProjectPaths.UI))
            @Suppress("UNCHECKED_CAST")
            val theme = UiThemes.compile(assetDocument.getValue("theme") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val images = assetDocument.getValue("images") as Map<String, Map<String, Any?>>
            val dimensions = images.mapValues { (_, value) ->
                (value.getValue("width") as Number).toInt() to
                    (value.getValue("height") as Number).toInt()
            }
            val imageSources = images.mapValues { (_, value) ->
                (value["content-width"] as? Number ?: value.getValue("width") as Number).toInt() to
                    (value["content-height"] as? Number ?: value.getValue("height") as Number)
                        .toInt()
            }
            val hashes = linkedMapOf<Path, String>()
            // 先展开模板和样式，再编译所需字体，不执行脚本或提供器。
            val menus = documents(ProjectPaths.MENUS, sources, hashes)
            val templates = documents(ProjectPaths.TEMPLATES, sources, hashes)
            val data = documents(ProjectPaths.DATA, sources, hashes)
            val skins = documents(ProjectPaths.SKINS, sources, hashes)
            val structural =
                MenuCompiler(dimensions, styles = theme.styles, imageSources = imageSources)
                    .compile(menus, templates, data, skins)
            val expanded =
                structural.snapshot ?: throw DocumentError(structural.diagnostics.first())
            val requested = FontRequests.collect(expanded, theme)
            val imageRequests =
                ImageSizing.requests(expanded) +
                    com.hiusers.rafter.asset.MeterAssets.requests(expanded)
            val frames =
                expanded.menus.values
                    .flatMap { it.spec.pages.values }
                    .mapNotNull { it.frame?.asset }
                    .toSet()
            val result =
                AssetCompiler.compile(
                    root.resolve(ProjectPaths.UI),
                    assetDocument,
                    previous,
                    requested,
                    imageRequests,
                    frameAssets = frames,
                    buttonSkins = ButtonSkins.requests(expanded),
                    modal = ModalAssets.collect(expanded, imageSources),
                    textOffsets = FontRequests.canvasOffsets(expanded, theme),
                )
            require(hashes.all { (path, digest) -> AssetCompiler.sha(path) == digest }) {
                "Menu sources changed during discovery; retry"
            }
            require(AssetCompiler.sha(config) == hash) {
                "config.yml changed during asset discovery; retry"
            }
            result.copy(sourceHashes = result.sourceHashes + hashes + mapOf(config to hash))
        } catch (failure: DocumentError) {
            throw DocumentError(failure.diagnostic.withSources(sources))
        }
    }

    /** 在主线程执行平台检查，复用已发现的资源；不激活候选。 */
    fun load(
        previous: Map<String, Int> = emptyMap(),
        compiledAssets: AssetCatalog? = null,
    ): LoadResult {
        val sources = linkedMapOf<String, YamlSourceIndex>()
        return try {
            checkSourceDirectories()
            val rawSettings = read(root.resolve("config.yml"), sources)
            Contract.check(rawSettings, Contract.settings, "config.yml", "$")
            val settings = Contract.withDefaults(rawSettings, Contract.settings)
            runtime(settings)
            val timeout = (settings.getValue("idle-timeout-seconds") as Number).toLong()
            val assets = compiledAssets ?: assets(previous)
            assets.sourceHashes[root.resolve("config.yml")]?.let {
                require(AssetCompiler.sha(root.resolve("config.yml")) == it) {
                    "config.yml changed before candidate validation; retry"
                }
            }
            val text = CompositeText(assets)
            fun validateTheme(spec: TextSpec, path: String, canvas: Boolean = false) {
                try {
                    text.validate(spec)
                    if (canvas) text.validateCanvas(spec)
                } catch (failure: Exception) {
                    throw DocumentError(
                        Diagnostic(
                            ProjectPaths.THEME,
                            path,
                            "TEXT",
                            failure.message ?: "Invalid theme text",
                        )
                    )
                }
                spec.parts.forEachIndexed { index, part ->
                    validateTheme(part, "$path.parts[$index]")
                }
                spec.hover?.let { validateTheme(it, "$path.hover") }
            }
            mapOf(
                    "close" to assets.theme.closeLabel,
                    "form-error" to assets.theme.formError,
                )
                .forEach { (key, spec) ->
                    validateTheme(
                        spec,
                        "$.controls.$key",
                        canvas = key == "toggle-on" || key == "toggle-off",
                    )
                }
            val menus = documents(ProjectPaths.MENUS, sources)
            val templates = documents(ProjectPaths.TEMPLATES, sources)
            val data = documents(ProjectPaths.DATA, sources)
            val skins = documents(ProjectPaths.SKINS, sources)
            val expectedDocuments =
                assets.sourceHashes.keys
                    .filter {
                        listOf(
                                ProjectPaths.MENUS,
                                ProjectPaths.TEMPLATES,
                                ProjectPaths.DATA,
                                ProjectPaths.SKINS,
                            )
                            .any { directory -> it.startsWith(root.resolve(directory)) }
                    }
                    .toSet()
            require(
                (menus.keys + templates.keys + data.keys + skins.keys)
                    .map { root.resolve(it) }
                    .toSet() == expectedDocuments &&
                    expectedDocuments.all { AssetCompiler.sha(it) == assets.sourceHashes[it] }
            ) {
                "Menu sources changed before candidate validation; retry"
            }
            val result =
                MenuCompiler(
                        assets.dimensions(),
                        scriptCheck,
                        text::validate,
                        extensionCheck,
                        text::validateCanvas,
                        styles = assets.theme.styles,
                        imageSources = assets.sourceDimensions(),
                        canvasMeasure = { text.bounds(text.render(it)) },
                        buttonTextCheck = text::validateButtonLabel,
                    )
                    .compile(menus, templates, data, skins)
            val snapshot = result.snapshot
            if (snapshot == null) {
                LoadResult(null, result.diagnostics.map { it.withSources(sources) })
            } else {
                snapshot.menus.values
                    .flatMap { it.pages.values }
                    .forEach { page ->
                        page.modalLayout?.feedback?.let { feedback ->
                            val node = page.spec.nodes.single { it.id == feedback.id }
                            val errorSpec =
                                feedbackText(
                                    node.text,
                                    assets.theme.formError,
                                    assets.theme.palette.error,
                                )
                            val error = text.fitLine(text.render(errorSpec), feedback.bounds.width)
                            if (text.bounds(error).second > feedback.bounds.height) {
                                val at = node.origin
                                throw DocumentError(
                                    Diagnostic(
                                        at?.file ?: ProjectPaths.THEME,
                                        at?.path ?: "$.controls.form-error",
                                        "LAYOUT",
                                        "Form error text exceeds fixed feedback region height",
                                        feedback.id,
                                    )
                                )
                            }
                        }
                    }
                val default = settings["default-menu"] as String
                require(default in snapshot.menus) { "Default menu does not exist: $default" }
                val pack = pack(settings)
                val policy = runtime(settings)
                val bound = InputAppearances.bind(
                    CanvasItemShaders.bind(DialogFocusShaders.bind(assets, snapshot), snapshot),
                    snapshot,
                )
                LoadResult(
                    Candidate(
                        snapshot,
                        bound,
                        default,
                        timeout * 1000,
                        pack,
                        policy,
                    ),
                    result.diagnostics.map { it.withSources(sources) },
                )
            }
        } catch (failure: DocumentError) {
            LoadResult(null, listOf(failure.diagnostic.withSources(sources)))
        } catch (failure: Exception) {
            LoadResult(
                null,
                listOf(
                    Diagnostic(
                        "project",
                        "$",
                        "LOAD",
                        failure.message ?: failure.javaClass.simpleName,
                    )
                ),
            )
        }
    }
}
