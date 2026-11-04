import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.body.ItemDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import com.hiusers.rafter.api.ItemProvider;
import com.hiusers.rafter.api.ProviderDescriptor;
import com.hiusers.rafter.api.RafterService;
import com.hiusers.rafter.api.UiFailureCode;
import com.hiusers.rafter.api.UiActionResult;
import com.hiusers.rafter.api.UiOperationException;
import com.hiusers.rafter.transport.DialogItemData;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** 为隔离测试提供真实物品，不实现业务逻辑。 */
public final class ItemProbePlugin extends JavaPlugin {
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private final Map<String, ItemStack> templates = new HashMap<>();

    @Override
    public void onEnable() {
        RafterService service = getServer().getServicesManager().load(RafterService.class);
        if (service == null) {
            throw new IllegalStateException("RafterService is unavailable");
        }

        templates.put("diamond", new ItemStack(Material.DIAMOND, 32));
        templates.put("block", new ItemStack(Material.OAK_PLANKS, 7));

        ItemStack tool = new ItemStack(Material.DIAMOND_SWORD);
        tool.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        ItemMeta toolMeta = tool.getItemMeta();
        ((Damageable) toolMeta).setDamage(7);
        tool.setItemMeta(toolMeta);
        templates.put("tool", tool);

        ItemStack custom = new ItemStack(Material.PAPER, 3);
        ItemMeta itemMeta = custom.getItemMeta();
        if (itemMeta == null) {
            throw new IllegalStateException("Bukkit returned no ItemMeta for the custom probe item");
        }
        setItemModel(itemMeta);
        itemMeta.setDisplayName("Model sample");
        itemMeta.setLore(List.of("Full component snapshot", "Isolated rendering probe"));
        custom.setItemMeta(itemMeta);
        templates.put("custom", custom);

        ItemProvider provider =
                (context, id) -> {
                    String key = id.equals("row") && context.getItem() != null
                            ? String.valueOf(context.getItem().get("kind")) : id;
                    ItemStack template = templates.get(key);
                    if (template == null) {
                        return CompletableFuture.failedFuture(
                                new UiOperationException(
                                        UiFailureCode.INVALID_ARGUMENT,
                                        "Unknown item probe id: " + id));
                    }
                    return CompletableFuture.completedFuture(template.clone());
                };

        registrations.add(
                service.registerItems(
                        this,
                        "rafterprobe:items",
                        new ProviderDescriptor("Real ItemStack probe", Collections.emptyMap()),
                        provider));

        for (String source : List.of("rows", "left", "right")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            String[] kinds = {"diamond", "block", "tool", "custom"};
            int count = source.equals("rows") ? 24 : 12;
            for (int i = 0; i < count; i++) {
                rows.add(Map.of("id", source + "-" + i, "kind", kinds[i % kinds.length],
                        "name", source + " item " + i));
            }
            registrations.add(service.registerData(this, "rafterprobe:" + source,
                    new ProviderDescriptor("Isolated item rows", Collections.emptyMap()),
                    (context, args) -> CompletableFuture.completedFuture(rows)));
        }
        registrations.add(service.registerAction(this, "rafterprobe:click",
                new ProviderDescriptor("Isolated row click", Collections.emptyMap()),
                (context, args) -> {
                    if (context.getItem() == null) throw new IllegalStateException("Missing row context");
                    String id = String.valueOf(context.getItem().get("id"));
                    getLogger().info("[CANVAS_ITEM_CLICK] " + id);
                    context.getPlayer().sendMessage("CANVAS_ITEM_CLICK " + id);
                    return CompletableFuture.completedFuture(new UiActionResult(
                            Map.of("last-click", id, "hint", "clicked: " + id)));
                }));

        getCommand("rafteritemprobe").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) return false;
            String action = args.length == 0 ? "overlay" : args[0];
            if (action.equals("clicked")) {
                getLogger().info("[ITEM_PROBE_CLICK] " + player.getName());
                player.sendMessage("ITEM_PROBE_CLICK");
            } else if (action.equals("close")) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerClearDialog());
            } else if (action.equals("overlay")) {
                showOverlay(player);
            } else return false;
            return true;
        });
    }

    @Override
    public void onDisable() {
        for (AutoCloseable registration : registrations) {
            try {
                registration.close();
            } catch (Exception failure) {
                getLogger().log(Level.WARNING, "Failed to close Rafter item probe registration", failure);
            }
        }
        registrations.clear();
        templates.clear();
    }

    private static void setItemModel(ItemMeta itemMeta) {
        final Method setItemModel;
        try {
            setItemModel = ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                    "Bukkit API does not expose ItemMeta.setItemModel(NamespacedKey); "
                            + "cannot create the rafterprobe:custom item",
                    failure);
        }

        try {
            setItemModel.invoke(itemMeta, new NamespacedKey("rafterprobe", "custom"));
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(
                    "Failed to set ItemMeta item model rafterprobe:custom", failure);
        }
    }

    /** 协议级实验：视觉物品和独立文字命中层，不修改客户端控件几何。 */
    private void showOverlay(Player player) {
        ItemStack stack = templates.get("custom").clone();
        NBTCompound snapshot = DialogItemData.INSTANCE.capture(stack);
        var body = new ItemDialogBody(
                SpigotConversionUtil.fromBukkitItemStack(stack),
                new PlainMessage(Component.empty(), 300), true, false, 32, 32);
        var common = new CommonDialogData(
                Component.text("Item interaction probe"), null, true, false, DialogAction.NONE,
                List.of(body), List.of());
        var close = new ActionButton(new CommonButtonData(Component.text("Close"), null, 150), null);
        var dialog = new MultiActionDialog(common, List.of(close), null, 1);
        var packet = new WrapperPlayServerShowDialog(dialog) {
            @Override
            public void write() {
                writeMappedEntityOrDirect(getDialog(), (wrapper, value) -> {
                    NBTCompound data = (NBTCompound) Dialog.encodeDirect(value, wrapper);
                    NBTCompound encodedBody = data.getCompoundTagOrThrow("body");
                    encodedBody.setTag("item", snapshot.copy());
                    encodedBody.getCompoundTagOrThrow("description").setTag("contents", overlayText(snapshot));
                    wrapper.writeNBTRaw(data);
                });
            }
        };
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private static NBTCompound overlayText(NBTCompound item) {
        NBTCompound root = text("");
        NBTList<NBTCompound> lines = NBTList.createCompoundList();
        for (int row = 0; row < 8; row++) {
            if (row > 0) lines.addTag(text("\n"));
            NBTCompound padding = text("\ue001");
            padding.setTag("font", new NBTString("rafterprobe:hit"));
            lines.addTag(padding);
            NBTCompound hit = text("\ue002");
            hit.setTag("font", new NBTString("rafterprobe:hit"));
            NBTCompound hover = item.copy();
            hover.setTag("action", new NBTString("show_item"));
            hit.setTag("hover_event", hover);
            NBTCompound click = new NBTCompound();
            click.setTag("action", new NBTString("run_command"));
            click.setTag("command", new NBTString("/rafteritemprobe clicked"));
            hit.setTag("click_event", click);
            lines.addTag(hit);
        }
        root.setTag("extra", lines);
        return root;
    }

    private static NBTCompound text(String value) {
        NBTCompound text = new NBTCompound();
        text.setTag("text", new NBTString(value));
        return text;
    }
}
