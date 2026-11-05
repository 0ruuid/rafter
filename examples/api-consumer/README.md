# API 调用示例

以 `compileOnly("com.hiusers.rafter:rafter-api:0.0.5")` 引用，运行时使用 Rafter `0.0.5+`，在 `plugin.yml` 声明 `depend: [Rafter]`。

```kotlin
val ui = RafterService.get()
ui.open(player, "example:menu")
ui.open(player, "example:menu", UiOpenOptions(
    initialState = mapOf("title" to "Example"),
    initialPage = "edit",
))
```

- [ApiConsumer.java](src/main/java/com/hiusers/rafter/example/ApiConsumer.java)：提供器、状态结果与会话操作。
- [ApiValueConsumer.java](src/main/java/com/hiusers/rafter/example/ApiValueConsumer.java)：Java 简便创建与打开入口。
- [KotlinConsumer.kt](src/main/kotlin/com/hiusers/rafter/example/KotlinConsumer.kt)：命名参数、默认值、枚举和简便注册。

菜单须声明初始状态；modal 目标须有纯 Canvas 入口。表单显式提交，动作完成自动重绘。刷新和关闭使用原会话 ID。

使用 JDK 21，先保存部署构件，再生成 API 并执行轻量消费方检查：

```sh
./gradlew :plugin:build -x test --no-daemon
mkdir -p build/api-verification
cp plugin/build/libs/Rafter.jar build/api-verification/Rafter.jar
./gradlew taboolibBuildApi :project:api:sourcesJar -PDeleteCode -x test --no-daemon
./gradlew :project:api:verifyApiAbi -PrafterVerificationPluginJar=build/api-verification/Rafter.jar --no-daemon
```

检查 Java／Kotlin 调用及 API 与部署 JAR 的签名，不启动游戏或执行业务。完整接口见 [API](../../docs/api-guide.md)。
