import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/** 使用原版排序算法核对合成边界，不启动游戏或声称完成 GPU 验收。 */
public final class GuiLayerProbe {
    private static GuiElementRenderState area(ScreenRectangle bounds) {
        return (GuiElementRenderState) Proxy.newProxyInstance(
                GuiLayerProbe.class.getClassLoader(), new Class<?>[] {GuiElementRenderState.class},
                (proxy, method, arguments) -> method.getName().equals("bounds") ? bounds : null);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static int layer(GuiRenderState state) throws ReflectiveOperationException {
        Object node = field(state, "current");
        int depth = 0;
        while ((node = field(node, "parent")) != null) depth++;
        return depth;
    }

    private static List<Integer> items(boolean anchored) throws ReflectiveOperationException {
        GuiRenderState state = new GuiRenderState();
        state.addGuiElement(area(anchored
                ? new ScreenRectangle(-108000, 13, 110353, 4096)
                : new ScreenRectangle(-108000, 269, 76000, 12)));
        List<Integer> layers = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            state.addGuiElement(area(new ScreenRectangle(313, 59 + row * 11, 16, 16)));
            layers.add(layer(state));
        }
        return layers;
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        List<Integer> before = items(false);
        List<Integer> after = items(true);
        if (before.getFirst() != 0 || after.stream().anyMatch(layer -> layer <= 0)) {
            throw new AssertionError("Unexpected native ordering: " + before + " -> " + after);
        }
        System.out.println("PASS native CPU ordering: " + before + " -> " + after);
        System.out.println("Synthetic bounds only; not a GPU/client screenshot acceptance.");
    }
}
