# Mobile Local Storage Audit

状态：P6.0 审计完成；本文保留迁移前事实。P6.1–P6.4 已按审计结论实施，当前规范见 [Local Storage Contract](local-storage-contract.md)。它是架构输入，不是新的 Product Capability，也不改变同步 API、Backend PostgreSQL Schema 或跨设备同步规则。

## 1. 审计结论

Android 和 iOS 当前都没有 SQLite、Room、Core Data 或 SwiftData 数据库。两端已经有相同的业务数据语义，但物理存储分散：

- Android 的书籍、进度、书签、设置、同步配置和缓存大多位于同一个 `SharedPreferences` 文件，其中部分值再编码为 JSON；TOC 和阅读窗口另存普通文件。
- iOS 的书库、进度、书签、TOC 和同步缓存位于 Application Support 下的多份 JSON；阅读设置与同步配置位于 UserDefaults。
- Android Token 使用 Android Keystore 中的 AES 密钥加密，密文与 IV 存在 SharedPreferences；iOS Token 连同绑定身份存在 Keychain。两端均不得把可解密凭据迁入普通 SQLite。
- TXT 正文是用户书籍内容，不属于 metadata，继续使用应用私有文件系统。
- Android 的 `LocalProgressStore`、iOS 的阅读会话和两端分页窗口都是进程内状态，不是持久化机制。

P6 的目标边界固定为：

| 数据类别 | 目标存储 | 说明 |
|---|---|---|
| 结构化业务数据和非敏感持久设置 | SQLite | 由 Repository 访问；业务层不直接执行 SQL |
| Token、Secret、Credential | Secure Store | Android 保留 Keystore 保护，iOS 保留 Keychain |
| TXT 正文 | Book File | 数据库只保存应用容器内的相对路径和文件 metadata |
| 可重建缓存和临时文件 | Disposable Cache | 可使用 SQLite 缓存表或独立缓存文件；不得成为业务 source of truth |

## 2. Android 当前存储

Android 使用应用私有目录。`MainActivity` 以 `getSharedPreferences("xlib_reader", MODE_PRIVATE)` 装配全部 Preference Store，因此下表中的 SharedPreferences 项物理上共同位于系统管理的 `shared_prefs/xlib_reader.xml`。Manifest 关闭云备份和设备迁移备份；这里的“跨版本保留”指应用升级期间必须保留，不表示卸载后恢复。

| 当前实现 | 保存的数据与实际形式 | 主要调用方与生命周期 | 敏感 / 缓存 | 跨版本 | P6 分类 |
|---|---|---|---|---|---|
| `BookStore` | `books` 键中的 JSON 数组；保存本地书 ID、标题、原文件名、作者、绝对路径、文件大小、编码、offset、派生 progress、单书 pageMode 和 updatedAt | `LibraryController`、`MainActivity`；启动全量加载，书库编辑、导入、阅读进度保存和删除时全量重写 | 非敏感，正式数据 | 必须 | SQLite |
| `BookmarkStore` | `bookmarks` 键中的 JSON 数组；保存 ID、bookId、offset、createdAt | `CatalogController`；按书加载，添加、单条删除和删书时全量重写 | 非敏感，正式数据 | 必须 | SQLite |
| `ReadingPreferences` | 8 个原生 Preference 值：`auto_toc`、`app_theme`、`keep_screen_on`、`auto_page_interval`、`sensitivity`、`font_family`、`font_size`、`line_spacing` | `MainActivity` 和设置页面；应用级长期设置 | 非敏感，正式设置 | 必须 | SQLite |
| `SyncTokenStore` 凭据部分 | `sync_token_ciphertext`、`sync_token_iv`；Token 由 Android Keystore alias `xlib_sync_token_key` 的 AES/GCM 密钥加密 | `SyncConfigurationSession`、`ProgressSyncCoordinator`；开启同步后持续存在，配置失效或关闭同步时清除 | 敏感凭据，不是缓存 | 必须 | Secure Store |
| `SyncTokenStore` 配置部分 | `sync_started`、已认证邮箱、配置邮箱、活动邮箱/设备名/服务器、设备 ID、设备名等原生 Preference 值 | 同步配置和身份会话；应用升级时保留 | 邮箱属于个人数据但不是 Secret；正式配置 | 必须 | SQLite |
| `SyncServerConfig` | `sync_server_url` 字符串 | 同步设置与 HTTP Client 初始化；长期配置 | 非敏感，正式配置 | 必须 | SQLite |
| `RemoteProgressStore` | `sync_remote_email` 与 `sync_remote_items` JSON；远端 offset、readAt、version、来源设备和本地 fetchedAt | `ProgressSyncCoordinator`；启动按邮箱恢复，拉取/上传后替换或更新，配置变更时清除 | 非敏感，可重新拉取 | 否；丢失不应破坏本地阅读 | SQLite 中的 Disposable Cache |
| `BookHashCache` | 每本书一个 `sync_book_hash_<localBookId>` JSON 值；SHA-256、fileSize、modifiedAt | 同步身份计算；文件变化或删书时失效 | 非敏感，可重算但成本较高 | 否 | SQLite 中的 Disposable Cache |
| `TocStore` | `files/toc/<bookId>.json`；源文件大小、修改时间、编码及章节 level/title/offset | `CatalogController`；按需/自动生成，源文件变化时判为无效，用户可删除和重建 | 非敏感，可重建 | 否 | SQLite 中的 Disposable Cache |
| `ReaderCacheStore` | `files/reader-cache/<bookId>.window` 的 XLI2 二进制窗口及同目录 `.tmp` | 阅读器打开与延迟写入；源文件大小或修改时间不匹配即失效，删书时删除 | 非敏感，可重建 | 否 | Disposable Cache 文件 |
| `BookImportController` | `files/books/<id>-<sanitized-name>` TXT 正文；发布前使用同目录 `.tmp` | 导入、阅读和本地删书；正文与书籍生命周期一致，未发布临时文件在失败/销毁时清理 | TXT 是用户内容；`.tmp` 可丢弃 | 正文必须，临时文件不需要 | Book File / Disposable Cache |
| `LocalProgressStore` | 仅内存 Map；book hash、fileSize、offset、readAt、localSequence | 阅读进度提交与同步协调；进程退出即消失 | 运行时派生状态 | 不适用 | 不持久化；以后由 `ProgressRepository` 快照派生 |

