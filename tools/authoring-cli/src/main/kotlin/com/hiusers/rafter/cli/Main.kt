package com.hiusers.rafter.cli

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.authoring.*
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.Diagnostic
import com.hiusers.rafter.model.DocumentError
import com.hiusers.rafter.pack.PackBuild
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.system.exitProcess
import taboolib.module.chat.Components

fun main(args: Array<String>) {
    exitProcess(runAuthoring(args.toList()))
}

/** 仅操作本地文件，不初始化玩家、脚本执行器或托管服务。 */
fun runAuthoring(args: List<String>, output: (String) -> Unit = ::println): Int {
    val options =
        try {
            Options.parse(args)
        } catch (failure: IllegalArgumentException) {
            output(
                Json.gson.toJson(
                    mapOf(
                        "status" to "usage-error",
                        "message" to failure.message,
                        "usage" to Options.USAGE,
                    )
                )
            )
            return 64
        }
    if (options.command == "help") {
        output(Options.USAGE)
        return 0
    }
    try {
        System.setProperty("java.awt.headless", "true")
        Components.useAdventure = true
        val version =
            Properties()
                .apply {
                    Options::class.java.getResourceAsStream("/rafter-author.properties").use { input
                        ->
                        requireNotNull(input) { "Missing tool build version" }
                        load(input)
                    }
                }
                .getProperty("version")
        val manifest = AuthoringContract.manifest(requireNotNull(version), emptyMap())
        if (options.project == null) {
            output(Json.gson.toJson(manifest))
            return 0
        }
        val root = Path.of(options.project).toRealPath()
        require(Files.isDirectory(root)) { "Project must be an existing directory" }
        val checks = OfflineChecks()
        val loaded =
            MenuRepository(root, checks::script, checks::extension)
                .load(AssetCompiler.readAllocation(root))
        val candidate = loaded.candidate
        val artifacts = linkedMapOf<String, String>()
        // 无效项目仍可导出基础规则，但不能残留旧候选文件。
        artifacts["contract"] = AuthoringExport.write(root, manifest, candidate).toString()
        if (candidate != null)
            when (options.command) {
                "preview" -> {
                    val menu = options.menu ?: candidate.defaultMenu
                    val page =
                        options.page
                            ?: candidate.snapshot.menus[menu]?.spec?.entry
                            ?: error("Unknown menu $menu")
                    val scenario =
                        options.scenario?.let { PreviewScenario.read(root, it) }
                            ?: PreviewScenario()
                    artifacts["preview"] =
                        PreviewRenderer.write(root, candidate, menu, page, scenario).toString()
                }
                "pack" -> {
                    val settings =
                        candidate.pack.copy(
                            archive = options.archive || candidate.pack.archive,
                            host = null,
                        )
                    PackBuild.prepare(root, candidate.assets, settings).use { build ->
                        AuthoringExport.verify(candidate)
                        artifacts["resource-pack"] = build.commit(root).toString()
                    }
                }
            }
        val report =
            linkedMapOf<String, Any?>(
                "status" to
                    if (candidate == null) "invalid"
                    else if (checks.incomplete) "partial" else "checked",
                "scope" to "offline",
                "engine-version" to version,
                "contract-version" to AuthoringContract.VERSION,
                "checks" to checks.report(candidate != null),
                "diagnostics" to loaded.diagnostics,
                "artifacts" to artifacts,
            )
        GeneratedFiles.publish(root, "validation") { directory ->
            Json.writeContract(directory.resolve("report.json"), report)
        }
        output(Json.gson.toJson(report))
        return if (candidate == null) 1 else if (checks.incomplete) 2 else 0
    } catch (failure: Exception) {
        val diagnostic =
            (failure as? DocumentError)?.diagnostic
                ?: Diagnostic(
                    "project",
                    "$",
                    "AUTHORING",
                    failure.message ?: failure.javaClass.simpleName,
                )
        output(
            Json.gson.toJson(
                mapOf(
                    "status" to "invalid",
                    "scope" to "offline",
                    "diagnostics" to listOf(diagnostic),
                )
            )
        )
        return 1
    }
}

internal data class Options(
    val command: String,
    val project: String?,
    val menu: String?,
    val page: String?,
    val scenario: String?,
    val archive: Boolean,
) {
    companion object {
        const val USAGE =
            """rafter-author describe [--project DIR]
rafter-author validate --project DIR
rafter-author preview --project DIR [--menu ID] [--page ID] [--scenario PROJECT_RELATIVE_YAML]
rafter-author pack --project DIR [--archive]
Exit: 0=offline checks passed; 1=invalid; 2=target Kether/extensions not verified; 64=usage.
No game/server, script evaluation, provider invocation or resource-pack delivery."""

        fun parse(args: List<String>): Options {
            if (args.isEmpty() || args == listOf("--help"))
                return Options("help", null, null, null, null, false)
            val command = args.first()
            require(command in setOf("describe", "validate", "preview", "pack")) {
                "Unknown command $command"
            }
            val allowed =
                setOf("--project") +
                    when (command) {
                        "preview" -> setOf("--menu", "--page", "--scenario")
                        "pack" -> setOf("--archive")
                        else -> emptySet()
                    }
            val values = linkedMapOf<String, String>()
            var index = 1
            while (index < args.size) {
                val key = args[index++]
                require(key in allowed && key !in values) { "Unknown or repeated option $key" }
                val value =
                    if (key == "--archive") "true"
                    else {
                        require(
                            index < args.size &&
                                !args[index].startsWith("--") &&
                                args[index].isNotBlank()
                        ) {
                            "Missing value for $key"
                        }
                        args[index++]
                    }
                values[key] = value
            }
            require(command == "describe" || "--project" in values) { "--project is required" }
            return Options(
                command,
                values["--project"],
                values["--menu"],
                values["--page"],
                values["--scenario"],
                "--archive" in values,
            )
        }
    }
}
