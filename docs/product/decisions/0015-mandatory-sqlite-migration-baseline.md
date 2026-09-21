# Decision 0015：强制经过 SQLite 迁移基线后再升级

状态：accepted，用户于 2026-09-21 明确确认。不长期支持 `0.9.0 → 最新版` 直接升级。

## Context

Android/iOS 在 0.9.11 开始把结构化本地业务数据迁入 SQLite。只要最新版仍允许 pre-SQLite 的 0.9.0 直接升级，一次性 legacy migrator 就不能从安装包删除。

长期保留 migrator 会持续携带旧 JSON、SharedPreferences、UserDefaults 和文件解析代码。用户选择通过强制升级窗口收敛升级来源，而不是永久维护 0.9.0 到任意未来版本的直升兼容。

## Decision

- `0.9.11` 是 Android/iOS 必须经过的 SQLite 本地存储迁移基线。
- 0.9.11 发布后设置强制迁移窗口，仍使用 0.9.0 等 pre-SQLite 版本的用户必须先升级到 0.9.11，并在设备上完成迁移。
- 未来删除 migrator 的 retirement 版本，最低直接升级来源为 0.9.11；不再承诺 0.9.0 可直接升级到该版本或更高版本。
- retirement 版本发布前，Android 与 iOS 的实际分发渠道必须能落实同一版本下限，并完成 0.9.0 → 0.9.11 及 0.9.11 → retirement 版本的真机验收。
- 0.9.11 尚未 released/tagged、强制窗口未执行完、双端分发约束未验证时，migrator retirement 保持 disabled，不删除 migrator 或迁移夹具。
- 强制升级只改变版本兼容窗口，不改变现有产品 Capability、同步 API、Backend PostgreSQL Schema 或用户数据语义。

## Reason

明确迁移基线后，可以在受控版本窗口结束时删除旧解析和兼容代码，避免长期维护两套本地存储格式。先发布带 migrator 的基线并要求用户经过它，可以把数据迁移与后续 migrator 删除分成两个可验证版本。

## Consequences

- 发布计划必须为 0.9.11 保留足够迁移窗口，并清楚告知旧版用户升级截止时间和恢复方式。
- 未在窗口内升级的 pre-SQLite 安装，之后可能需要手动安装 0.9.11 或重新导入本地书籍；最新版不承担直接读取其旧 metadata 的义务。
- P7.3 仍不能立即开始。它等待 0.9.11 released/tagged、双端真机与强制升级链通过、回退窗口结束，然后才允许把 retirement 开关改为 enabled。
- 机器门禁由 `make check-migrator-retirement` 持续执行。
