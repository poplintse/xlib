# Backend Implementation

## Role

同步能力的服务提供方，不承担终端阅读界面。源码位于 [services/backend](../../../services/backend/)。

## Implemented Capabilities

CAP-SYNC-IDENTITY、CAP-PROGRESS-SYNC、CAP-CLOUD-DATA-CONTROL 的服务端职责已有实现。单书删除路由及传输契约已替换旧全量删除与身份删除；当前源码状态不代表生产已部署。见 [矩阵](../matrix/capability-matrix.md)。

## Partial Capabilities

暂无额外服务范围缺口；隔离 PostgreSQL 17 集成测试已通过，指定双端真机接续由用户确认通过，证据边界见 [P8 验收记录](../../architecture/p8-system-validation.md)。服务不能代替客户端验证阅读阶段和删除后的本次会话暂停。

## Planned Capabilities

更强身份认证是方向，方案未定，不据此创建密码/验证码能力。

## Unsupported Capabilities

当前服务不提供本地书库、正文阅读、书内搜索、目录、书签、阅读偏好或自动翻页。不提供身份删除和全量进度清空接口。

## Client-specific Features

没有终端专属功能；健康检查、指标、限流、备份与恢复属于服务运维。

## Implementation Notes

TypeScript / Fastify / PostgreSQL；事务、用户行锁、条件更新和凭据加密属实现细节。[OpenAPI](../../../contracts/openapi.yaml) 是机器契约，[API](../../API.md) 是集中说明；部署步骤仅维护在 [服务 README](../../../services/backend/README.md)。Alpha 邮箱恢复固定 Token 风险仍存在。
