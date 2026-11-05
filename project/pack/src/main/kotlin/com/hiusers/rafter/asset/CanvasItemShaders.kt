package com.hiusers.rafter.asset

import com.hiusers.rafter.compile.LayoutCompiler
import com.hiusers.rafter.compile.ModalLayoutCompiler
import com.hiusers.rafter.model.*
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil

/** 只编译原生载体的位置，不扫描或烘焙业务物品模型。 */
object CanvasItemShaders {
    fun bind(catalog: AssetCatalog, snapshot: MenuSnapshot): AssetCatalog {
        val routes = mutableListOf<CanvasItemRoute>()
        val flows = linkedMapOf<String, CanvasFlowPlan>()
        val modalFlows = linkedMapOf<String, CanvasModalFlow>()
        val backdrops = catalog.modalBackdrops.toMutableList()
        val modals = catalog.modalLayouts.filter { "@from/" !in it.key }.toMutableList()
        // 按偶数正文宽度预演一行尾占位，取得不同来源共享的原生按钮边缘。
        val controlEdges = buildSet {
            snapshot.menus
                .filterValues { menu ->
                    menu.pages.values.any { page ->
                        page.scene.any { it.node.content is NodeContent.Item }
                    }
                }
                .forEach { (menuId, menu) ->
                    menu.pages.values
                        .filter { it.spec.modal != null }
                        .forEach { page ->
                            val base =
                                modals.single {
                                    it.key == ModalEncoding.key(menuId, page.spec.id)
                                }
                            val plan =
                                ModalLayoutCompiler.layout(
                                    page.spec,
                                    minimumBodyWidth = (base.bodyWidth + 1) / 2 * 2,
                                    minimumBodyHeight =
                                        base.bodyHeight +
                                            NativeGeometry.BUTTON_HEIGHT +
                                            NativeGeometry.GRID_GAP,
                                )
                            // 避免原生按钮和计数占位纹理顶点被误当作物品路由。
                            plan.buttons.forEach { cell ->
                                val offset =
                                    cell.bounds.x +
                                        (cell.bounds.width - cell.nativeWidth) / 2 -
                                        plan.bodyWidth / 2
                                // 普通／禁用按钮的九宫格边框为 3px／1px，窄按钮会收缩。
                                for (border in 0..minOf(3, cell.nativeWidth)) {
                                    add(offset + border)
                                    add(offset + cell.nativeWidth - border)
                                }
                            }
                        }
                }
        }
        val reservedChannels =
            (0 until CanvasItems.MAX_CHANNELS)
                .filter {
                    (-1 - it) in controlEdges ||
                        (CanvasItems.ICON_SIZE - 1 - it) in controlEdges
                }
                .toSet()
        val text = CompositeText(catalog)
        fun fail(node: NodeSpec, message: String): Nothing {
            val at = node.origin ?: SourceLocation("project", "$.elements", node.id)
            throw DocumentError(Diagnostic(at.file, at.path, "CAPABILITY", message, node.id))
        }
        fun hitRows(page: CompiledPage, popup: String? = null): Set<Int> {
            if (popup != null) return (0 until page.height / CanvasGeometry.ROW_HEIGHT).toSet()
            val rows = linkedSetOf<Int>()
            page.scene.forEach { placed ->
                fun include(node: NodeSpec) {
                    val content = node.content
                    if (
                        content != NodeContent.HitArea &&
                            !node.clickable &&
                            node.tooltip == null &&
                            (content as? NodeContent.Item)?.let {
                                it.showTooltip || it.showCount
                            } != true
                    )
                        return
                    val box =
                        if (content == NodeContent.Text)
                            LayoutCompiler.textBounds(
                                page.spec,
                                node,
                                text.bounds(text.render(node.text)),
                                heightLimit = page.height,
                            )
                        else placed.bounds
                    rows += box.y / 9 until LayoutCompiler.rows(box.y + box.height) / 9
                }
                include(placed.node)
                placed.node.variants.forEach { include(it.node) }
            }
            return rows
        }
        fun appendRoutes(
            menu: String,
            page: String,
            items: List<PositionedNode>,
            flow: CanvasFlowPlan,
            popup: String? = null,
            origin: String? = null,
            target: ModalBackdropLayout? = null,
            dim: Double = 1.0,
            clip: CanvasItemClip? = null,
            width: Int = CanvasGeometry.bodyWidth(flow.width),
            layoutHeight: Int = flow.layoutHeight,
        ): List<CanvasItemRoute> {
            val first = routes.size
            items.forEachIndexed { row, placed ->
                val carrier =
                    flow.blocks.filterIsInstance<CanvasCarrier>().single {
                        it.node == placed.node.id
                    }
                // 同时校验跨页正文顶端的变化，四个角不能指向不同载体。
                val minimumHeight =
                    CanvasGeometry.HEADER_HEIGHT +
                        maxOf(layoutHeight, target?.layoutHeight ?: flow.layoutHeight) +
                        CanvasGeometry.EMPTY_FOOTER_HEIGHT
                val channel =
                    (0 until CanvasItems.MAX_CHANNELS).firstOrNull { candidate ->
                        candidate !in reservedChannels && routes.none { other ->
                            val height = maxOf(minimumHeight, other.minimumGuiHeight)
                            val start =
                                CanvasItems.bodyTop(height, layoutHeight) + carrier.y -
                                    other.sourceTop(height)
                            val resting = carrier.y - other.sourceY
                            val vertical = minOf(start, resting)..maxOf(start, resting)
                            val sameY =
                                listOf(-CanvasItems.ICON_SIZE, 0, CanvasItems.ICON_SIZE).any {
                                    it in vertical
                                }
                            sameY &&
                                abs(other.channel - candidate) in setOf(0, CanvasItems.ICON_SIZE)
                        }
                    }
                        ?: fail(
                            placed.node,
                            "Canvas item source corners exhausted ${CanvasItems.MAX_CHANNELS} native width channels",
                        )
                routes +=
                    CanvasItemRoute(
                        menu,
                        page,
                        placed.node.id,
                        channel,
                        row,
                        items.size,
                        target
                            ?: ModalBackdropLayout(
                                flow.width,
                                flow.height,
                                flow.inset,
                                flow.layoutHeight,
                            ),
                        placed.bounds,
                        carrier.y,
                        carrier.height,
                        width,
                        layoutHeight,
                        popup,
                        origin,
                        dim,
                        clip,
                    )
            }
            val maximumWidth = maxOf(width, routes.drop(first).maxOfOrNull { it.itemWidth } ?: 0)
            val containerWidth = (maximumWidth + 1) / 2 * 2
            for (index in first until routes.size) {
                routes[index] =
                    routes[index].copy(
                        sourceBodyWidth = containerWidth,
                        layout =
                            target ?: routes[index].layout.copy(containerWidth = containerWidth),
                    )
            }
            return routes.drop(first)
        }
        snapshot.menus.toSortedMap().forEach { (menuId, menu) ->
            menu.pages.toSortedMap().forEach pageLoop@{ (pageId, page) ->
                val allItems = page.scene.filter { it.node.content is NodeContent.Item }
                if (allItems.isEmpty()) return@pageLoop
                if (
                    page.spec.components.isNotEmpty() ||
                        page.spec.frame != null ||
                        page.spec.modal != null
                )
                    fail(
                        allItems.first().node,
                        "Canvas items require a standalone Canvas without native controls or a viewport frame",
                    )
                allItems.forEach { placed ->
                    val content = placed.node.content as NodeContent.Item
                    val digit = ('0'..'9').maxOf { catalog.textWidth(it.toString()) }
                    if (
                        content.showCount &&
                            (ceil(digit * 2) > placed.bounds.width ||
                                ceil(catalog.theme.defaultSize) > placed.bounds.height)
                    )
                        fail(
                            placed.node,
                            "Item count text does not fit; increase size or reduce the theme font size",
                        )
                    page.popups.forEach { (key, scene) ->
                        if (
                            scene.any { it.bounds.overlaps(placed.bounds) } &&
                                placed.node.id !in page.spec.popups.getValue(key).hideUnderlay
                        )
                            fail(
                                placed.node,
                                "A popup covering an item must explicitly hide it through hide-underlay",
                            )
                    }
                }
                (listOf<String?>(null) + page.spec.popups.keys.sorted()).forEach { popup ->
                    val hidden = popup?.let { page.spec.popups.getValue(it).hideUnderlay }.orEmpty()
                    val items = allItems.filter { it.node.id !in hidden }
                    val flow =
                        CanvasFlow.plan(
                            page.spec.width,
                            page.height,
                            items.map { it.node.id },
                            hitRows(page, popup),
                        )
                    val key = CanvasItems.key(menuId, pageId, popup)
                    flows[key] = flow
                    val live = appendRoutes(menuId, pageId, items, flow, popup)
                    val backdrop =
                        live.firstOrNull()?.layout
                            ?: ModalBackdropLayout(
                                flow.width,
                                flow.height,
                                flow.inset,
                                flow.layoutHeight,
                            )
                    backdrops += backdrop
                    // 每个可冻结来源有独立的原生表单流，替换 modal 不叠加载体。
                    menu.pages
                        .toSortedMap()
                        .values
                        .filter { it.spec.modal != null }
                        .forEach { modalPage ->
                            val modal = requireNotNull(modalPage.spec.modal)
                            val base =
                                catalog.modalLayouts.single {
                                    it.key == ModalEncoding.key(menuId, modalPage.spec.id)
                                }
                            val header =
                                CanvasFlow.plan(
                                    modalPage.spec.width,
                                    modalPage.height,
                                    items.map { it.node.id },
                                    hitRows(modalPage),
                                )
                            val modalKey = CanvasItems.modalKey(menuId, modalPage.spec.id, key)
                            val layoutHeight =
                                ModalLayoutCompiler.layout(
                                    modalPage.spec,
                                    leadingInset = header.inset,
                                    minimumBodyHeight = backdrop.layoutHeight,
                                ).bodyHeight
                            val backgroundRoutes =
                                appendRoutes(
                                    menuId,
                                    modalPage.spec.id,
                                    items,
                                    header,
                                    origin = key,
                                    target = backdrop,
                                    dim = modal.dim,
                                    clip =
                                        CanvasItemClip(
                                            base.width,
                                            base.height,
                                            base.offsetY + header.inset,
                                            layoutHeight,
                                        ),
                                    width = maxOf(base.bodyWidth, backdrop.containerWidth),
                                    layoutHeight = layoutHeight,
                                )
                            val bodyWidth =
                                backgroundRoutes.firstOrNull()?.sourceBodyWidth
                                    ?: (maxOf(base.bodyWidth, backdrop.containerWidth) + 1) / 2 * 2
                            val plan =
                                ModalLayoutCompiler.layout(
                                    modalPage.spec, bodyWidth, header.inset, backdrop.layoutHeight,
                                )
                            val layout =
                                base.copy(
                                    key = modalKey,
                                    offsetY = base.offsetY + header.inset,
                                    plan = plan,
                                )
                            modals += layout
                            modalFlows[modalKey] =
                                CanvasModalFlow(key, modalPage.spec.id, header, layout)
                            backdrops +=
                                ModalBackdropLayout(
                                    header.width,
                                    header.height,
                                    header.inset,
                                    plan.bodyHeight,
                                    bodyWidth,
                                )
                        }
                }
            }
        }
        val uniqueBackdrops =
            backdrops
                .distinct()
                .sortedWith(
                    compareBy<ModalBackdropLayout> { it.width }
                        .thenBy { it.height }
                        .thenBy { it.inset }
                        .thenBy { it.layoutHeight }
                        .thenBy { it.containerWidth }
                )
        if (
            catalog.canvasItems == routes &&
                catalog.canvasFlows == flows &&
                catalog.canvasModalFlows == modalFlows &&
                catalog.modalBackdrops == uniqueBackdrops
        )
            return catalog
        val base = catalog.canvasItemBaseFingerprint ?: catalog.fingerprint
        val focus =
            (routes.map {
                    NativeFocusLayout(
                        it.menu,
                        it.page,
                        "@canvas-items",
                        NativeFocusKind.TEXT,
                        it.sourceBodyWidth,
                        0,
                        0,
                    )
                } +
                    flows.values.map {
                        NativeFocusLayout(
                            "",
                            "",
                            "@canvas-items",
                            NativeFocusKind.TEXT,
                            CanvasGeometry.bodyWidth(it.width),
                            0,
                            0,
                        )
                    })
                .distinctBy { it.vector() }
        val carrierFocus =
            routes
                .filter { it.sourceHeight > 1 }
                .map { route ->
                    NativeFocusLayout(
                        route.menu,
                        route.page,
                        "@canvas-item-carrier/${route.channel}",
                        NativeFocusKind.ITEM,
                        route.itemWidth,
                        route.sourceHeight,
                        0,
                    )
                } +
                (flows.values + modalFlows.values.map { it.flow }).flatMap { flow ->
                    flow.blocks
                        .filterIsInstance<CanvasCarrier>()
                        .filter { it.node == null && it.height > 1 }
                        .map {
                            NativeFocusLayout(
                                "",
                                "",
                                "@canvas-item-carrier/spacer",
                                NativeFocusKind.ITEM,
                                1,
                                it.height,
                                0,
                            )
                        }
                }
        val next =
            catalog.copy(
                canvasItems = Values.list(routes),
                canvasFlows = java.util.Collections.unmodifiableMap(flows),
                canvasModalFlows = java.util.Collections.unmodifiableMap(modalFlows),
                modalLayouts = Values.list(modals),
                modalBackdrops = Values.list(uniqueBackdrops),
                modalFrames = DialogFrames.compileModal(catalog.imageSpecs, modals),
                canvasItemBaseFingerprint = base,
                nativeFocusLayouts =
                    Values.list(
                        catalog.nativeFocusLayouts.filter {
                            it.component != "@canvas-items" &&
                                !it.component.startsWith("@canvas-item-carrier/")
                        } +
                            focus +
                            carrierFocus +
                            listOf(
                                NativeFocusLayout(
                                    "",
                                    "",
                                    "@canvas-item-carrier/rows",
                                    NativeFocusKind.CARRIER_ROW,
                                    CanvasItems.MAX_CHANNELS * 2 - 1,
                                    1,
                                    0,
                                )
                            )
                    ),
            )
        DialogFocusShaders.checkBudget(next)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(
            (base +
                    "\ncanvas-flow-v2\n" +
                    Json.gson.toJson(listOf(routes, flows, modalFlows, uniqueBackdrops)))
                .toByteArray()
        )
        DialogFrameShaders.fingerprint(hash, modals, uniqueBackdrops)
        DialogFocusShaders.fingerprint(hash, next)
        val spacers = clipSpacers(modals)
        ClientRenderProfiles.all.forEach {
            hash.update(source(routes, it, spacers).toByteArray())
            hash.update(fragment(it, spacers).toByteArray())
        }
        return next.copy(fingerprint = hash.digest().joinToString("") { "%02x".format(it) })
    }

