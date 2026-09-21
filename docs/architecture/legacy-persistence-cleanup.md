# Legacy Persistence Cleanup

状态：P7.0 及 pre-P7.1、pre-P7.2、pre-P7.3 本地准备按顺序完成；所有生产清理开关保持关闭。当前只有 0.9.0 标记为 released，包含 SQLite 迁移的 0.9.11 仍为 draft。Android Emulator 与 iOS Simulator 的 `0.9.0 → 当前工作区` 原位升级均已通过；Android 没有连接设备，已登记的 iOS 真机处于 unavailable，因此仍无双端真实设备升级与回退窗口证据。

前置证据分别见 [Pre-P7.1 Upgrade Validation](pre-p7-upgrade-validation.md)、[Pre-P7.2 Device Data Cleanup](pre-p7-data-cleanup.md) 和 [Pre-P7.3 Migrator Retirement](pre-p7-migrator-retirement.md)。

本文定义 P7 的删除边界和执行门槛。它不新增 Product Capability，不修改同步 API、Backend PostgreSQL Schema 或跨设备业务规则。

## 1. 三类清理不能同时执行

### P7.1 旧运行时路径

首个 SQLite 版本完成真实设备升级验证和回退窗口后，可以删除业务 Store 中的 JSON、SharedPreferences、UserDefaults 写入与迁移失败后的旧 Store 回退。迁移失败应进入明确的只读错误状态，保留旧数据并阻止创建第二个 source of truth。

一次性 legacy 读取器此时仍须保留。只要仍支持从 0.9.0 等 pre-SQLite 版本直接升级，删除读取器就会让长期未升级的设备无法迁移。

### P7.2 已迁移设备的数据

只有设备上的 `legacy_migrations` 存在预期 migration ID、fingerprint 一致、SQLite 回读校验通过，并且回退窗口结束后，才能删除该设备的旧业务数据。清理必须幂等；中断后重试不能碰 TXT、凭据或 SQLite 正式数据。

### P7.3 一次性迁移读取器

只有最低受支持升级来源已经是 SQLite 版本，或发布策略明确要求用户先经过含 migrator 的中间版本，才能从安装包删除 legacy parser、旧模型和迁移夹具。在此之前，migrator 是升级兼容代码，不是可删除的临时死代码。

## 2. Android 清理清单

P7.1 后删除的运行时分支：

- `BookStore`、`BookmarkStore`、`TocStore`、`ReadingPreferences`、`BookHashCache`、`RemoteProgressStore` 和 `SyncServerConfig` 的 legacy 构造器与旧读写分支。
- `SyncTokenStore` 中非敏感配置的 SharedPreferences 分支；Token 密文与 IV 的安全存储分支永久保留。
- `MainActivity` 的迁移失败 legacy Store 装配；替换为不写数据的启动错误状态。
- 只验证旧 Store 写入行为的测试；迁移格式继续由 `LocalDatabaseTest` fixture 覆盖。

P7.2 可按设备删除：

- `books`、`bookmarks`。
- `auto_toc`、`app_theme`、`keep_screen_on`、`auto_page_interval`、`sensitivity`、`font_family`、`font_size`、`line_spacing`。
- `sync_configured_email`、`sync_email`、`sync_device_id`、`sync_device_name`、`sync_server_url`、`sync_started`、`sync_active_server_url`、`sync_active_email`、`sync_active_device_name`。
- `sync_remote_email`、`sync_remote_items`、`sync_book_hash_*` 以及旧 `files/toc/*.json` 缓存。

永久保留：

- `sync_token_ciphertext`、`sync_token_iv` 和 Android Keystore alias `xlib_sync_token_key`。
- `files/books` TXT 正文、`xlib.db`、仍使用的 reader cache 和待删除正文恢复队列。

不能删除整个 `xlib_reader` SharedPreferences 文件，因为它仍承载 Keystore 保护后的 Token 密文和 IV。

## 3. iOS 清理清单

P7.1 后删除的运行时分支：

- `LibraryStore` 的 JSON snapshot、bookmark、tombstone 和 TOC 读写分支。
- `SettingsStore` 的 UserDefaults 读写分支。
- `SyncStateStore` 的 `sync-state.json` 读写分支。
- `SyncConfigurationSession` 的 UserDefaults 业务配置分支。
- `XLibReaderApp` 的迁移失败 legacy 装配；替换为保留数据的启动错误状态。

P7.2 可按设备删除：

- `Metadata/books.json`、`Metadata/books.last-good.json`、`Metadata/bookmarks.json`。
- `TOC/*.json`、`Sync/sync-state.json`。
- `reader.settings.v1` 和已迁入 `sync_configuration` 的非敏感 UserDefaults 键。

永久保留：

- `Books/*.txt`、`Metadata/xlib.db`。
- Keychain service `com.xlib.txtreader.progress-sync` / account `sync-token` 及其访问级别。
- 明确仍由当前实现使用的临时文件和可重建缓存目录规则。

## 4. 启用门槛

P7.1 和 P7.2 必须同时具备：

1. 含 Android/iOS SQLite migrator 的版本已发布，不是 draft。
2. Android 与 iOS 各至少完成一次从最后 pre-SQLite 发布版本升级的真实设备验证。
3. 验证书库、进度、书签、设置、同步配置、凭据解密、TXT 打开和删书恢复；SQLite 回读与迁移前样本一致。
4. 迁移失败、磁盘空间不足和应用被终止场景不会删除或覆盖 legacy。
5. 项目约定的回退窗口已经结束，并保留可审计的版本与设备记录。

P7.3 还必须确认最低受支持的直接升级来源已经包含 SQLite。仅完成回退窗口不足以删除 migrator。

## 5. 当前结论

本地前置工作已经完成，不能安全执行生产代码删除或设备数据清理。实际 P7.1 仍等待双端真实设备升级、迁移版本发布和回退窗口；P7.2 还依赖 P7.1 完成。P7.3 的发布策略已经由 [Decision 0015](../product/decisions/0015-mandatory-sqlite-migration-baseline.md) 确认为强制经过 0.9.11，当前仍等待该版本 released/tagged 和双端分发链验证。发布、签名和部署仍需单独授权。
