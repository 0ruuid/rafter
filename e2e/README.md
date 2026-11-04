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

2026-11-04 已在 26.2、逻辑 GUI 1280×960 下实测上述 Canvas 布局和随包示例。未验证滚动裁剪、任意 GUI 缩放、复杂模型遮挡和其他客户端版本；当前正式后端对此保留限制。测试结束关闭自己的客户端和隔离服务器。
