# 素材

## 图片

PNG 放入 `core/ui/images/`，支持子目录，资源 ID 按相对路径生成：

| 文件 | 资源 ID | 逻辑尺寸 |
| --- | --- | --- |
| `images/panel.png` | `rafter:panel` | 原图尺寸 |
| `images/buttons/ok@4x.png` | `rafter:buttons/ok` | 原图尺寸 ÷ 4 |

- 支持 `@1x`–`@8x`，原图宽高须能被倍率整除；去掉倍率后缀后不能同名。
- 只指定显示高度时，宽度按比例计算。同时指定宽高时，可选 `contain`、`cover` 或 `stretch`。
- 超过 256px 的图片会自动切片并拼回，使用最近邻采样。

## 字体

TTF 放入 `core/ui/fonts/`。例如 `fonts/pixel/menu.ttf` 对应字体族 `pixel/menu`。

在 `theme.yml` 中设置默认字体和字号：

```yaml
defaults:
  font: rafter-mono
  font-size: 10
styles:
  title:
    font-size: 20
```

字号为整数 `1–128`，宽度按字体实际度量计算。所选字号产生的字符步进宽度（advance）须为整数，否则编译报错。

不同字号共用同一个 TTF，无需复制文件。文字超出布局范围时会报错。
