import com.google.gson.*;
import com.mojang.blaze3d.font.*;

import net.minecraft.client.gui.font.glyphs.*;
import net.minecraft.client.gui.font.providers.*;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 通过原生提供器加载字体，不登录游戏、不覆盖全局资源包。 */
class LocalFontProviders implements AutoCloseable {
    final Map<String, List<GlyphProvider>> fonts = new HashMap<>();
    final Map<String, Map<Integer, GlyphInfo>> cache = new HashMap<>();
    final Map<String, Map<Integer, BakedGlyph>> bakedCache = new HashMap<>();
    final ResourceManager resources;

    LocalFontProviders(Path pack) throws Exception {
        final Path root = pack.toAbsolutePath().normalize();
        resources =
                (ResourceManager)
                        Proxy.newProxyInstance(
                                ResourceManager.class.getClassLoader(),
                                new Class<?>[] {ResourceManager.class},
                                (proxy, method, values) -> {
                                    String name = method.getName();
                                    if (Set.of("getResource", "getResourceOrThrow", "open")
                                            .contains(name)) {
                                        Identifier id = (Identifier) values[0];
                                        Path file =
                                                root.resolve(
                                                                "assets/"
                                                                        + id.getNamespace()
                                                                        + "/"
                                                                        + id.getPath())
                                                        .normalize();
                                        if (!file.startsWith(root))
                                            throw new IllegalArgumentException(
                                                    "Resource path escapes pack");
                                        if (name.equals("open")) return Files.newInputStream(file);
                                        Resource resource =
                                                new Resource(
                                                        null, () -> Files.newInputStream(file));
                                        return name.equals("getResource")
                                                ? Optional.of(resource)
                                                : resource;
                                    }
                                    if (name.equals("toString")) return "Rafter local probe pack";
                                    throw new UnsupportedOperationException(
                                            "Unexpected resource lookup " + method);
                                });
        try (var entries = Files.list(root.resolve("assets/rafter/font"))) {
            for (Path file : entries.filter(p -> p.toString().endsWith(".json")).toList()) {
                List<GlyphProvider> providers = new ArrayList<>();
                String key = "rafter:" + file.getFileName().toString().replace(".json", "");
                JsonArray definitions =
                        JsonParser.parseString(Files.readString(file))
                                .getAsJsonObject()
                                .getAsJsonArray("providers");
                for (JsonElement entry : definitions) {
                    JsonObject d = entry.getAsJsonObject();
                    String type = d.get("type").getAsString();
                    GlyphProvider provider;
                    if (type.equals("reference"))
                        continue; // 未知回退字形不得虚构度量。
                    if (type.equals("ttf")) {
                        JsonArray shift = d.getAsJsonArray("shift");
                        provider =
                                new TrueTypeGlyphProviderDefinition(
                                                Identifier.parse(d.get("file").getAsString()),
                                                d.get("size").getAsFloat(),
                                                d.get("oversample").getAsFloat(),
                                                new TrueTypeGlyphProviderDefinition.Shift(
                                                        shift.get(0).getAsFloat(),
                                                        shift.get(1).getAsFloat()),
                                                d.get("skip").getAsString())
                                        .unpack()
                                        .left()
                                        .orElseThrow()
                                        .load(resources);
                    } else if (type.equals("space")) {
                        Map<Integer, Float> values = new HashMap<>();
                        for (var v : d.getAsJsonObject("advances").entrySet())
                            values.put(v.getKey().codePointAt(0), v.getValue().getAsFloat());
                        provider = new SpaceProvider(values);
                    } else if (type.equals("bitmap")) {
                        int[][] chars = new int[d.getAsJsonArray("chars").size()][];
                        for (int i = 0; i < chars.length; i++)
                            chars[i] =
                                    d.getAsJsonArray("chars")
                                            .get(i)
                                            .getAsString()
                                            .codePoints()
                                            .toArray();
                        provider =
                                new BitmapProvider.Definition(
                                                Identifier.parse(d.get("file").getAsString()),
                                                d.get("height").getAsInt(),
                                                d.get("ascent").getAsInt(),
                                                chars)
                                        .unpack()
                                        .left()
                                        .orElseThrow()
                                        .load(resources);
                    } else throw new IllegalArgumentException("Unsupported provider " + type);
                    providers.add(provider);
                }
                fonts.put(key, providers);
            }
        }
    }

    GlyphInfo info(FontDescription description, int code) {
        if (code == '\n' || code == '\r') return GlyphInfo.simple(0);
        if (!(description instanceof FontDescription.Resource r))
            throw new IllegalArgumentException("Non-resource font");
        String key = r.id().toString();
        Map<Integer, GlyphInfo> memo = cache.computeIfAbsent(key, k -> new HashMap<>());
        if (memo.containsKey(code)) return memo.get(code);
        for (GlyphProvider provider : fonts.getOrDefault(key, List.of())) {
            UnbakedGlyph glyph = provider.getGlyph(code);
            if (glyph != null) {
                memo.put(code, glyph.info());
                return glyph.info();
            }
        }
        throw new IllegalArgumentException(
                "Unmeasurable native glyph " + key + " U+" + Integer.toHexString(code));
    }

    // 保留原生字形与空白几何，不上传或绘制纹理。
    BakedGlyph baked(FontDescription description, int code) {
        if (!(description instanceof FontDescription.Resource r))
            throw new IllegalArgumentException("Non-resource font");
        String key = r.id().toString();
        Map<Integer, BakedGlyph> memo = bakedCache.computeIfAbsent(key, k -> new HashMap<>());
        if (memo.containsKey(code)) return memo.get(code);
        for (GlyphProvider provider : fonts.getOrDefault(key, List.of())) {
            UnbakedGlyph glyph = provider.getGlyph(code);
            if (glyph != null) {
                BakedGlyph baked =
                        glyph.bake(
                                new UnbakedGlyph.Stitcher() {
                                    public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
                                        return new BakedSheetGlyph(
                                                info,
                                                null,
                                                null,
                                                0,
                                                1,
                                                0,
                                                1,
                                                bitmap.getLeft(),
                                                bitmap.getRight(),
                                                bitmap.getTop(),
                                                bitmap.getBottom());
                                    }

                                    public BakedGlyph getMissing() {
                                        throw new IllegalStateException(
                                                "Unexpected missing-glyph path");
                                    }
                                });
                memo.put(code, baked);
                return baked;
            }
        }
        throw new IllegalArgumentException(
                "Unmeasurable baked glyph " + key + " U+" + Integer.toHexString(code));
    }

    public void close() {
        fonts.values().forEach(list -> list.forEach(GlyphProvider::close));
    }
}
