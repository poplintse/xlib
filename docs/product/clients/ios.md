# iOS Client

## Role

已有 Swift 原生 TXT 阅读客户端，自行定义界面和平台集成，不继承 Android 布局与交互。

## Implemented Capabilities

CAP-LIBRARY、CAP-READING、CAP-SEARCH、CAP-TOC、CAP-BOOKMARK、CAP-READING-PREFERENCES，以及 Shared Optional 的 CAP-SYNC-IDENTITY、CAP-PROGRESS-SYNC、CAP-CLOUD-DATA-CONTROL；candidate CAP-AUTO-PAGING 有实现。见 [矩阵](../matrix/capability-matrix.md)。

### Product Capability 实现

搜索与书签属于 A. Existing Product Capability；没有新增正式能力。

| Capability | iOS 设计与实现 | 验收重点 |
|---|---|---|
| CAP-SEARCH | 当前页作为固定原起点；每批最多 200 条，按钮继续加载；书末提供用户主动“从开头继续”，到原起点结束；新查询取消旧任务，失败可重试；结果进入临时阅读，隔离正式保存和同步事件 | 大小写、不重叠、跨段/跨批、200 条后的结束判定、手动回绕范围、UTF-16 字节游标、组合字符 2–32 计数 |
| CAP-BOOKMARK | 存储层按本地书籍 ID + 字节 offset 原子判重；重复提示“当前位置已有书签”；保留原记录；继续支持单条删除 | 重复位置、相邻位置、不同书同位置、持久化重开、单条删除和空列表 |

这些属于已有 Product Capability 的业务补齐。续查按钮、原生导航及异步任务取消属于 iOS 实现细节，不成为跨端 Feature。历史重复书签保持原样，不自动清理。

### Shared Optional Capability 实现（分类 D）

| Capability | iOS 设计与实现 | 验收重点 |
|---|---|---|
| CAP-SYNC-IDENTITY | 配置代际隔离旧登录、拉取、上传与设备响应；凭据清除/写入串行，配置修改立即使旧会话失效 | 延迟登录/拉取不能覆盖新配置，配置离线持久化 |
| CAP-PROGRESS-SYNC | 阅读前比较、选择和定位门控；提示本地与云端百分比；阅读内实际位移更新时间，接受远端保留远端时间；只上传活动正式阅读书籍；新导入/未知时间不上传 | 恢复/原地停留不改时间，准备期不上传，离线恢复先比较，拉取失败只本地阅读，无会话不补传 |
| CAP-CLOUD-DATA-CONTROL | 目录/书签页提供本书云端删除与原生确认；上传/删除串行；成功后暂停本次阅读上传，真正关闭重开才恢复 | 在途上传排序、同会话刷新/配置重建不解除暂停、失败不暂停、本地数据保留 |

书签重复错误传递至页面；导航中断加载后返回可恢复加载，离开阅读停止自动翻页。任务取消、凭据串行写入、隔离 UI 测试入口属于 F. Implementation Detail。既有自动化结果及真实服务验收边界见 [CURRENT](../../CURRENT.md)，不以隔离服务测试代替真实云端验收。

## Partial Capabilities

当前确认范围无已知未实现项；真实跨设备与发布验收仍待执行。

## 阅读时间与数据兼容

新导入书籍使用现有 Date 格式中的 Unix 时间 0 表示未知/未读，书架显示“暂无阅读记录”；未知时间不上传。打开后仍先比较云端，拒绝跳转继续保留未知时间，接受跳转保留云端真实时间，正式实际位移才生成本地阅读时间。导入伪阅读时间的实现冲突已修复，能力定义不变。

不更改 Schema、API 或已有历史时间；对早期版本产生的错误时间不做推测清零。数据基线覆盖未读值持久化/重开、元数据编辑不改时间、真实时间保存和重开。同步回归覆盖准备期、原地停留、首次位移以及较早的真实云端进度。验收记录见 [CURRENT](../../CURRENT.md)。

## Planned Capabilities

尚无明确的逐项新增能力承诺，不自动从其他客户端复制计划。

## Unsupported Capabilities

暂无明确排除的正式能力；缺失不等于 unsupported。身份删除已从产品范围排除，不另建客户端能力。

## Client-specific Features

本次盘点未确认独立的平台专属产品功能。系统文件选择、安全凭据存储及渲染技术属于能力实现，不单独创建 Feature。

## Implementation Notes

P4 将阅读同步会话、身份配置、串行副作用与正式进度时间规则提取为独立内部组件，属于 F. Implementation Detail。沿用同一能力及 API，不新增平台 Feature。临时页面离开只暂停，返回同一阅读会话仍保留云端删除后的上传暂停；旧页面回调不能修改同书的新会话。书籍边界裁剪后没有实际位移时不生成时间。旧服务器凭据清理与新登录保存按顺序执行。验证与未验收边界见 CURRENT。

P6 将书库、进度、书签、目录、非敏感设置与同步状态迁入 `xlib.db`；`LibraryStore`、`SettingsStore`、`SyncStateStore` 和 `SyncConfigurationSession` 通过 `LocalDatabase` 访问。TXT 仍在 Application Support，Credential 仍保留原 Keychain service/account。迁移失败回退 legacy，成功后不双写；legacy 清理由 P7 独立执行。这是 Implementation Detail，不改变 Capability 或同步契约。

P7.0 已确认当前迁移版本尚未发布且已登记真机处于 offline，因此旧运行时分支、设备 legacy 数据和 migrator 均暂留。清理顺序及永久保留的 Keychain/TXT 边界见 [Legacy Persistence Cleanup](../../architecture/legacy-persistence-cleanup.md)。

SwiftUI / UIKit / Core Text 与 Keychain 是平台实现；AppleShared 只容纳 UI 无关代码。当前前台单一 20 秒调度，后台仅尽力执行，不承诺持续运行。存储约束见 [Local Storage Contract](../../architecture/local-storage-contract.md)，细节见 [iOS 实现说明](../../features/ios.md)。

无阅读会话的全书库上传路径已移除，不建立离线退出后的补传队列。完整验证边界见 [CURRENT](../../CURRENT.md)。