Android 没有额外的数据库依赖。`SyncApiClient` 中的 JSON 只是 HTTP 编解码，不是本地持久化。资源 XML、Gradle `version.properties` 与签名配置也不属于应用运行时业务存储。

## 3. iOS 当前存储

iOS 默认根目录是应用容器的 `Library/Application Support/XLibReader`。除 TOC 明确排除备份外，当前代码没有为其他 Application Support 数据设置额外备份排除规则。UserDefaults 的物理 plist 由系统管理，代码只依赖键值语义。

| 当前实现 | 保存的数据与实际形式 | 主要调用方与生命周期 | 敏感 / 缓存 | 跨版本 | P6 分类 |
|---|---|---|---|---|---|
| `LibraryStore` 书库快照 | `Metadata/books.json`；`LibrarySnapshot` 保存 `Book` 数组与删除 tombstones。每本书含 UUID、标题、原文件名、作者、相对路径、fileSize、modifiedAt、编码、offset、updatedAt 和记录 schemaVersion | `LibraryModel`、`ReaderCoordinator`；启动加载，导入、编辑、进度保存和删除时原子全量写入 | 非敏感，正式数据 | 必须 | SQLite |
| `LibraryStore` 恢复副本 | `Metadata/books.last-good.json`；每次写主快照前复制上一有效版本 | `LibraryStore.decode` 在主快照无效时回退 | 非敏感，恢复副本 | 迁移时必须识别，切换后不再长期保留 | Legacy migration input |
| `LibraryStore` 书签 | `Metadata/bookmarks.json`；UUID、bookID、offset、excerpt、createdAt | `CatalogView` 经 `LibraryStore` 访问；添加/删除/删书时原子全量写入 | 非敏感，正式数据 | 必须 | SQLite |
| `LibraryStore` 删除 tombstones | `books.json` 内的 UUID 列表 | 删除时先写 tombstone，再删除正文/TOC/书签，启动时修复中断删除 | 非敏感，事务补偿状态 | 只需完成未结束操作 | 迁移前修复，不进入最终 Schema |
| `LibraryStore` TOC | `TOC/<bookUUID>.json`；schemaVersion、源文件大小/修改时间和章节 ID/title/offset/level；目录已排除备份 | `LibraryModel` 与目录页面；可生成、重建和删除 | 非敏感，可重建 | 否 | SQLite 中的 Disposable Cache |
| `LibraryStore` TXT | `Books/<bookUUID>.txt`；导入期间为 `Books/.<UUID>.importing` | 导入、阅读和本地删书；启动清理中断导入文件 | TXT 是用户内容；`.importing` 可丢弃 | 正文必须，临时文件不需要 | Book File / Disposable Cache |
| `LibraryStore.atomicWrite` | metadata/TOC 目录下随机 `.tmp`，随后 replace/move | 原子发布期间短暂存在 | 可丢弃临时文件 | 否 | Disposable Cache |
| `SettingsStore` | UserDefaults 的 `reader.settings.v1`，值本身是 `ReaderSettings` 的 JSON Data；主题、字体、字号、行距、常亮、自动翻页间隔、翻页灵敏度 | 全应用设置与阅读布局；每次变更整体编码 | 非敏感，正式设置 | 必须 | SQLite |
| `SyncConfigurationSession` / `SyncServerConfiguration` | UserDefaults：服务器地址、凭据所属服务器、设备 ID/名称、配置邮箱、是否曾开启同步 | `ProgressSyncCoordinator` 和同步设置；应用升级时保留 | 邮箱属于个人数据但不是 Secret；正式配置 | 必须 | SQLite |
| `SyncCredentialVault` | Keychain generic password，service `com.xlib.txtreader.progress-sync`、account `sync-token`；JSON 编码的 Token、userID、email 和绑定设备，`AfterFirstUnlockThisDeviceOnly` | `SyncRequestExecution`、`ProgressSyncCoordinator`；登录后保存，禁用或配置变化时清除 | 敏感凭据 | 必须 | Secure Store |
| `SyncStateStore` hash cache | `Sync/sync-state.json` 中的 `hashes` 字典；book UUID、fileSize、modifiedAt、SHA-256 | 计算同步书籍身份；文件 metadata 匹配时复用 | 非敏感，可重算 | 否 | SQLite 中的 Disposable Cache |
| `SyncStateStore` remote cache | 同一 JSON 的 `remote` 字典；远端进度按 hash 与 fileSize 建键 | 启动恢复，拉取/上传/删除后更新，配置失效时清除 | 非敏感，可重新拉取 | 否 | SQLite 中的 Disposable Cache |
| `ReaderEngine` / `ReaderCache` | 仅内存分页窗口和 segment cache | 单次阅读会话 | 可重建 | 不适用 | 不持久化 |

