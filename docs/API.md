# XLib Sync API

机器可读合同位于 [`contracts/openapi.yaml`](../contracts/openapi.yaml)，基础路径为 `/v1`。

## 认证

`POST /v1/auth/start-sync` 使用邮箱和设备信息创建或恢复固定同步 Token。其他 `/v1` 请求必须同时携带：

```http
Authorization: Bearer <43-character-token>
X-Device-Id: <uuid>
```

Token 是敏感凭据。客户端必须存入平台安全存储，不得记录、提交或通过查询参数传输。

## 资源

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/auth/start-sync` | 创建或恢复 Token，并登记设备 |
| GET | `/v1/devices` | 列出设备 |
| DELETE | `/v1/devices/{deviceId}` | 撤销设备 |
| DELETE | `/v1/account` | 删除同步身份和全部云端数据 |
| GET | `/v1/progress` | 拉取全部阅读进度 |
| POST | `/v1/progress/sync` | 同步 1–100 条进度 |
| DELETE | `/v1/progress` | 删除全部云端进度 |

`DELETE /account` 和 `DELETE /progress` 是破坏性操作，客户端必须在调用前提供明确确认。

## 非业务端点

- `GET /health`：无需认证。
- `GET /metrics`：使用独立 `METRICS_TOKEN`，生产环境应只允许内部网络访问。

详细裁决、数据和安全设计见 [features/sync-server.md](features/sync-server.md)。
