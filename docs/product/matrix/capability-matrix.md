# Capability Matrix

基于代码与文档，非发布验收。业务定义以能力文件为准。Backend 列表示服务端支持职责，不表示终端功能；API 不是独立客户端，apple-shared 是共享实现包。

- done：当前代码覆盖已定义范围，不代表所有未知产品规则已解决。
- partial：已实现一部分，或存在已知语义冲突/入口缺口。
- planned：有明确计划；不因 macOS 整体 planned 就把所有能力列为 planned。
- unsupported：当前组件职责明确不提供该能力，不代表永久不可变。
- not-evaluated：无明确范围判断；不能等同于不支持或计划实现。

| Capability | Status | Android | iOS | macOS | Web | Windows | Backend |
|---|---|---|---|---|---|---|---|
| [CAP-LIBRARY](../capabilities/library.md) | active | done | done | planned | not-evaluated | not-evaluated | unsupported |
| [CAP-READING](../capabilities/reading.md) | active | done | done | planned | not-evaluated | not-evaluated | unsupported |
| [CAP-SEARCH](../capabilities/search.md) | active | done | done | not-evaluated | not-evaluated | not-evaluated | unsupported |
| [CAP-TOC](../capabilities/toc.md) | active | done | done | not-evaluated | not-evaluated | not-evaluated | unsupported |
| [CAP-BOOKMARK](../capabilities/bookmark.md) | active | done | done | not-evaluated | not-evaluated | not-evaluated | unsupported |
| [CAP-READING-PREFERENCES](../capabilities/reading-preferences.md) | active | done | done | not-evaluated | not-evaluated | not-evaluated | unsupported |
| [CAP-SYNC-IDENTITY](../capabilities/sync-identity.md) | active | done | done | not-evaluated | not-evaluated | not-evaluated | done |
| [CAP-PROGRESS-SYNC](../capabilities/progress-sync.md) | active | partial | done | not-evaluated | not-evaluated | not-evaluated | done |
| [CAP-AUTO-PAGING](../capabilities/auto-paging.md) | candidate | done | done | not-evaluated | not-evaluated | not-evaluated | unsupported |
| [CAP-CLOUD-DATA-CONTROL](../capabilities/cloud-data-control.md) | active | done | done | not-evaluated | not-evaluated | not-evaluated | done |
| [CAP-TEXT-COPY](../capabilities/text-copy.md) | candidate | done | not-evaluated | not-evaluated | not-evaluated | not-evaluated | unsupported |

候选能力的 done 仅表示已存在实现，不表示正式产品承诺。Web / Windows 未立项，无对应空客户端文件。Shared Optional 的选择由客户端范围决定。所有 partial 的具体原因见能力及客户端文档。iOS 新导入时间充当阅读时间的问题已修复，历史记录保持原样；0.11.0 双端真机跨设备接续已由用户确认通过，证据范围见 [P8 验收记录](../../architecture/p8-system-validation.md)。Android 批量导入和搜索起点选择属于已有能力扩展，不据此给其他端新增计划。

Android 进度同步的 partial 来自 P1 发现的来源设备长名称响应兼容缺陷，不是新增需求。
