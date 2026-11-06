# Rafter

基于 TabooLib 的 Minecraft Dialog 菜单引擎，通过配置组合图片、文字、原生控件和点击交互。

- 原生页面、Canvas 画布与模态弹窗。
- TabooLib 复合文本、Kether 与业务扩展 API。
- [AI 创作 Skill](skills/rafter-ui/SKILL.md)，根据需求和素材制作界面。

## 使用

面向 Minecraft Java 1.21.6+，依赖 PacketEvents 2.14.0。

1. 将 `Rafter.jar` 和 PacketEvents 放入服务器 `plugins/`，重启服务器。
2. 执行 `/rafter pack`，加载生成的 `plugins/Rafter/generate/resource-pack/` 资源包。
3. 在游戏中执行 `/rafter open`，打开默认好友界面。

输入框透明底图与字色使用客户端 `GameTime` 标记实现，可能使同一客户端的相关动画重复或跳变，详见[输入外观说明](docs/rendering.md#输入外观与透明模式)。

## 编译

使用 JDK 21：

```sh
./gradlew build
```

产物：`plugin/build/libs/Rafter.jar`。

## 文档

[配置](docs/configuration.md) · [素材](docs/assets.md) · [布局与交互](docs/rendering.md) · [命令](docs/commands.md) · [资源包](docs/resource-pack-deployment.md)

[API](docs/api-guide.md) · [AI 创作](docs/ai-authoring.md) · [架构](docs/architecture.md)
