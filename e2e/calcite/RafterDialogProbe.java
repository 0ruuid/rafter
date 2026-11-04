import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import calcite.probe.Ref;
import calcite.probe.api.Calcite;
import calcite.probe.api.CalciteException;
import calcite.probe.api.CalciteExtension;

/** 读取原生控件并转发输入，不修改控件布局或渲染器。 */
public final class RafterDialogProbe implements CalciteExtension {

    private Calcite calcite;
    private Ref ref;

    @Override
    public String id() {
        return "rafterui";
    }

    @Override
    public void init(final Calcite c) {
        calcite = c;
        ref = c.ref();
        c.command("state", args -> onGame(() -> state()));
        c.command("resource", args -> onGame(() -> resource(args)));
        c.command("click", args -> onGame(() -> click(args)));
        c.command("hover", args -> onGame(() -> hover(args)));
        c.command("scroll", args -> onGame(() -> scroll(args)));
        c.command("close", args -> onGame(() -> close()));
        c.command("reload", args -> onGame(() -> reload()));
        c.command("setValue", args -> onGame(() -> setValue(args)));
    }

    private Object onGame(final Callable<Object> task) throws Exception {
        if (calcite.minecraft() == null) {
            throw new CalciteException("not_ready", "Minecraft is not ready");
        }
        return calcite.onGameThread(task, 5000L);
    }

    private Object screen() throws Exception {
        Object gui = ref.get(calcite.minecraft(), "gui");
        return gui == null ? null : ref.get(gui, "screen");
    }

