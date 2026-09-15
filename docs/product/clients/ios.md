# iOS Client

## Role

已有 Swift 原生 TXT 阅读客户端，自行定义界面和平台集成，不继承 Android 布局与交互。

## Implemented Capabilities

CAP-LIBRARY、CAP-READING、CAP-TOC、CAP-READING-PREFERENCES。candidate CAP-AUTO-PAGING 有实现。见 [矩阵](../matrix/capability-matrix.md)。

## Partial Capabilities

- CAP-SEARCH：不区分大小写、不重叠和 String.count 方向一致；分批续查、书末后回绕及组合字符边界验收待补齐。
- CAP-BOOKMARK：单条删除已有，同位置唯一待实现；历史重复记录未自动删除。
- CAP-SYNC-IDENTITY：有效配置自动应用方向一致；旧请求隔离与新会话状态仍需验证。
- CAP-PROGRESS-SYNC：当前进度提示、阅读前阶段及时间生成需完善；无会话全书库上传仍需移除。
- CAP-CLOUD-DATA-CONTROL：新单书删除传输方法已适配，旧全量删除方法已移除；用户入口、会话暂停和重开恢复待实现。

## Planned Capabilities

尚无明确的逐项新增能力承诺，不自动从其他客户端复制计划。

## Unsupported Capabilities

暂无明确排除的正式能力；缺失不等于 unsupported。身份删除已从产品范围排除，不另建客户端能力。

## Client-specific Features

本次盘点未确认独立的平台专属产品功能。系统文件选择、安全凭据存储及渲染技术属于能力实现，不单独创建 Feature。

## Implementation Notes

SwiftUI / UIKit / Core Text 与 Keychain 是平台实现；AppleShared 只容纳 UI 无关代码。当前前台单一 20 秒调度，后台仅尽力执行，不承诺持续运行。细节见 [iOS 实现说明](../../features/ios.md)。

syncStoredReadingProgress 仍将书库装为单请求，未按 bookKey 去重或 100 条拆分；该路径违反已确认上传范围，应移除，不为保留它扩展批量功能。完整缺口与验证见 [CURRENT](../../CURRENT.md)。
