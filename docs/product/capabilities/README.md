# Capability 编写与索引

每个文件只描述用户或业务层面有意义的能力。禁止平台 UI、框架、动画、控件、尺寸、存储机制进入业务定义；这些放到客户端文档或架构文档。

Status 只用 candidate / active / deprecated；Type 使用 Product Capability 或 Shared Optional Capability。Introduced From 和 Source 必须说明已有需求或实现证据。active 表示有既有产品依据，不代表全部实现已符合要求。

文件固定包含：标题 CAP-ID，Status、Type、Introduced From，以及 Purpose、Business Behavior、Rules、Data、API / Contract、Edge Cases、Client Implementations、Source、Related Capabilities。业务模型只解释意义，不复制数据库 Schema。

Client Implementations 使用 implemented / partial / planned / unsupported；对应矩阵 done / partial / planned / unsupported。未评估的客户端不列入能力表，统一在矩阵标为 not-evaluated，不以 planned 暗示承诺。

## Active

- [CAP-LIBRARY](library.md)：本地 TXT 书库。
- [CAP-READING](reading.md)：离线阅读与位置恢复。
- [CAP-SEARCH](search.md)：书内搜索与临时阅读。
- [CAP-TOC](toc.md)：目录生成与定位。
- [CAP-BOOKMARK](bookmark.md)：本地书签。
- [CAP-READING-PREFERENCES](reading-preferences.md)：阅读偏好。
- [CAP-SYNC-IDENTITY](sync-identity.md)：可选同步身份和设备管理。
- [CAP-PROGRESS-SYNC](progress-sync.md)：可选阅读进度同步。
- [CAP-CLOUD-DATA-CONTROL](cloud-data-control.md)：单书云端进度删除；Android/iOS 入口与会话行为已实现，指定双端真机流程由用户确认，证据边界见 [P8 验收记录](../../architecture/p8-system-validation.md)。

## Candidate

- [CAP-AUTO-PAGING](auto-paging.md)：自动翻页，已存在但尚无独立跨端承诺。
- [CAP-TEXT-COPY](text-copy.md)：正文选取复制，来源 Android，其他客户端范围未评估。

离线能力由愿景明确为阅读前提，归入 CAP-READING，不因通用示例将其降为可选能力。后台调度不等于独立“后台同步产品”；缓存窗口、字节映射和限流是实现细节。
