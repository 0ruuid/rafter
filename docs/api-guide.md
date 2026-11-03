# API

业务插件通过 `com.hiusers.rafter.api` 接入。

## 接入

```kotlin
repositories {
    maven("https://repo.hiusers.com/releases")
}

dependencies {
    compileOnly("com.hiusers.rafter:rafter-api:0.0.3")
}
```

在 `plugin.yml` 中声明 `depend: [Rafter]`。API 仅用于编译，运行时由 Rafter 提供，不要打进业务插件。

插件启用后，在主线程获取服务：

```kotlin
val service = Bukkit.getServicesManager().load(RafterService::class.java)
    ?: error("Rafter 服务不可用")
```

TabooLib 插件可在 ENABLE 注册提供器，菜单在 ACTIVE 加载。提前打开可能得到 `NO_VALID_SNAPSHOT`。

## 界面操作

| 方法 | 行为 |
| --- | --- |
| `open(player, menu, args)` | 返回 `CompletableFuture<OpenResult>`，包含 `opened`、`failure`、`sessionId`；失败读取 `OpenResult.failure` |
| `open(player, menu, args, options)` | 首读前初始化状态并打开目标页，成功表示最终目标页已交给发送层 |
| `refresh(player, expectedSessionId)` | 无会话或 ID 不匹配时返回 `SUPERSEDED`；忙碌时合并刷新 |
| `close(player, expectedSessionId)` | 无会话时成功；存在其他会话时返回 `SUPERSEDED`，不会关闭它 |
| `contract()` | 返回界面能力与已注册扩展的只读描述 |

`refresh`、`close` 返回 `CompletableFuture<Void>`。会话 ID 取自原 `OpenResult.sessionId` 或 `UiContext.sessionId`；原会话失效后，排队刷新会异常完成，不转交新会话。

`UiOpenOptions` 需要 Rafter `0.0.3+`：

- `initialState`：已声明状态的初始补丁，默认空。接收时深拷贝并冻结，全部校验通过后一起写入，在首次提供器读取前生效。
- `initialPage`：菜单内已有的目标页，默认使用 `entry`。普通页可为 native 或 Canvas；目标为 modal 时，入口须为不含原生控件、无 frame 的纯 Canvas。
- 直接打开 modal 会先发送入口 Canvas 并捕获背景，再打开目标表单，沿用同一会话 ID。两阶段共用渲染时限。
- 参数、初始状态或目标页校验失败时保留旧会话。初始化被关闭、替换、重载或超时后，迟到结果失效。

modal 之间用 `open-page` 同层替换，复用背景，不追加历史。表单仍需显式 `submit`；Esc、关闭或返回不会自动保存输入。

打开与刷新 Future 在对应帧交给发送层或明确失败后完成，不代表客户端已显示。`open` 失败读取 `OpenResult.failure`。`refresh`、`close` 的失败通过 `UiOperationException.code` 判断。

`contract()` 提供以下能力字段：

- `service-api.open.initial-state`: `declared-state-atomic-before-first-read`
- `service-api.open.initial-page`: `entry-or-existing-page`
- `service-api.open.initial-modal`: `present-entry-canvas-then-modal`
- `service-api.open.completion`: `final-target-sent`
- `native-modal.replacement`: `same-layer-reuse-backdrop-no-history-push`
- `contract-version`: `13`

界面操作可跨线程发起，但后台不能读取 Player，也不要阻塞等待 Future。完成回调不保证位于主线程。

## 注册扩展

| 方法 | 回调 | 返回值 |
| --- | --- | --- |
| `registerData` | `read(context, args)` | `CompletableFuture<Any?>` |
| `registerCondition` | `test(context, args)` | `CompletableFuture<Boolean>` |
| `registerAction` | `execute(context, args)` | `CompletableFuture<UiActionResult>` |
| `registerItems` | `read(context, id)` | `CompletableFuture<ItemStack>` |

注册时传入 `owner`、`id`、`descriptor`、`provider`。在主线程注册，`owner` 须已启用，ID 使用命名空间。

返回的 `AutoCloseable` 用于注销，所属插件停用时也会自动注销。句柄可跨线程关闭，返回时不保证清理已完成；同 ID 重新注册后，旧句柄不会删除新提供器。有效注销会使现有会话失效。

回调从主线程发起，可异步完成。后台只处理 UUID 等普通数据，Bukkit 操作需回到主线程。返回 ItemStack 后不要再并发修改它；物品提供器的 `descriptor.parameters` 必须为空。

## 参数与上下文

`ProviderDescriptor` 声明参数，`ParameterSpec` 支持类型、必填、null、默认值、枚举和范围：

- 未声明参数会被拒绝。`required` 与 `nullable` 独立，默认值只补缺失的可选参数。
- INTEGER 只接受无小数的有限数值，不转换字符串。范围包含端点；枚举按 JSON 值比较，`1` 与 `1.0` 相等。
- 普通值限 `null`、字符串、布尔、有限普通数值、列表和字符串键映射，最多嵌套 32 层。

`UiContext` 包含 `player`、`sessionId`、`menu`、`page`、`args`、`state`、`form` 和当前行 `item`，集合为只读快照。

列表中的 ItemProvider 取得各节点自己的行记录，非列表节点的 `item` 为 `null`，可通过 `state` 或 `args` 展示选中项。ItemStack 只通过 ItemProvider 返回，不能放入 DataProvider。

## 动作结果

先在菜单声明状态：

```yaml
state:
  status: ''
```

动作通过 `statePatch` 返回结果，由界面中的状态绑定显示：

```kotlin
val registration = service.registerAction(
    plugin,
    "example:result",
    ProviderDescriptor("操作结果"),
    ActionProvider { _, _ ->
        CompletableFuture.completedFuture(
            UiActionResult(mapOf("status" to "操作未通过"))
        )
    }
)
```

- 结果接收时深拷贝并冻结，只能更新已声明的 `state` 字段，同时满足状态类型、列表和控件约束。
- 所有字段通过校验后才一起写入。非法 patch 返回 `INVALID_ARGUMENT`，不会留下部分更新；后续动作可读到新状态。
- 写回在主线程执行，并检查原会话、页面、代数、时限和提供器所有权。迟到结果不写回，也不因此取消、回滚或重试外部业务。
- 正常业务拒绝可用状态反馈，无需抛 `INTERNAL_ERROR`。
- 无状态更新时返回 `UiActionResult()` 或 `UiActionResult.empty()`；Java 可用 `new UiActionResult()` 或 `UiActionResult.empty()`，不能返回 null。

动作结束后自动重绘，并合并排队刷新。**ActionProvider 不应在自己的返回链中等待同会话 refresh**，否则会相互等待。

Java 示例见 [ApiConsumer](../examples/api-consumer/README.md)。

## 构建 API

```sh
./gradlew taboolibBuildApi -PDeleteCode -x test
```

产物：`project/api/build/libs/rafter-api-0.0.3-api.jar`。部署插件请单独构建，不要使用 `DeleteCode` 处理后的插件 JAR。
