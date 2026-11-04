package com.hiusers.rafter.asset

import com.hiusers.rafter.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.ceil

/** 只为编译后的物品载体生成路由，不扫描或烘焙业务物品模型。 */
object CanvasItemShaders {

    fun bind(catalog: AssetCatalog, snapshot: MenuSnapshot): AssetCatalog {
        val routes = mutableListOf<CanvasItemRoute>()
        snapshot.menus.toSortedMap().forEach { (menuId, menu) ->
            menu.pages.toSortedMap().forEach pageLoop@{ (pageId, page) ->
                val items = page.scene.filter { it.node.content is NodeContent.Item }
                if (items.isEmpty()) return@pageLoop
                fun fail(node: NodeSpec, message: String): Nothing {
                    val at =
                        node.origin
                            ?: SourceLocation(menu.spec.source, "$.pages.$pageId.elements", node.id)
                    throw DocumentError(
                        Diagnostic(at.file, at.path, "CAPABILITY", message, node.id)
                    )
                }
                if (
                    page.spec.components.isNotEmpty() ||
                        page.spec.frame != null ||
                        page.spec.modal != null
                ) {
                    fail(
                        items.first().node,
                        "Canvas items require a standalone Canvas without native controls or a dialog frame",
                    )
                }
                if (page.spec.popups.isNotEmpty()) {
                    items.forEach { item ->
                        page.popups.forEach { (key, scene) ->
                            if (
                                scene.any { it.bounds.overlaps(item.bounds) } &&
                                    item.node.id !in page.spec.popups.getValue(key).hideUnderlay
                            ) {
                                fail(
                                    item.node,
                                    "A popup covering an item must explicitly hide it through hide-underlay",
                                )
                            }
                        }
                    }
                }
                val largestDigit = ('0'..'9').maxBy { catalog.textWidth(it.toString()) }
                val countBounds =
                    ceil(catalog.textWidth("$largestDigit$largestDigit")).toInt() to
                        ceil(catalog.theme.defaultSize).toInt()
                val start = routes.size
                items.forEachIndexed { row, placed ->
                    val content = placed.node.content as NodeContent.Item
                    if (
                        content.showCount &&
                            (countBounds.first > placed.bounds.width ||
                                countBounds.second > placed.bounds.height)
                    ) {
                        fail(
                            placed.node,
                            "Item count text does not fit; increase size or reduce the theme font size",
                        )
                    }
                    // 不依赖混合顶点缓冲的 gl_VertexID 相位；四角必须唯一指向同一载体。
                    val channel =
                        (0 until CanvasItems.MAX_CHANNELS).firstOrNull { candidate ->
                            routes.none {
                                it.channel == candidate ||
                                    it.row == row &&
                                        kotlin.math.abs(it.channel - candidate) in
                                            (CanvasItems.ICON_SIZE - 1)..(CanvasItems.ICON_SIZE + 1)
                            }
                        }
                            ?: fail(
                                placed.node,
                                "Canvas item routing exhausted ${CanvasItems.MAX_CHANNELS} non-conflicting native width channels",
                            )
                    routes +=
                        CanvasItemRoute(
                            menuId,
                            pageId,
                            placed.node.id,
                            channel,
                            row,
                            items.size,
                            page.spec.width,
                            page.height,
                            placed.bounds,
                        )
                }
                val maximum = routes.drop(start).maxOf { it.channel }
                for (index in start until routes.size) {
                    routes[index] = routes[index].copy(maximumChannel = maximum)
                }
            }
        }
        if (catalog.canvasItems == routes) return catalog
        val base = catalog.canvasItemBaseFingerprint ?: catalog.fingerprint
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    (base +
                            "\ncanvas-items-v1\n" +
                            ClientRenderProfiles.all.joinToString("\n") { source(routes, it) } +
                            Json.gson.toJson(routes))
                        .toByteArray()
                )
                .joinToString("") { "%02x".format(it) }
        val focus =
            routes
                .groupBy { it.menu to it.page }
                .values
                .map { group ->
                    val route = group.first()
                    NativeFocusLayout(
                        route.menu,
                        route.page,
                        "@canvas-items",
                        NativeFocusKind.TEXT,
                        CanvasGeometry.bodyWidth(route.canvasWidth),
                        0,
                        0,
                    )
                }
        return catalog.copy(
            canvasItems = Values.list(routes),
            fingerprint = hash,
            canvasItemBaseFingerprint = base,
            nativeFocusLayouts =
                Values.list(
                    catalog.nativeFocusLayouts.filter { it.component != "@canvas-items" } + focus
                ),
        )
    }

    internal fun source(routes: List<CanvasItemRoute>, profile: ClientRenderProfile): String {
        val cases =
            routes.joinToString("\n") { route ->
                val b = route.bounds
                """
            vec2 source${route.channel} = vec2(trunc((dimensions.x - ${route.bodyOuterWidth}.0) * 0.5) + ${CanvasItems.BODY_MARGIN}.0 + ${route.sourceInsetX}.0, ${route.sourceTop}.0);
            vec2 local${route.channel} = point - source${route.channel};
            if ((abs(local${route.channel}.x) < 0.02 || abs(local${route.channel}.x - ${CanvasItems.ICON_SIZE}.0) < 0.02) &&
                (abs(local${route.channel}.y) < 0.02 || abs(local${route.channel}.y - ${CanvasItems.ICON_SIZE}.0) < 0.02)) {
                if (dimensions.y < ${route.minimumGuiHeight}.0 || dimensions.x < ${route.minimumGuiWidth}.0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    return;
                }
                vec2 target = vec2(trunc((dimensions.x - ${route.bodyOuterWidth}.0) * 0.5) + ${CanvasItems.BODY_MARGIN}.0 + ${route.canvasInsetX + b.x}.0,
                    ${route.canvasTop + b.y}.0);
                vec2 moved = target + local${route.channel} * ${b.width / CanvasItems.FRAME_SIZE}.0;
                gl_Position = ProjMat * vec4(moved, position.zw);
                return;
            }
            """
                    .trimIndent()
            }
        val vanilla = ClientShaderSources.read(profile, "position_tex_color.vsh")
        val projection = "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);"
        val color = "    vertexColor = Color;"
        require(vanilla.split(projection).size == 2 && vanilla.split(color).size == 2) {
            "Unexpected item shader anchors: ${profile.directory}"
        }
        val routeSource =
            """
            if (abs(ProjMat[3][3] - 1.0) > 0.0001 || abs(gl_Position.w) < 0.0001) return;
            vec2 dimensions = round(abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1])));
            if (any(lessThanEqual(dimensions, vec2(0.0)))) return;
            vec2 point = position.xy;
            $cases
        """
                .trimIndent()
        return vanilla
            .replace(
                projection,
                "    vec4 position = ModelViewMat * vec4(Position, 1.0);\n    gl_Position = ProjMat * position;",
            )
            .replace(color, color + "\n" + routeSource)
    }

    fun write(stage: Path, catalog: AssetCatalog) {
        if (catalog.canvasItems.isEmpty()) return
        ClientRenderProfiles.all.forEach { profile ->
            val folder = stage.resolve("${profile.directory}/assets/minecraft/shaders/core")
            Files.createDirectories(folder)
            Files.writeString(
                folder.resolve("position_tex_color.vsh"),
                source(catalog.canvasItems, profile),
            )
        }
        Json.write(
            stage.resolve("assets/rafter/items/empty.json"),
            mapOf("model" to mapOf("type" to "minecraft:model", "model" to "rafter:item/empty")),
        )
        Json.write(
            stage.resolve("assets/rafter/models/item/empty.json"),
            mapOf(
                "elements" to emptyList<Any>(),
                "textures" to mapOf("particle" to "minecraft:item/paper"),
            ),
        )
        Json.write(
            stage.resolve("item-layouts.json"),
            mapOf(
                "profiles" to ClientRenderProfiles.all.map { it.manifest() },
                "layout" to "compiled-local-canvas-coordinates",
                "scroll" to false,
                "routes" to catalog.canvasItems.map { it.manifest() },
                "count-overlay" to "read-only-actual-stack-count",
                "channel-limit" to CanvasItems.MAX_CHANNELS,
                "durability-bar" to false,
                "cooldown-overlay" to false,
            ),
        )
    }
}
