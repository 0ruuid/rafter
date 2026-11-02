import com.mojang.blaze3d.font.*;
import com.mojang.blaze3d.platform.NativeImage;

import org.lwjgl.util.freetype.FT_Face;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 读取原生 FreeType 栅格结果，不上传 GPU。 */
class FontRasterProbe {
    static Object field(Object object, String name) throws Exception {
        Field value = object.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(object);
    }

    public static void main(String[] args) throws Exception {
        try (var fonts = new LocalFontProviders(Path.of(args[0]))) {
            for (String name : List.of("text", "heading", "caption")) {
                String id = "rafter:" + name;
                var provider =
                        fonts.fonts.get(id).stream()
                                .filter(p -> p instanceof TrueTypeGlyphProvider)
                                .findFirst()
                                .orElseThrow();
                FT_Face face = (FT_Face) field(provider, "face");
                int partial = 0, pixels = 0, count = 0;
                for (int code : "玩家旅行冒险清晰文字RafterWi□☑".codePoints().toArray()) {
                    var glyph = provider.getGlyph(code);
                    int width = (Integer) field(glyph, "width"),
                            height = (Integer) field(glyph, "height"),
                            index = (Integer) field(glyph, "index");
                    try (var raster =
                            new NativeImage(NativeImage.Format.LUMINANCE, width, height, false)) {
                        if (!raster.copyFromFont(face, index))
                            throw new AssertionError("Vanilla rasterization failed");
                        for (int y = 0; y < height; y++)
                            for (int x = 0; x < width; x++) {
                                int alpha = raster.getLuminanceOrAlpha(x, y) & 255;
                                if (alpha != 0 && alpha != 255) partial++;
                                pixels++;
                            }
                    }
                    count++;
                }
                float oversample = (Float) field(provider, "oversample");
                System.out.printf(
                        Locale.ROOT,
                        "%s oversample=%.1f samples=%d raster-pixels=%d intermediate-alpha=%d%n",
                        id,
                        oversample,
                        count,
                        pixels,
                        partial);
                if (args.length > 1 && args[1].equals("crisp") && (oversample != 1 || partial != 0))
                    throw new AssertionError(
                            "Pixel font is not rendered on its native binary-alpha grid");
            }
        }
    }
}
