package com.hiusers.rafter.asset

import com.google.gson.GsonBuilder
import com.hiusers.rafter.compile.Contract
import com.hiusers.rafter.compile.LayoutCompiler
import com.hiusers.rafter.model.CanvasGeometry
import com.hiusers.rafter.model.NativeGeometry
import com.hiusers.rafter.model.ProjectPaths
import com.hiusers.rafter.model.Values
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import javax.imageio.ImageIO

object Json {
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(path: Path, value: Any?) {
        Files.createDirectories(path.parent)
        Files.writeString(path, gson.toJson(value))
    }

    private val contractGson = gson.newBuilder().serializeNulls().create()

    /** 保留 null，以区分显式空值与缺失。 */
    fun writeContract(path: Path, value: Any?) {
        Files.createDirectories(path.parent)
        Files.writeString(path, contractGson.toJson(value))
    }

    fun map(path: Path): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(Files.readString(path), Map::class.java) as Map<String, Any?>
    }
}

/** 描述候选资产，不写入或发布资源包。 */
fun AssetCatalog.manifest(): Map<String, Any?> =
    mapOf(
        "fingerprint" to fingerprint,
        "client-profiles" to ClientRenderProfiles.all.map { it.manifest() },
        "canvas-items" to canvasItems.map { it.manifest() },
        "canvas-flows" to canvasFlows,
        "canvas-modal-flows" to canvasModalFlows,
        "cell-height" to 9,
        "unknown-glyph-policy" to "reject-canvas-native-fallback",
        "bounds-anchor" to
            mapOf(
                "font" to "rafter:fill",
                "character" to "\ue001",
                "advance" to 9,
                "height" to 9,
                "ascent" to AssetCompiler.GLYPH_ASCENT,
            ),
        "theme" to theme,
        "allocation" to allocation,
        "font-families" to
            fontFamilies.mapValues { (family, source) ->
                mapOf(
                    "source" to
                        "fonts/$family.${source.fileName.toString().substringAfterLast('.')}",
                    "source-sha256" to sourceHashes[source],
                )
            },
        "font-sizes" to fontSizes,
        "fonts" to
            fontFaces.mapValues { (id, face) ->
                mapOf(
                    "type" to "ttf",
                    "family" to face.request.family,
                    "source" to
                        "fonts/${face.request.family}.${face.source.fileName.toString().substringAfterLast('.')}",
                    "size" to face.request.size,
                    "source-sha256" to sourceHashes[face.source],
                    "oversample" to theme.oversample,
                    "measurement" to "integral-hmtx-em",
                    "bold-offset" to 1,
                    "widths" to faces.getValue(id).mapKeys { it.key.toString() },
                )
            },
        "images" to
            images.mapValues { (id, glyph) ->
                val spec = imageSpecs.first { it.id == id }
                mapOf(
                    "source" to spec.source,
                    "color" to spec.color,
                    "scale" to spec.scale,
                    "content-width" to spec.contentWidth,
                    "content-height" to spec.contentHeight,
                    "transform" to
                        spec.transform?.let { plan ->
                            mapOf(
                                "source-width" to plan.sourceWidth,
                                "source-height" to plan.sourceHeight,
                                "width" to plan.width,
                                "height" to plan.height,
                                "scaled-width" to plan.scaledWidth,
                                "scaled-height" to plan.scaledHeight,
                                "offset-x" to plan.offsetX,
                                "offset-y" to plan.offsetY,
                                "layout-width" to plan.layoutWidth,
                                "layout-height" to plan.layoutHeight,
                            ) +
                                if (plan.topPadding == 0) emptyMap()
                                else mapOf("top-padding" to plan.topPadding)
                        },
                    "character" to glyph.character,
                    "font" to "rafter:images",
                    "width" to glyph.width,
                    "height" to glyph.height,
                    "advance" to glyph.advance,
                    "ascent" to AssetCompiler.GLYPH_ASCENT,
                    "tiles" to glyph.tiles,
                )
            },
        "hide-dialog-warning" to hideWarning,
        "dialog-focus-outline" to DialogFocusShaders.manifest(this),
        "dialog-frames" to DialogFrameShaders.manifest(this),
        "button-skins" to ButtonSkinFonts.manifest(buttonSkins),
        "modals" to ModalFonts.manifest(this),
    )