`Info.plist` 中的构建时同步地址、进程环境变量和 UI 测试临时目录不是生产业务持久化，不迁入 SQLite。

## 4. Source of truth 与职责交叉

### 4.1 正式阅读进度

- Android 的持久 source of truth 是 `BookStore` 中的 `offset` 与 `updatedAt`。`progress` 是由 offset/fileSize 得出的重复值；历史数据仅在没有 offset 时由 progress 恢复一次。`LocalProgressStore` 是同步期间的内存镜像，不应成为第二个持久 source of truth。
- iOS 的持久 source of truth 是 `LibraryStore` 的 `Book.offset` 与 `Book.updatedAt`。`ReaderCoordinator` 和 `ReadingSyncSession` 持有会话副本，并通过延迟写入提交。
- P6 后应由一行 `reading_progress` 同时承载 offset 与真实阅读时间；显示百分比只在读取时计算，不再持久保存。

### 4.2 文件 metadata

两端均把 fileSize/modifiedAt 同时用于 Book、TOC 验证和 hash cache。TXT 文件是字节内容的最终事实，数据库保存的是最近验证过的 metadata。TOC 与 hash cache 必须通过 fileSize/modifiedAt 指纹失效，不能反向覆盖 `books` 或正文。

Android 当前保存绝对路径，iOS 保存相对路径。P6 统一语义为“应用管理目录内的相对路径”；迁移时 Android 必须确认旧绝对路径确实位于应用书籍目录，再转换为相对路径。无法安全转换时停止该书迁移并保留 legacy 数据，不能静默丢书。

### 4.3 同步配置与凭据

“用户正在编辑/期望使用的配置”与“当前 Token 实际绑定的配置”是两个不同状态，不应合并为一个字段：

- configured email/device/server 属于非敏感配置，目标是 SQLite；
- active/credential email、device 和 server 用于防止跨服务器或跨身份复用 Token，其中 Token 和解密所需材料属于 Secure Store；
- iOS Keychain 凭据中的 email/device 与 UserDefaults 的 configured 值看似重复，但语义不同。P6 要在 Repository API 中显式命名，而不是删除其中一方。

