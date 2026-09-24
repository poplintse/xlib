# Decision 0015：强制经过 SQLite 迁移基线后再升级

状态：accepted，用户于 2026-09-21 明确确认。不长期支持 `0.9.0 → 最新版` 直接升级。

## Context

Android/iOS 从 0.10.0 开始通过实际发布资产把结构化本地业务数据迁入 SQLite。原计划版本虽有对应源码 tag，但发布的移动端资产没有包含最终 migrator，因此不能作为迁移基线。只要最新版仍允许 pre-SQLite 的 0.9.0 直接升级，一次性 legacy migrator 就不能从安装包删除。

长期保留 migrator 会持续携带旧 JSON、SharedPreferences、UserDefaults 和文件解析代码。用户选择通过强制升级窗口收敛升级来源，而不是永久维护 0.9.0 到任意未来版本的直升兼容。

## Decision

- `0.10.0` 是 Android/iOS 必须经过的 SQLite 本地存储迁移基线。
- 0.10.0 接受 0.9.0 等 pre-SQLite 版本直接升级；迁移窗口内用户必须安装 0.10.0，并在设备上完成迁移。
- 未来删除 migrator 的 retirement 版本，最低直接升级来源为 0.10.0；不再承诺 0.9.0 可直接升级到该版本或更高版本。
- retirement 版本发布前，实际分发渠道必须落实 0.10.0 版本下限。Android 0.10.0 真机测试/发布由用户确认；iOS 本阶段按用户决定不作为源码推进阻塞，但发布证据仍应如实区分 Simulator 与真机。
- 0.11.0 是首个 retirement draft：源码删除 migrator 和迁移夹具，清单强制经过 0.10.0；它在发布前仍须通过发布门禁。
- 强制升级只改变版本兼容窗口，不改变现有产品 Capability、同步 API、Backend PostgreSQL Schema 或用户数据语义。

## Reason

明确迁移基线后，可以在受控版本窗口结束时删除旧解析和兼容代码，避免长期维护两套本地存储格式。先发布带 migrator 的基线并要求用户经过它，可以把数据迁移与后续 migrator 删除分成两个可验证版本。

## Consequences

- 发布计划必须为 0.10.0 保留足够迁移窗口，并清楚告知旧版用户升级截止时间和恢复方式。
- 未在窗口内升级的 pre-SQLite 安装，之后可能需要手动安装 0.10.0 或重新导入本地书籍；最新版不承担直接读取其旧 metadata 的义务。
- P7.1–P7.3 已在 0.11.0 draft 源码完成；这不等同 0.11.0 已发布。
- `make check-migrator-retirement` 验证 0.10.0 released/tagged 基线、0.11.0 升级声明、旧 parser 标记和历史覆盖脚本均符合退役状态。
- 0.11.0 的实际分发渠道最低直升版本及 iOS 真机发布链由用户在 P8 确认通过；未提供逐项日志，当前清单仍为 draft，见 [P8 验收记录](../../architecture/p8-system-validation.md)。
