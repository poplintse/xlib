# Pre-P7.1 Upgrade Validation

状态：本地自动化完成。Android Emulator 与 iOS Simulator 原位升级均已通过；Android/iOS 真机、迁移版本发布与回退窗口仍是实际 P7.1 启用门禁。

本文记录删除旧运行时持久化路径之前的升级证据。清理范围和最终门槛以 [Legacy Persistence Cleanup](legacy-persistence-cleanup.md) 为准。

## 1. 验证基线

| 项目 | 值 |
|---|---|
| 最后一个 pre-SQLite 发布来源 | `0.9.0`，released；Android 54、iOS 31 |
| 当前迁移候选 | `0.9.11`，draft；Android 67、iOS 43 |
| iOS bundle identifier | `com.xlib.txtreader`，来源版与候选版一致 |
| Android application ID | `com.xlib.txtreader`，来源版与候选版一致 |

`0.9.11` 未发布，所以本文件的自动化结果是发布前证据，不构成回退窗口或生产设备证据。

## 2. iOS 自动化原位升级

2026-09-21 在隔离的 iOS 26.5 模拟器中执行：

```sh
make test-ios-storage-upgrade
```

脚本从 Git tag `0.9.0` 构建旧版，在旧版真实应用容器中按该版本的 Codable/文件格式准备书库、进度、书签、目录、阅读设置、非敏感同步配置和 hash cache，然后使用相同 bundle identifier 覆盖安装当前工作区构建。

已通过的断言：

- `xlib.db` 创建成功且 `PRAGMA integrity_check` 返回 `ok`。
- Book、Progress、Bookmark、TOC、Setting、SyncConfiguration 和 BookHashCache 的字段值与迁移前一致。
- `legacy_migrations` 只有一条预期记录；第二次启动不重复导入。
- `Books/*.txt` 可以保留，JSON、UserDefaults 和 sync-state 迁移证据未被修改或提前删除。
- 覆盖安装前后的应用数据存在于当前容器中，没有将测试夹具作为新安装重新导入。

脚本使用临时模拟器和临时 DerivedData，退出时只清理自己创建的资源。它不加入普通 `make check`，因为会构建两个版本并创建模拟器；应在发布候选和 P7 门禁审核时显式运行。

## 3. Android 自动化原位升级

2026-09-21 在隔离的 Android API 35 ARM64 Emulator 中执行：

```sh
make test-android-storage-upgrade
```

脚本从 Git tag `0.9.0` 构建并安装旧版，按旧版 SharedPreferences、TOC JSON 和应用文件目录格式准备书库、进度、书签、阅读设置、非敏感同步配置、hash cache 与 remote cache，然后以相同 application ID 和 Debug 签名覆盖安装当前工作区构建。

已通过的断言：

- `xlib.db` 创建成功且 `PRAGMA integrity_check` 返回 `ok`。
- Book、Progress、BookPreference、Bookmark、TOC、Setting、SyncConfiguration、BookHashCache 和 RemoteProgressCache 与迁移前一致。
- `legacy_migrations` 只有一条预期记录；第二次启动不重复导入。
- `files/books/*.txt`、SharedPreferences 与 TOC JSON 未被修改或提前删除。

脚本使用临时 AVD，默认要求 API 35 Google APIs ARM64 system image，退出时删除 AVD 和临时构建目录。它与 iOS 升级命令一样只在发布候选和 P7 门禁审核时显式运行。

## 4. 尚未满足的门禁

| 平台/场景 | 状态 | 缺少的证据 |
|---|---|---|
| Android Robolectric legacy migration | passed | 已覆盖 Schema/迁移规则，但不等同安装覆盖 |
| Android 模拟器原位升级 | passed | API 35 ARM64 临时 AVD 自动化通过 |
| Android 真机原位升级 | blocked | 当前没有连接设备 |
| iOS 模拟器原位升级 | passed | 自动化命令见上文 |
| iOS 真机原位升级 | blocked | 已登记 `Laguna-15PM` 当前 unavailable |
| 凭据连续性 | pending | Android Keystore Token 与 iOS Keychain Credential 必须在真机覆盖安装后解密/读取成功 |
| 故障注入 | pending | 磁盘空间不足、迁移中止、失败后旧数据可用性仍需设备级验证 |
| 回退窗口 | pending | SQLite 迁移版本尚未发布，无法开始约定窗口 |

P7.1 仍需 Android/iOS 各至少一次真实设备的 `0.9.0 → 已发布迁移版本` 覆盖安装验收。验收必须检查书库、进度、书签、设置、同步配置、凭据、TXT 打开、删除恢复和重复启动，并保存版本、设备、结果和日期。

## 5. 结论

双端自动化原位升级已经把迁移从单元夹具验证提升到安装覆盖验证，pre-P7.1 的本地准备完成，但不替代真机与已发布版本证据。当前不得删除旧运行时分支、设备 legacy 数据或一次性 migrator。
