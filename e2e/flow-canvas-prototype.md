# 画面与原生占位分离原型

2026-11-04，26.2 / Paper 26.2-123 / Calcite。仅为独立协议原型，尚未接入 Rafter 编译器、ItemProvider、会话与公开契约，不能作为 0.0.5 完整发布验收。

## 结果与机制

在真实逻辑 GUI **640×480** 下，**560×342** 画面内显示两排共 18 个真实 ItemStack，原生内容高度 380、最大滚动量 0。数量由同一物品快照生成普通文本；自定义模型、附魔和损伤仍由原生物品组件保留。四个角槽的原生 tooltip 和自定义点击回传均通过；间隙点击不触发，原始载体处无物品 tooltip，滚轮不改变位置，关闭后没有残留屏幕。

借鉴 Xerr 的是绘制与坐标解耦，不是它的物品能力，也没有引入 Xerr 依赖或接管 HUD：

- **画面层**：用 Rafter 已有标题位图协议的实验分支绘制静态装饰；不包含物品图片，不额外发送整块 Canvas 文本正文。
- **物品层**：保留 18 个真实原生 item body，放入没有点击／悬停的纵向空带，再由 Shader 移到槽位；不删除载体、不截断物品、不隐藏滚动条。
- **交互与数量层**：仅在两条实际交互带保留原生多行文本，每带 4 行、36px；透明字形锚点保证空格区域有客户端命中边界。tooltip 与点击仍由原生 Dialog 处理。

真实正文布局是 18 个 1px 载体、两块 44px 文本和一块 44px 空白，加 20 个 10px 间隔：`18 + 88 + 44 + 200 = 350 = 342 + 8`。追加原生操作行后是 380px。因此这里复用了画布空带，而不是把 `18行 + 342px` 相加。

## 复现

测试参数在 `e2e/fixtures/flow-canvas.json`，不是新增的公开菜单格式。生成独立资源包和隔离测试世界函数：

```sh
python3 e2e/calcite/flow-canvas-prototype.py \
  --base-pack /absolute/matching/resource-pack \
  --output /absolute/new-run \
  --datapack /absolute/test-world/datapacks/rafter-flow
```

原型需要测试版 `ItemProbePlugin` 的 `rafterprobe:flow/*` 回执监听器。它只返回测试消息，不处理业务和 Rafter 会话；正式集成须继续使用引擎原有安全句柄。

```sh
python3 e2e/calcite/check-flow-evidence.py --run /absolute/run
```

检查器复用同运行边界、PNG/UI 哈希检查。自动检查不能代替查看实际模型、数量及 tooltip 截图。正文高度、源载体坐标与目标图形位置分别核对，不用截图物理分辨率冒充逻辑 GUI。

## 已保留的实验记录

- `flow-canvas-01`：布局无滚动，但原生数量逐顶点搬运造成破面；纯空格文本没有命中边界。未通过。
- `flow-canvas-02`：改为文本带数量和透明锚点后，画面及 tooltip 正常；测试用高权限命令触发客户端安全确认。未通过点击验收。
- `flow-canvas-03`：聊天命令仍触发客户端确认。未通过点击验收。
- `flow-canvas-04`：改为与 Rafter 同类的 custom 点击数据包，14 项记录检查通过，并人工查看原样截图。通过的是本页原型，不是完整引擎。

记录均保留在外置盘 `/Volumes/iiDisk/environment/test/rafter-e2e/`。没有拼接不同运行的图片、聊天或元数据。

## 正式接入前仍需完成

1. 通用的原生空带排版器。载体仍需真实布局空间；没有足够空带时必须给出诊断，不能宣称任意布局都能放下。9×2 与本例尺寸不能成为框架限制。
2. 复用现有 ItemProvider、group/list/template/slots、条件与分页；测试程序的固定数据不能进入生产源码。正式文本继续由 TabooLib 编译。
3. 带物品 Canvas → native modal 的不可变快照、压暗、遮挡与交互隔离，以及返回、迟到 Future、关闭与重载清理。
4. 自由 slots、多区域分页、空行、隐藏项、popup 和跨客户端版本回归。目前只实机验证 26.2。
5. 历史客户端资源包重载 OOM 尚未复核；“2GB”所指对象缺少原始日志，不能当作资源包大小。本原型不作相关结论。
