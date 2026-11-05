package com.hiusers.rafter

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.hiusers.rafter.asset.*
import com.hiusers.rafter.compile.MenuRepository
import com.hiusers.rafter.model.BindingSpec
import com.hiusers.rafter.render.*
import com.hiusers.rafter.runtime.UiSession
import com.hiusers.rafter.text.CompositeText
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/** 按协议字形顺序重放，不按设计稿层序推断。 */
class UiPaintOrderTest {
    @TempDir lateinit var temp: Path

    @Test
    fun legacyFixtureForegroundIsNotPaintedOverByLaterBackgroundRows() {
        taboolib.module.chat.Components.useAdventure = true
        installLegacyDefaults(temp)
        val root = temp.resolve("core/ui")
        val loaded = MenuRepository(temp, {}, ::checkStaticItemReference).load()
        val candidate = assertNotNull(loaded.candidate, loaded.diagnostics.joinToString("\n"))
        val assets = candidate.assets
        val text = CompositeText(assets)
        val menu = candidate.snapshot.menus.getValue("rafter:demo")
        val authoredIds = ImageSizing.requests(candidate.snapshot).associate { it.id to it.asset }
        val pack = AssetCompiler.build(root, assets, temp.resolve("generate"))
        val tiles =
            assets.images
                .flatMap { (id, g) -> g.tiles.map { it.character.codePointAt(0) to (id to it) } }
                .toMap()
        // 回放实际导出的字形贴图，包含尺寸变换与透明顶行。
        val bitmaps =
            JsonParser.parseString(Files.readString(pack.resolve("assets/rafter/font/images.json")))
                .asJsonObject.getAsJsonArray("providers")
                .associate { entry ->
                    val provider = entry.asJsonObject
                    val code = provider.getAsJsonArray("chars")[0].asString.codePointAt(0)
                    val file = provider.get("file").asString.substringAfter(':')
                    code to ImageIO.read(pack.resolve("assets/rafter/textures/$file").toFile())
                }
        fun topAsset(pageId: String, px: Int, py: Int): String? {
            val session = UiSession(UUID.randomUUID(), menu, emptyMap())
            if (session.page != pageId) session.navigate(pageId)
            val visible = session.current().scene.associate { it.node.id to true }
            val view =
                CanvasRenderer.render(
                    session,
                    Frame(mapOf(BindingSpec("state", "name") to "旅行者"), visible, visible),
                    text,
                )
            // 验证协议转换后的真实行序。
            val wire =
                com.hiusers.rafter.transport.DialogTransport().wire(view.messages.single().parts)
            val raw =
                net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    .serialize(wire)
            var x = 0.0
            var y = 0
            var top: String? = null
            fun visit(e: JsonElement, font: String, bold: Boolean) {
                if (e.isJsonArray) {
                    e.asJsonArray.forEach { visit(it, font, bold) }
                    return
                }
                if (e.isJsonPrimitive) {
                    e.asString.codePoints().forEach { code ->
                        if (code == 10) {
                            x = 0.0
                            y += 9
                        } else {
                            if (font == "rafter:images") {
                                val (id, tile) = tiles.getValue(code)
                                val lx = px + CanvasRenderer.EDGE_INSET - x
                                val ly = py - y
                                if (lx >= 0 && lx < tile.width && ly >= 0 && ly < tile.height) {
                                    val bitmap = bitmaps.getValue(code)
                                    val scale = bitmap.width / tile.width
                                    val alpha =
                                        bitmap.getRGB(lx.toInt() * scale, ly * scale) ushr 24
                                    if (alpha > 25) top = authoredIds[id] ?: id
                                }
                            }
                            x += assets.textWidth(String(Character.toChars(code)), bold, font)
                        }
                    }
                    return
                }
                val o = e.asJsonObject
                val face = o.get("font")?.asString ?: font
                val weight = o.get("bold")?.asBoolean ?: bold
                o.get("text")?.let { visit(it, face, weight) }
                o.getAsJsonArray("extra")?.forEach { visit(it, face, weight) }
            }
            visit(JsonParser.parseString(raw), assets.theme.defaultFont, false)
            return top
        }
        // 这些位置对应已报告的跨行遮挡区域。
        assertEquals(
            "rafter:hero",
            topAsset("home", 500, 130),
            "Floating island's middle must not be covered by frame row 126",
        )
        assertEquals(
            "rafter:atlas-card",
            topAsset("home", 36, 270),
            "White card must remain white through its caption band",
        )
        assertEquals(
            "rafter:journal",
            topAsset("journal", 36, 198),
            "Journal panel must not be cut by background row 189",
        )
        assertEquals(
            "rafter:route-card",
            topAsset("timeline", 50, 630),
            "Long-page chapter must not be cut by the paper background",
        )
    }
}
