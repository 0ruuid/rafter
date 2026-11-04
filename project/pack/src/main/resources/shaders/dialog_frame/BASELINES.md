# Shader 基线

原版文件统一存放在 `shaders/client/`；`baselines.json` 记录来源和 SHA-256。
`ClientRenderProfiles` 是协议、资源格式和 Shader 接口的唯一声明。

- 1.21.6–1.21.8：GLSL 150，旧文字管线。
- 1.21.9–26.1.2：GLSL 330，旧文字管线；保留各版本的 uniform 布局。
- 26.2：统一 `text` 管线。
- 26.3：显式接口、调整后的 DynamicTransforms 和原版 OIT 路径。

普通文本保留原版计算，只有已识别的 Rafter 字形进入自定义分支。
旧文字管线以正交投影保护入口，新文字管线额外使用 `IS_GUI`。
未知协议不继承相邻版本能力。

离线编译不代表客户端验收。GLSL 150 在 SPIR-V 前端检查时临时提升至 330，
生成资源仍保持 150；该检查不替代真实 OpenGL 3.2 驱动验证。
