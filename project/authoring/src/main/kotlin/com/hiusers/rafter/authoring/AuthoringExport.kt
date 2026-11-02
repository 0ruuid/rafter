package com.hiusers.rafter.authoring

import com.hiusers.rafter.asset.AssetCompiler
import com.hiusers.rafter.asset.Json
import com.hiusers.rafter.asset.manifest
import com.hiusers.rafter.compile.AuthoringContract
import com.hiusers.rafter.compile.Candidate
import com.hiusers.rafter.compile.Contract
import java.nio.file.Path
import java.util.Collections

/** 宿主与离线工具共用的导出集合，不执行扩展。 */
object AuthoringExport {
    fun fingerprints(root: Path, candidate: Candidate?): Map<String, String> {
        val base = root.toAbsolutePath().normalize()
        return candidate
            ?.assets
            ?.sourceHashes
            .orEmpty()
            .entries
            .associate { (path, hash) ->
                val source = path.toAbsolutePath().normalize()
                require(source.startsWith(base)) { "Candidate source escapes project" }
                base.relativize(source).toString().replace('\\', '/') to hash
            }
            .toSortedMap()
    }

    fun documents(
        root: Path,
        manifest: Map<String, Any?>,
        candidate: Candidate?,
    ): Map<String, Any?> {
        val hashes = fingerprints(root, candidate)
        return Collections.unmodifiableMap(
            linkedMapOf<String, Any?>(
                    "authoring.json" to
                        (manifest +
                            mapOf(
                                "source-fingerprints" to hashes,
                                "has-candidate" to (candidate != null),
                            )),
                    "menu.schema.json" to Contract.schema(),
                    "assets.schema.json" to Contract.assetSchema(),
                    "template.schema.json" to Contract.templateSchema(),
                    "data.schema.json" to Contract.dataSchema(),
                    "skin.schema.json" to Contract.skinSchema(),
                    "theme.schema.json" to Contract.themeSchema(),
                    "preview.schema.json" to PreviewScenario.schema(),
                )
                .apply {
                    if (candidate != null) {
                        put("assets.json", candidate.assets.manifest())
                        put("layouts.json", AuthoringContract.layouts(candidate.snapshot))
                        put("preview-data.json", AuthoringContract.previewData(candidate.snapshot))
                        put("preview-stubs.json", PreviewInputs.describe(candidate.snapshot))
                    }
                }
        )
    }

    fun verify(candidate: Candidate?) {
        require(
            candidate?.assets?.sourceHashes.orEmpty().all { (path, hash) ->
                AssetCompiler.sha(path) == hash
            }
        ) {
            "Sources changed after compilation; retry with a fresh candidate"
        }
    }

    fun write(root: Path, manifest: Map<String, Any?>, candidate: Candidate?): Path {
        val documents = documents(root, manifest, candidate)
        return GeneratedFiles.publish(root, "contract", { verify(candidate) }) { directory ->
            documents.forEach { (name, value) ->
                Json.writeContract(directory.resolve(name), value)
            }
        }
    }
}
