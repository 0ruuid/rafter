# Java API 示例

[ApiConsumer.java](src/main/java/com/hiusers/rafter/example/ApiConsumer.java) 展示服务获取、提供器注册、动作状态写回、会话操作和失败处理。示例同时保留原三参数 `open` 的展示/校验，并用 `UiOpenOptions` 预填单行 `title` 与多行 `notes`；菜单需声明这两个状态，`initialPage` 使用已有普通页 `edit`，也可作为 modal 表单目标。若目标为 modal，菜单 `entry` 必须是纯 Canvas 且无 frame 入口。

以 `compileOnly` 引用 API，在 `plugin.yml` 声明 `depend: [Rafter]`。示例动作要求菜单声明字符串状态 `status`；刷新和关闭传入原 `OpenResult.sessionId`。动作完成后会自动重绘，不在返回链中等待同会话刷新。接口说明见 [API](../../docs/api-guide.md)。

检查已有 JAR 的 Java 接口：

```sh
python3 scripts/verify-api.py \
  --java-home /path/to/jdk \
  --api-jar project/api/build/libs/rafter-api-0.0.3-api.jar \
  --plugin-jar plugin/build/libs/Rafter.jar \
  --classpath /path/to/bukkit.jar:/path/to/dependencies.jar
```

`classpath` 使用显式路径，不包含另一份 Rafter API。脚本分别对 API 和插件 JAR 编译示例，不执行回调。
