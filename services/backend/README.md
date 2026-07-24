# XLib Sync Server

可独立部署的 XLib 阅读进度同步 API。实现以 [`contracts/openapi.yaml`](../../contracts/openapi.yaml) 和 [`docs/features/sync-server.md`](../../docs/features/sync-server.md) 的第一阶段语义为准；服务只保存规范化邮箱、同步 Token、设备元数据和 TXT 的哈希/文件大小/阅读 offset，不接收原文件、书名、正文或本地路径。

## 认证边界

第一阶段没有密码、注册/登录区分、Access Token、Refresh Token、刷新或服务端 logout。用户输入邮箱调用 `POST /v1/auth/start-sync`：

- 新邮箱生成至少 256 bit 的固定随机同步 Token；
- 已有邮箱解密并返回完全相同的 Token；
- 同一邮箱的所有设备共享该 Token；
- Token 摘要用于 Bearer 校验，AES-256-GCM 密文用于同邮箱恢复，数据库不保存明文；
- 除 `start-sync` 和 `/health` 外，客户端请求同时携带 `Authorization: Bearer <token>` 与 `X-Device-Id: <uuid>`。

仅凭邮箱即可恢复 Token 不是强身份认证，这是当前产品明确接受的简化风险。因此服务不保存 TXT 正文等敏感内容，`start-sync` 按 IP 和规范化邮箱限流，业务接口按 Token 与设备限流。

## 功能

- 固定 Token 发放/恢复、设备登记、列表、撤销和重新启用。
- PostgreSQL 条件 UPSERT 是唯一进度裁决点；时间相同时按公开 `deviceId` UUID 稳定排序。
- 单请求最多 100 条，单邮箱最多 10,000 本；批量完整校验后在短事务中稳定排序处理。
- 未来超过五分钟的 `readAtMs` 收敛到服务端时间。
- 云端进度删除和同步身份级联删除，不影响客户端本地书籍。
- 统一错误格式、请求 ID、限流、结构化访问日志、健康检查和 Prometheus 指标。
- Caddy HTTPS、受限数据库账号、迁移、备份和恢复演练脚本。

## 接口

基础路径为 `/v1`：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/auth/start-sync` | 按邮箱创建或恢复固定 Token，并登记/重新启用设备 |
| `GET` | `/devices` | 列出当前邮箱的设备 |
| `DELETE` | `/devices/{deviceId}` | 撤销目标设备，不改变共享 Token 或进度 |
| `DELETE` | `/account` | 删除邮箱、Token、设备和全部云端进度 |
| `GET` | `/progress` | 只读拉取全部云端进度 |
| `POST` | `/progress/sync` | 原子裁决一至 100 条进度 |
| `DELETE` | `/progress` | 删除当前邮箱的全部云端进度 |

`DELETE /v1/account` 和 `DELETE /v1/progress` 不接收密码或 JSON 请求体，固定 Token 即授权凭据。客户端必须在调用前提供明确的破坏性确认。

另有无需同步 Token 的 `GET /health`。`GET /metrics` 使用独立 `METRICS_TOKEN`，且默认被 Caddy 从公网阻断，仅供容器网络内监控采集。

## 本地开发

需要 Node.js 22+、pnpm 11+ 和 PostgreSQL 17+。

```bash
cp ../../.env.example ../../.env
# 生成 TOKEN_ENCRYPTION_KEY：openssl rand -base64 32
pnpm install
pnpm migrate
pnpm dev
```

`TOKEN_ENCRYPTION_KEY` 必须是 base64 编码的 32 字节主密钥。丢失或错误更换该密钥会导致已有固定 Token 无法恢复；它必须只存在于 VPS 密钥配置和安全备份中，不能提交仓库。

迁移必须用表所有者连接执行；API 的 `DATABASE_URL` 使用受限 `xlib_api` 账号。Docker Compose 会自动用 owner 账号迁移，再以 app 账号启动 API。

旧的密码/会话模型与固定 Token 模型不兼容。迁移会主动检测并拒绝在旧实验 schema 上静默启动，不会自动删除旧数据；如曾运行旧版本，必须先备份，再显式迁移或重建该未发布环境。

验证命令：

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

真实 PostgreSQL 集成测试默认跳过；为专用测试数据库设置 `TEST_DATABASE_URL` 后运行：

```bash
TEST_DATABASE_URL=postgresql://... pnpm test:integration
```

测试会创建随机 schema，并在结束时删除该 schema；不要把生产数据库用于测试。

## Docker Compose 部署

在 `/opt/apps/xlib-sync/repo/services/backend`：

```bash
cp ../../.env.example ../../.env
# 填写域名、数据库密码、TOKEN_ENCRYPTION_KEY 和至少 32 字符的 METRICS_TOKEN
docker compose --env-file ../../.env -f compose.yaml config
docker compose --env-file ../../.env -f compose.yaml build
docker compose --env-file ../../.env -f compose.yaml up -d
```

Compose 只公开 Caddy 的 80/443；API 和 PostgreSQL 仅存在于内部网络，PostgreSQL 数据保存在命名卷。生产部署、重启、迁移和回滚仍需逐次确认。

备份目录默认对应 `/opt/apps/xlib-sync/backups`。建议由 VPS cron 每日执行：

```bash
/opt/apps/xlib-sync/repo/services/backend/scripts/backup.sh
```

数据库备份不包含 `.env` 中的 `TOKEN_ENCRYPTION_KEY`，必须另行安全备份该密钥。定期选择一个备份执行临时数据库恢复演练：

```bash
./scripts/restore-drill.sh xlib-sync-20260722T120000Z.dump
```

恢复演练只创建并删除名称带 `xlib_restore_drill_` 的临时数据库，不覆盖正式数据库。备份保留策略、异地复制和监控告警由 VPS 运维配置管理。