Android 当前 `SyncTokenStore` 同时负责加密凭据和普通配置，职责交叉最明显。P6 应拆为 `CredentialStore` 与 `SyncConfigurationRepository`，但不得为每个旧键增加永久 Adapter。

### 4.4 远端状态与本地状态

RemoteProgressStore/SyncStateStore 中的远端进度只是最近一次服务端快照。服务端仍是远端状态的 source of truth，本地 SQLite 缓存不得绕过“打开先拉取/比较”的现有规则，也不得在缓存丢失时把本地进度当作已比较。

### 4.5 恢复副本与临时状态

`books.last-good.json` 是上一有效快照，不是并行书库。迁移读取顺序必须与当前实现一致：先使用可解码的主快照，主快照无效才使用 last-good。iOS tombstone 是中断删除恢复状态；Android/iOS 导入临时文件和原子写临时文件都可清理，不应转成数据库记录。

## 5. Shared Local Storage Contract

共享 Contract 统一数据意义和约束，不要求 Android 与 iOS 共享数据库库、SQL 文件或 Repository 实现代码。

| 语义 | 共同定义 |
|---|---|
| `Book` | 客户端本地、稳定且不跨设备同步的 opaque ID；展示 metadata；受管理 TXT 的相对路径；已验证的文件大小、修改时间和编码。书籍正文不进入数据库。 |
| `Progress` | 每本本地书最多一条正式进度；offset 是正文原始字节位置，范围为 `0...fileSize`；readAt 只表示真实阅读位移或接受的远端时间，未知值保持未知。百分比是派生值。 |
| `Bookmark` | 稳定本地 ID、book ID、字节 offset、创建时间和可选摘要；同一本书同一 offset 唯一。删书必须在同一数据库事务中删除书签。 |
| `TOC` | 章节标题、层级、字节 offset 和确定顺序；与源文件指纹绑定。它可重建，因此可清空，但不可变成正式进度来源。 |
| `Setting` | 非敏感、持久、经过 Repository 类型校验和归一化的键值。平台没有的设置无需创建虚假值。 |
| `SyncState` | 非敏感同步配置、文件 hash 缓存和远端快照缓存。Token/Secret 不属于该表；运行时会话 ID、配置代次、请求队列也不持久化。 |

共同删除事务至少保证：删除 `books` 时，同步删除 `reading_progress`、`bookmarks`、TOC 与 hash/remote 的本地关联记录；TXT 文件删除仍是数据库事务之外的文件操作，需要可恢复的删除流程。云端单书进度删除仍由现有 API 单独执行，不能与本地删书混为同一动作。

## 6. 建议 SQLite Schema

这是依据迁移前字段得出的最小逻辑 Schema。P6.1 已把它固化为两端迁移夹具和 [Repository Contract](local-storage-contract.md)；平台可按 SQLite API 调整等价 DDL，但不能改变字段语义。

```text
books
  local_id TEXT PRIMARY KEY
  title TEXT NOT NULL
  source_name TEXT NOT NULL
  author TEXT NOT NULL
  relative_path TEXT NOT NULL UNIQUE
  file_size INTEGER NOT NULL CHECK (file_size >= 0)
  source_modified_at_ms INTEGER NULL
  encoding TEXT NOT NULL

reading_progress
  book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE
  offset_bytes INTEGER NOT NULL CHECK (offset_bytes >= 0)
  read_at_ms INTEGER NULL

bookmarks
  local_id TEXT PRIMARY KEY
  book_id TEXT NOT NULL REFERENCES books(local_id) ON DELETE CASCADE
  offset_bytes INTEGER NOT NULL CHECK (offset_bytes >= 0)
  excerpt TEXT NULL
  created_at_ms INTEGER NOT NULL
  UNIQUE (book_id, offset_bytes)

toc_documents                         -- rebuildable
  book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE
  source_file_size INTEGER NOT NULL
  source_modified_at_ms INTEGER NOT NULL
  encoding TEXT NULL
  generator_schema_version INTEGER NOT NULL

toc_entries                           -- rebuildable
  book_id TEXT NOT NULL REFERENCES toc_documents(book_id) ON DELETE CASCADE
  ordinal INTEGER NOT NULL
  entry_id TEXT NULL
  title TEXT NOT NULL
  offset_bytes INTEGER NOT NULL
  level INTEGER NOT NULL
  PRIMARY KEY (book_id, ordinal)

settings
  key TEXT PRIMARY KEY
  value TEXT NOT NULL

sync_configuration
  singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1)
  configured_email TEXT NULL
  device_id TEXT NOT NULL
  device_name TEXT NOT NULL
  server_url TEXT NOT NULL
  has_started INTEGER NOT NULL CHECK (has_started IN (0, 1))
  credential_server_url TEXT NULL
  active_email TEXT NULL               -- Android legacy binding
  active_device_name TEXT NULL         -- Android legacy binding

book_hash_cache                        -- rebuildable
  book_id TEXT PRIMARY KEY REFERENCES books(local_id) ON DELETE CASCADE
  source_file_size INTEGER NOT NULL
  source_modified_at_ms INTEGER NOT NULL
  book_hash TEXT NOT NULL

remote_progress_cache                  -- rebuildable server snapshot
  account_scope TEXT NOT NULL
  book_hash TEXT NOT NULL
  file_size INTEGER NOT NULL
  offset_bytes INTEGER NOT NULL
  read_at_ms INTEGER NOT NULL
  version TEXT NOT NULL
  source_device_id TEXT NOT NULL
  source_device_name TEXT NOT NULL
  source_platform TEXT NOT NULL
  fetched_at_ms INTEGER NULL
  PRIMARY KEY (account_scope, book_hash, file_size)

legacy_migrations
  migration_id TEXT PRIMARY KEY
  source_fingerprint TEXT NOT NULL
  completed_at_ms INTEGER NOT NULL
  imported_rows INTEGER NOT NULL
```

