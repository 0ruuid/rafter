package com.hiusers.rafter.example;

import com.hiusers.rafter.api.OpenResult;
import com.hiusers.rafter.api.ParameterSpec;
import com.hiusers.rafter.api.ParameterType;
import com.hiusers.rafter.api.ProviderDescriptor;
import com.hiusers.rafter.api.RafterService;
import com.hiusers.rafter.api.UiActionResult;
import com.hiusers.rafter.api.UiFailureCode;
import com.hiusers.rafter.api.UiOpenOptions;
import com.hiusers.rafter.api.UiOperationException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

/** 覆盖 Java 简便入口，不执行界面或业务。 */
public final class ApiValueConsumer {
    public static UiActionResult empty() {
        return new UiActionResult();
    }

    public static ParameterSpec required() {
        return ParameterSpec.of(ParameterType.STRING);
    }

    public static ParameterSpec optional() {
        return ParameterSpec.optional(ParameterType.STRING);
    }

    public static ProviderDescriptor descriptor() {
        return new ProviderDescriptor("Example");
    }

    public static OpenResult result(UUID session) {
        return OpenResult.of(true, null, session);
    }

    public static UiOperationException failure(Throwable cause) {
        return UiOperationException.of(UiFailureCode.INVALID_ARGUMENT, "Example", cause);
    }

    public static RafterService service() {
        return RafterService.get();
    }

    public static CompletableFuture<OpenResult> open(RafterService service, Player player) {
        return service.open(player, "example:menu");
    }

    public static CompletableFuture<OpenResult> form(RafterService service, Player player) {
        return service.open(player, "example:menu",
                new UiOpenOptions(Map.of("name", "Example"), "edit"));
    }
}
