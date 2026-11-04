import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.hiusers.rafter.api.ItemProvider;
import com.hiusers.rafter.api.ProviderDescriptor;
import com.hiusers.rafter.api.RafterService;
import com.hiusers.rafter.api.UiFailureCode;
import com.hiusers.rafter.api.UiActionResult;
import com.hiusers.rafter.api.UiOpenOptions;
import com.hiusers.rafter.api.UiOperationException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
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
        // 独立协议原型的测试回执；不处理 Rafter 会话或真实业务。
        PacketListenerAbstract flowListener = new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return;
                String id = new WrapperPlayClientCustomClickAction(event).getId().toString();
                if (!id.matches("rafterprobe:flow/(?:[0-9]|1[0-7])")) return;
                event.setCancelled(true);
                int index = Integer.parseInt(id.substring(id.lastIndexOf('/') + 1));
                java.util.UUID playerId = event.getUser().getUUID();
                getServer().getScheduler().runTask(ItemProbePlugin.this, () -> {
                    org.bukkit.entity.Player player = getServer().getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        player.sendMessage("FLOW_ITEM_CLICK row-" + index);
                        getLogger().info("FLOW_ITEM_CLICK row-" + index);
                    }
                });
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(flowListener);
        registrations.add(() -> PacketEvents.getAPI().getEventManager().unregisterListener(flowListener));
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
                    (context, args) -> CompletableFuture.completedFuture(
                            Boolean.TRUE.equals(context.getState().get("empty")) ? List.of() : rows)));
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

        if (getCommand("itemprobe") == null) throw new IllegalStateException("Missing itemprobe command");
        getCommand("itemprobe").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) return false;
            String mode = args.length == 0 ? "form" : args[0];
            String page = mode.equals("empty") ? "grid" :
                    (mode.equals("occlusion") || mode.equals("regions") ? mode : "form");
            service.open(player, "rafterprobe:item-viewport", Collections.emptyMap(),
                    new UiOpenOptions(Map.of("query", "Initial name", "message", "First line\nSecond line",
                            "empty", mode.equals("empty")), page))
                    .thenAccept(result -> player.sendMessage("ITEM_PROBE_OPEN " + result.getOpened()));
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
}
