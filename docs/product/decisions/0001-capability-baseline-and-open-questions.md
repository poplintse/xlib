# Decision 0001：能力初始化与差异处理索引

初始盘点：2026-09-14；本记录整理于 2026-09-15。状态：分类基线 accepted，已发现的主要业务差异已有后续用户决定；实现验收另见 CURRENT。

## Context

既有文档混合 Android 规范、iOS 当前行为与跨端目标。两端代码可提供证据，但不能直接成为新客户端完整需求。

初次盘点发现最远位置旧文档与时间优先代码、同步配置应用、上传范围、搜索和书签差异；还将 Android 未被调用的云端删除确认方法误计为用户入口，后续调用链检查已纠正。

## Decision

抽象有业务意义的能力，保留原生客户端实现自由。离线阅读由愿景确定为产品前提，归入 CAP-READING；同步为 Shared Optional。自动翻页仍为 candidate；单书云端删除经用户确认后已升为 active。

不为缓存、字节映射、渲染引擎、调度周期和安全存储创建业务能力。macOS 只有基础阅读/书库方向，其余未评估；Web/Windows 未立项。

## Reason

能力是业务语义，不是函数列表。争议规则需用户决定；实现缺口不能通过改写需求掩盖。

## Consequences

| 差异 | 用户已确认的决定 | 当前实现跟踪 |
|---|---|---|
| 同步裁决 | [0002](0002-latest-reading-time-wins.md) 最近阅读时间优先，提示当前进度 | 服务及 Android/iOS 已实现；0.11.0 双端真机接续由用户确认通过，未提供逐项运行日志 |
| 阅读前与恢复 | [0003](0003-pre-reading-progress-comparison.md) 先比较定位，再正式阅读 | 两端已有阶段门控；iOS 已修复新导入时间进入同步，增加未读/首次位移及旧云端进度回归；指定真机生命周期流程由用户确认通过 |
| 配置应用 | [0004](0004-sync-configuration-auto-apply.md) 首次手动、之后有效修改自动应用 | Android/iOS 已补齐并有协调器回归测试 |
| 上传范围 | [0005](0005-sync-only-current-reading-book.md) 仅当前正式阅读书籍 | iOS 全书库上传已移除，仅活动正式阅读书籍上传 |
| 搜索 | [0006](0006-search-case-insensitive.md)、[0007](0007-search-non-overlapping-matches.md)、[0012](0012-search-results-in-batches.md)、[0013](0013-search-wrap-with-user-choice.md)、[0014](0014-search-query-character-count.md) | Android/iOS 已补齐续查、手动回绕及边界测试 |
| 书签 | [0008](0008-bookmark-position-uniqueness.md) 同位置唯一；[0009](0009-delete-individual-bookmark.md) 单条删除 | Android/iOS 已补齐去重及单条删除，iOS 重复提示已接入页面 |
| 云端删除 | [0010](0010-delete-cloud-progress-per-book.md) 仅单书，删除后会话暂停；[0011](0011-no-sync-identity-deletion.md) 不提供身份删除 | Android/iOS 入口、串行删除及会话暂停/重开恢复已实现 |

具体证据在各能力 Source 与客户端文档，完整缺口和检查状态只在 [CURRENT](../../CURRENT.md) 汇总。尚未明确的是正文复制与自动翻页跨端范围、批量导入与搜索起点选择的共同范围、章节一致性、空文件/重复导入/历史重复书签处理、量化性能目标、新端同步范围及更强认证方案。
