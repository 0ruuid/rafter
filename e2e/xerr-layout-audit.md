# Xerr 布局机制审查

审查日期：2026-11-04。只读核对同级 `../Xerr` 仓库（HEAD `1f500ed`）；未运行 Gradle/游戏，未修改 Xerr。

## 结论

Xerr 可借的是“绘制内容与布局坐标分离”的编码链：逻辑元素先生成文本/JSON，位置通过 space font、TTF carrier 和 shader 解码，最后由具体显示通道投递。它适合作为“标题画面 + 空带复用”的视觉参考；原生 hit 文本带和鼠标交互仍应由 Rafter 自己的 Dialog 协议负责。不要引入 Xerr 依赖，也不要接管 HUD。

## 机制证据

- **水平定位与游标平衡**：`project/module-api/src/main/kotlin/com/hiusers/xerr/api/builder/PositionBuilder.kt:9-45,64-78` 的 `buildString`/`component` 用资源包 space font 编码整数及 12-bit 小数位移。`project/module-api/src/main/kotlin/com/hiusers/xerr/api/entity/layout/LayoutEntity.kt:179-334` 的 `buildComponentStringInternal` 对元素执行“前移—绘制—回退”，避免影响后续元素的水平起点；它本身不会减少 Dialog 的原生正文高度。
- **TTF 位置解耦**：`project/module-api/src/main/kotlin/com/hiusers/xerr/api/font/TextPositioning.kt:9-58,81-107` 的 `register`、`sourceFont`、`offset` 把 `(anchor,y)` 变成语义位置 ID 和 carrier；同一套文本可在不同投递面使用不同实际载体。
- **位图定位标记**：`project/module-api/src/main/kotlin/com/hiusers/xerr/api/font/BitmapPositioning.kt:8-33` 的 `wrap` 在位图前后加 marker，保持位图局部绘制与文本游标分离；仅借标记思路，不复制 Xerr 资源命名空间。
- **客户端解码**：`project/module-api/src/main/kotlin/com/hiusers/xerr/api/font/TextPositioning.kt:110-173` 的 `shaderCode` 解码 TTF 位置；`project/module-api/src/main/kotlin/com/hiusers/xerr/api/shader/ShaderGenerator.kt:88-127,152-187` 的 `generatePositionCode`/`processShaderTemplate` 注入文本和位图变换；`project/module-core/src/main/kotlin/com/hiusers/xerr/core/loader/ResourcePackGenerator.kt:207-210,398-418` 在资源包生成阶段投递这些代码。
- **布局绘制投递**：`project/module-api/src/main/kotlin/com/hiusers/xerr/api/container/LayoutContainer.kt:29-62` 调 `LayoutEntity` 生成原始组件；`project/module-api/src/main/kotlin/com/hiusers/xerr/api/render/LayoutCanvas.kt:117-139,140-205,238-250` 负责异步元素绘制、bounds 测量、JSON 合并和主线程投递。`project/module-core/src/main/kotlin/com/hiusers/xerr/core/listener/InventoryListener.kt:18-38` 是最接近标题画面的通道：只改 `OpenWindow/OpenScreen` 的 title，并经 `project/module-api/src/main/kotlin/com/hiusers/xerr/api/font/PositionedText.kt:81-107` 的 `PositionedText.render` 写回 packet。

## 交互边界与借用边界

Xerr 的视觉通道不提供 Dialog 正文的坐标 hit-test。`project/module-core/src/main/kotlin/com/hiusers/xerr/core/listener/TooltipListener.kt:50-74` 的 click/drag 监听只用于库存 tooltip/cache 预热；不能当作 native Dialog 的 hover/click 协议。故主模型的“标题画面 + 空带复用 + 原生 hit 文本带”应保持两条独立链：Xerr 算法只借给标题/Canvas 绘制，实际 hover/click 继续走 Rafter 原生文本带。

实现边界：在 Rafter 自有资源包命名空间中重做位置编码、carrier 和 shader 注入；保留真实 560×342 布局盒，不叠加完整 342px 正文；不调用 Xerr 的 `BossbarHudRenderer`、`ScreenEffectService` 或 `StackService`，不改运行时。
