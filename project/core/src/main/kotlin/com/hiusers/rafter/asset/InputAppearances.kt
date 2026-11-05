package com.hiusers.rafter.asset

import com.hiusers.rafter.model.CompiledPage
import com.hiusers.rafter.model.MenuSnapshot
import com.hiusers.rafter.model.NodeContent
import com.hiusers.rafter.model.Rect
import com.hiusers.rafter.model.TextInputAppearance
import com.hiusers.rafter.model.Values
import java.security.MessageDigest

data class InputAppearanceRegion(
    val bounds: Rect,
    val appearance: TextInputAppearance,
)

data class InputAppearanceLayout(
    val key: String,
    val id: Int,
    val bodyWidth: Int,
    val bodyHeight: Int,
    val inputs: List<InputAppearanceRegion>,
)

/** Stable per-layout input appearance IDs carried by the client clock marker. */
object InputAppearances {
    const val REVISION = "rafter-input-appearance-v1"
    const val MAX_LAYOUTS = 64

    fun layoutJson(layouts: List<InputAppearanceLayout>): String =
        JsonForHash.toJson(layouts)

    fun bind(catalog: AssetCatalog, snapshot: MenuSnapshot): AssetCatalog {
        val candidates =
            snapshot.menus.toSortedMap().flatMap { (menuId, menu) ->
                menu.pages.toSortedMap().values.flatMap { page ->
                    if (page.spec.modal == null) return@flatMap emptyList()
                    val baseKey = ModalEncoding.key(menuId, page.spec.id)
                    catalog.modalLayouts
                        .filter { it.key == baseKey || it.key.startsWith("$baseKey@from/") }
                        .mapNotNull { layout ->
                            val regions = inputRegions(page, layout)
                            if (regions.isEmpty()) null
                            else InputAppearanceLayoutCandidate(layout.key, layout, regions)
                        }
                }
            }
                .sortedBy { it.key }
        require(candidates.size <= MAX_LAYOUTS) {
            "At most $MAX_LAYOUTS input appearance layouts are supported"
        }
        val layouts =
            candidates.mapIndexed { index, candidate ->
                InputAppearanceLayout(
                    key = candidate.key,
                    id = index + 1,
                    bodyWidth = candidate.layout.bodyWidth,
                    bodyHeight = candidate.layout.bodyHeight,
                    inputs = Values.list(candidate.regions),
                )
            }
        if (catalog.inputAppearances == layouts) return catalog
        if (layouts.isEmpty()) return catalog.copy(inputAppearances = emptyList())

        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(catalog.fingerprint.toByteArray(Charsets.UTF_8))
        hash.update(REVISION.toByteArray(Charsets.UTF_8))
        hash.update(layoutJson(layouts).toByteArray(Charsets.UTF_8))
        return catalog.copy(
            inputAppearances = Values.list(layouts),
            fingerprint = hash.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun inputRegions(
        page: CompiledPage,
        layout: NativeModalLayout,
    ): List<InputAppearanceRegion> {
        val nodes = page.spec.components.associateBy { it.id }
        return layout.plan.inputs.mapNotNull { slot ->
            val content = nodes[slot.id]?.content as? NodeContent.TextInput ?: return@mapNotNull null
            val appearance = content.appearance ?: return@mapNotNull null
            require(appearance.background == "native" || appearance.background == "transparent") {
                "Unsupported input appearance background: ${appearance.background}"
            }
            appearance.textColor?.let {
                require(it.matches(Regex("#[0-9a-fA-F]{6}"))) {
                    "Invalid input appearance text color: $it"
                }
            }
            InputAppearanceRegion(slot.controlBounds, appearance)
        }
    }

    private data class InputAppearanceLayoutCandidate(
        val key: String,
        val layout: NativeModalLayout,
        val regions: List<InputAppearanceRegion>,
    )

    private object JsonForHash {
        fun toJson(value: Any?): String =
            com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(value)
    }
}
