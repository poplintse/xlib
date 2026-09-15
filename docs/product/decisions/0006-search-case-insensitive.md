# Decision 0006：书内搜索不区分大小写

状态：accepted，用户于 2026-09-14 明确确认。

## Context

Android 当前使用区分大小写的匹配，iOS 使用不区分大小写的匹配。同一本书与同一查询可能返回不同结果。

## Decision

书内搜索统一不区分大小写。例如搜索 hello，应匹配 hello、Hello 和 HELLO。此项作为共同业务规则，不要求用户选择额外的大小写开关。

结果仍指向原始 TXT 的字节位置，保留原文内容和正确的命中高亮范围；匹配时的大小写处理不能改变原始阅读位置含义。本决定不额外引入去重音、繁简转换等匹配规则，关键词长度计数另见已确认的 [Decision 0014](0014-search-query-character-count.md)，分批续查另见 [Decision 0012](0012-search-results-in-batches.md)；重叠规则另由 [Decision 0007](0007-search-non-overlapping-matches.md) 确认为不重叠。

## Reason

用户明确选择不区分大小写，统一各端普通书内查找的业务语义。

## Consequences

CAP-SEARCH 采用本规则。Android 已补齐并测试大小写匹配与原始字节定位；iOS 当前方向一致，但续查与回绕仍有缺口。当前状态以能力矩阵为准。

实施与验收状态见 [CURRENT](../../CURRENT.md)。后续至少验证混合大小写匹配及对应原文字节位置、高亮范围。
