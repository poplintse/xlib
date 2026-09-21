# Pre-P7.2 Device Data Cleanup

状态：本地设计与保护性验证完成；清理开关关闭，尚未删除任何设备数据。

本文定义 P7.2 清理已迁移设备 legacy 数据之前的执行合同。机器可检验的删除与保留清单位于 [legacy-persistence-cleanup-plan.json](legacy-persistence-cleanup-plan.json)。

## 1. 启用条件

设备清理只能在实际 P7.1 已完成并满足以下条件的版本中启用：

1. 迁移版本已发布，Android/iOS 真机升级通过，约定回退窗口结束。
2. 当前数据库 `user_version` 与清理实现支持的 Schema 一致。
3. `legacy_migrations` 存在平台预期 migration ID，且清理开始前重新计算的 fingerprint 与 ledger 一致。
4. `PRAGMA integrity_check` 通过，书籍、进度、书签及设置能通过 Repository 回读；所有 Book 的 TXT 路径存在且位于受管目录。
5. 清理版本内置的计划仍为显式 allowlist；发现未知 key/file 时保留并报告，不扩大删除范围。

任一条件失败都不得开始删除，也不得用空值覆盖 legacy。

## 2. 可中断恢复状态机

P7.2 实现时应把清理状态放入 SQLite 专用表，不复用用户 `settings`：

```text
legacy_cleanup
  migration_id          primary key, references completed migration
  source_fingerprint    must equal legacy_migrations
  state                 in_progress | completed
  started_at_ms
  completed_at_ms       nullable
  removed_items         monotonic count
```

执行顺序：

1. 在 SQLite 事务中完成全部启用条件检查并写入 `in_progress`。
2. 逐项删除 allowlist 中的 key/file。每项操作都必须允许“不存在”，且不能删除整个 Preferences/UserDefaults 容器。
3. 中断后看到 `in_progress` 时按同一 allowlist 重试；此时 legacy 可能已被部分删除，不再重新计算来源 fingerprint。
4. 验证所有目标不存在、所有 preserve 项存在、SQLite 仍可完整回读，再在事务中写 `completed`。
5. `completed` 后重复启动只做轻量状态检查，不再次枚举或扩大删除范围。

实际实现会引入本地 Schema 升级，因此必须作为独立 P7.2 代码变更，并提供 v1 → v2 数据库升级测试；pre-P7.2 不提前改变生产 Schema。

## 3. 平台边界

Android 只能逐 key 删除 `xlib_reader` 中已迁移业务值和 `sync_book_hash_*`，以及 `files/toc/*.json`。不得删除整个 SharedPreferences 文件，因为 `sync_token_ciphertext` 和 `sync_token_iv` 仍在其中；不得删除 Keystore alias、`files/books/**` 或 `databases/xlib.db*`。

iOS 只能删除列出的 UserDefaults key、书库/书签 JSON、TOC JSON 与 sync-state JSON。不得操作 Keychain service/account、`Books/**` 或 `Metadata/xlib.db*`。未知 UserDefaults 和文件保持不动。

## 4. 自动化保护

```sh
make check-legacy-cleanup-plan
```

检查会确认：

- 清理计划仍处于 disabled。
- migration ID 与数据库 Schema 版本匹配两端源码。
- migrator 读取的业务 key 均被明确分类为 remove 或 preserve。
- Android Token/Keystore、iOS Keychain、TXT 和 SQLite 路径不会与删除规则重叠。
- 清理中断后继续和完成后再次执行均保持幂等；未知 key 会保留。

这项检查验证删除合同，不代表 P7.2 已启用。启用仍受真实设备、发布和回退窗口门禁约束。
