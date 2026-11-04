# 配置

菜单使用 YAML，ID 来自 `id` 字段。组件、动作和条件通过 `type` 指定类型。未知字段、重复 ID、循环和无效引用会报错。

## 目录

```text
Rafter/
├── config.yml                 默认菜单与资源包设置
├── kether.yml
├── lang/
├── core/
│   ├── menus/                 菜单、状态、数据与动作
│   ├── data/                  静态记录
│   └── ui/
│       ├── theme.yml          字体与样式
│       ├── skins/             页面布局与内容槽
│       ├── templates/         可复用组件
│       ├── images/            PNG
│       └── fonts/             TTF
└── generate/                  输出目录
```

源目录支持嵌套分类，同一类定义不能使用重复 ID。图片与字体的命名见[素材](assets.md)。

## 最小菜单

保存为 `core/menus/hello.yml`：

```yaml
id: example:hello
entry: home
pages:
  home:
    mode: native
    title: 欢迎
    components:
      - id: greeting
        type: text
        text: '&a你好'
      - id: exit
        type: button
        role: exit
        text: 关闭
        actions:
          - type: close
```

`entry` 指定入口页。页面可使用三类会话数据：

- `args`：打开菜单时传入的只读参数。
- `state`：菜单声明的状态，每个会话独立维护。
- `form`：尚未提交的表单，取消时不写入状态。

## 页面与复用

| 声明 | 用途 |
| --- | --- |
| `mode: native` | 原生正文、物品、按钮和表单 |
| `mode: canvas` | 独立图片、文字和点击区，按声明顺序绘制 |
| `skin` / `content` | 皮肤定义布局，菜单填写内容与动作 |
| `template` | 通过参数复用组件 |
| `group` | 组织局部坐标，编译后展开 |

Canvas 中的原生控件按客户端规则排列，见[布局与交互](rendering.md)。完整示例见[好友菜单](../project/bukkit/src/main/resources/core/menus/friends.yml)和[任务菜单](../project/bukkit/src/main/resources/core/menus/tasks.yml)。

Canvas 物品叶节点沿用普通 `group`、`list`、`template` 和 `slots`，不使用物品专用网格。最小示例：

```yaml
- id: diamond
  type: item
  item: minecraft:diamond
  size: 36
  show-count: true
  show-tooltip: true
  x: 189
  y: 45
```

Canvas 的 `size` 只能取 18 的整数倍且不超过 864；`y` 按 9px 对齐，`x` 不要求对齐。`item` 也可写已注册提供器引用；纯 vanilla 示例无需提供器。Native 物品不接受 `size` 或 `show-count`，只接受新增的 `show-tooltip`；原版 `show_decorations` 无法只控制数量。

## 数据与条件

- 本地数据声明 `id` 和 `items`，每条记录使用稳定 ID；列表选择保存记录 ID。
- `list` 支持分页、过滤和子集合。Canvas 可用行高或显式槽位；原生 `components` 列表按流排版，不接受 `x`、`y`、`slots`、`row-height`。
- 普通静态列表数据写在 `core/data`，菜单用 `data` 的 `type: document` 和 `key` 引用；当前 schema 不添加未定义的 `menu.documents` 字段。
- 可绑定 `args`、`state`、当前行、选中记录、列表统计和业务提供器。本地记录修改会话副本，外部数据通过业务动作更新。
- 条件支持 `equals`、`at-least`、`all`、`any`、`not`、`kether`。比较条件在 `value` 与 `compare` 中二选一；显式 `null` 与未填写不同。
- 变体使用第一个匹配项，未命中时使用基础定义。变体可调整显示和行为，不改变 ID 和布局；点击时重新检查权限、条件、变体和表单。

## 文本与动作

文字支持 TabooLib 颜色，如 `&a你好&{#ffffff}hello`。字体、悬停和文本片段使用 TabooLib 复合文本。玩家输入和绑定字符串按字面显示，不再解析为格式或脚本。

文字和多行 tooltip 可使用 `{{ player name }}` 等内联 Kether。Canvas 正文按单行文字分别配置。显示表达式和条件可能多次求值，不应包含扣款、发奖等操作。

导航、状态、关闭和提交使用结构化动作，复杂逻辑使用 Kether 或[业务 API](api-guide.md)。表单全部校验通过后才提交，外部业务操作不会自动回滚。

## 应用修改

| 修改内容 | 操作 |
| --- | --- |
| 不影响生成资源的内容或业务逻辑 | `/rafter reload` |
| 字体、图片、尺寸或生成布局 | `/rafter pack`，然后更新客户端资源包 |

加载或构包失败时继续使用上一份有效配置。成功更新会关闭旧会话，未提交表单不迁移。已有自定义文件不会被默认配置覆盖。

内置 `/rafter open rafter:items` 演示单件放大、单排、网格、不规则位置和双独立分页，好友菜单仍是默认入口。全局最多 128 个物品编译路由通道，几何冲突可能提前耗尽。物品页须整体放入视口，不支持滚动、item popup 或原生 modal 承载。
