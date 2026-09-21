# Pre-P7.3 Migrator Retirement

状态：发布策略已确认，本地依赖与发布链审计完成；retirement 开关关闭，当前仍不具备删除 migrator 的资格。

本文定义 P7.3 删除一次性 legacy parser、旧模型和迁移夹具前的门禁。机器可检验的当前计划位于 [migrator-retirement-plan.json](migrator-retirement-plan.json)。

## 1. 当前事实

- 最后一个 released manifest 是 pre-SQLite 的 `0.9.0`，且 Git tag 存在。
- 首个包含 Android/iOS SQLite migrator 的计划版本是 `0.9.11`，当前仍为 draft，且没有 Git tag。
- [Decision 0015](../product/decisions/0015-mandatory-sqlite-migration-baseline.md) 已确认 0.9.11 为强制中间版本，未来 retirement 版本的最低直接升级来源为 0.9.11。
- Android/iOS migrator、平台单元夹具和双端安装覆盖脚本仍完整存在。

因此，即使未来 P7.1/P7.2 已完成，现阶段删除 migrator 仍会破坏长期未升级用户从 0.9.0 直接安装新版的迁移路径。

## 2. 删除资格

用户已经选择“限制最低直接升级来源”。以下“继续支持任意旧版直接升级”不再是当前方案，仅保留用于说明取舍。

### 继续支持任意旧版直接升级

保留 migrator 和迁移夹具。业务旧运行时分支及设备上的 legacy 数据仍可在 P7.1/P7.2 清理，但安装包内的一次性读取器不是死代码。

本项目不采用该方案。

### 限制最低直接升级来源

只有满足以下全部条件才能删除 migrator：

1. 含 migrator 的 SQLite 版本已经发布并有不可变 Git tag。
2. 产品/发布策略明确最低直接升级来源不早于该 SQLite 版本，或者商店/分发系统能强制用户先安装指定中间版本。
3. Android 与 iOS 都能执行同一升级限制；不能只在一个客户端成立。
4. 发布检查能拒绝不满足升级链的 manifest，支持与恢复文档已说明旧版本用户的升级步骤。
5. 删除后的安装包仍通过 SQLite → 新 Schema 升级测试；pre-SQLite 安装覆盖测试转为历史兼容证据，不再作为当前包测试。

该策略已经由 Decision 0015 确认，但分发机制和发布门禁仍须在 0.9.11 发布前落实。

## 3. 自动化门禁

```sh
make check-migrator-retirement
```

检查会读取 release manifest、Git tag、migration source 和 fixture：

- retirement 为 disabled 时，列出阻止删除的事实并成功结束，供日常检查持续审计。
- retirement 被改为 enabled 后，任何 draft/无 tag 的迁移版本、pre-SQLite 最低来源、无效中间版本或缺失 fixture 都会使检查失败。
- `release-check` 要求 0.9.11 声明包含 legacy migrator 并接受 0.9.0 直升；未来声明不含 migrator 的版本必须使用计划中的 0.9.11 最低来源和中间版本。
- 检查不会发布、打 tag、修改版本或删除源码。

## 4. 已确认策略与剩余执行条件

不长期支持 `0.9.0 → 最新版` 直接升级。0.9.11 是强制迁移基线，retirement 版本最低只接受从 0.9.11 直接升级。

剩余条件是发布 0.9.11 并建立不可变 tag、完成双端真机迁移、在实际 Android/iOS 分发渠道执行强制迁移窗口，并验证 0.9.11 → retirement 版本升级。完成前 retirement 仍为 disabled。
