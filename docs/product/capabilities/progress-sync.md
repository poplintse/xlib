# CAP-PROGRESS-SYNC

Status: active
Type: Shared Optional Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

在拥有同一 TXT 的设备间继续阅读，仅交换进度。

## Business Behavior

打开书籍先进入阅读前准备：保留原有本地位置与时间，取得并比较云端状态，完成必要的提示、用户选择和定位后，才进入正式阅读。准备期间不更新时间、不上传本书进度。远端响应不能未经用户确认改变当前阅读页。详见 [Decision 0003](../decisions/0003-pre-reading-progress-comparison.md)。

## Rules

文件身份为完整文件 SHA-256 + fileSize。按用户确认的 [Decision 0002](../decisions/0002-latest-reading-time-wins.md)，readAtMs 更新的状态优先，即使其 offset 更小；offset 仍是精确位置真值。时间相同沿用现有确定性裁决。

每次跳转提示必须同时展示本地“当前阅读进度 xx%”和远端目标进度，当前值取提示时保留的本地阅读位置；用户确认后才能跳转。百分比由 offset / fileSize 派生，与阅读界面精度一致；平台自行选择布局。实现缺口见 Client Implementations。

正式阅读后，实际改变位置的翻页或主动跳转才更新 readAtMs；仅打开、拉取、周期上传、原地停留和搜索临时阅读不更新。接受远端跳转保留远端时间；选择不跳转保留本地原时间，阶段切换不刷新时间。

只上传当前活动正式阅读会话的书籍，不在书架或其他无阅读会话场景上传保存的书库进度。离线阅读结束后不补传；若恢复网络时仍在阅读，先比较再恢复本书上传。重新打开书籍须重新完成阅读前流程。见 [Decision 0005](../decisions/0005-sync-only-current-reading-book.md)。

单书云端进度删除成功后，该客户端本次阅读会话暂停该书上传，本地阅读与时间记录继续；退出并重新打开、完成阅读前比较后才恢复同步。手动刷新、网络恢复或同步会话重建不得绕过暂停，见 [Decision 0010](../decisions/0010-delete-cloud-progress-per-book.md)。

## Data

文件哈希、大小、offset、readAtMs、云端版本和来源设备；不上传正文、书名、路径、目录、书签或偏好。

## API / Contract

GET /v1/progress 为 pull-only；POST /v1/progress/sync 为 1–100 条且 bookKey 不重复。0 <= offset <= fileSize，fileSize > 0；结果返回 accepted/server_kept/unchanged 和最终状态。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

离线、并发、回读、未来时间、首次比较、批量上限；超前超过五分钟的时间由服务端调整。时间更新及阅读前比较边界已由 Decision 0003 确认；离线或服务不可用时从本地位置正式阅读，实际改变位置正常记录时间；恢复后先拉取并比较最新双方状态，完成必要提示与用户选择后才恢复本书上传，不自动跳页、不因恢复或上传刷新阅读时间。拉取失败继续本地阅读但不恢复上传。无会话不上传，离线阅读结束后不补传；上述恢复上传仅适用于仍在阅读的书籍，见 Decision 0005；时间优先已由 Decision 0002 确认。Android/iOS 均已补齐双进度提示，真实跨设备验收仍待执行。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | done | 正式阅读阶段门控、时间优先、双进度提示、离线恢复先比较、仅活动书籍上传；真实设备联调待验收 |
| iOS | done | 阅读前比较/定位门控、精确时间保留、双进度提示、仅活动书籍上传及离线恢复测试通过 |
| Backend | implemented | 时间优先裁决已有；客户端阅读阶段需各端保证 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [docs/DECISIONS.md](../../../docs/DECISIONS.md)
- [contracts/openapi.yaml](../../../contracts/openapi.yaml)
- [services/backend/src/arbitration.ts](../../../services/backend/src/arbitration.ts)
- [services/backend/src/progress-service.ts](../../../services/backend/src/progress-service.ts)
- [apps/android/app/src/main/java/com/xlib/txtreader/SyncRules.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/SyncRules.java)
- [apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift](../../../apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift)

## Related Capabilities

CAP-READING、CAP-SYNC-IDENTITY，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
