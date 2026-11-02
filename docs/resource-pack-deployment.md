# 资源包

资源包输出到 `generate/resource-pack/`，ZIP 输出到 `generate/resource-pack.zip`。默认只生成目录。

## 构建与部署

1. 执行 `/rafter pack`。
2. 构建成功后，将完整目录或 ZIP 部署给客户端，也可使用下方托管。

构建失败会保留上一份有效结果。

## 配置

```yaml
resource-pack:
  archive: false
  compression-level: 6
  merge:
    sources: []
  self-host:
    enabled: false
    bind-address: 127.0.0.1
    port: 8187
    public-url: ''
    send-on-join: false
    send-after-build: false
    required: false
```

- **ZIP**：设置 `archive: true`；启用托管也会生成 ZIP。
- **合并**：`sources` 填写目录或 ZIP 路径，后列来源优先，最后叠加 Rafter。留空不合并。
- **托管**：设置 `self-host.enabled: true`。`public-url` 留空适合同机测试，远程玩家需填写客户端可访问的地址。
- **发送**：`send-on-join` 和 `send-after-build` 分别控制加入服务器、构包后自动发送；手动发送使用 `/rafter pack send [player]`。

内置服务使用 HTTP。需要 HTTPS 时配置反向代理；使用 URL 路径前缀时，转发其下的 `/rafter/packs/`。

修改来源后重新构包，不直接改已发布的文件。客户端未加载菜单所需资源时，菜单不会打开。
