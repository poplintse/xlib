# Android Client

## Role

已有 Java 原生 TXT 阅读客户端，是已验证行为参考，不是其他客户端的完整需求源。

## Implemented Capabilities

CAP-LIBRARY、CAP-READING、CAP-SEARCH、CAP-TOC、CAP-BOOKMARK、CAP-READING-PREFERENCES；本版本同时实现 Shared Optional Capability：CAP-SYNC-IDENTITY、CAP-PROGRESS-SYNC、CAP-CLOUD-DATA-CONTROL。candidate CAP-AUTO-PAGING 有实现但无跨端交付承诺。见 [矩阵](../matrix/capability-matrix.md)。done 表示代码及自动化覆盖，不等于真机或发布验收通过。

## Partial Capabilities

本次明确范围内的五项缺口已补齐；真实设备、真实服务和跨设备并发仍需验收，不将未验证环境写成已通过。

## 本轮设计与自动化覆盖

| Capability | Android 实现 | 回归测试 |
|---|---|---|
| CAP-SEARCH | ICU 扩展字素簇计数；原文大小写不敏感、不重叠匹配，保留字节定位；200 条分批续查及用户手动回绕 | SearchTextRulesTest、ReaderTextSearchTest |
| CAP-BOOKMARK | 书签列表提供单条删除确认；按具体记录删除，不触碰阅读位置/时间 | BookmarkStoreTest |
| CAP-SYNC-IDENTITY | 首次手动开启；后续有效配置自动重建，排队时读取最新配置，旧凭据/缓存/提示失效 | ProgressSyncCoordinatorTest |
| CAP-PROGRESS-SYNC | 定位与比较完成后进入正式阅读；仅实际位移生成时间；远端跳转保留远端时间；离线恢复先比较最新本地状态 | FormalReadingProgressTest、ReadingSyncPhaseTest、SyncRulesTest、ProgressSyncCoordinatorTest |
| CAP-CLOUD-DATA-CONTROL | 书籍编辑和当前书同步设置提供单书删除；上传/删除串行；本次阅读会话持续暂停上传，关闭重开才解除 | SyncApiClientTest、ReadingSyncPhaseTest、ProgressSyncCoordinatorTest |

阅读同步仅针对当前前台、活动、非临时搜索的正式阅读书籍；后台和退出后不补传。配置重建、刷新、网络恢复都不能解除单书删除后的上传暂停。未开启同步不依赖后端即可阅读。

## Planned Capabilities

尚无明确的逐项新增能力承诺，不自动从其他客户端复制计划。

## Unsupported Capabilities

暂无明确排除的正式能力；缺失不等于 unsupported。身份删除已从产品范围排除，不另建客户端能力。

## Client-specific Features

本次盘点未确认独立的平台专属产品功能。系统文件选择、安全凭据存储及渲染技术属于能力实现，不单独创建 Feature。

## Implementation Notes

SharedPreferences、StaticLayout、连续缓存与分页窗口均属技术实现。具体原生交互见 [reader](../../features/reader.md)，实现边界见 [architecture](../../architecture.md)。源码位于 [apps/android](../../../apps/android/)。

搜索使用固定版本 ICU4J，以兼容 min SDK 23 的用户感知字符计数；需在发布验收关注包体及低端设备开销。详细检查结果统一见 [CURRENT](../../CURRENT.md)。本轮直接修改 local，不自动继承其他 worktree 的未提交功能或其他客户端的平台专属 Feature。
