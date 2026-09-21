# Pre-P7.1 Upgrade Validation

状态：历史验证完成。0.10.0 已 released/tagged；Android Emulator 与 iOS Simulator 原位升级通过，用户随后确认 Android 已完成真机测试和发布。安装覆盖脚本已在 P7.3 随一次性 migrator 一同退役。

本文记录删除旧运行时持久化路径之前的升级证据。清理范围和最终门槛以 [Legacy Persistence Cleanup](legacy-persistence-cleanup.md) 为准。

## 1. 验证基线

| 项目 | 值 |
|---|---|
| 最后一个 pre-SQLite 发布来源 | `0.9.0`，released；Android 54、iOS 31 |
| 当前迁移基线 | `0.10.0`，released；Android 68、iOS 44 |
| iOS bundle identifier | `com.xlib.txtreader`，来源版与候选版一致 |
| Android application ID | `com.xlib.txtreader`，来源版与候选版一致 |

`0.10.0` 已发布，且 Android/iOS 发布二进制均确认包含 SQLite migrator。本文件的自动化结果仍不构成真机、凭据连续性或回退窗口证据。

## 2. iOS 自动化原位升级

2026-09-21 曾在隔离的 iOS 26.5 模拟器中执行现已退役的命令：

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

该历史脚本使用临时模拟器和临时 DerivedData，只清理自己创建的资源。P7.3 后它不再属于当前源码验证入口；结果保留在本文和 Git 历史中。

## 3. Android 自动化原位升级

2026-09-21 曾在隔离的 Android API 35 ARM64 Emulator 中执行现已退役的命令：

```sh
make test-android-storage-upgrade
```

脚本从 Git tag `0.9.0` 构建并安装旧版，按旧版 SharedPreferences、TOC JSON 和应用文件目录格式准备书库、进度、书签、阅读设置、非敏感同步配置、hash cache 与 remote cache，然后以相同 application ID 和 Debug 签名覆盖安装当前工作区构建。

已通过的断言：

- `xlib.db` 创建成功且 `PRAGMA integrity_check` 返回 `ok`。
- Book、Progress、BookPreference、Bookmark、TOC、Setting、SyncConfiguration、BookHashCache 和 RemoteProgressCache 与迁移前一致。
- `legacy_migrations` 只有一条预期记录；第二次启动不重复导入。
- `files/books/*.txt`、SharedPreferences 与 TOC JSON 未被修改或提前删除。

该历史脚本使用临时 AVD 和临时构建目录。用户之后确认 0.10.0 Android 已在真机测试并发布；本文不补写未提供的设备型号或逐项结果。

## 4. 最终证据边界

| 平台/场景 | 状态 | 缺少的证据 |
|---|---|---|
| Android Robolectric legacy migration | passed | 已覆盖 Schema/迁移规则，但不等同安装覆盖 |
| Android 模拟器原位升级 | passed | API 35 ARM64 临时 AVD 自动化通过 |
| Android 真机/发布 | user-confirmed | 用户确认已完成真机测试并发布 0.10.0；没有补写设备明细 |
| iOS 模拟器原位升级 | passed | 自动化命令见上文 |
| iOS 真机原位升级 | not-recorded | 用户明确 iOS 不影响本阶段继续；不把模拟器结果表述为真机证据 |
| 凭据边界 | protected | Android Keystore 与 iOS Keychain 未迁入 SQLite；当前源码不删除它们 |
| 故障注入 | partial | 单元测试覆盖清理中断恢复、来源变化和正文缺失；磁盘耗尽仍属 P8 |
| 升级下限 | enforced-by-manifest | 0.11.0 draft 最低来源及强制中间版本均为 0.10.0 |

0.11.0 发布前，分发渠道仍须落实清单声明的版本下限；这属于发布门禁，不恢复已删除的旧格式读取器。

## 5. 结论

这些结果证明 0.10.0 基线曾能读取 pre-SQLite 数据。当前 0.11.0 源码已完成 P7.1–P7.3，只支持由 0.10.0 升级；历史脚本和旧解析器不再维护。