    private Object resource(final Map<String, Object> args) throws Exception {
        String path = String.valueOf(args.get("path"));
        if (!path.equals("minecraft:shaders/core/position_tex_color.vsh")) {
            throw new CalciteException("bad_request", "Only the item shader may be inspected");
        }
        Object id = ref.cls("net.minecraft.resources.Identifier")
                .getMethod("parse", String.class).invoke(null, path);
        Object manager = ref.call(calcite.minecraft(), "getResourceManager");
        Optional<?> found = (Optional<?>) ref.call(manager, "getResource", id);
        if (!found.isPresent()) throw new CalciteException("missing_resource", path);
        Object source = found.get();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = (InputStream) ref.call(source, "open")) {
            byte[] block = new byte[4096];
            int length;
            while ((length = stream.read(block)) >= 0) {
                if (bytes.size() + length > 262144) throw new CalciteException("too_large", path);
                bytes.write(block, 0, length);
            }
        }
        String text = new String(bytes.toByteArray(), "UTF-8");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("pack", ref.call(source, "sourcePackId"));
        result.put("bytes", bytes.size());
        result.put("compiledRoutes", text.contains("vec2 source0") || text.contains("vec2 source1"));
        result.put("cornerPhaseIndependent", !text.contains("gl_VertexID"));
        return result;
    }

    private Object requireScreen() throws Exception {
        Object current = screen();
        if (current == null) {
            throw new CalciteException("no_screen", "No screen is open");
        }
        return current;
    }

    private List<?> children(final Object parent) throws Exception {
        if (parent == null || !ref.has(parent, "children", 0)) {
            return Collections.emptyList();
        }
        Object value = ref.call(parent, "children");
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    private Map<String, Object> state() throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object window = ref.call(calcite.minecraft(), "getWindow");
        result.put("guiScaledWidth", ref.call(window, "getGuiScaledWidth"));
        result.put("guiScaledHeight", ref.call(window, "getGuiScaledHeight"));

        Object current = screen();
        Object gui = ref.get(calcite.minecraft(), "gui");
        Object overlay = ref.get(gui, "overlay");
        result.put("overlay", overlay == null ? null : overlay.getClass().getName());
        result.put("screen", current == null ? null : current.getClass().getName());
        result.put("title", current == null ? null : text(ref.call(current, "getTitle")));

        List<Object> widgets = new ArrayList<Object>();
        if (current != null) {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            collect(current, "", widgets, seen, 0);
        }
        result.put("widgets", widgets);
        return result;
    }

    private void collect(
            final Object parent,
            final String prefix,
            final List<Object> output,
            final Set<Object> seen,
            final int depth)
            throws Exception {
        if (parent == null || depth > 32 || !seen.add(parent)) {
            return;
        }
        List<?> list = children(parent);
        for (int i = 0; i < list.size(); i++) {
            Object widget = list.get(i);
            if (widget == null) {
                continue;
            }
            String path = prefix.length() == 0 ? String.valueOf(i) : prefix + "." + i;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("path", path);
            item.put("type", widget.getClass().getName());
            item.put("x", read(widget, "getX"));
            item.put("y", read(widget, "getY"));
            item.put("width", read(widget, "getWidth"));
            item.put("height", read(widget, "getHeight"));
            item.put("message", message(widget));
            if (ref.has(widget, "isHovered", 0)) {
                item.put("hovered", ref.call(widget, "isHovered"));
            }
            addItemStack(widget, item);
            output.add(item);
            collect(widget, path, output, seen, depth + 1);
        }
    }

    private Object read(final Object target, final String method) throws Exception {
        return ref.has(target, method, 0) ? ref.call(target, method) : null;
    }

    private String message(final Object widget) throws Exception {
        Object component = read(widget, "getMessage");
        return text(component);
    }

    private String text(final Object component) throws Exception {
        if (component == null) {
            return null;
        }
        return ref.has(component, "getString", 0) ? String.valueOf(ref.call(component, "getString")) : String.valueOf(component);
    }

    private void addItemStack(final Object widget, final Map<String, Object> output) throws Exception {
        Class<?> itemDisplay = ref.cls("net.minecraft.client.gui.components.ItemDisplayWidget");
        if (itemDisplay == null || !itemDisplay.isInstance(widget)) {
            return;
        }
        Object stack = ref.get(widget, "itemStack");
        if (stack == null || !ref.has(stack, "getItem", 0) || !ref.has(stack, "getCount", 0)) {
            throw new CalciteException("unsupported", "ItemDisplayWidget.itemStack is not readable in this version");
        }
        Object item = ref.call(stack, "getItem");
        output.put("itemType", itemType(item));
        output.put("count", ((Number) ref.call(stack, "getCount")).intValue());
        Object itemModel = ref.getStatic(ref.cls("net.minecraft.core.component.DataComponents"), "ITEM_MODEL");
        Object model = ref.call(stack, "get", itemModel);
        output.put("itemModel", model == null ? null : String.valueOf(model));
        output.put("itemOffsetX", ref.get(widget, "offsetX"));
        output.put("itemOffsetY", ref.get(widget, "offsetY"));
        output.put("tooltipEnabled", ref.get(widget, "tooltip"));
        output.put("foil", ref.call(stack, "hasFoil"));
        output.put("itemName", text(ref.call(stack, "getHoverName")));
        output.put("damage", ref.call(stack, "getDamageValue"));
    }

    private String itemType(final Object item) throws Exception {
        if (item == null) {
            return null;
        }
        Class<?> registries = ref.cls("net.minecraft.core.registries.BuiltInRegistries");
        if (registries != null) {
            Object itemRegistry = ref.getStatic(registries, "ITEM");
            Object key = ref.call(itemRegistry, "getKey", item);
            if (key != null) {
                return String.valueOf(key);
            }
        }
        return item.getClass().getName();
    }

    private Object click(final Map<String, Object> args) throws Exception {
        requireScreen();
        hover(args);
        Object mouse = ref.get(calcite.minecraft(), "mouseHandler");
        Object window = ref.call(calcite.minecraft(), "getWindow");
        int button = integer(args, "button", 0);
        int modifiers = integer(args, "modifiers", 0);
        Object info = ref.construct(
                "net.minecraft.client.input.MouseButtonInfo", Integer.valueOf(button), Integer.valueOf(modifiers));
        long handle = ((Number) ref.call(window, "handle")).longValue();
        ref.call(mouse, "onButton", Long.valueOf(handle), info, Integer.valueOf(1));
        ref.call(mouse, "onButton", Long.valueOf(handle), info, Integer.valueOf(0));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("dispatched", true);
        return result;
    }

    /** 使用真实鼠标回调，不写入控件的悬停标志。 */
    private Object hover(final Map<String, Object> args) throws Exception {
        number(args, "x");
        number(args, "y");
        Object mouse = ref.get(calcite.minecraft(), "mouseHandler");
        Object window = ref.call(calcite.minecraft(), "getWindow");
        if (mouse == null || window == null) {
            throw new CalciteException("unsupported", "Native client mouse position entry is unavailable");
        }
        Method onMove = ref.method(mouse.getClass(), "onMove", 3, "long", "double", "double");
        if (onMove == null) {
            throw new CalciteException("unsupported", "MouseHandler.onMove(long,double,double) is unavailable");
        }
        int guiWidth = ((Number) ref.call(window, "getGuiScaledWidth")).intValue();
        int guiHeight = ((Number) ref.call(window, "getGuiScaledHeight")).intValue();
        int windowWidth = ((Number) ref.call(window, "getScreenWidth")).intValue();
        int windowHeight = ((Number) ref.call(window, "getScreenHeight")).intValue();
        if (guiWidth <= 0 || guiHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) {
            throw new CalciteException("unsupported", "Window dimensions are unavailable for native mouse input");
        }
        double guiX = number(args, "x");
        double guiY = number(args, "y");
        double rawX = guiX * windowWidth / guiWidth;
        double rawY = guiY * windowHeight / guiHeight;
        long handle = ((Number) ref.call(window, "handle")).longValue();
        onMove.invoke(mouse, Long.valueOf(handle), Double.valueOf(rawX), Double.valueOf(rawY));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("x", guiX);
        result.put("y", guiY);
        result.put("rawX", rawX);
        result.put("rawY", rawY);
        return result;
    }

    private Object scroll(final Map<String, Object> args) throws Exception {
        Object current = requireScreen();
        return ref.call(current, "mouseScrolled", number(args, "x"), number(args, "y"), 0.0, number(args, "delta"));
    }

    private Object close() throws Exception {
        Object current = screen();
        if (current != null) {
            ref.call(current, "onClose");
        }
        return true;
    }

    private Object reload() throws Exception {
        ref.call(calcite.minecraft(), "reloadResourcePacks");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ack", true);
        return result;
    }

    private Object setValue(final Map<String, Object> args) throws Exception {
        Object widget = locate(String.valueOf(args.get("path")));
        Class<?> editBox = ref.cls("net.minecraft.client.gui.components.EditBox");
        if (editBox == null || !editBox.isInstance(widget)) {
            throw new CalciteException("bad_request", "Only a native EditBox may receive setValue");
        }
        ref.call(widget, "setValue", String.valueOf(args.get("value")));
        return ref.call(widget, "getValue");
    }

    private Object locate(final String path) throws Exception {
        if (path == null || path.length() == 0) {
            throw new CalciteException("bad_request", "Widget path is required");
        }
        Object current = requireScreen();
        String[] indexes = path.split("\\.");
        for (String index : indexes) {
            final int position;
            try {
                position = Integer.parseInt(index);
            } catch (NumberFormatException e) {
                throw new CalciteException("bad_request", "Invalid widget path: " + path);
            }
            List<?> list = children(current);
            if (position < 0 || position >= list.size()) {
                throw new CalciteException("bad_request", "Widget path does not exist: " + path);
            }
            current = list.get(position);
        }
        return current;
    }

    private double number(final Map<String, Object> args, final String key) {
        Object value = args.get(key);
        if (!(value instanceof Number)) {
            throw new CalciteException("bad_request", "Missing numeric argument: " + key);
        }
        return ((Number) value).doubleValue();
    }

    private int integer(final Map<String, Object> args, final String key, final int fallback) {
        Object value = args.get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
}
