# 架构

## 模块

| 模块 | 职责 |
| --- | --- |
| api | 公开服务、提供器、参数和结果类型 |
| core | 配置模型、校验、布局、会话和组件文本 |
| pack | 字体、图片、Shader、合包与归档 |
| authoring | 工程读取、诊断、导出和预览 |
| bukkit | 生命周期、业务求值、会话管理和协议发送 |
| plugin | TabooLib／Klos 插件装配 |
| tools:authoring-cli | 本地创作命令行 |

## 运行流程

```text
配置与素材 → 校验、编译 → 只读菜单快照 → 玩家会话 → 渲染、发送
```

`ResourceCoordinator` 更新菜单和资源，`RafterEngine` 管理会话、渲染和动作。`args`、`state`、`form` 按会话隔离，业务插件通过[公开 API](api-guide.md)接入。

- Bukkit 访问、状态修改、点击校验和界面发送在主线程执行。
- 异步结果写回前检查原会话、页面、代数、时限和提供器所有权。
- 点击使用一次性句柄，服务端重新检查权限、条件和输入；列表按稳定记录 ID 定位。
- 文本使用 TabooLib `ComponentText`，只在发送时转换为协议类型。
- 会话按闲置时间清理；关闭或替换后，原会话的待处理结果失效。

布局规则见[布局与交互](rendering.md)。
