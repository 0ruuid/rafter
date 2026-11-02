import com.google.gson.*;
import com.mojang.blaze3d.font.GlyphInfo;

import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.*;

import java.nio.file.*;
import java.util.*;

/** 用指定客户端的真实字体与断行器重放布局，不作为 GPU 渲染验收。 */
class ClientLayoutProbe {
    static LocalFontProviders nativeFonts;
    static final Map<String, Map<Integer, Float>> widths = new HashMap<>();
    static final Map<Integer, Float> images = new HashMap<>();
    static final Map<Integer, String> assetIds = new HashMap<>();
    static final Map<Integer, Path> textures = new HashMap<>();
    static final Map<Integer, java.awt.image.BufferedImage> bitmapCache = new HashMap<>();

    static class PaintProbe {
        final int x, y;
        final String expected;
        String actual;

        PaintProbe(JsonObject p) {
            x = p.get("x").getAsInt();
            y = p.get("y").getAsInt();
            expected = p.get("asset").getAsString();
        }
    }

    static float advance(FontDescription font, int code) {
        if (nativeFonts != null) return nativeFonts.info(font, code).getAdvance();
        String name =
                font instanceof FontDescription.Resource r
                        ? r.id().toString()
                        : "minecraft:default";
        return widths.getOrDefault(name, Map.of()).getOrDefault(code, code == 32 ? 4f : 9f);
    }

    static MutableComponent component(JsonElement e, Style inherited) {
        if (e.isJsonPrimitive()) return Component.literal(e.getAsString()).setStyle(inherited);
        if (e.isJsonArray()) {
            MutableComponent result = Component.empty();
            for (JsonElement child : e.getAsJsonArray()) result.append(component(child, inherited));
            return result;
        }
        JsonObject o = e.getAsJsonObject();
        Style s = inherited;
        if (o.has("font"))
            s =
                    s.withFont(
                            new FontDescription.Resource(
                                    Identifier.parse(o.get("font").getAsString())));
        if (o.has("shadow_color")) s = s.withShadowColor(o.get("shadow_color").getAsInt());
        if (o.has("bold")) s = s.withBold(o.get("bold").getAsBoolean());
        if (o.has("italic")) s = s.withItalic(o.get("italic").getAsBoolean());
        JsonElement click = o.has("click_event") ? o.get("click_event") : o.get("clickEvent");
        if (click != null) {
            JsonObject event = click.getAsJsonObject();
            if (!event.get("action").getAsString().equals("custom"))
                throw new IllegalArgumentException("Unexpected non-CUSTOM click");
            String id =
                    event.has("id")
                            ? event.get("id").getAsString()
                            : event.get("value").getAsString();
            s = s.withClickEvent(new ClickEvent.Custom(Identifier.parse(id), Optional.empty()));
        }
        MutableComponent result =
                Component.literal(o.has("text") ? o.get("text").getAsString() : "").setStyle(s);
        if (o.has("extra"))
            for (JsonElement child : o.getAsJsonArray("extra")) result.append(component(child, s));
        return result;
    }