    private fun clipSpacers(layouts: List<NativeModalLayout>) =
        layouts.filter { layout -> layout.plan.buttons.any { it.role == ModalCellRole.CLIP_SPACER } }

    internal fun source(
        routes: List<CanvasItemRoute>,
        profile: ClientRenderProfile,
        spacers: List<NativeModalLayout> = emptyList(),
    ): String {
        val cases =
            routes
                .mapIndexed { index, route ->
                    val b = route.bounds
                    val l = route.layout
                    val clip =
                        route.clip
                            ?.let {
                                "rafterItemClip = vec4((dimensions.x - ${it.width}.0) * 0.5, rafter_item_body_top(dimensions.y, ${it.layoutHeight}.0) + ${it.top}.0, ${it.width}.0, ${it.height}.0);"
                            }
                            .orEmpty()
                    """
            vec2 source$index = vec2(trunc((dimensions.x - ${route.bodyOuterWidth}.0) * 0.5) + ${CanvasItems.BODY_MARGIN}.0 + ${route.sourceInsetX}.0,
                rafter_item_body_top(dimensions.y, ${route.sourceLayoutHeight}.0) + ${route.sourceY}.0);
            vec2 local$index = point - source$index;
            if ((abs(local$index.x) < 0.02 || abs(local$index.x - 16.0) < 0.02) &&
                (abs(local$index.y) < 0.02 || abs(local$index.y - 16.0) < 0.02)) {
                vec2 target = vec2(trunc((dimensions.x - ${l.containerWidth + CanvasItems.BODY_MARGIN * 2}.0) * 0.5) + ${CanvasItems.BODY_MARGIN + l.contentInsetX + b.x}.0,
                    rafter_item_body_top(dimensions.y, ${l.layoutHeight}.0) + ${CanvasGeometry.TEXT_PADDING + l.inset + b.y}.0);
                vec2 moved = target + local$index * ${b.width / CanvasItems.FRAME_SIZE}.0;
                gl_Position = ProjMat * vec4(moved, position.zw);
                vertexColor.rgb *= ${route.dim.toFloat()};
                rafterItemPoint = moved;
                $clip
                return;
            }
            """
                        .trimIndent()
                }
                .joinToString("\n")
        val vanilla = ClientShaderSources.read(profile, "position_tex_color.vsh")
        val projection = "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);"
        val color = "    vertexColor = Color;"
        require(vanilla.split(projection).size == 2 && vanilla.split(color).size == 2)
        fun varying(location: Int, type: String, name: String, flat: Boolean = false) =
            (if (profile.vulkanInterface) "layout(location = $location) " else "") +
                (if (flat) "flat " else "") +
                "out $type $name;"
        val declarations =
            varying(2, "vec2", "rafterItemPoint") +
                "\n" +
                varying(3, "vec4", "rafterItemClip", true) +
                "\n" +
                (if (spacers.isEmpty()) "" else
                    varying(4, "vec2", "rafterSpacerQuad") + "\n" +
                        varying(5, "vec2", "rafterSpacerViewport", true) + "\n") +
                "float rafter_item_body_top(float height, float content) { return min(63.0, height - 5.0 - min(content, max(height - 38.0, 0.0))); }\n"
        val spacerSetup =
            if (spacers.isEmpty()) "" else """
                ${if (profile.vulkanInterface)
                    "#ifdef VULKAN\n    int corner = gl_VertexIndex & 3;\n#else\n    int corner = gl_VertexID & 3;\n#endif"
                    else "int corner = gl_VertexID & 3;"}
                rafterSpacerQuad = vec2(corner >= 2 ? 1.0 : -1.0,
                    corner == 1 || corner == 2 ? 1.0 : -1.0);
                rafterSpacerViewport = vec2(0.0);
                rafterItemPoint = position.xy;
            """.trimIndent()
        return vanilla
            .replace("void main() {", declarations + "\nvoid main() {")
            .replace(
                projection,
                "    vec4 position = ModelViewMat * vec4(Position, 1.0);\n    gl_Position = ProjMat * position;",
            )
            .replace(
                color,
                color +
                    "\n    rafterItemPoint = vec2(0.0);\n    rafterItemClip = vec4(0.0);\n" + spacerSetup + "\n" +
                    """
            if (abs(ProjMat[3][3] - 1.0) > 0.0001 || abs(gl_Position.w) < 0.0001) return;
            vec2 dimensions = round(abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1])));
            if (any(lessThanEqual(dimensions, vec2(0.0)))) return;
            ${if (spacers.isEmpty()) "" else "rafterSpacerViewport = dimensions;"}
            vec2 point = position.xy;
            $cases
            """
                        .trimIndent(),
            )
    }

