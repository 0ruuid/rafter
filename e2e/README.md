# Calcite 物品检查

在隔离的 Minecraft 26.2 实例中检查正式 Canvas `item` 节点：真实模型与数量、tooltip、不同尺寸和位置、9×2 网格、空槽、独立分页及行内点击。物品布局由服务端菜单编译，不修改客户端控件坐标或渲染器。

`fixtures/core` 提供测试菜单与模板，`ItemProbePlugin` 注册普通数据、物品和动作提供器；随包示例 `/rafter open rafter:items` 不依赖该插件。

## 运行

复用已有 Calcite 0.5.4、Java 25 和正版缓存登录，不复制或输出凭据。缓存、客户端、服务端和证据放在外置盘；不操作 Spool 实例或原测试服。脚本不自动搭建服务端，需预先在 `127.0.0.1:25577` 启动隔离 Paper，安装插件和夹具，再构建并加载该服生成的资源包。

```sh
export RAFTER_E2E_ROOT=/Volumes/iiDisk/environment/test/rafter-e2e
export CALCITE_HOME=/Volumes/iiDisk/environment/test/spool-rafter-e2e/calcite-home
export RAFTER_CALCITE_PACKAGE=/Volumes/iiDisk/environment/test/spool-rafter-e2e/tools/node_modules/@bkm016/calcite
export RAFTER_CALCITE_ACCOUNT=microsoft
node e2e/calcite/run.mjs
```

标准输入逐行接收 JSONL，客户端使用独立的 `RafterItemProbe` 实例：

```jsonl
{"op":"start"}
{"op":"command","value":"rafter open rafterprobe:canvas-items"}
{"op":"snap","name":"canvas-grid-final"}
{"op":"resource","path":"minecraft:shaders/core/position_tex_color.vsh"}
{"op":"stop"}
```

`hover`、`click` 使用客户端真实鼠标事件，坐标是当前窗口的逻辑 GUI 像素，不是截图物理像素。截图前检查资源加载和覆盖层，操作失败返回非零退出码。可用 `RAFTER_JAVA_PATH` 覆盖 Java 路径。

## 核对

完成夹具页面的悬停、点击和翻页后：

```sh
python3 e2e/calcite/check-evidence.py \
  --evidence /Volumes/iiDisk/environment/test/rafter-e2e/evidence
```

检查器核对物品组件、稳定行 ID、点击结果、空载体、独立分页和实际加载的 Shader。`--suite native` 检查原生流式物品的基础证据。输出的 `manual-visual-review` 必须另行查看：控件坐标仍在原生载体处，不能用控件快照推断 Shader 后的视觉坐标或 tooltip 已显示。

2026-11-04 已在 26.2、逻辑 GUI 1280×960 下实测上述 Canvas 布局和随包示例。其他版本已有源码适配，但本目录没有对应实机记录；滚动裁剪、任意 GUI 缩放和复杂模型遮挡仍未验证。测试结束关闭自己的客户端和隔离服务器。

## 640×480 阻塞检查

安装 `fixtures/core/menus/item-viewport.yml`、原有模板和提供器，重新构包。为隔离 26.2 世界生成原版最薄载体对照：

```sh
python3 e2e/calcite/viewport-requests.py \
  --datapack /absolute/test-world/datapacks/rafter-viewport > requests.jsonl
```

在隔离服执行 `minecraft:reload`。为本次运行设置独立的 `RAFTER_E2E_ROOT`，确保客户端 `ui` 返回的逻辑尺寸是 640×480，再执行：

```sh
node e2e/calcite/run.mjs < requests.jsonl
python3 e2e/calcite/check-evidence.py --suite viewport --evidence /absolute/run/evidence
```

截图原样保存，记录包含 UI 与 PNG 的 SHA-256。检查器不把不完整的最新运行替换成历史成功运行，同名文件被覆盖也不能借用旧记录通过。

2026-11-04，0.0.5 / contract 15 的本项实测**失败**：560×342、18 槽 Canvas 的正文高度为 740，原生滚动区高 442，最大滚动量 298；18 个无描述、1px 高的原版物品载体加同高正文，仍有 136px 滚动量。本项不是完整验收通过，带物品 Canvas 的原生 modal 仍未支持。历史客户端资源包重载 OOM 仍待复核；“2GB”所指对象缺少原始记录，不能据此判断资源包大小。

直接承载入口的限定审查见[原版入口](carrier-client-audit.md)与[参考项目源码](carrier-reference-audit.md)。后续参考 Xerr 的绘制解耦思路完成了[画面与占位分离原型](flow-canvas-prototype.md)，再接入正式编译器、会话和传输；旧方案的失败证据仍保留。

## 正式物品与 modal 回归

使用同一次服务端 `/rafter pack` 的资源包和 `/rafter export` 的契约。不要用另一份项目重新分配字形后生成的包代替。固定本次插件、契约、字体和 Shader 的 SHA-256 到 run 目录的 `manifest.json`，客户端加载同一份包。

```sh
python3 e2e/calcite/integrated-requests.py \
  --pack /absolute/run/artifacts/resource-pack > /absolute/run/requests.jsonl
RAFTER_E2E_ROOT=/absolute/run node e2e/calcite/run.mjs < /absolute/run/requests.jsonl
python3 e2e/calcite/check-integrated-evidence.py \
  --run /absolute/run --manifest /absolute/run/manifest.json
```

操作生成器从正式布局读取 modal 的输入路径和按钮位置，使用夹具的 640×480 逻辑坐标；900ms 等待用于客户端接收重绘和既有点击冷却，不更改引擎节流。

2026-11-04 的最终 `integrated-05` 使用 0.0.5 / contract 16，32 项数据断言通过：18 槽无滚动、四角行点击、物品背景冻结、tooltip／点击隔离、modal 同层替换与状态保留、预填单行和多行表单、空页、短页恢复、双区域独立分页及面板完整位于视口内。检查器只使用该次 start→stop 的记录，并校验构件、实际加载字体、Shader、UI 和截图哈希。

截图仍需人工检查，控件快照不能证明 Shader 后的视觉效果。模型完整／部分被面板遮挡、面板外保留和 tooltip 已检查。`integrated-04` 发现复用的高表单贴图底部裁掉 8px；最终夹具通过既有皮肤参数换用 211px 测试面板，不改变输入、按钮、原生占位或命中区，并新增装饰溢出回归。旧运行和失败记录保留，不拼接到最终结果。其余版本与历史热更 OOM 未验证。客户端和隔离服务器已关闭，GUI 缩放和选包设置已恢复。

检查证据脚本本身：

```sh
python3 e2e/calcite/test_check_evidence.py
python3 e2e/calcite/test_integrated_evidence.py
```
