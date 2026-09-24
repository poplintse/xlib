# CAP-CLOUD-DATA-CONTROL

Status: active
Type: Shared Optional Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

允许用户删除指定单本书的云端阅读进度。

## Business Behavior

选择单本书并删除其云端进度；不提供当前身份下所有书籍进度的批量清空。用户入口及阅读会话暂停实现进度见 Client Implementations。

## Rules

删除以当前认证身份和 bookHash + fileSize 限定范围，不影响其他书籍云端进度、身份、设备、本地书籍、书签或阅读位置与时间。缺少书籍标识不得退化为全量删除。用户入口需明确选中书籍及云端删除范围。见 [Decision 0010](../decisions/0010-delete-cloud-progress-per-book.md)。

删除成功后，发起删除客户端在本次阅读会话暂停该书上传，本地阅读和进度保存继续。周期同步、手动刷新、网络恢复或同步会话重建不解除暂停；退出该书并重新打开，完成阅读前比较、必要选择及定位后，新正式阅读会话才恢复同步。非当前阅读书籍不产生补传任务。本规则不代表其他设备的阅读会话被全局暂停。

## Data

当前认证身份、目标书的文件哈希与大小、该书云端进度；设备和本地数据不在删除范围。

## API / Contract

`DELETE /v1/progress/{bookHash}/{fileSize}` 已在后端与 OpenAPI 实现，删除范围为认证身份与指定文件。记录不存在也返回 204；无效书籍身份不执行删除。详细参数和兼容性见 [API](../../API.md)。

全量进度清空与身份删除路由已从当前源码和契约移除；不能用其他接口模拟全量删除。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

本次阅读会话暂停及重新打开后恢复条件已确认。实现需处理本客户端在途上传与删除的竞争，避免旧请求在删除成功后重建记录。其他边界包括书籍身份无效、记录不存在、请求重复以及不同身份拥有同一文件。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 单书删除入口、上传/删除串行、会话暂停及关闭重开恢复，包含协调器回归测试 |
| iOS | implemented | 本书删除入口及确认、上传/删除串行、阅读会话暂停与关闭重开恢复；协调器和隔离 UI 测试通过，指定双端真机单书删除流程由用户确认通过 |
| Backend | implemented | 单书范围限定和幂等 204 已实现，运行验收见 CURRENT |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

后端 PostgreSQL 集成测试覆盖并发和身份范围；指定双端真机单书删除流程由用户确认通过，未提供逐项脱敏运行日志，见 [P8 验收记录](../../architecture/p8-system-validation.md)。

## Source

- [docs/API.md](../../../docs/API.md)
- [services/backend/src/app.ts](../../../services/backend/src/app.ts)
- [apps/android/app/src/main/java/com/xlib/txtreader/MainActivity.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/MainActivity.java)
- [apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift](../../../apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift)

## Related Capabilities

CAP-SYNC-IDENTITY、CAP-PROGRESS-SYNC，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
