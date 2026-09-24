# XLib Sync API

[OpenAPI](../contracts/openapi.yaml) 是当前 HTTP 契约；基础路径 `/v1`。业务定义见 [进度同步](product/capabilities/progress-sync.md)、[同步身份](product/capabilities/sync-identity.md) 和 [单书云端删除](product/capabilities/cloud-data-control.md)。

## 当前接口

| 方法 | 路径 | 语义 |
|---|---|---|
| POST | `/v1/auth/start-sync` | 按邮箱创建/恢复固定 Token，登记或重新启用设备 |
| GET | `/v1/devices` | 当前身份设备列表 |
| DELETE | `/v1/devices/{deviceId}` | 撤销指定设备，不删除身份或进度 |
| GET | `/v1/progress` | 只读拉取当前身份全部云端进度 |
| POST | `/v1/progress/sync` | 裁决 1–100 条候选；当前客户端产品范围只允许上传正在正式阅读的书 |
| DELETE | `/v1/progress/{bookHash}/{fileSize}` | 仅删除当前身份的指定书籍进度；成功或记录已不存在均返回 204 |

除 start-sync 外，以上接口同时要求 `Authorization: Bearer <token>` 与 `X-Device-Id: <uuid>`。Token 放在平台安全存储，不记录、不提交、不使用查询参数传输。Alpha 仅凭规范化邮箱恢复 Token，仍不是强身份认证。

## 阅读进度契约

- 书籍由完整文件 SHA-256 的小写 64 位十六进制哈希与文件大小标识；不上传正文、书名或路径。
- `offset` 是原始字节位置，范围为 0 到 fileSize，fileSize 必须大于 0；显示进度为二者比值。
- `readAtMs` 更新的候选获胜，即使位置更靠前；未来超过服务端时间五分钟时收敛到服务端时间，返回 timeAdjusted。
- 时间相同，设备公开 UUID 稳定字典序较大者获胜；同设备同时间同 offset 为 unchanged。同设备同时间不同 offset 保留既有状态，客户端实际改变位置时必须产生更新的阅读时间。
- 响应包含最终状态及 accepted / server_kept / unchanged。版本只用于相等性判断，不能以大小比较跨删除重建的记录。
- GET 无写入副作用；同步请求字段完整校验后才写入，重复 bookKey 拒绝。大于 100 条返回 413，字段业务校验失败返回 422。

## 单书删除与兼容性

bookHash 必须是小写 64 位十六进制，fileSize 路径参数为无前导零的正十进制整数，最大 9007199254740991。身份从认证获取，不能由路径或请求体覆盖。无效书籍标识返回 422；缺少路径段不匹配路由，绝不退化成全部删除。不需要请求体，兼容客户端发送空 JSON `{}`。

服务端事务内再次检查身份与设备有效性，按 user_id、book_hash、file_size 删除。不会删除其他书籍、身份、设备或任何本地数据。删除不是跨设备永久禁写，其他设备后续合法上传仍可能创建新记录。

旧 `DELETE /v1/progress` 与 `DELETE /v1/account` 已从当前源码及契约移除，调用返回 404；不提供兼容性的全量删除回退。这是删除接口的破坏性变更，已有部署不会因源码修改自动升级。Android/iOS 均已实现单书删除用户入口、删除后本次阅读会话暂停及关闭重开后的比较恢复；指定双端真机流程由用户确认通过，证据边界见 [P8 验收记录](architecture/p8-system-validation.md)。

## 非业务端点与错误

`GET /health` 无需认证，检查 API/数据库可用性。`GET /metrics` 使用独立 METRICS_TOKEN，只供内部监控。错误格式为 error 对象，包含 code、message、retryable、requestId；X-Request-Id 用于排障，不记录凭据或正文。

常见状态：400 格式/设备头错误，401 无效凭据，403 身份或设备不可用，404 资源/路由不存在，413 请求过大，422 校验失败，429 限流，500/503 服务失败。受保护请求认证先于业务字段校验。

部署与数据库操作见 [服务 README](../services/backend/README.md)，实现职责见 [同步服务设计](features/sync-server.md)。

## 契约回归入口

[共享 JSON 样例](../contracts/fixtures/README.md) 由 Backend、Android、iOS 按职责直接消费；`scripts/check-contract-fixtures.rb` 校验当前 OpenAPI Schema 的报文正例与反例，已接入 `make check` 和 CI。字段/行为差异及验证限制在该说明维护。Schema 验证不替代跨字段业务约束、数据库集成或真实跨设备测试。
