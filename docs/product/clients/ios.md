# iOS Client

## Role

已有 Swift 原生 TXT 阅读客户端，自行定义界面和平台集成，不继承 Android 布局与交互。

## Implemented Capabilities

CAP-LIBRARY、CAP-READING、CAP-SEARCH、CAP-TOC、CAP-BOOKMARK、CAP-READING-PREFERENCES，以及 Shared Optional 的 CAP-SYNC-IDENTITY、CAP-PROGRESS-SYNC、CAP-CLOUD-DATA-CONTROL。candidate CAP-AUTO-PAGING 有实现。见 [矩阵](../matrix/capability-matrix.md)。

### 本版本 Product Capability 补齐（2026-09-15）

按能力 Type 与矩阵盘点，正式 Product Capability 的缺口为 CAP-SEARCH、CAP-BOOKMARK；其余四项已有实现。同步三项 Shared Optional Capability 于 2026-09-16 补齐。

| Capability | iOS 设计与实现 | 验收重点 |
|---|---|---|
| CAP-SEARCH | 当前页作为固定原起点；每批最多 200 条，按钮继续加载；书末提供用户主动“从开头继续”，到原起点结束；新查询取消旧任务，失败可重试；结果进入临时阅读，隔离正式保存和同步事件 | 大小写、不重叠、跨段/跨批、200 条后的结束判定、手动回绕范围、UTF-16 字节游标、组合字符 2–32 计数 |
| CAP-BOOKMARK | 存储层按本地书籍 ID + 字节 offset 原子判重；重复提示“当前位置已有书签”；保留原记录；继续支持单条删除 | 重复位置、相邻位置、不同书同位置、持久化重开、单条删除和空列表 |

这些属于已有 Product Capability 的业务补齐。续查按钮、原生导航及异步任务取消属于 iOS 实现细节，不成为跨端 Feature。历史重复书签保持原样，不自动清理。

### 同步能力补齐（2026-09-16）

| Capability | iOS 设计与实现 | 验收重点 |
|---|---|---|
| CAP-SYNC-IDENTITY | 配置代际隔离旧登录、拉取、上传与设备响应；凭据清除/写入串行，配置修改立即使旧会话失效 | 延迟登录/拉取不能覆盖新配置，配置离线持久化 |
| CAP-PROGRESS-SYNC | 阅读前比较、选择和定位门控；提示本地与云端百分比；仅实际位移更新时间，接受远端保留远端时间；只上传活动正式阅读书籍 | 恢复/原地停留不改时间，准备期不上传，离线恢复先比较，拉取失败只本地阅读，无会话不补传 |
| CAP-CLOUD-DATA-CONTROL | 目录/书签页提供本书云端删除与原生确认；上传/删除串行；成功后暂停本次阅读上传，真正关闭重开才恢复 | 在途上传排序、同会话刷新/配置重建不解除暂停、失败不暂停、本地数据保留 |

书签重复错误已从存储层传递至页面，不再被静默吞掉。2026-09-17 补齐阅读加载中断后返回的恢复处理及隔离 UI 测试：70 项单元测试、11 项 UI 测试均有通过记录，Debug/不签名 Release 模拟器构建通过。UI 已覆盖书签去重删除、搜索临时阅读隔离、单书云端删除确认/取消和本地数据保留；删除采用内存服务，真实云端删除和跨设备验收未执行，详见 [CURRENT](../../CURRENT.md)。

## Partial Capabilities

当前已确认范围内无已知未实现项；不代表全部发布验收已完成。

## Planned Capabilities

尚无明确的逐项新增能力承诺，不自动从其他客户端复制计划。

## Unsupported Capabilities

暂无明确排除的正式能力；缺失不等于 unsupported。身份删除已从产品范围排除，不另建客户端能力。

## Client-specific Features

本次盘点未确认独立的平台专属产品功能。系统文件选择、安全凭据存储及渲染技术属于能力实现，不单独创建 Feature。

## Implementation Notes

SwiftUI / UIKit / Core Text 与 Keychain 是平台实现；AppleShared 只容纳 UI 无关代码。当前前台单一 20 秒调度，后台仅尽力执行，不承诺持续运行。细节见 [iOS 实现说明](../../features/ios.md)。

无阅读会话的全书库上传路径已移除，不建立离线退出后的补传队列。完整验证边界见 [CURRENT](../../CURRENT.md)。
