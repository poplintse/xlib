# 产品决策

重要取舍使用稳定编号，固定结构为 Context、Decision、Reason、Consequences。记录状态，区分已接受决定与待决问题；candidate 升格、拆分/合并或明确平台不支持时留下理由。日常开发过程不写入此目录。

- [0001 能力初始化与待决差异](0001-capability-baseline-and-open-questions.md)。
- [0002 最近阅读时间优先与当前位置提示](0002-latest-reading-time-wins.md)：用户已确认。
- [0003 阅读前比较定位与正式阅读边界](0003-pre-reading-progress-comparison.md)：用户已确认，含离线阅读及恢复后先比较再上传。
- [0004 首次手动开启，后续有效配置自动应用](0004-sync-configuration-auto-apply.md)：用户已确认。
- [0005 只上传正在阅读的书籍](0005-sync-only-current-reading-book.md)：用户已确认，离线阅读结束后不补传。
- [0006 书内搜索不区分大小写](0006-search-case-insensitive.md)：用户已确认。
- [0007 书内搜索不重叠命中](0007-search-non-overlapping-matches.md)：用户已确认。
- [0008 同一本书同一位置只保留一个书签](0008-bookmark-position-uniqueness.md)：用户已确认。
- [0009 支持单独删除书签](0009-delete-individual-bookmark.md)：用户已确认。
- [0010 云端进度只按单本书删除](0010-delete-cloud-progress-per-book.md)：用户已确认，能力升为 active。
- [0011 当前产品不提供同步身份删除](0011-no-sync-identity-deletion.md)：用户已确认，旧接口已移除。
- [0012 搜索分批加载，200 条不是总上限](0012-search-results-in-batches.md)：用户已确认。
- [0013 搜索到书末后由用户选择从开头继续](0013-search-wrap-with-user-choice.md)：用户已确认。
- [0014 搜索关键词按用户感知字符计数](0014-search-query-character-count.md)：用户已确认，去首尾空白后 2–32 个字符。
- [0015 强制经过 SQLite 迁移基线后再升级](0015-mandatory-sqlite-migration-baseline.md)：用户已确认 0.10.0 为强制中间版本，未来不长期支持 0.9.0 直升最新版。

已有 [docs/DECISIONS.md](../../DECISIONS.md) 的 D-001～D-008 保留原编号和历史，不复制成新决策。发现过期或冲突时相互链接，不通过删掉旧记录掩盖变化。
