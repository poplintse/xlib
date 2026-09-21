# Mobile Local Storage Contract

状态：P6.1–P6.4 已在当前工作树实现并通过本地自动化验证。本文定义 Android/iOS 共享的数据语义、SQLite 约束和迁移行为；平台实现代码不共享。它不新增 Product Capability，也不修改同步 API、Backend PostgreSQL Schema 或跨设备同步规则。

## 1. 持久化边界

| 数据 | 唯一持久化边界 | 规则 |
|---|---|---|
| Book、Progress、Bookmark、TOC、Setting、非敏感 SyncState | 客户端 `xlib.db` | 业务代码经 typed Store/Repository 访问，不直接执行 SQL |
| TXT 正文 | 应用私有 `books` / `Books` 目录 | SQLite 只保存相对路径与验证用 metadata |
| Token、Secret、Credential | Android Keystore 保护的现有密文；iOS Keychain | 不写入 SQLite、迁移指纹、日志或测试夹具 |
| 阅读窗口、导入临时文件 | Disposable Cache | 可删除、可重建，不作为业务 source of truth |
| Backend 数据 | PostgreSQL | 不受本 Contract 影响 |

SQLite 开启 foreign keys 和 WAL。Android/iOS 各自使用平台数据库 API；两端不共享 ORM、迁移运行时代码或本地 ID。

## 2. 共享数据语义

- `Book`：客户端本地 ID、展示 metadata、应用管理目录内相对路径、文件大小、修改时间和编码。TXT 文件是正文事实来源。
- `Progress`：每本书一个 byte offset 和可空 `readAtMs`。空值表示未知/未读；百分比只由 offset/fileSize 计算。
- `Bookmark`：本地 ID、book ID、byte offset、可空摘录和创建时间；同一本书同一 offset 唯一。
- `TOC`：可重建快照；document 保存源文件指纹，entry 按 ordinal 排序并使用 byte offset。
- `Setting`：稳定 key 的规范字符串值；typed Repository 负责默认值、解析和归一化，不保存 JSON blob。
- `SyncState`：非敏感同步配置、书籍 hash cache 与按账号隔离的远端进度 cache。Token/credential 不属于 SyncState。
- `PendingFileDeletion`：数据库删除书籍时记录待删相对路径；提交后删除 TXT，失败则在下次书库加载重试。

Android legacy `long` ID 写为十进制字符串；iOS UUID 写为规范小写字符串。它们只在本地有效。跨设备书籍身份继续使用 SHA-256 + fileSize。

## 3. Schema v1

以下 DDL 是共享最小约束。Android 额外使用 `book_preferences` 保存现有单书 `pageMode`；iOS 不建立无业务使用者的对应数据。字段声明可按平台 SQLite API 等价表达。

```sql
CREATE TABLE books (
  local_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  source_name TEXT NOT NULL,
  author TEXT NOT NULL,
  relative_path TEXT NOT NULL UNIQUE,
  file_size INTEGER NOT NULL CHECK (file_size >= 0),
  source_modified_at_ms INTEGER,
  encoding TEXT NOT NULL
);

CREATE TABLE reading_progress (
  book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE,
  offset_bytes INTEGER NOT NULL CHECK (offset_bytes >= 0),
  read_at_ms INTEGER
);

CREATE TABLE bookmarks (
  local_id TEXT PRIMARY KEY,
  book_id TEXT NOT NULL REFERENCES books(local_id) ON DELETE CASCADE,
  offset_bytes INTEGER NOT NULL CHECK (offset_bytes >= 0),
  excerpt TEXT,
  created_at_ms INTEGER NOT NULL,
  UNIQUE (book_id, offset_bytes)
);

CREATE TABLE toc_documents (
  book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE,
  source_file_size INTEGER NOT NULL,
  source_modified_at_ms INTEGER NOT NULL,
  encoding TEXT,
  generator_schema_version INTEGER NOT NULL
);

CREATE TABLE toc_entries (
  book_id TEXT NOT NULL REFERENCES toc_documents(book_id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  entry_id TEXT,
  title TEXT NOT NULL,
  offset_bytes INTEGER NOT NULL,
  level INTEGER NOT NULL,
  PRIMARY KEY (book_id, ordinal)
);

CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE sync_configuration (
  singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
  configured_email TEXT,
  authenticated_email TEXT,
  device_id TEXT,
  device_name TEXT,
  server_url TEXT,
  has_started INTEGER NOT NULL DEFAULT 0 CHECK (has_started IN (0, 1)),
  credential_server_url TEXT,
  active_email TEXT,
  active_device_name TEXT
);

CREATE TABLE book_hash_cache (
  book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE,
  source_file_size INTEGER NOT NULL,
  source_modified_at_ms INTEGER NOT NULL,
  book_hash TEXT NOT NULL
);

CREATE TABLE remote_progress_cache (
  account_scope TEXT NOT NULL,
  book_hash TEXT NOT NULL,
  file_size INTEGER NOT NULL,
  offset_bytes INTEGER NOT NULL,
  read_at_ms INTEGER NOT NULL,
  version TEXT NOT NULL,
  source_device_id TEXT NOT NULL,
  source_device_name TEXT NOT NULL,
  source_platform TEXT NOT NULL,
  fetched_at_ms INTEGER,
  PRIMARY KEY (account_scope, book_hash, file_size)
);

CREATE TABLE pending_file_deletions (
  relative_path TEXT PRIMARY KEY,
  requested_at_ms INTEGER NOT NULL
);

CREATE TABLE legacy_migrations (
  migration_id TEXT PRIMARY KEY,
  source_fingerprint TEXT NOT NULL,
  completed_at_ms INTEGER NOT NULL,
  imported_rows INTEGER NOT NULL
);
```