补充规则：

- Android legacy `long` ID 以十进制字符串迁移；iOS UUID 以规范小写字符串迁移。Local ID 不作为跨客户端书籍身份，云同步仍使用 SHA-256 + fileSize。
- legacy `updatedAt == 0` / Unix epoch 表示未知阅读时间，迁移为 `NULL`；Repository 对尚未迁移完的现有模型可映射回 0，不制造当前时间。
- Android 的 `progress` 不进入 Schema；iOS/Android 的百分比统一由 offset/fileSize 计算。
- Android 单书 `pageMode` 是客户端实现差异，可放入仅 Android 使用的 `book_preferences(book_id, page_mode)`，不提升为共享业务字段。iOS 不需要伪造对应能力。
- `settings.value` 使用每个稳定 key 的规范字符串表示；布尔、整数和小数由 `SettingsRepository` 严格解析、归一化并提供默认值，禁止业务层读写任意字符串或 JSON blob。
- TOC、hash 和 remote 表可整体清空重建；`books`、`reading_progress`、`bookmarks`、`settings` 和 `sync_configuration` 不可按缓存处理。
- SQLite 外键必须开启。书籍与 metadata 删除使用事务；文件删除失败要留下可重试状态或恢复正文，不能造成数据库与 TXT 无法判断的半删除。

## 7. Repository 与文件边界

P6 只需要以下长期边界：

- `LocalDatabase`：连接、事务、Schema version 和数据库迁移；不包含产品规则。
- `LibraryRepository`：Book metadata 与本地删除事务。
- `ProgressRepository`：正式 offset/readAt 的唯一持久入口。
- `BookmarkRepository`：书签查询与唯一约束。
- `TocRepository`：可重建目录缓存。
- `SettingsRepository`：类型安全的应用和阅读设置。
- `SyncStateRepository`：非敏感同步配置、hash 和远端快照缓存。
- `CredentialStore`：Android Keystore 保护的凭据包或 iOS Keychain；不暴露 SQL。
- `BookFileStore`：TXT 导入、发布、路径解析和删除。

旧 JSON/Preferences 的读取只存在于一次性 `LegacyStorageMigrator`。迁移完成后业务 Repository 不回退读取 legacy，也不双写两套存储。P7 会删除 Migrator 和已确认无用的旧 Store。

## 8. 无损、可重复迁移

### 8.1 通用顺序

