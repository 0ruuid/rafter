# FutureUI / DialogMenu ItemStack 承载候选审查

本报告只核对两项目已有的承载入口，不是否定所有组合方式。后续参考 Xerr 的绘制解耦思路完成了[独立协议原型](flow-canvas-prototype.md)，尚未作为正式 Rafter 能力发布。

审查日期：2026-11-04。只读核对两个仓库的 `main`：

- [FutureUI @ 8156d456](https://github.com/Ti-Avanti/FutureUI/tree/8156d45630b3d8a124d5b06a154218ad82f293db)，`Release FutureUI 1.0.0`。
- [DialogMenu @ f531be387](https://github.com/Usasi103/DialogMenu/tree/f531be387fc6eebd1e84bc98c15fa6150f2d0dac)，`Release 0.2.10`。

26.2 客户端基线采用主线程已确认的结果：`ItemBody` 的正文字段是 `ItemStackTemplate item`、可选 `PlainMessage description`、`showDecorations`、`showTooltip`、`width`、`height`；`DialogBodyHandlers$ItemHandler.createControls` 创建真实 `ItemStack` 和 `ItemDisplayWidget`。`DialogScreen` 把 body 列表加入垂直 `LinearLayout`，再交给 `ScrollableLayout`。因此原生 item body 能真实渲染模型和 tooltip，但当前正文协议没有把多个 item body 变成文本内联、网格或绝对坐标。

## 结论表

| 路径 | 是否真实 ItemStack | 是否满足“不是每物品独立纵向 body” | 结论 |
|---|---:|---:|---|
| DialogMenu `Renderer: items` / `DialogBody.item` | 是 | 否 | 保留为真实机制对照；严格目标下淘汰 |
| DialogMenu 文本 `<image:...>`、canvas sprite | 否，字体/图片 | 不适用 | 静态贴图，不是 ItemStack |
| FutureUI Dialog `type: item` | 否，资源包 glyph | 不适用 | 静态物品贴图，不是 ItemStack |
| FutureUI `CanvasTooltip` | 否，PAPER tooltip carrier | 不适用 | tooltip/交互装饰，不是真实物品渲染 |

按任务边界未把 HUD、实体或容器 renderer 作为候选；没有为这些路径做实现原型。

## 候选 1：DialogMenu 原生 item body（真实，但仍一项一行）

已实现机制与证据：

- [ItemMenuPage.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/ItemMenuPage.java#L21-L37) 的 `usesItems` 在 `Renderer: items` 或 `Icons.<id>.Display.Material` / `Type: item` 时切到 item 页面，并明确拒绝把带物品的页面放进 `canvas`。
- [ItemMenuPage.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/ItemMenuPage.java#L64-L179) 的 `parse` 只接受 `button/item/text/heading`；物品展示字段是 `Material`、`Name`、`Lore`、`Amount`、`Fallback`。`Material` 可以是原版 ID 或 `source:插件ID:物品ID`，不是任意序列化 ItemStack 文本。
- [MenuItemSources.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/MenuItemSources.java#L38-L59) 的 `build` 从 `MenuItemSource` 得到 Bukkit `ItemStack`，clone 后仅改展示数量；[ItemBridgeSource.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/ItemBridgeSource.java#L43-L49) 通过 `buildOrNull(provider, input, player, BuildContext.empty())` 取得自定义物品。因此 provider 返回的自定义模型/数据会进入真实 item body，但“任意 ItemStack 直接内联”没有配置入口。
- [ItemMenuRenderer.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/ItemMenuRenderer.java#L41-L103) 对 `page.itemEntries()` 逐项循环；有物品时逐项执行 `bodies.add(DialogBody.item(resolved.item(), description, true, true, 24, 24))`。这是当前仓库最明确的真实 ItemStack 证据。
- [MenuDialog.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/MenuDialog.java#L457-L500) 把 `rendered.bodies()` 原样交给 `DialogBase.body`；没有 row/column/grid/坐标字段。结合 26.2 客户端 body 的垂直 `LinearLayout`，每个 item 仍占一个独立纵向 body。

占位与交互约束：

- `24 × 24` 是该仓库传给原生 item body 的占位尺寸；不是自由缩放/定位。当前客户端 `ItemBody` 只接受 `width/height`，不接受 x/y 或文本内联位置。
- `showTooltip=true` 是真实 `ItemDisplayWidget` 的原生物品 tooltip；不是 Adventure 文本里的 `show_item`。图标本身没有动作，源码把点击事件加在说明 caption（`caption.clickEvent`）和下方 `ActionButton`，不是 `ItemDisplayWidget`。
- `Renderer: items` 页面不能混入原 canvas 的开关、滑条、下拉框；需要另开页面跳转。标题仍是 `Component`（`MenuDialog.show` 的 `title`），没有把 ItemStack 放入标题或普通 native 控件。

严格判定：真实渲染成立；但“多物品不各占独立纵向行”不成立，当前没有合格的原生其他布局候选。

## 非候选：DialogMenu 文本内联 / canvas 图标

- [RichMenuText.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/RichMenuText.java#L78-L101) 的 `<image:...>` 标签把图片解析为 `Component` 字形；[MenuImages.java](https://github.com/Usasi103/DialogMenu/blob/f531be387fc6eebd1e84bc98c15fa6150f2d0dac/src/main/java/online/toraka/dialogmenu/MenuImages.java#L75-L200) 读取 CraftEngine/ItemsAdder 的字体图片和 PNG 宽度，不构造 `ItemStack`。
- 当前源码只看到 `HoverEvent.showText`（`ActionValues`、`DialogCanvas`），没有 `HoverEvent.showItem` / `show_item` 文本内联路径。故这些图标只能算静态贴图或文字 tooltip，不算真实 ItemStack 承载。

## FutureUI：Dialog `item` 是静态图，不是真实 ItemStack

已实现机制与证据：

- [DialogRenderer.java](https://github.com/Ti-Avanti/FutureUI/blob/8156d45630b3d8a124d5b06a154218ad82f293db/src/main/java/gg/fotia/futureui/render/DialogRenderer.java#L21-L35) 的 `case "item"` 只调用 `DialogItemPreview.create`。
- [DialogItemPreview.java](https://github.com/Ti-Avanti/FutureUI/blob/8156d45630b3d8a124d5b06a154218ad82f293db/src/main/java/gg/fotia/futureui/render/DialogItemPreview.java#L10-L20) 明确写的是“使用资源包图片”；它从 `icon/material` 取 glyph，调用 `PixelCanvas.picture`，最后返回 `DialogBody.plainMessage`。这里没有 `ItemStack`、`DialogBody.item` 或客户端 item widget。
- [MenuValidator.java](https://github.com/Ti-Avanti/FutureUI/blob/8156d45630b3d8a124d5b06a154218ad82f293db/src/main/java/gg/fotia/futureui/config/MenuValidator.java#L97-L99) 的 `validateCanvas` 类型集合不含 `item`；把 item 放入 Canvas 会被拒绝。`row/column/grid` 只负责 Canvas 的 glyph/文字布局，不能把真实 ItemStack 带进来。
- `renderer: native` 还会由 [NativeFallback.java](https://github.com/Ti-Avanti/FutureUI/blob/8156d45630b3d8a124d5b06a154218ad82f293db/src/main/java/gg/fotia/futureui/render/NativeFallback.java#L6-L10) 直接丢弃 `image`、`spacer`、`item`，所以也没有原生控件承载它。

区分 tooltip 与真实渲染：

- [CanvasTooltip.java](https://github.com/Ti-Avanti/FutureUI/blob/8156d45630b3d8a124d5b06a154218ad82f293db/src/main/java/gg/fotia/futureui/render/CanvasTooltip.java#L9-L15) 创建的是 `new ItemStack(Material.PAPER)` 作为 tooltip carrier，再调用 `asHoverEvent()`；它不使用待展示物品，也不渲染待展示物品模型。
- FutureUI 的 `ItemFactory` / `ItemAttributes` 虽然有真实 ItemStack 构造和 `item-model` / custom model data 字段，但当前 Dialog `item` 分支没有调用这条链；源码证据不足以把它认定为 Dialog 内联候选。容器 renderer 路径按本任务边界不纳入审查。

严格判定：FutureUI 当前两条 Dialog/Canvas/native 路径均未找到真实 ItemStack 承载；无合格候选。

## 最终结论

在两个仓库当前 `main` 源码中，唯一明确的真实 ItemStack 机制是 DialogMenu 的原生 `DialogBody.item`；它能保留 provider 自定义模型并显示原生 tooltip，但客户端正文布局仍是每个 body 独立纵向排列。文本内联、标题、普通 native 控件和 Canvas 路径没有第二种真实承载机制；其余命中均为静态字体贴图或 tooltip carrier。没有继续试 shader 或猜测未读扩展。

## 源码证据

两份官方仓库的浅克隆已归档到外置盘：

- `/Volumes/iiDisk/environment/test/rafter-e2e/carrier-candidate-audit-0.0.5/references/FutureUI`，HEAD `8156d45630b3d8a124d5b06a154218ad82f293db`。
- `/Volumes/iiDisk/environment/test/rafter-e2e/carrier-candidate-audit-0.0.5/references/DialogMenu`，HEAD `f531be387fc6eebd1e84bc98c15fa6150f2d0dac`。

已直接读取上述实现，不以 README 功能名称作结论。主模型另经 GitHub 官方 API 独立核对 `DialogItemPreview.java`（blob `f72eb65bdce64420da8c947ade5fb0a6eb59e258`）和 `ItemMenuRenderer.java`（blob `2b3e4aea8f6528612267c49c0928fc8ca6bfdfc9`）。
