import com.google.gson.*;
import com.mojang.blaze3d.font.*;

import net.minecraft.client.gui.font.providers.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 读取指定客户端字体提供器，不使用 GPU 或登录游戏。 */
class NativeFontProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        LocalFontProviders fonts = new LocalFontProviders(root);
        JsonObject manifest =
                JsonParser.parseString(Files.readString(root.resolve("manifest.json")))
                        .getAsJsonObject();
        for (String name : List.of("text", "heading", "caption")) {
            String fontName = "rafter:" + name;
            var fontId =
                    new net.minecraft.network.chat.FontDescription.Resource(
                            Identifier.parse(fontName));
            JsonObject exported =
                    manifest.getAsJsonObject("fonts")
                            .getAsJsonObject(fontName)
                            .getAsJsonObject("widths");
            int count = 0;
            for (var entry : exported.entrySet()) {
                int code = Integer.parseInt(entry.getKey());
                GlyphInfo glyph = fonts.info(fontId, code);
                float client = glyph.getAdvance();
                double server = entry.getValue().getAsDouble();
                if (Math.abs(client - server) > 0.000001 || glyph.getBoldOffset() != 1)
                    throw new AssertionError(
                            fontName
                                    + " U+"
                                    + Integer.toHexString(code)
                                    + " native="
                                    + client
                                    + " exported="
                                    + server
                                    + " bold="
                                    + glyph.getBoldOffset());
                count++;
            }
            var bounds =
                    fonts.baked(fontId, '中')
                            .createGlyph(0, 0, -1, 0, net.minecraft.network.chat.Style.EMPTY, 1, 1);
            System.out.printf(
                    java.util.Locale.ROOT,
                    "%s 中 actual glyph bounds=[%.3f,%.3f,%.3f,%.3f]%n",
                    fontName,
                    bounds.activeLeft(),
                    bounds.activeTop(),
                    bounds.activeRight(),
                    bounds.activeBottom());
            System.out.println(
                    "PASS: "
                            + fontName
                            + " all "
                            + count
                            + " exported glyph advances and bold offsets match the actual native"
                            + " provider");
        }
        fonts.close();
    }
}
