# 原版物品承载候选审查

后续更新：下述审查未找到新的原生多物品节点，但不能据此排除重组现有节点。[画面与占位分离原型](flow-canvas-prototype.md)随后在 26.2 通过了目标视口的一页实验，并接入正式引擎。正式集成结果见 [Calcite 检查](README.md#正式物品与-modal-回归)；旧串联方案的失败记录仍然有效。

范围：本地正式版 26.2 客户端，目标仍是逻辑 GUI 640×480、560×342 Canvas、18 个独立真实 ItemStack、完整数量／悬停／点击且无滚动。未修改运行时、公开 API、Shader 或菜单规则。

## 结果

本轮没有找到满足全部条件的新承载机制，不创建猜测性原型。此结论限于以下已核对入口，不是对所有未知技巧或未来版本的“不可能证明”。

| 候选 | 核对的客户端入口 | 结果 |
| --- | --- | --- |
| 文本内联物品 | `ObjectInfos`、`AtlasGlyphProvider`、`GuiItemAtlas` | 对象文本只注册 `atlas`、`player`。字形从 `TextureAtlas` 按 sprite ID 读取；GUI 动态物品缓存使用独立 `GpuTexture` 和渲染状态标识。没有按任意 ItemStack 解析文字对象的入口，不能把精灵图片加 `show_item` 当成真实物品模型。 |
| 一个物品的 composite 子模型 | `CompositeModel.update` | 循环中所有子模型接收同一个 ItemStack 参数。不是多个独立物品快照，不能直接保留每件物品各自的模型、染色及其他组件。 |
| bundle selected item | `BundleSelectedItemSpecialRenderer.update` | 调用 `BundleItem.getSelectedItem`，只向解析器追加被选中的一件物品，不遍历全部内容；没有多个索引／槽位入口。递归包装还需另建物品和模型转换链，未验证通用组件、布局、成本和跨版本能力，不作为候选通过。 |
| 收纳袋 tooltip 网格 | `ClientBundleTooltip` | 确实逐件渲染物品，但属于鼠标悬停产生的 tooltip，不是常驻 Canvas，也没有各格业务点击控件。不能作为横版附件区的替代实现。 |
| 换用其他 Dialog 节点／按钮 | `DialogBodyTypes`、`DialogScreen`、`DialogBodyHandlers$ItemHandler` | 正文只注册 `item` 和 `plain_message`。原生正文逐项加入纵向布局；物品描述是右侧文本，不是可嵌套的物品子布局。动作按钮并未提供 ItemStack 渲染字段。 |

官方版本说明交叉核对：1.21.9 对象文本只列精灵与玩家头；1.21.4 composite 组合子模型、bundle/selected_item 只读选中项；26.1 的 transformation 不应用于 bundle/selected_item。不能仅因某版本多了变换字段，就宣称存在通用多 ItemStack 承载能力。

## 可重复证据

```sh
python3 e2e/calcite/audit-carriers.py \
  --client-jar /absolute/26.2.jar \
  --java-home /absolute/jdk25 \
  --output /absolute/external-disk/carrier-audit
```

脚本只读取客户端 JAR、记录版本／哈希并导出上述类的 `javap` 字节码，不启动游戏。命令成功只说明证据已提取，不说明候选可行或 UI 已通过。

此前真实客户端的失败检查仍须保留：

```sh
python3 e2e/calcite/check-evidence.py --suite viewport --evidence /absolute/run/evidence
```

0.0.5 / contract 15 的既有同运行证据中，640×480 的无滚动断言仍失败。本次直接承载审查没有原型通过；后续组合方案的结果见开头更新。历史客户端资源包重载 OOM 尚未复核，“2GB”含义缺少原始记录，不据此判断包大小。