/** 候选内分配字形，保留历史预留以稳定已有 ID。 */
internal class GlyphAllocations(previous: Map<String, Int>) {
    val entries = previous.filterKeys { !it.startsWith("held:") }.toMutableMap()
    private var next = 0xe300

    init {
        require(
            entries.values.all { it in 0xe300..0xf7ff } &&
                entries.values.toSet().size == entries.size
        ) {
            "Invalid glyph allocation ledger"
        }
        next = maxOf(next, (entries.values.maxOrNull() ?: 0xe2ff) + 1)
    }

    fun image(key: String): Int =
        entries.getOrPut(key) {
            require(next <= 0xf7ff) { "Glyph allocation exhausted" }
            next++
        }
}

object AssetCompiler {
    // 原生位图与空白几何共用 7px 基线。
    const val GLYPH_ASCENT = 7
    private const val ASSET_FORMAT_REVISION = "rafter-ui-assets-v18"
    private val safeId = Regex("rafter:[a-z0-9_./-]+")

    private fun safe(root: Path, relative: String): Path {
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root.normalize()) && !Files.isSymbolicLink(target)) {
            "Unsafe asset path $relative"
        }
        require(root.toRealPath().let { target.toRealPath().startsWith(it) }) {
            "Asset path escapes root"
        }
        return target
    }

    /** 后台读取源文件，分配变化仅保存在候选中。 */
    fun compile(
        root: Path,
        document: Map<String, Any?>,
        previous: Map<String, Int> = emptyMap(),
        requests: Set<FontRequest> = emptySet(),
        imageRequests: Set<ImageRequest> = emptySet(),
        frameAssets: Set<String> = emptySet(),
        buttonSkins: Map<ImageRequest, Int> = emptyMap(),
        modal: ModalAssets.Requests = ModalAssets.Requests(emptyList(), emptyList()),
        textOffsets: Set<Int> = TextOffsets.OFFSETS.toSet(),
    ): AssetCatalog {
        Contract.check(document, Contract.asset, ProjectPaths.UI + "/", "$")
        require(textOffsets.all { it in -512..512 }) { "Invalid text provider displacement" }
        val requestedOffsets =
            Collections.unmodifiableSet((TextOffsets.OFFSETS.toSet() + textOffsets).toSortedSet())
        val modalLayouts = Values.list(modal.layouts.map { it.copy(clear = Values.list(it.clear)) })
        val modalBackdrops = Values.list(modal.backdrops)
        require(
            modalBackdrops.all {
                it.width in 1..864 &&
                    it.height in CanvasGeometry.ROW_HEIGHT..LayoutCompiler.MAX_HEIGHT &&
                    it.height % CanvasGeometry.ROW_HEIGHT == 0
            }
        ) {
            "Invalid modal backdrop Canvas dimensions"
        }
        val themeFile = root.resolve(UiThemes.FILE)
        val diskTheme = UiThemes.document(root)
        val themeHash = if (Files.isRegularFile(themeFile)) sha(themeFile) else null
        @Suppress("UNCHECKED_CAST")
        val themeDocument = document["theme"] as? Map<String, Any?> ?: diskTheme
        require(Values.equal(themeDocument, diskTheme)) {
            "Theme changed during asset discovery; retry"
        }
        val theme = UiThemes.compile(themeDocument)
        val discovered = FontDiscovery.discover(root)
        val required =
            requests.toMutableSet().apply {
                add(theme.defaultRequest)
                theme.styles.forEach { (name, style) ->
                    if ("font" in style || "font-size" in style) {
                        val family =
                            if ("font" in style)
                                requireNotNull(style["font"] as? String) {
                                    "Theme style $name font must be a family string"
                                }
                            else theme.defaultFamily
                        val size =
                            if ("font-size" in style)
                                requireNotNull(style["font-size"] as? Number) {
                                        "Theme style $name size must be a number"
                                    }
                                    .toDouble()
                            else theme.defaultSize
                        add(FontRequest(family, size))
                    }
                }
            }
        val fontFaces =
            required
                .sortedBy { it.id }
                .associate { request ->
                    val source =
                        requireNotNull(discovered[request.family]) {
                            "Unknown font family '${request.family}' requested at size ${request.size}; expected ui/fonts/${request.family}.ttf"
                        }
                    request.id to FontFace(request, source)
                }
        val usedSources = fontFaces.values.map { it.source }.distinct()
        usedSources.forEach {
            require(Files.size(it) <= 8 * 1024 * 1024) { "Font exceeds 8 MiB: $it" }
        }
        val fontHashes =
            (usedSources + usedSources.mapNotNull { FontDiscovery.notice(root, it) })
                .associateWith { sha(it) }
        val faces =
            fontFaces.values
                .groupBy { it.source }
                .flatMap { (source, specs) ->
                    NativeFonts.widths(source, specs.associate { it.request.id to it.request.size })
                        .entries
                }
                .associate { it.key to it.value }
        val source = fontFaces.getValue(theme.defaultFont).source
        val widths = faces.getValue(theme.defaultFont)
        @Suppress("UNCHECKED_CAST")
        val authoredDefinitions = document["images"] as Map<String, Map<String, Any?>>
        require(authoredDefinitions.keys.none(ImageSizing::isGeneratedAsset)) {
            "Image sources cannot use a generated asset namespace"
        }
        val definitions =
            authoredDefinitions +
                if (imageRequests.any { it.asset == MeterAssets.PIXEL })
                    mapOf(MeterAssets.PIXEL to MeterAssets.definition)
                else emptyMap()
        require(frameAssets.all { it in definitions }) {
            "Dialog frames must reference original image assets: ${frameAssets - definitions.keys}"
        }
        val ledger = GlyphAllocations(previous)
        val allocations = ledger.entries
        val imageHashes =
            definitions.values
                .mapNotNull { it["source"] as? String }
                .distinct()
                .associate { name -> safe(root, name).let { it to sha(it) } }
        val skins =
            buttonSkins.entries
                .sortedBy { it.key.id }
                .associate { (request, inset) ->
                    require(inset in 0..4) { "Button skin inset must be 0..4" }
                    require(
                        request.width != null &&
                            request.height == NativeGeometry.BUTTON_HEIGHT - 2 * inset &&
                            request.fit == "stretch"
                    ) {
                        "Button skins need an explicit width, height 20 - 2 * inset and fit=stretch"
                    }
                    request.id to inset
                }
        val sizedRequests = imageRequests + buttonSkins.keys
        val plans =
            sizedRequests
                .sortedBy { it.id }
                .associate { request ->
                    require(request.id !in definitions) {
                        "Generated image ID collides with real asset ${request.id}"
                    }
                    val definition =
                        requireNotNull(definitions[request.asset]) {
                            "Unknown image asset ${request.asset}"
                        }
                    request.id to
                        ImageSizing.resolve(
                            request,
                            ((definition["content-width"] ?: definition.getValue("width"))
                                    as Number)
                                .toInt(),
                            ((definition["content-height"] ?: definition.getValue("height"))
                                    as Number)
                                .toInt(),
                        )
                }
        require(plans.size == sizedRequests.size) { "Generated image request ID collision" }
        val variants =
            sizedRequests
                .sortedBy { it.id }
                .associate { request ->
                    val plan = plans.getValue(request.id)
                    request.id to
                        (definitions.getValue(request.asset) +
                            mapOf(
                                "width" to plan.layoutWidth,
                                "height" to plan.layoutHeight,
                                "content-width" to plan.width,
                                "content-height" to plan.height + plan.topPadding,
                            ))
                }
        val tileMap = linkedMapOf<String, List<ImageTile>>()
        val specs =
            (definitions.toSortedMap() + variants).map { (id, definition) ->
                require(
                    safeId.matches(id) &&
                        id.substringAfter(':').split('/').none { it == ".." || it == "." }
                ) {
                    "Invalid asset ID $id"
                }
                Contract.check(
                    definition,
                    Contract.assetImage,
                    ProjectPaths.UI + "/",
                    "$.images.$id",
                )
                val width = (definition["width"] as Number).toInt()
                val height = (definition["height"] as Number).toInt()
                require(
                    width in 9..864 &&
                        height in 9..LayoutCompiler.MAX_HEIGHT &&
                        width % 9 == 0 &&
                        height % 9 == 0
                ) {
                    "Image dimensions must use 9px cells"
                }
                val contentWidth = ((definition["content-width"] ?: width) as Number).toInt()
                val contentHeight = ((definition["content-height"] ?: height) as Number).toInt()
                require(
                    contentWidth in (width - 8)..width && contentHeight in (height - 8)..height
                ) {
                    "Image content dimensions do not match cell bounds"
                }
                val plan = plans[id]
                val scale = (definition["scale"] as? Number)?.toInt() ?: 1
                require(scale in 1..8)
                require(("source" in definition) != ("color" in definition)) {
                    "Image needs exactly one of source/color"
                }
                (definition["color"] as? String)?.let {
                    require(it.matches(Regex("#[0-9a-fA-F]{6}"))) { "Invalid image color" }
                }
                if (plan == null)
                    (definition["source"] as? String)?.let {
                        imageFromFile(
                            safe(root, it),
                            width * scale,
                            height * scale,
                            scale,
                            (definition["content-width"] as? Number)?.toInt()?.times(scale),
                            (definition["content-height"] as? Number)?.toInt()?.times(scale),
                        )
                    }
                // 按源像素切片，同时保留 Dialog 的 9px 行绘制。
                // 单字形不超过 256px 图集边界，不缩小整张原图。
                val edge = (252 / scale) / 9 * 9
                val tiles = mutableListOf<ImageTile>()
                // 正文按行提交，后行的大背景会覆盖前行文字。
                // 普通图片按 9px 行拆分；按钮皮肤在一个标签内绘制，保留完整单元高度。
                val tileHeight = if (id in skins) height else 9
                for (y in 0 until contentHeight step tileHeight) {
                    for (x in 0 until contentWidth step edge) {
                        val tileWidth = minOf(edge, contentWidth - x)
                        val key = if (x == 0 && y == 0) id else "$id#$x,$y"
                        tiles +=
                            ImageTile(
                                Character.toString(ledger.image(key)),
                                x,
                                y,
                                tileWidth,
                                tileHeight,
                                tileWidth + 1,
                            )
                    }
                }
                tileMap[id] = tiles.toList()
                ImageSpec(
                    id,
                    width,
                    height,
                    definition["color"] as? String,
                    definition["source"] as? String,
                    scale,
                    contentWidth,
                    contentHeight,
                    plan,
                )
            }

        val frames = DialogFrames.compile(specs, frameAssets)
        val modalFrames = DialogFrames.compileModal(specs, modalLayouts)
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(ASSET_FORMAT_REVISION.toByteArray())
        hash.update(Json.gson.toJson(ButtonSkinFonts.manifest(skins)).toByteArray(Charsets.UTF_8))
        hash.update(Json.gson.toJson(frames).toByteArray(Charsets.UTF_8))
        hash.update(
            Json.gson
                .toJson(ModalAssets.Requests(modalLayouts, modalBackdrops))
                .toByteArray(Charsets.UTF_8)
        )
        hash.update(Json.gson.toJson(modalFrames).toByteArray(Charsets.UTF_8))
        hash.update(Json.gson.toJson(requestedOffsets).toByteArray(Charsets.UTF_8))
        if (modalLayouts.isNotEmpty() || modalBackdrops.isNotEmpty()) {
            hash.update(ModalFonts.VERSION.toByteArray(Charsets.UTF_8))
        }
        if (frames.isNotEmpty() || modalFrames.isNotEmpty() || modalBackdrops.isNotEmpty()) {
            DialogFrameShaders.fingerprint(hash, modalLayouts, modalBackdrops)
        }
        hash.update(Json.gson.toJson(document + ("theme" to themeDocument)).toByteArray())
        // 身份和内容都参与摘要，同源别名与不同字号仍相互独立。
        hash.update(
            Json.gson
                .toJson(
                    fontFaces.mapValues { (_, face) ->
                        mapOf("family" to face.request.family, "size" to face.request.size)
                    }
                )
                .toByteArray(Charsets.UTF_8)
        )
        hash.update(
            Json.gson
                .toJson(
                    fontHashes.entries
                        .associate { (path, digest) ->
                            root.toAbsolutePath().normalize().relativize(path).joinToString("/") to
                                digest
                        }
                        .toSortedMap()
                )
                .toByteArray(Charsets.UTF_8)
        )
        hash.update(Json.gson.toJson(sizedRequests.sortedBy { it.id }).toByteArray(Charsets.UTF_8))
        hash.update(Json.gson.toJson(plans).toByteArray(Charsets.UTF_8))
        hash.update(
            Json.gson
                .toJson(
                    imageHashes.entries
                        .associate { (path, digest) ->
                            root
                                .toAbsolutePath()
                                .normalize()
                                .relativize(path.toAbsolutePath().normalize())
                                .joinToString("/") to digest
                        }
                        .toSortedMap()
                )
                .toByteArray(Charsets.UTF_8)
        )
        val allocationBytes =
            Json.gson.toJson(allocations.toSortedMap()).toByteArray(Charsets.UTF_8)
        require(allocationBytes.size <= 1024 * 1024) { "Allocation ledger exceeds 1 MiB" }
        hash.update(allocationBytes)
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        val glyphs = specs.associate {
            it.id to
                Glyph(
                    Character.toString(allocations.getValue(it.id)),
                    it.width,
                    it.height,
                    it.width + 1,
                    tileMap.getValue(it.id),
                )
        }
        val sources = fontHashes + imageHashes
        val sourceHashes =
            sources + if (themeHash != null) mapOf(themeFile to themeHash) else emptyMap()
        val catalog =
            AssetCatalog(
                Collections.unmodifiableMap(glyphs),
                Collections.unmodifiableMap(widths),
                Collections.unmodifiableMap(allocations),
                source,
                Values.list(specs),
                digest,
                Collections.unmodifiableMap(sourceHashes),
                Collections.unmodifiableMap(faces),
                theme,
                document["hide-dialog-warning"] as? Boolean ?: theme.hideWarning,
                document["hide-canvas-focus-outline"] as? Boolean ?: theme.hideCanvasFocus,
                hideNativeFocusOutline =
                    document["hide-native-focus-outline"] as? Boolean ?: theme.hideNativeFocus,
                fontFaces = Collections.unmodifiableMap(fontFaces),
                fontFamilies = discovered,
                fontSizes =
                    Collections.unmodifiableMap(
                        discovered.mapValues { (_, source) ->
                            Values.list(NativeFonts.supportedSizes(source))
                        }
                    ),
                dialogFrames = frames,
                buttonSkins = Collections.unmodifiableMap(skins),
                modalLayouts = modalLayouts,
                modalBackdrops = modalBackdrops,
                modalFrames = modalFrames,
                textOffsets = requestedOffsets,
            )
        verifySources(catalog)
        return catalog
    }

    /** 候选来源校验的模块接口，不是业务 API。 */
    fun sha(path: Path) =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
            "%02x".format(it)
        }

    private fun verifySources(catalog: AssetCatalog) {
        require(catalog.sourceHashes.all { (path, hash) -> sha(path) == hash }) {
            "Assets changed during build; retry with a fresh candidate"
        }
    }

    /** 构包与预览复用完全相同的源图采样。 */
    fun raster(root: Path, spec: ImageSpec): BufferedImage {
        val source = spec.source
        val plan = spec.transform
        return when {
            plan != null -> {
                val original = source?.let {
                    imageFromFile(
                        safe(root, it),
                        plan.sourceWidth * spec.scale,
                        plan.sourceHeight * spec.scale,
                        spec.scale,
                        plan.sourceWidth * spec.scale,
                        plan.sourceHeight * spec.scale,
                    )
                }
                ImageRaster.render(original, spec.color, plan, spec.scale)
            }
            source != null ->
                imageFromFile(
                    safe(root, source),
                    spec.width * spec.scale,
                    spec.height * spec.scale,
                    spec.scale,
                )
            else ->
                BufferedImage(
                        spec.width * spec.scale,
                        spec.height * spec.scale,
                        BufferedImage.TYPE_INT_ARGB,
                    )
                    .apply {
                        val graphics = createGraphics()
                        try {
                            graphics.color = Color.decode(spec.color)
                            graphics.fillRect(
                                0,
                                0,
                                spec.contentWidth * spec.scale,
                                spec.contentHeight * spec.scale,
                            )
                        } finally {
                            graphics.dispose()
                        }
                    }
        }
    }

    internal fun imageFromFile(
        path: Path,
        width: Int,
        height: Int,
        scale: Int = 1,
        expectedSourceWidth: Int? = null,
        expectedSourceHeight: Int? = null,
    ): BufferedImage {
        require(Files.size(path) <= 16 * 1024 * 1024) { "Image exceeds 16 MiB" }
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            require(input != null) { "Invalid image" }
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Not an image" }
            val reader = readers.next()
            try {
                reader.input = input
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)
                require(
                    (expectedSourceWidth == null || sourceWidth == expectedSourceWidth) &&
                        (expectedSourceHeight == null || sourceHeight == expectedSourceHeight)
                ) {
                    "Source image dimensions changed or disagree with content metadata"
                }
                require(
                    sourceWidth in (width - 9 * scale + 1)..width &&
                        sourceHeight in (height - 9 * scale + 1)..height
                ) {
                    "Source image dimensions do not match density/cell bounds"
                }
                val image = reader.read(0)
                require(image.width == sourceWidth && image.height == sourceHeight) {
                    "Decoded image dimensions changed"
                }
                if (sourceWidth == width && sourceHeight == height) return image
                // 传输留白仅加在右侧和底部，不移动原图左上角。
                return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { padded ->
                    padded.setRGB(
                        0,
                        0,
                        sourceWidth,
                        sourceHeight,
                        image.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth),
                        0,
                        sourceWidth,
                    )
                }
            } finally {
                reader.dispose()
            }
        }
    }

    /** 只读上次发布账本，不预留字形或修改资源包。 */
    fun readAllocation(dataFolder: Path): Map<String, Int> {
        val file = dataFolder.resolve(ProjectPaths.ALLOCATION)
        if (!Files.exists(file)) return emptyMap()
        require(Files.size(file) <= 1024 * 1024) { "Allocation ledger exceeds 1 MiB" }
        @Suppress("UNCHECKED_CAST") val values = Json.map(file) as Map<String, Number>
        return values.mapValues { (_, value) ->
            require(value.toDouble() == value.toInt().toDouble()) { "Non-integer glyph allocation" }
            value.toInt()
        }
    }

    /** 在后台构包，替换前复核来源；仅替换指定产物。发布失败尽力恢复旧输出，否则保留恢复现场。 */
    fun build(root: Path, catalog: AssetCatalog, dataFolder: Path): Path {
        verifySources(catalog)
        Files.createDirectories(dataFolder)
        val destination = dataFolder.resolve("resource-pack")
        require(!Files.isSymbolicLink(destination)) {
            "Resource directory must not be a symbolic link"
        }
        require(!Files.exists(destination) || Files.isDirectory(destination)) {
            "Resource output is not a directory"
        }
        val work = Files.createTempDirectory(dataFolder, ".resource-pack-build-")
        val stage = Files.createDirectory(work.resolve("candidate"))
        val previous = work.resolve("previous")
        try {
            val textures = stage.resolve("assets/rafter/textures/font")
            Files.createDirectories(textures)
            val fontDir = stage.resolve("assets/rafter/font")
            catalog.fontFaces.values
                .distinctBy { it.request.family }
                .forEach { face ->
                    val target = fontDir.resolve("sources/${face.request.family}.ttf")
                    Files.createDirectories(target.parent)
                    Files.copy(FontDiscovery.checked(root, face.source), target)
                    FontDiscovery.notice(root, face.source)?.let { notice ->
                        require(notice in catalog.sourceHashes) {
                            "Font license changed after discovery; rebuild the candidate"
                        }
                        val license = stage.resolve("licenses/fonts/${face.request.family}.txt")
                        Files.createDirectories(license.parent)
                        Files.copy(notice, license)
                    }
                }
            val ascents =
                catalog.fontFaces.values
                    .map { it.source }
                    .distinct()
                    .associateWith(TextOffsetFonts::ascent)
            catalog.fontFaces.forEach { (id, face) ->
                // 通过字体位移将作者顶部对齐画布 Y，不直接采用原生 7px 基线。
                val shiftY = ascents.getValue(face.source) * face.request.size - GLYPH_ASCENT
                require(shiftY in -512.0..512.0) {
                    "TTF provider shift exceeds [-512,512]: $id at $shiftY"
                }
                Json.write(
                    fontDir.resolve(id.substringAfter(':') + ".json"),
                    mapOf(
                        "providers" to
                            listOf(
                                mapOf(
                                    "type" to "ttf",
                                    "file" to "rafter:sources/${face.request.family}.ttf",
                                    "size" to face.request.size,
                                    "oversample" to catalog.theme.oversample,
                                    "shift" to listOf(0.0, shiftY),
                                    "skip" to "",
                                ),
                                mapOf("type" to "reference", "id" to "minecraft:default"),
                            )
                    ),
                )
            }
            val providers =
                catalog.imageSpecs.flatMap { spec ->
                    val image = raster(root, spec)
                    catalog.dialogFrames[spec.id]?.let {
                        DialogFrames.write(stage, it, spec, image)
                    }
                    if (spec.transform == null)
                        catalog.modalLayouts.forEachIndexed { index, layout ->
                            if (layout.asset == spec.id)
                                DialogFrames.writeModal(
                                    stage,
                                    catalog.modalFrames.getValue(layout.key),
                                    spec,
                                    image,
                                    layout,
                                    index,
                                )
                        }
                    catalog.images.getValue(spec.id).tiles.map { tile ->
                        val part =
                            BufferedImage(
                                tile.width * spec.scale,
                                tile.height * spec.scale,
                                BufferedImage.TYPE_INT_ARGB,
                            )
                        val graphics = part.createGraphics()
                        graphics.drawImage(image, -tile.x * spec.scale, -tile.y * spec.scale, null)
                        graphics.dispose()
                        part.setRGB(part.width - 1, 0, part.getRGB(part.width - 1, 0) or 0x01000000)
                        val path = spec.id.substringAfter(':') + "_${tile.x}_${tile.y}"
                        val target = textures.resolve("images/$path.png")
                        Files.createDirectories(target.parent)
                        ImageIO.write(part, "png", target.toFile())
                        mapOf(
                            "type" to "bitmap",
                            "file" to "rafter:font/images/$path.png",
                            "height" to tile.height,
                            "ascent" to GLYPH_ASCENT,
                            "chars" to listOf(tile.character),
                        )
                    }
                }
            Json.write(fontDir.resolve("images.json"), mapOf("providers" to providers))
            ButtonSkinFonts.write(stage, catalog)
            val fill = BufferedImage(18, 9, BufferedImage.TYPE_INT_ARGB)
            for (x in 0..8) {
                for (y in 0..8) {
                    fill.setRGB(x, y, 0xffffffff.toInt())
                }
            }
            fill.setRGB(9 + 7, 0, 0x01000000)
            ImageIO.write(fill, "png", textures.resolve("fill.png").toFile())
            Json.write(
                fontDir.resolve("fill.json"),
                mapOf(
                    "providers" to
                        listOf(
                            mapOf(
                                "type" to "bitmap",
                                "file" to "rafter:font/fill.png",
                                "height" to 9,
                                "ascent" to GLYPH_ASCENT,
                                "chars" to listOf("\ue000\ue001"),
                            )
                        )
                ),
            )
            val advances = linkedMapOf<String, Double>()
            for (bit in 0..18) {
                advances[Character.toString(0xe100 + bit)] = (1 shl bit) / 64.0
                advances[Character.toString(0xe140 + bit)] = -(1 shl bit) / 64.0
            }
            Json.write(
                fontDir.resolve("space.json"),
                mapOf("providers" to listOf(mapOf("type" to "space", "advances" to advances))),
            )
            if (catalog.hideWarning) {
                val warning = stage.resolve("assets/minecraft/textures/gui/sprites/dialog")
                Files.createDirectories(warning)
                for (name in
                    listOf(
                        "warning_button",
                        "warning_button_highlighted",
                        "warning_button_disabled",
                    )) {
                    ImageIO.write(
                        BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB),
                        "png",
                        warning.resolve("$name.png").toFile(),
                    )
                }
                for (lang in listOf("en_us", "zh_cn", "zh_tw")) {
                    Json.write(
                        stage.resolve("assets/minecraft/lang/$lang.json"),
                        mapOf(
                            "menu.custom_screen_info.tooltip" to "",
                            "menu.custom_screen_info.button_narration" to "",
                        ),
                    )
                }
            }
            TextOffsetFonts.write(stage, catalog)
            ModalFonts.write(stage, catalog)
            DialogFocusShaders.write(stage, catalog)
            DialogFrameShaders.write(stage, catalog)
            CanvasItemShaders.write(stage, catalog)
            Json.write(stage.resolve("pack.mcmeta"), DialogFrameShaders.packMetadata(catalog))
            Json.write(stage.resolve("manifest.json"), catalog.manifest())
            Json.write(stage.resolve("allocation.json"), catalog.allocation)
            verifySources(catalog)
            if (Files.exists(destination))
                Files.move(destination, previous, StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (failure: Exception) {
                if (Files.exists(previous)) {
                    try {
                        Files.move(previous, destination, StandardCopyOption.ATOMIC_MOVE)
                    } catch (restoreFailure: Exception) {
                        failure.addSuppressed(restoreFailure)
                    }
                }
                throw failure
            }
            previous.toFile().deleteRecursively()
            return destination
        } finally {
            // 恢复失败时保留旧成功产物，供人工恢复。
            if (!Files.exists(previous)) work.toFile().deleteRecursively()
        }
    }
}