所有写入把 offset 限制在 `0...fileSize`。缓存表可以重建；`books`、`reading_progress`、`bookmarks`、`settings` 和 `sync_configuration` 是正式数据。删书在同一事务中级联删除 metadata 并写入 `pending_file_deletions`，TXT 删除失败不会留下无法判定的半状态。

## 4. Repository 边界

当前实现以最少边界接入现有代码，不为每张表建立空 Adapter：

- Android `LocalDatabase` 只暴露 typed 方法；`BookStore`、`BookmarkStore`、`TocStore`、`ReadingPreferences`、`BookHashCache`、`RemoteProgressStore`、`SyncServerConfig` 和 `SyncTokenStore` 委托这些方法。生产路径不再把正式业务值写回 SharedPreferences。
- iOS `LocalDatabase` 由 `LibraryStore`、`SettingsStore`、`SyncStateStore` 和 `SyncConfigurationSession` 使用。生产路径不再把正式业务值写回 JSON/UserDefaults。
- Android Token 密文/IV 与 Keystore alias 保持原实现；iOS `SyncCredentialVault` 的 Keychain service/account 与编码保持原实现。
- legacy 构造器暂时只服务迁移失败回退和现有隔离测试；P7.1 验证回退窗口后删除。一次性 legacy 读取器继续保留到最低受支持升级来源已经包含 SQLite。

## 5. 首次迁移合同

1. Schema 创建后、任何业务写入前运行一次迁移。
2. 正式输入先完整解析和校验；正文缺失、路径越界、孤立书签、重复正式键或不可恢复快照使事务失败。
3. Android 从同一 `xlib_reader` SharedPreferences 快照迁移；iOS 优先 `books.json`，失败时只使用可解码的 `books.last-good.json`。
4. `updatedAt == 0` / Unix epoch 迁为 NULL；不得制造当前阅读时间。
5. TOC、hash 与 remote cache 损坏时丢弃该缓存；正式书库、进度、书签和设置不能静默变空。
6. 正式数据、设置、可用缓存和 `legacy_migrations` 在一个事务中提交。失败回滚全部数据库写入并继续使用 legacy 路径。
7. 同一 migration ID + fingerprint 重开直接使用 SQLite，不重复插入；同一 ID 但 fingerprint 改变时拒绝覆盖并回退 legacy，等待调查。
8. 成功后 SQLite 是唯一业务读写源。legacy 保持只读，P7 前不删除、不双写。

迁移指纹排除 Android Token 密文与 IV；凭据正常轮换不应被解释为业务数据源改变。iOS Keychain 从未进入指纹。

## 6. 验证夹具与回退

Android `LocalDatabaseTest` 和 iOS `LocalDatabaseTests` 是平台 legacy 格式的可执行迁移夹具，覆盖：正式书籍/进度/书签、设置、非敏感同步配置、重复打开、来源变更拒绝、孤立书签回滚、正文缺失、iOS last-good 恢复，以及 Android 损坏 TOC 丢弃和文件删除失败后的重试。iOS 嵌套缓存写入以 SQLite savepoint 隔离，失败时不会在外层正式数据迁移事务中留下半个缓存。iOS 另以 `make test-ios-storage-upgrade` 验证 0.9.0 到当前工作区的模拟器安装覆盖；双端真机结果仍是 P7.1 前置条件。两端既有同步测试继续验证迁移没有改变阅读前比较、时间生成、上传范围、身份隔离和单书云端删除。

首次迁移失败时客户端不删除或修改 legacy；该次启动使用现有 legacy Store。P7.1/P7.2 只在迁移版本经过回退窗口和真实设备升级验收后开始，删除旧运行时分支及设备上的 JSON/Preference/UserDefaults 业务数据；TXT、Secure Store 与仍有用途的 cache 不在清理范围。只要仍支持从 pre-SQLite 版本直接升级，P7.3 就必须保留一次性 legacy 读取器和迁移夹具。具体清单见 [Legacy Persistence Cleanup](legacy-persistence-cleanup.md)，disabled 删除计划见 [Pre-P7.2 Device Data Cleanup](pre-p7-data-cleanup.md)，migrator 发布链门禁见 [Pre-P7.3 Migrator Retirement](pre-p7-migrator-retirement.md)。