    static void catalog(Path pack) throws Exception {
        JsonObject m =
                JsonParser.parseString(Files.readString(pack.resolve("manifest.json")))
                        .getAsJsonObject();
        for (var face : m.getAsJsonObject("fonts").entrySet()) {
            Map<Integer, Float> values = new HashMap<>();
            for (var entry : face.getValue().getAsJsonObject().getAsJsonObject("widths").entrySet())
                values.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsFloat());
            widths.put(face.getKey(), values);
        }
        for (var image : m.getAsJsonObject("images").entrySet())
            for (JsonElement value : image.getValue().getAsJsonObject().getAsJsonArray("tiles")) {
                JsonObject tile = value.getAsJsonObject();
                int code = tile.get("character").getAsString().codePointAt(0);
                assetIds.put(code, image.getKey());
                images.put(code, tile.get("width").getAsFloat());
                widths.computeIfAbsent("rafter:images", k -> new HashMap<>())
                        .put(code, tile.get("advance").getAsFloat());
            }
        for (JsonElement e :
                JsonParser.parseString(
                                Files.readString(pack.resolve("assets/rafter/font/images.json")))
                        .getAsJsonObject()
                        .getAsJsonArray("providers")) {
            JsonObject p = e.getAsJsonObject();
            int code = p.getAsJsonArray("chars").get(0).getAsString().codePointAt(0);
            String file = p.get("file").getAsString();
            textures.put(code, pack.resolve("assets/" + file.replace(":", "/textures/")));
        }
        JsonObject provider =
                JsonParser.parseString(
                                Files.readString(pack.resolve("assets/rafter/font/space.json")))
                        .getAsJsonObject()
                        .getAsJsonArray("providers")
                        .get(0)
                        .getAsJsonObject();
        for (var space : provider.getAsJsonObject("advances").entrySet())
            widths.computeIfAbsent("rafter:space", k -> new HashMap<>())
                    .put(space.getKey().codePointAt(0), space.getValue().getAsFloat());
        widths.put("rafter:fill", Map.of(0xe000, 10f, 0xe001, 9f));
    }

    static class Metrics implements Font.Provider {
        public GlyphSource glyphs(FontDescription description) {
            return new GlyphSource() {
                public BakedGlyph getGlyph(int code) {
                    if (nativeFonts != null) return nativeFonts.baked(description, code);
                    return new BakedGlyph() {
                        public GlyphInfo info() {
                            if (nativeFonts != null) return nativeFonts.info(description, code);
                            return new GlyphInfo() {
                                public float getAdvance() {
                                    return advance(description, code);
                                }

                                public float getBoldOffset() {
                                    return description instanceof FontDescription.Resource r
                                                    && Set.of("rafter:space", "rafter:fill")
                                                            .contains(r.id().toString())
                                            ? 0
                                            : 1;
                                }
                            };
                        }

                        public TextRenderable.Styled createGlyph(
                                float x,
                                float y,
                                int color,
                                int shadow,
                                Style style,
                                float bold,
                                float italic) {
                            throw new UnsupportedOperationException(
                                    "No GPU rendering in this probe");
                        }
                    };
                }

                public BakedGlyph getRandomGlyph(RandomSource random, int code) {
                    return getGlyph(code);
                }
            };
        }

        public EffectGlyph effect() {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args[0]);
        catalog(pack);
        nativeFonts = new LocalFontProviders(pack);
        Component message =
                component(JsonParser.parseString(Files.readString(Path.of(args[1]))), Style.EMPTY);
        int expectedWidth = Integer.parseInt(args[2]), expectedHeight = Integer.parseInt(args[3]);
        if (args.length > 4 && args[4].equals("first-line")) {
            MutableComponent firstLine = Component.empty();
            message.visit(
                    (Style style, String text) -> {
                        int newline = text.indexOf('\n');
                        String part = newline < 0 ? text : text.substring(0, newline);
                        if (!part.isEmpty())
                            firstLine.append(Component.literal(part).setStyle(style));
                        return newline >= 0 ? Optional.of(Unit.INSTANCE) : Optional.empty();
                    },
                    Style.EMPTY);
            message = firstLine;
            expectedHeight = 9;
        }
        int bodyWidth = args.length > 5 ? Integer.parseInt(args[5]) : expectedWidth;
        if (args.length > 4 && args[4].equals("minimal-image")) {
            int code = images.keySet().stream().sorted().findFirst().orElseThrow();
            float amount = widths.get("rafter:images").get(code);
            int negative =
                    widths.get("rafter:space").entrySet().stream()
                            .filter(v -> v.getValue() == -amount)
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElseThrow();
            Style imageStyle =
                    Style.EMPTY.withFont(
                            new FontDescription.Resource(Identifier.parse("rafter:images")));
            Style spaceStyle =
                    Style.EMPTY.withFont(
                            new FontDescription.Resource(Identifier.parse("rafter:space")));
            message =
                    Component.literal(Character.toString(code))
                            .setStyle(imageStyle)
                            .append(
                                    Component.literal(Character.toString(negative))
                                            .setStyle(spaceStyle));
            expectedWidth = images.get(code).intValue();
            expectedHeight = 9;
            bodyWidth = (int) amount + 9;
            System.out.println(
                    "MINIMAL: one packaged image glyph + one negative-advance glyph; no labels, no"
                        + " bitmap tiles, no click data");
        }
        Font font = new Font(new Metrics());
        var widget =
                FocusableTextWidget.builder(message, font)
                        .maxWidth(bodyWidth)
                        .alwaysShowBorder(false)
                        .backgroundFill(FocusableTextWidget.BackgroundFill.NEVER)
                        .build()
                        .setCentered(true);
        widget.setPosition(-widget.getWidth() / 2, 0);
        System.out.println("Focusable padding=" + ((FocusableTextWidget) widget).getPadding());
        int declaredLines = font.splitIgnoringLanguage(message, bodyWidth - 8).size();
        int explicitRows = message.getString().split("\\n", -1).length;
        System.out.printf(
                Locale.ROOT,
                "native-widget width=%d height=%d explicit-rows=%d expected-canvas=%dx%d%n",
                widget.getWidth(),
                widget.getHeight(),
                explicitRows,
                expectedWidth,
                expectedHeight);
        List<PaintProbe> paintProbes = new ArrayList<>();
        if (args.length > 6 && args[4].equals("full")) {
            JsonObject v =
                    JsonParser.parseString(Files.readString(Path.of(args[6]))).getAsJsonObject();
            if (v.has("paint-probes"))
                for (JsonElement e : v.getAsJsonArray("paint-probes"))
                    paintProbes.add(new PaintProbe(e.getAsJsonObject()));
        }
        final int fixedLineWidth = expectedWidth + 2;
        class Collector implements ActiveTextCollector {
            Parameters defaults;
            int rows = 0;
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            float first = Float.NaN;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;

            public void acceptScrolling(
                    Component c, int x, int y, int a, int b, int d, Parameters p) {
                throw new AssertionError("Unexpected scrolling text path");
            }

            public Parameters defaultParameters() {
                return defaults;
            }

            public void defaultParameters(Parameters value) {
                defaults = value;
            }

            public void accept(
                    TextAlignment align,
                    int x,
                    int y,
                    Parameters params,
                    FormattedCharSequence line) {
                float[] cursor = {align.calculateLeft(x, font, line)};
                int index = rows++;
                line.accept(
                        (i, style, code) -> {
                            if (args.length > 7 && !Objects.equals(style.getShadowColor(), 0))
                                throw new AssertionError("Rafter glyph inherited a client shadow");
                            String name =
                                    style.getFont() instanceof FontDescription.Resource r
                                            ? r.id().toString()
                                            : "minecraft:default";
                            if (name.equals("rafter:images") && images.containsKey(code)) {
                                if (Float.isNaN(first)) first = cursor[0];
                                min = Math.min(min, cursor[0]);
                                max = Math.max(max, cursor[0] + images.get(code));
                                var glyph =
                                        nativeFonts
                                                .baked(style.getFont(), code)
                                                .createGlyph(cursor[0], y, -1, 0, style, 1, 1);
                                minY = Math.min(minY, glyph.activeTop());
                                maxY = Math.max(maxY, glyph.activeBottom());
                                for (PaintProbe p : paintProbes) {
                                    float sx = first + p.x, sy = 4 + p.y;
                                    if (sx >= glyph.activeLeft()
                                            && sx < glyph.activeRight()
                                            && sy >= glyph.activeTop()
                                            && sy < glyph.activeBottom()) {
                                        var bitmap =
                                                bitmapCache.computeIfAbsent(
                                                        code,
                                                        k -> {
                                                            try {
                                                                return javax.imageio.ImageIO.read(
                                                                        textures.get(k).toFile());
                                                            } catch (Exception e) {
                                                                throw new RuntimeException(e);
                                                            }
                                                        });
                                        int px =
                                                (int)
                                                        ((sx - glyph.activeLeft())
                                                                / (glyph.activeRight()
                                                                        - glyph.activeLeft())
                                                                * bitmap.getWidth());
                                        int py =
                                                (int)
                                                        ((sy - glyph.activeTop())
                                                                / (glyph.activeBottom()
                                                                        - glyph.activeTop())
                                                                * bitmap.getHeight());
                                        if ((bitmap.getRGB(px, py) >>> 24) > 25)
                                            p.actual = assetIds.get(code);
                                    }
                                }
                            }
                            cursor[0] += advance(style.getFont(), code) + (style.isBold() ? 1 : 0);
                            return true;
                        });
                if (args[4].equals("full") && font.width(line) != fixedLineWidth)
                    throw new AssertionError("Non-uniform intrinsic row width at " + index);
                if (index < 6)
                    System.out.printf(
                            Locale.ROOT,
                            "row=%d native-width=%d centered-start=%d%n",
                            index,
                            font.width(line),
                            align.calculateLeft(x, font, line));
            }
        }
        Collector collector = new Collector();
        widget.visitLines(collector);
        for (PaintProbe p : paintProbes) {
            System.out.println(
                    "PAINT logical="
                            + p.x
                            + ","
                            + p.y
                            + " expected="
                            + p.expected
                            + " actual="
                            + p.actual);
            if (!p.expected.equals(p.actual))
                throw new AssertionError(
                        "Native row paint stream covers foreground with a later background bitmap");
        }
        System.out.printf(
                Locale.ROOT,
                "client-visible-lines=%d image-range=[%.3f,%.3f] first-image-x=%.3f"
                    + " expected-first-image-x=%.3f%n",
                collector.rows,
                collector.min,
                collector.max,
                collector.first,
                -expectedWidth / 2f);
        System.out.printf(
                Locale.ROOT,
                "actual-image-y-range=[%.3f,%.3f] expected=[%d,%d]%n",
                collector.minY,
                collector.maxY,
                ((FocusableTextWidget) widget).getPadding(),
                expectedHeight + ((FocusableTextWidget) widget).getPadding());
        if (args[4].equals("full")
                && (Math.abs(collector.minY - ((FocusableTextWidget) widget).getPadding()) > 0.0001f
                        || Math.abs(
                                        collector.maxY
                                                - expectedHeight
                                                - ((FocusableTextWidget) widget).getPadding())
                                > 0.0001f))
            throw new AssertionError("Bitmap geometry does not coincide with native click rows");
        System.out.println(
                "native-split-lines=" + declaredLines + " explicit-lines=" + explicitRows);
        boolean wraps = declaredLines != explicitRows;
        boolean heightMismatch =
                widget.getHeight()
                        != expectedHeight + ((FocusableTextWidget) widget).getPadding() * 2;
        boolean shifted = Math.abs(collector.first + expectedWidth / 2f) > 1.1f;
        System.out.println(
                "VERDICT unexpected-wrap="
                        + wraps
                        + " shifted-origin="
                        + shifted
                        + " wrong-widget-height="
                        + heightMismatch);
        if (args.length > 6 && args[4].equals("full")) {
            JsonObject view =
                    JsonParser.parseString(Files.readString(Path.of(args[6]))).getAsJsonObject();
            int checks = 0;
            int originX = Math.round(collector.first),
                    originY = ((FocusableTextWidget) widget).getPadding();
            for (JsonElement value : view.getAsJsonArray("hits")) {
                JsonObject hit = value.getAsJsonObject();
                int x = hit.get("x").getAsInt(),
                        y = hit.get("y").getAsInt(),
                        w = hit.get("width").getAsInt(),
                        h = hit.get("height").getAsInt();
                String wanted = "rafter:ui/" + hit.get("handle").getAsString();
                for (int yy = y; yy < y + h; yy += 9)
                    for (int offset : new int[] {1, 4, 8})
                        for (int xx : new int[] {x + 1, x + w / 2, x + w - 1}) {
                            var finder =
                                    new ActiveTextCollector.ClickableStyleFinder(
                                            font, originX + xx, originY + yy + offset);
                            widget.visitLines(finder);
                            Style style = finder.result();
                            String actual =
                                    style != null
                                                    && style.getClickEvent()
                                                            instanceof ClickEvent.Custom c
                                            ? c.id().toString()
                                            : null;
                            if (!wanted.equals(actual))
                                throw new AssertionError(
                                        "Native hit mismatch "
                                                + hit.get("id")
                                                + " at "
                                                + xx
                                                + ","
                                                + yy
                                                + ": wanted="
                                                + wanted
                                                + " actual="
                                                + actual);
                            checks++;
                        }
            }
            int outsideChecks = 0;
            for (JsonElement value : view.getAsJsonArray("hits")) {
                JsonObject h = value.getAsJsonObject();
                int x = h.get("x").getAsInt(),
                        y = h.get("y").getAsInt(),
                        w = h.get("width").getAsInt(),
                        height = h.get("height").getAsInt();
                for (int[] point :
                        new int[][] {
                            {x - 1, y + height / 2},
                            {x + w + 1, y + height / 2},
                            {x + w / 2, y - 1},
                            {x + w / 2, y + height + 1}
                        }) {
                    String wanted = null;
                    for (JsonElement other : view.getAsJsonArray("hits")) {
                        JsonObject b = other.getAsJsonObject();
                        int bx = b.get("x").getAsInt(), by = b.get("y").getAsInt();
                        if (point[0] >= bx
                                && point[0] < bx + b.get("width").getAsInt()
                                && point[1] >= by
                                && point[1] < by + b.get("height").getAsInt())
                            wanted = "rafter:ui/" + b.get("handle").getAsString();
                    }
                    var finder =
                            new ActiveTextCollector.ClickableStyleFinder(
                                    font, originX + point[0], originY + point[1]);
                    widget.visitLines(finder);
                    Style style = finder.result();
                    String actual =
                            style != null && style.getClickEvent() instanceof ClickEvent.Custom c
                                    ? c.id().toString()
                                    : null;
                    if (!Objects.equals(wanted, actual))
                        throw new AssertionError(
                                "Native outside hit mismatch at "
                                        + Arrays.toString(point)
                                        + ": wanted="
                                        + wanted
                                        + " actual="
                                        + actual);
                    outsideChecks++;
                }
            }
            System.out.println(
                    "PASS: actual vanilla ClickableStyleFinder matched "
                            + checks
                            + " interior edge/center/row samples and "
                            + outsideChecks
                            + " exterior/adjacent-control samples to the exported geometry");
        }
        if (args.length > 7 && args[4].equals("full")) {
            int viewport = Integer.parseInt(args[7]);
            JsonObject view =
                    JsonParser.parseString(Files.readString(Path.of(args[6]))).getAsJsonObject();
            var content = net.minecraft.client.gui.layouts.LinearLayout.vertical();
            content.addChild(widget);
            var scroll = new ScrollableLayout(null, content, viewport);
            scroll.arrangeElements();
            scroll.setX(0);
            scroll.setY(0);
            java.util.List<AbstractScrollArea> areas = new ArrayList<>();
            scroll.visitChildren(
                    e -> {
                        if (e instanceof AbstractScrollArea area) areas.add(area);
                    });
            AbstractScrollArea area = areas.get(0);
            if (area.maxScrollAmount() <= 0 || scroll.getHeight() != viewport)
                throw new AssertionError(
                        "Long body did not create a genuine native scroll viewport");
            double before = area.scrollAmount();
            area.mouseScrolled(10, 10, 0, -1);
            if (area.scrollAmount() <= before || widget.getY() != -(int) area.scrollAmount())
                throw new AssertionError("Native mouse wheel did not move the child content");
            int checks = 0;
            for (JsonElement value : view.getAsJsonArray("hits")) {
                JsonObject hit = value.getAsJsonObject();
                int x = hit.get("x").getAsInt(),
                        y = hit.get("y").getAsInt(),
                        w = hit.get("width").getAsInt(),
                        h = hit.get("height").getAsInt();
                area.setScrollAmount(y + h / 2 + 4 - viewport / 2);
                int originX = widget.getX() + widget.getWidth() / 2 - fixedLineWidth / 2 + 1,
                        originY = widget.getY() + 4;
                int screenY = originY + y + h / 2;
                if (screenY < 0 || screenY >= viewport)
                    throw new AssertionError(
                            "Expected control not visible after native scroll clamp");
                var finder =
                        new ActiveTextCollector.ClickableStyleFinder(
                                font, originX + x + w / 2, screenY);
                widget.visitLines(finder);
                Style style = finder.result();
                String actual =
                        style != null && style.getClickEvent() instanceof ClickEvent.Custom c
                                ? c.id().toString()
                                : null;
                if (!("rafter:ui/" + hit.get("handle").getAsString()).equals(actual))
                    throw new AssertionError("Scrolled control hit mismatch " + hit.get("id"));
                checks++;
            }
            area.setScrollAmount(Double.MAX_VALUE);
            if (area.scrollAmount() != area.maxScrollAmount())
                throw new AssertionError("Native bottom clamp failed");
            double bottom = area.scrollAmount();
            area.mouseScrolled(10, 10, 0, 1);
            if (area.scrollAmount() >= bottom)
                throw new AssertionError("Native upward wheel failed");
            System.out.println(
                    "PASS: actual vanilla ScrollableLayout viewport="
                            + viewport
                            + " content="
                            + content.getHeight()
                            + " max-scroll="
                            + area.maxScrollAmount()
                            + "; wheel/bottom clamp/"
                            + checks
                            + " scrolled control hits verified; no Rafter text shadow");
        }
        nativeFonts.close();
        if (wraps || shifted || heightMismatch)
            throw new AssertionError(
                    "Native client replay reproduces wrapping/shifted canvas origin");
    }
}
