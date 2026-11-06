# 第三方说明

项目根目录的 CC0 许可用于 Rafter 自有代码，不替代第三方依赖和素材的许可。

## 默认字体

内置 `rafter-mono.ttf` 的字体名称为 **Fusion Pixel 10px Mono zh_hans**，版权元数据为 TakWolf（2022），保留字体名为 “Fusion Pixel”。字体采用 SIL Open Font License 1.1。

完整版权与许可位于 [rafter-mono.license](project/bukkit/src/main/resources/core/ui/fonts/rafter-mono.license)。该文件随默认源工程分发；资源编译器将已使用字体的同名 `.license` 原样复制到资源包的 `licenses/fonts/`。

字体来源：https://github.com/TakWolf/fusion-pixel-font

## Minecraft Shader 基线

`project/pack/src/main/resources/shaders/client/` 包含源自 Minecraft Java 客户端的 Shader 基线，用于适配各版本的渲染接口。这些第三方文件不属于 Rafter 自有代码的 CC0 授权范围。

基线版本由 `ClientRenderProfiles` 声明；`globals.glsl` 的官方客户端来源和校验摘要见 [client-globals-sources.json](docs/client-globals-sources.json)。修改这些文件时应保留对应版本的来源记录。

## 示例美术

示例使用的 PNG 与 SVG 保存在运行资源及 `assets/design/`。新增或替换第三方素材时应同时保留其来源和分发许可，不据项目根许可推定第三方授权。
