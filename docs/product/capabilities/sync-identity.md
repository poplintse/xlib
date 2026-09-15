# CAP-SYNC-IDENTITY

Status: active
Type: Shared Optional Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

为选择同步的用户关联设备与云端阅读进度。

## Business Behavior

通过邮箱创建或恢复同步身份，登记当前设备，列出并撤销设备。

## Rules

Alpha 按规范化邮箱恢复固定 Token，不是强身份认证。撤销设备不删除阅读进度；再次开始同步可以重新登记。配置生效策略已由 [Decision 0004](../decisions/0004-sync-configuration-auto-apply.md) 确认：首次手动开启；成功开启后，有效配置修改自动应用。旧会话及其云端状态失效，保留本地书籍和进度，按最新有效配置建立新会话；先拉取并完成必要比较与选择，再上传本书进度。不得复用旧凭据到新服务器，不得接受旧请求结果覆盖新会话；配置变化不刷新阅读时间。

当前产品不提供删除整个同步身份的功能或接口；切换邮箱只切换本机会话，保留原身份及云端数据。DELETE /v1/account 已从当前源码与契约移除，见 [Decision 0011](../decisions/0011-no-sync-identity-deletion.md)。设备撤销仍保留。

## Data

规范化邮箱、同步凭据、设备标识、设备名称、设备撤销状态；不包含书籍正文。

## API / Contract

POST /v1/auth/start-sync；GET /v1/devices；DELETE /v1/devices/{deviceId}。受保护接口使用 Bearer Token 与 X-Device-Id。合同目前只接受 ios/android 平台。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

无效配置、设备撤销、Token 无效、服务不可达；旧凭据不得发送到新服务器。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | done | 首次手动开启、有效配置自动重建、旧凭据与异步提示隔离；见客户端验证说明 |
| iOS | partial | 以客户端文档及已知差异为准 |
| Backend | implemented | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [docs/API.md](../../../docs/API.md)
- [docs/DECISIONS.md](../../../docs/DECISIONS.md)
- [services/backend/src/auth-service.ts](../../../services/backend/src/auth-service.ts)
- [apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift](../../../apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift)
- [apps/android/app/src/main/java/com/xlib/txtreader/ProgressSyncCoordinator.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/ProgressSyncCoordinator.java)

## Related Capabilities

CAP-PROGRESS-SYNC、CAP-CLOUD-DATA-CONTROL，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