    internal fun fragment(
        profile: ClientRenderProfile,
        spacers: List<NativeModalLayout> = emptyList(),
    ): String {
        val vanilla = ClientShaderSources.read(profile, "position_tex_color.fsh")
        fun varying(location: Int, type: String, name: String, flat: Boolean = false) =
            (if (profile.vulkanInterface) "layout(location = $location) " else "") +
                (if (flat) "flat " else "") +
                "in $type $name;"
        return vanilla.replace(
            "void main() {",
            varying(2, "vec2", "rafterItemPoint") +
                "\n" +
                varying(3, "vec4", "rafterItemClip", true) +
                "\n" +
                (if (spacers.isEmpty()) "" else
                    varying(4, "vec2", "rafterSpacerQuad") + "\n" +
                        varying(5, "vec2", "rafterSpacerViewport", true) + "\n" +
                        spacerFragment(spacers)) +
                """
                void main() {
                    ${if (spacers.isEmpty()) "" else "if (rafter_modal_clip_spacer()) discard;"}
                    if (rafterItemClip.z > 0.0 && all(greaterThanEqual(rafterItemPoint, rafterItemClip.xy)) &&
                        all(lessThan(rafterItemPoint, rafterItemClip.xy + rafterItemClip.zw))) discard;
                """
                    .trimIndent(),
        )
    }

