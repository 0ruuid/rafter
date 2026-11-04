# 创作工具

`python3 scripts/author.py` 只是启动器。实际检查由配套的 Kotlin `rafter-author` 完成；不自动下载、编译、联网或启动服务。正式使用匹配 Rafter 版本的独立分发；源码工程可经授权执行 `:tools:authoring-cli:installDist` 构建。

## 调用

```sh
python3 scripts/author.py describe
python3 scripts/author.py validate --project /absolute/path/to/Rafter
python3 scripts/author.py preview --project /absolute/path/to/Rafter --menu rafter:friends --page search --scenario core/previews/search.yml
python3 scripts/author.py pack --project /absolute/path/to/Rafter --archive
```

`describe --project ...` 同时导出该项目的编译布局和规则。工具不会连接服务器，扩展清单以目标服务器导出为准。`pack` 只读取配置中显式声明的合并源，不启动自托管，也不发送资源包。

能力字段见 `service-api.open`、`native-modal.replacement`、`canvas.items.layout-budget` 和 `canvas.items.modal`。服务 API 可用 `initialPage` 指定入口或已有页，`initialState` 在首读前原子校验；纯 Canvas（可含 items）先呈现入口，再切换到 native modal。modal 同层替换复用背景且不追加历史；背景使用冻结的独立 `ItemStack`，不重读 `ItemProvider`，也不参与 hover/click，面板裁剪只作用于冻结模型路由。表单只有显式 `submit` 才写入状态，Esc、关闭或返回不自动保存原生输入；`ActionProvider` 返回链不要等待同会话 `refresh`。

Canvas item 的布局预算从生成包 `item-layouts.json` 查询：`routes[*].minimum-gui-width`、`routes[*].minimum-gui-height` 是实际 GUI 预算，`flows` 与 `modal-flows` 是原生正文 flow；不要用 item 数量和 Canvas 高度拼接旧公式。flow 载体高度为 1..256，载体间隔为 10px，优先使用交互以外空带，空间不足时由编译器增加少量 9px 前置 inset；modal 内不新增 item 节点，也不支持任意物品 z、遮罩或滚动。

退出码：`0`=本次离线范围通过，`1`=错误，`2`=结构可编译但目标 Kether／扩展未验证，`64`=用法或工具不可用。stdout 是 JSON（help 除外）；不能只看进程是否生成了图片。

## 派生产物

- `generate/contract/authoring.json`：引擎能力、契约版本、源指纹。
- 同目录 Schema、`assets.json`、`layouts.json`、`preview-data.json` 和 `preview-stubs.json`。
- `generate/preview/preview.png`：设计证明图；`regions.png`：额外显示点击范围，不用 ID 遮挡图片。
- `generate/preview/report.json`：近似范围、使用的 stub 和来源。
- `generate/validation/report.json`：本次检查结果。
- `generate/resource-pack/` 与可选 `resource-pack.zip`：资源输出。

每次预览替换上一组预览；保留多个状态时，把需要交付的图片另存到授权的设计输出目录，不混入菜单配置。

## 预览场景

场景 YAML 是工具样例，不是服务器菜单。用 `preview.schema.json` 校验字段；普通页面不需要创建场景文件。预览用 `--menu`、`--page` 和 `--scenario` 指定页面及 scenario state，例如：

```sh
python3 scripts/author.py preview --project /absolute/path/to/Rafter --menu rafter:friends --page search --scenario core/previews/search.yml
```

```yaml
origin: friends
state:
  selected-id: friend-1
form:
  input: Player
```

`origin` 是 modal 的 Canvas 来源页；默认使用菜单入口。`origin-popup` 指定冻结前的 popup，`popup` 指定当前页 popup。`args`、`state`、`form` 只提供样例数据，`errors` 只投影表单错误。原生 form 仍按真实类型和长度规则检查。

遇到 Kether／PAPI／提供器读取，先在 `preview-stubs.json` 查精确请求，再向 `stubs` 写入该 ID 对应的普通值。ID 包含参数、文本出现位置及行作用域；表达式变化后旧 ID 会失效。条件 stub 必须为布尔，提供器集合必须满足真实记录结构；显式 null 与未提供不同。

不支持的读取不补空串、不猜条件，不运行脚本。CLI 不执行真实动作，也不承诺模拟原生客户端；渲染成功不证明业务动作有效。
