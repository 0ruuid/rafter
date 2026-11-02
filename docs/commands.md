# 命令

`[]` 表示可选参数。

| 命令 | 用途 | 权限 |
| --- | --- | --- |
| `/rafter` | 查看帮助 | `rafter.use` |
| `/rafter open [menu] [player]` | 打开菜单，省略参数时使用默认菜单 | `rafter.use`；指定玩家需 `rafter.admin` |
| `/rafter close [player]` | 关闭菜单，默认关闭自己的菜单 | `rafter.use`；指定玩家需 `rafter.admin` |
| `/rafter reload` | 重载语言与菜单 | `rafter.admin` |
| `/rafter pack` | 构建资源包并更新菜单 | `rafter.admin` |
| `/rafter pack send [player]` | 发送托管资源包，默认发给自己 | `rafter.admin` |
| `/rafter export` | 导出界面能力与业务扩展描述 | `rafter.admin` |