1. 在任何业务 Repository 开始写入前执行迁移；同一进程只允许一个迁移任务。
2. 清理明确可丢弃的导入/原子写临时文件；iOS 先按现有逻辑完成 tombstone 删除恢复。
3. 读取 legacy 主数据到内存并完整校验。Android 任一正式 JSON 解析失败、iOS 主快照和 last-good 均不可解码时，停止迁移并继续使用旧版本代码路径；绝不能把损坏输入迁移成空书库。
4. 在单个 SQLite 事务中写入正式表、设置和可用缓存，并写 `legacy_migrations`。固定 legacy ID 作为主键，重复执行使用相同键，不增加重复行。
5. 提交前校验书籍/进度/书签数、书签唯一性、offset 范围、相对文件路径与正文存在性、设置值和同步配置。可重建缓存失败不阻止正式数据迁移，但要丢弃该缓存而非写入不完整记录。
6. SQLite 事务提交后重新从 Repository 读取并与迁移输入比较；只有验证成功才把 SQLite 设为唯一读写源。
7. legacy 文件和 Preference 键保持只读，不双写。清理由独立 P7 执行，不在首次成功迁移时立即删除回退证据。

`legacy_migrations.source_fingerprint` 应由来源格式版本、主文件大小/修改时间和稳定摘要生成。相同 fingerprint 已成功时直接跳过；fingerprint 变化但 migration ID 相同时必须报错调查，不能覆盖已经投入使用的 SQLite。

### 8.2 Android 特项

- 从同一 `xlib_reader.xml` 一次性读取快照，避免多个 Store 在迁移过程中看到不同代次。
- `books` 和 `bookmarks` 使用原 ID；先插入 books/progress，再插 bookmarks、TOC 与 caches。
- 把绝对书籍路径转换成 `files/books` 下相对路径；路径越界、正文缺失或 ID 冲突都应列为迁移失败，保留 legacy。
- 现有 Keystore alias 与 AES/GCM Token 必须可解密验证后再切换 CredentialStore。Token、密钥、明文或可解密材料不得写入 SQLite、日志或迁移报告。
- `sync_*` 普通配置键迁入 `sync_configuration`；动态 hash 键迁入 `book_hash_cache`。远端缓存无法通过校验时可以丢弃并要求下次在线拉取。

### 8.3 iOS 特项

- `books.json` 可解码时为迁移源；否则使用 `books.last-good.json`。迁移报告要记录选择了哪一个，但不能记录书名、正文或用户邮箱。
- `bookmarks.json` 是正式数据；任一无法关联现存书籍的书签都应阻止无损迁移并要求明确修复策略。
- Keychain 条目保持原 service/account 和可访问性，P6 不重新编码 Token。只把 UserDefaults 中的非敏感同步配置迁入 SQLite。
- `sync-state.json` 的 hash 与 remote 都是缓存；损坏时直接忽略。若无法可靠确定 remote 所属账号，则丢弃 remote cache，下次在线重新拉取。
- `reader.settings.v1` 只迁移成功解码并 normalize 后的字段；缺失键由新 Repository 使用与当前版本相同的默认值。

## 9. 实施与清理阶段

- **P6.0（已完成）**：本审计、source of truth 识别、逻辑 Schema 与迁移边界；不改存储代码。
- **P6.1（已完成）**：固化共享 Local Storage Contract、SQLite DDL、Repository 接口和 Android/iOS legacy 迁移夹具。
- **P6.2（已完成）**：Android 实现 SQLite、typed Store 委托、无损事务迁移、Keystore 边界和文件删除重试。
- **P6.3（已完成）**：iOS 按相同数据语义实现 SQLite、JSON/UserDefaults 迁移和 Keychain 边界，没有复制 Android 框架代码。
- **P6.4（本地自动化完成）**：双端验证正式数据、设置、同步配置、重复迁移、回滚、来源变化、正文缺失/恢复、损坏缓存丢弃、文件删除重试和同步回归。真实设备升级、磁盘耗尽与性能属于 P8 发布验收，完成前不执行 P7 清理。
- **P7.0（已完成）**：建立 [Legacy Persistence Cleanup](legacy-persistence-cleanup.md) 清单并核对发布、设备、回退和直接升级门槛。
- **P7.1/P7.2**：在迁移版本经过真实设备验证和既定回退窗口后，删除旧运行时写路径并按设备清理旧 JSON/Preferences/UserDefaults 业务数据。Secure Store、TXT 和明确保留的 Disposable Cache 不属于待删除 legacy。
- **P7.3**：最低受支持升级来源已经包含 SQLite 后，才删除一次性 migrator 和迁移夹具，避免 pre-SQLite 版本直接升级断链。
- **P8：系统验收与维护**：真实双端接续、真实设备、性能与发布门禁；发布仍需单独授权。

P6/P7 不应引入长期双写、每个旧 Store 对应一个新 Adapter、跨平台 ORM 或为了形式统一而存在的空表。最少机制、明确事务和单一 source of truth 优先。
