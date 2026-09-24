# XLib 同步客户端实现索引

本文件只记录客户端实现衔接，跨客户端业务定义集中于 [CAP-SYNC-IDENTITY](../product/capabilities/sync-identity.md)、[CAP-PROGRESS-SYNC](../product/capabilities/progress-sync.md) 和 [CAP-CLOUD-DATA-CONTROL](../product/capabilities/cloud-data-control.md)。历史的 offset 优先、配置手动重启及全量删除流程已移除，不再作为当前需求。

## 实现与产品边界

| 部分 | 统一定义 | 当前实现说明 |
|---|---|---|
| 配置 | 首次手动开启，之后有效修改自动应用；旧会话失效 | Android/iOS 均已实现配置自动应用、旧请求与旧提示隔离，并有回归测试 |
| 阅读阶段 | 先比较、提示与定位，再记录正式阅读事件 | Android/iOS 均已实现阶段门控与离线恢复先比较；指定双端真机接续流程由用户确认通过 |
| 上传范围 | 仅当前正式阅读书籍，离线结束不补传 | 两端均限制为活动正式阅读会话；iOS 空闲期全书库上传路径已移除 |
| 跳转 | 展示当前进度与目标进度，用户确认才跳转 | 两端均已显示双方进度；接受/拒绝流程由用户在指定双端真机运行中确认 |
| 删除 | 单书删除，当前会话暂停，退出重开比较后恢复 | 两端均有用户入口、串行删除和会话暂停/重开恢复；指定双端真机流程由用户确认通过 |

## 代码入口

Android：[ProgressSyncCoordinator](../../apps/android/app/src/main/java/com/xlib/txtreader/ProgressSyncCoordinator.java)、[SyncApiClient](../../apps/android/app/src/main/java/com/xlib/txtreader/SyncApiClient.java)、[SyncRules](../../apps/android/app/src/main/java/com/xlib/txtreader/SyncRules.java)。

iOS：[ProgressSyncCoordinator](../../apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift)、[SyncAPIClient](../../apps/ios/XLibReader/Sync/SyncAPIClient.swift)。

两端 deleteBookProgress 必须收到完整文件身份；不存在不带书籍身份的删除传输方法。旧的全量删除协调器方法和无调用入口的 Android 删除确认方法已经移除。不能仅因为方法存在就记为用户已交付功能。

## 平台实现职责

周期调度、应用生命周期、凭据安全存储、哈希计算、配置输入页面和错误呈现由各端维护。20 秒调度是当前实现参数，不是对其他客户端或系统后台执行的承诺。

新会话必须先拉取；旧请求不能覆盖新账号状态。Android 的单书删除与上传使用同一串行执行器，暂停状态属于阅读会话，不随配置、刷新或网络恢复重置；两端均有协调器回归测试。0.11.0 使用同一非生产身份的真机跨设备接续由用户确认通过，未提供逐项脱敏日志；本仓库的 0.11.0 发布清单仍为 draft。不能由传输层接口签名代替客户端验收。

具体平台说明见 [Android](../product/clients/android.md)、[iOS](../product/clients/ios.md)；统一缺口及验收状态见 [CURRENT](../CURRENT.md)。