    /** 只剔除编译出的 1px 空按钮纹理；排序、裁剪和输入命中仍由原生流管理。 */
    private fun spacerFragment(layouts: List<NativeModalLayout>): String {
        val matches = layouts.flatMap { layout ->
            layout.plan.buttons.filter { it.role == ModalCellRole.CLIP_SPACER }
                .groupBy { it.bounds.x to it.bounds.width }
                .values.map { cells ->
                    val first = cells.first().bounds
                    val x = first.x + (first.width - 1) / 2
                    """
                        {
                            vec2 origin = vec2(trunc((rafterSpacerViewport.x - ${layout.bodyWidth + 20}.0) * 0.5) + ${10 + x}.0,
                                min(63.0, rafterSpacerViewport.y - 5.0 - min(${layout.bodyHeight}.0, max(rafterSpacerViewport.y - 38.0, 0.0))) + ${first.y}.0);
                            float row = floor((lower.y - origin.y + 0.02) / 22.0);
                            if (abs(lower.x - origin.x) < 0.02 && abs(upper.x - origin.x - 1.0) < 0.02 &&
                                row >= 0.0 && row < ${cells.size}.0 &&
                                lower.y >= origin.y + row * 22.0 - 0.02 &&
                                upper.y <= origin.y + row * 22.0 + 20.02) return true;
                        }
                    """.trimIndent()
                }
        }.distinct().joinToString("\n")
        return """
            bool rafter_modal_clip_spacer() {
                if (rafterSpacerViewport.y <= 0.0) return false;
                // 仿射恢复不依赖混合顶点缓冲的四角相位，也不会裁掉较大纹理的一角。
                vec2 qx = dFdx(rafterSpacerQuad), qy = dFdy(rafterSpacerQuad);
                vec2 px = dFdx(rafterItemPoint), py = dFdy(rafterItemPoint);
                float determinant = qx.x * qy.y - qx.y * qy.x;
                if (abs(determinant) < 1.0e-10) return false;
                vec2 u = (px * qy.y - py * qx.y) / determinant;
                vec2 v = (py * qx.x - px * qy.x) / determinant;
                vec2 extent = abs(u) + abs(v);
                if (abs(extent.x * 2.0 - 1.0) > 0.02 || extent.y <= 0.0 || extent.y > 10.01) return false;
                vec2 center = rafterItemPoint - u * rafterSpacerQuad.x - v * rafterSpacerQuad.y;
                vec2 lower = center - extent, upper = center + extent;
                $matches
                return false;
            }

        """.trimIndent() + "\n"
    }

    fun write(stage: Path, catalog: AssetCatalog) {
        if (catalog.canvasItems.isEmpty()) return
        val spacers = clipSpacers(catalog.modalLayouts)
        ClientRenderProfiles.all.forEach { profile ->
            val folder = stage.resolve("${profile.directory}/assets/minecraft/shaders/core")
            Files.createDirectories(folder)
            Files.writeString(
                folder.resolve("position_tex_color.vsh"),
                source(catalog.canvasItems, profile, spacers),
            )
            Files.writeString(folder.resolve("position_tex_color.fsh"), fragment(profile, spacers))
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
                "layout" to "title-paint-native-body-flow",
                "scroll" to false,
                "routes" to catalog.canvasItems.map { it.manifest() },
                "flows" to catalog.canvasFlows,
                "modal-flows" to catalog.canvasModalFlows,
                "count-overlay" to "read-only-actual-stack-count",
                "channel-limit" to CanvasItems.MAX_CHANNELS,
                "durability-bar" to false,
                "cooldown-overlay" to false,
            ),
        )
    }
}
