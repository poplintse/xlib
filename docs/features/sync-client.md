# XLib 同步客户端实现索引

本文件只记录客户端实现衔接，跨客户端业务定义集中于 [CAP-SYNC-IDENTITY](../product/capabilities/sync-identity.md)、[CAP-PROGRESS-SYNC](../product/capabilities/progress-sync.md) 和 [CAP-CLOUD-DATA-CONTROL](../product/capabilities/cloud-data-control.md)。历史的 offset 优先、配置手动重启及全量删除流程已移除，不再作为当前需求。

## 实现与产品边界

| 部分 | 统一定义 | 当前实现说明 |
|---|---|---|
| 配置 | 首次手动开启，之后有效修改自动应用；旧会话失效 | Android 已实现自动重建及旧提示隔离；iOS 方向一致，符合性待验证 |
| 阅读阶段 | 先比较、提示与定位，再记录正式阅读事件 | Android 已有门控和断网恢复回归测试，真机待验收；iOS 仍需核对 |
| 上传范围 | 仅当前正式阅读书籍，离线结束不补传 | Android 有活动会话限制；iOS 空闲期全书库上传仍待移除 |
| 跳转 | 展示当前进度与目标进度，用户确认才跳转 | Android 已补齐；iOS 当前位置提示待补齐 |
| 删除 | 单书删除，当前会话暂停，退出重开比较后恢复 | Android 已实现入口及会话行为；iOS 仅有传输层，入口及会话行为待补齐 |

## 代码入口

Android：[ProgressSyncCoordinator](../../apps/android/app/src/main/java/com/xlib/txtreader/ProgressSyncCoordinator.java)、[SyncApiClient](../../apps/android/app/src/main/java/com/xlib/txtreader/SyncApiClient.java)、[SyncRules](../../apps/android/app/src/main/java/com/xlib/txtreader/SyncRules.java)。

iOS：[ProgressSyncCoordinator](../../apps/ios/XLibReader/Sync/ProgressSyncCoordinator.swift)、[SyncAPIClient](../../apps/ios/XLibReader/Sync/SyncAPIClient.swift)。

两端 deleteBookProgress 必须收到完整文件身份；不存在不带书籍身份的删除传输方法。旧的全量删除协调器方法和无调用入口的 Android 删除确认方法已经移除。不能仅因为方法存在就记为用户已交付功能。

## 平台实现职责

周期调度、应用生命周期、凭据安全存储、哈希计算、配置输入页面和错误呈现由各端维护。20 秒调度是当前实现参数，不是对其他客户端或系统后台执行的承诺。

新会话必须先拉取；旧请求不能覆盖新账号状态。Android 的单书删除与上传使用同一串行执行器，暂停状态属于阅读会话，不随配置、刷新或网络恢复重置；已补齐协调器测试，真实服务联调仍待执行。不能由传输层接口签名代替客户端验收。

具体平台说明见 [Android](../product/clients/android.md)、[iOS](../product/clients/ios.md)；统一缺口及验收状态见 [CURRENT](../CURRENT.md)。
