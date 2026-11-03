package com.hiusers.rafter.example;

import com.hiusers.rafter.api.ActionProvider;
import com.hiusers.rafter.api.ConditionProvider;
import com.hiusers.rafter.api.DataProvider;
import com.hiusers.rafter.api.ItemProvider;
import com.hiusers.rafter.api.OpenResult;
import com.hiusers.rafter.api.ParameterSpec;
import com.hiusers.rafter.api.ParameterType;
import com.hiusers.rafter.api.ProviderDescriptor;
import com.hiusers.rafter.api.RafterService;
import com.hiusers.rafter.api.UiActionResult;
import com.hiusers.rafter.api.UiContext;
import com.hiusers.rafter.api.UiFailureCode;
import com.hiusers.rafter.api.UiOpenOptions;
import com.hiusers.rafter.api.UiOperationException;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/** 在主线程 onEnable 中创建、onDisable 中关闭；示例不参与插件装配。 */
public final class ApiConsumer implements AutoCloseable {
    private final Plugin owner;
    private final RafterService service;
    private final List<AutoCloseable> registrations = new ArrayList<>();

    public ApiConsumer(Plugin owner) {
        this.owner = owner;
        service = owner.getServer().getServicesManager().load(RafterService.class);
        if (service == null) {
            throw new IllegalStateException("Rafter service is unavailable");
        }

        // 回调入口在主线程，可读取玩家状态。
        DataProvider playerName =
                (context, args) -> CompletableFuture.completedFuture(context.getPlayer().getName());
        ConditionProvider mayUse =
                (context, args) ->
                        CompletableFuture.completedFuture(
                                context.getPlayer().hasPermission("api-consumer.use"));
        ActionProvider greet =
                (context, args) -> {
                    // 参数已校验并补齐缺失默认值；显式 null 不替换。
                    String greeting = (String) args.get("greeting");
                    String prefix = (String) args.get("prefix");
                    int times = ((Number) args.get("times")).intValue();
                    // 目标菜单声明 status 字符串，由现有文本绑定显示。
                    String status = ((prefix == null ? "" : prefix) + greeting).repeat(times);
                    return CompletableFuture.completedFuture(
                            new UiActionResult(Map.of("status", status)));
                };
        ItemProvider icons =
                (context, id) -> {
                    if (!"paper".equals(id)) {
                        return CompletableFuture.failedFuture(
                                new UiOperationException(
                                        UiFailureCode.INVALID_ARGUMENT, "Unknown item: " + id));
                    }
                    return CompletableFuture.completedFuture(new ItemStack(Material.PAPER));
                };

        try {
            // Java 使用完整构造器，不依赖 Kotlin 合成方法。
            registrations.add(
                    service.registerData(
                            owner,
                            "api-consumer:player-name",
                            new ProviderDescriptor("Player name", Collections.emptyMap()),
                            playerName));
            registrations.add(
                    service.registerCondition(
                            owner,
                            "api-consumer:may-use",
                            new ProviderDescriptor("Permission check", Collections.emptyMap()),
                            mayUse));
            registrations.add(
                    service.registerAction(
                            owner,
                            "api-consumer:greet",
                            new ProviderDescriptor(
                                    "Send a greeting",
                                    Map.of(
                                            "greeting",
                                                    new ParameterSpec(
                                                            ParameterType.STRING,
                                                            false,
                                                            false,
                                                            true,
                                                            "Hello",
                                                            List.of("Hello", "Welcome"),
                                                            null,
                                                            null,
                                                            "Greeting text"),
                                            "times",
                                                    new ParameterSpec(
                                                            ParameterType.INTEGER,
                                                            false,
                                                            false,
                                                            true,
                                                            1,
                                                            null,
                                                            1,
                                                            3,
                                                            "Repeat count, inclusive range 1..3"),
                                            // 显式 null 默认值与未设置默认值不同。
                                            "prefix",
                                                    new ParameterSpec(
                                                            ParameterType.STRING,
                                                            false,
                                                            true,
                                                            true,
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            "Optional literal prefix"))),
                            greet));
            registrations.add(
                    service.registerItems(
                            owner,
                            "api-consumer:icons",
                            new ProviderDescriptor("Paper icon", Collections.emptyMap()),
                            icons));
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public CompletableFuture<OpenResult> open(Player player, String menu) {
        return service.open(player, menu, Collections.emptyMap())
                .whenComplete(
                        (result, error) -> {
                            if (error != null) {
                                logFailure(error);
                            } else if (!result.getOpened()) {
                                // 失败读取枚举，不解析异常文本。
                                owner.getLogger()
                                        .warning("Rafter open rejected: " + result.getFailure());
                            }
                        });
    }

    /** 预填 title、notes 并直接打开 edit 表单；目标菜单须声明对应状态和页面。 */
    public CompletableFuture<OpenResult> openWithInitialState(Player player, String menu) {
        UiOpenOptions options =
                new UiOpenOptions(
                        Map.of(
                                "title", "季度计划",
                                "notes", "第一行\n第二行\n第三行"),
                        "edit");
        return service.open(player, menu, Collections.emptyMap(), options)
                .whenComplete(
                        (result, error) -> {
                            if (error != null) {
                                logFailure(error);
                            } else if (!result.getOpened()) {
                                owner.getLogger()
                                        .warning("Rafter open rejected: " + result.getFailure());
                            }
                        });
    }

    public CompletableFuture<Void> refresh(Player player, UUID expectedSessionId) {
        return service.refresh(player, expectedSessionId)
                .whenComplete(
                        (ignored, error) -> {
                            if (error != null) {
                                logFailure(error);
                            }
                        });
    }

    // 完成回调不保证在主线程，此处仅记日志。
    // whenComplete 保留原失败状态。
    private void logFailure(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        UiFailureCode code =
                failure instanceof UiOperationException operation
                        ? operation.getCode()
                        : UiFailureCode.INTERNAL_ERROR;
        owner.getLogger().log(Level.WARNING, "Rafter operation failed: " + code, failure);
    }

    public CompletableFuture<Void> close(Player player, UUID expectedSessionId) {
        return service.close(player, expectedSessionId);
    }

    /** 无状态增量的动作也返回明确结果，不返回 null。 */
    public static UiActionResult emptyActionResult() {
        return new UiActionResult();
    }

    public Map<String, ?> contract() {
        return service.contract();
    }

    /** 复制上下文字段，不深拷贝，也不创建会话。 */
    public static UiContext copyContext(UiContext context) {
        return new UiContext(
                context.getPlayer(),
                context.getSessionId(),
                context.getMenu(),
                context.getPage(),
                context.getArgs(),
                context.getState(),
                context.getForm(),
                context.getItem());
    }

    /** 复制参数声明，不取得注册时的不可变快照。 */
    public static ParameterSpec copyParameter(ParameterSpec spec) {
        return new ParameterSpec(
                spec.getType(),
                spec.getRequired(),
                spec.getNullable(),
                spec.getHasDefault(),
                spec.getDefaultValue(),
                spec.getChoices(),
                spec.getMinimum(),
                spec.getMaximum(),
                spec.getDescription());
    }

    /** 使用完整公开构造器复制结果。 */
    public static OpenResult copyResult(OpenResult result) {
        return new OpenResult(result.getOpened(), result.getFailure(), result.getSessionId());
    }

    /** 验证 Java 两参数重载，不创建会话。 */
    public static OpenResult rejectedResult(UiFailureCode code) {
        return new OpenResult(false, code);
    }

    /** 复制异常；message、cause 仅供诊断。 */
    public static UiOperationException copyFailure(UiOperationException failure) {
        return new UiOperationException(
                failure.getCode(), failure.getMessage(), failure.getCause());
    }

    /** 本例在主线程串行关闭，不保证列表并发安全。注册句柄可跨线程关闭，清理在主线程执行且仅影响原注册。 */
    @Override
    public void close() {
        for (AutoCloseable registration : registrations) {
            try {
                registration.close();
            } catch (Exception failure) {
                owner.getLogger()
                        .log(Level.WARNING, "Unable to unregister Rafter provider", failure);
            }
        }
        registrations.clear();
    }
}
