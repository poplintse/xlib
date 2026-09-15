# XLib 同步服务实现说明

本文件只维护服务端实现职责；产品规则见 [能力体系](../product/README.md)，HTTP 字段与路径见 [OpenAPI](../../contracts/openapi.yaml) 和 [API](../API.md)。不再复制数据库 Schema、请求样例和客户端交互需求。

## 当前实现

- TypeScript / Fastify / PostgreSQL 提供固定 Token 身份、设备管理、进度拉取/裁决和单书删除。
- 进度按有效 readAtMs 优先裁决，允许回读后的新位置覆盖旧的较远位置；不是最远位置模型。相同时间按公开 deviceId 稳定排序，规则详见 API。
- 进度更新与删除在事务内重新校验身份/设备，并锁定用户记录。查询必须限定当前 user_id，单书删除额外限定 bookHash 与 fileSize。
- GET 拉取无写入进度副作用；POST 完整校验候选后按稳定顺序写入，返回输入顺序对应的最终状态。
- 单书删除幂等返回 204。全量进度清空和身份删除不再暴露路由。
- 不接收 TXT 正文、书名、路径、书签、目录或显示设置；书籍哈希仍属于用户数据。

## 安全与运行

固定 Token 摘要用于认证，加密密文用于同邮箱恢复，不存明文。start-sync 按 IP 与规范化邮箱限流，业务操作按 Token/设备限流。数据库迁移使用 owner，API 使用受限账号；请求日志不包含 Token、邮箱或正文。

邮箱即可恢复凭据的风险仍然存在，单书删除范围控制不提升身份认证强度。服务端不能凭同步请求判断客户端是否正在正式阅读，客户端必须遵守已确认的阶段和上传范围。

数据库表结构以 [迁移](../../services/backend/migrations/) 为准，原子裁决与删除以 [ProgressService](../../services/backend/src/progress-service.ts) 为准。备份、恢复和部署步骤仅在 [服务 README](../../services/backend/README.md) 维护，避免两份操作文档漂移。

## 验证边界

路由与契约检查、参数校验、鉴权顺序及旧接口 404 已纳入测试；单书范围、不同文件大小、其他身份数据保留和撤销设备拒绝写入由专用 PostgreSQL 集成测试覆盖。集成测试必须使用专用测试数据库，未运行时不能宣称真实数据库验收通过。完整客户端状态见 [CURRENT](../CURRENT.md)。
