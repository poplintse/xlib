# Legacy Persistence Cleanup

状态：P7.1、P7.2、P7.3 已在 0.11.0 draft 源码中按顺序完成。0.10.0 是已发布并带有一次性 SQLite migrator 的强制迁移基线；0.11.0 不再支持从 pre-SQLite 版本直接升级。本文描述当前实现，不新增 Product Capability，也不修改同步 API、Backend PostgreSQL Schema 或跨设备业务规则。

历史安装覆盖证据见 [Pre-P7.1 Upgrade Validation](pre-p7-upgrade-validation.md)。删除 allowlist 位于 [legacy-persistence-cleanup-plan.json](legacy-persistence-cleanup-plan.json)，迁移器退役门禁位于 [migrator-retirement-plan.json](migrator-retirement-plan.json)。

## P7.1：SQLite 成为唯一业务运行时

Android 已删除 `BookStore`、`BookmarkStore`、`TocStore`、`ReadingPreferences`、`BookHashCache`、`RemoteProgressStore` 和 `SyncServerConfig` 的 SharedPreferences/JSON 回退。`SyncTokenStore` 的非敏感配置只访问 SQLite；Token 密文、IV 和 Keystore alias 继续留在安全凭据边界。数据库打不开时应用显示存储不可用，不启动第二套 legacy Store。

iOS 已删除 `LibraryStore`、`SettingsStore`、`SyncStateStore` 和 `SyncConfigurationSession` 的 JSON/UserDefaults 回退。`XLibReaderApp` 只有 SQLite 启动上下文；数据库打不开时显示不可用状态并保留磁盘数据。Keychain 凭据不变。

两端业务层仍通过 typed Store/Repository 和 `LocalDatabase` 访问数据，不直接执行 SQL。TXT 正文仍在文件系统。

## P7.2：按设备清理已迁移数据

两端本地数据库从 Schema v1 升到 v2，并增加 `legacy_cleanup`：

```text
legacy_cleanup
  migration_id          references legacy_migrations
  source_fingerprint
  state                 in_progress | completed
  started_at_ms
  completed_at_ms
  removed_items
```

清理只会在 0.10.0 migrator 已写入预期 ledger 时启动。首次删除前必须满足：fingerprint 未变化、`PRAGMA integrity_check` 和外键检查通过、所有正式书籍 TXT 位于受管目录且存在。通过后先记录 `in_progress`，再按 allowlist 删除；进程中断后从 `in_progress` 幂等重试，不再要求已被部分删除的 legacy 重新生成原 fingerprint。全部删除和 SQLite/TXT 复核成功后才写入 `completed`。

Android allowlist 删除：

- `books`、`bookmarks`、旧阅读设置和非敏感同步配置键。
- `sync_remote_email`、`sync_remote_items`、`sync_book_hash_*`。
- `files/toc/*.json`。

iOS allowlist 删除：

- `Metadata/books.json`、`Metadata/books.last-good.json`、`Metadata/bookmarks.json`。
- `TOC/*.json`、`Sync/sync-state.json`。
- `reader.settings.v1` 和已迁入 `sync_configuration` 的非敏感 UserDefaults 键。

未知 key/file 一律保留。新安装没有 0.10.0 migration ledger，因此不会误触 legacy 清理。

## P7.3：一次性读取器退役

0.11.0 的发布策略明确要求最低直接升级来源为 0.10.0，并声明 `required_intermediate: 0.10.0`。Android/iOS 的旧 JSON、SharedPreferences、UserDefaults 模型与解析迁移函数已经删除；`0.9.0 → 当前工作区` 安装覆盖脚本也已退役。平台单元测试现在验证 Schema v1 → v2 清理、正式 SQLite 读写和中断恢复，不再把旧格式解析器当作当前代码职责。

`make check-migrator-retirement` 同时验证：

- 0.10.0 清单仍声明包含 migrator、允许从 0.9.0 升级，且 released/tagged。
- 0.11.0 draft 声明不包含 migrator，并强制经过 0.10.0。
- 生产源码不再包含列明的旧解析入口，历史安装覆盖脚本不存在。

## 永久保留边界

- Android：`sync_token_ciphertext`、`sync_token_iv`、Keystore alias `xlib_sync_token_key`、`files/books/**`、`databases/xlib.db*`。
- iOS：Keychain service `com.xlib.txtreader.progress-sync` / account `sync-token`、`Books/**`、`Metadata/xlib.db*`。
- 两端未知存储项、仍在使用的 reader cache 和待删除正文恢复队列。

不能删除整个 Android `xlib_reader` SharedPreferences 容器，因为它仍保存 Keystore 加密后的 Token 密文和 IV。iOS 不能因业务设置迁入 SQLite 而删除或重建 Keychain 条目。

## 验证与发布边界

用户已确认 Android 0.10.0 在真机完成测试和发布。iOS 工作被明确接受为不阻塞本阶段；现有 iOS Simulator 升级证据、当前应用/测试目标构建及单元测试用于验证源码，但不冒充 iOS 真机分发证据。

0.11.0 当前仍是 draft，未执行发布、tag 或 push。`make check-alpha` 已在当前工作树通过；实际分发渠道拒绝 pre-SQLite 版本直接跳到 0.11.0，以及 iOS 真机发布链，均由用户确认通过，未提供逐项渠道或设备日志，见 [P8 验收记录](p8-system-validation.md)。未经过 0.10.0 的旧安装必须先安装 0.10.0 或重新导入书籍；0.11.0 不再携带恢复旧 metadata 的解析器。
