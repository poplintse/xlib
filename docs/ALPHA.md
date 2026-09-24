# Alpha 范围与退出标准

## 范围

当前 Alpha 开发清单为 [0.11.0（draft）](../releases/0.11.0.yaml)，记录 Android 0.11.0/build 69、iOS 0.11.0/build 45、Backend 0.10.0、OpenAPI v1 和 Apple 共享核心。0.10.0 是必须经过的 SQLite 迁移基线；0.11.0 删除旧格式读取器，只接受从 0.10.0 直接升级。macOS 标记为 planned，不属于本次可交付范围。

## 必须通过

- OpenAPI 文件可解析，已声明的所有 `/v1` 路由与后端实现一致。
- Backend lint、typecheck、unit test 和 build 通过。
- Apple 共享包测试通过。
- Android unit test、lint 和 Debug build 通过，且构建不修改版本文件。
- iOS unit/UI test 和 Simulator Debug build 通过，且构建不修改工程版本文件。
- 发布清单中的组件版本与源码一致。
- tracked-sensitive-data 检查通过；仓库不包含 `.env`、Token、签名密钥、keystore、真实数据库凭据或生产域名。
- 隔离 PostgreSQL 17 集成测试以 owner 执行 migration、以受限应用角色执行服务操作并全部通过。
- 整个 Alpha 检查前后的 Android、iOS、Backend 和所选发布清单版本源完全一致。

`make check-alpha` 自动创建只绑定本机 Unix socket 的临时 PostgreSQL 17 集群。PostgreSQL 17 或测试所需工具不可用时检查必须失败，不能跳过，也不能使用生产数据库代替。

## 人工验收

Android 与 iOS 至少完成一次：导入大 TXT、连续翻页、退出恢复、搜索跳转、目录跳转、书签、设置切换、离线阅读、开启同步、跨设备进度比较和破坏性删除确认。

发布前必须关闭 [CURRENT](CURRENT.md) 中对应范围的实现缺口；不能把协议层通过当作客户端能力完成。

真机跨设备接续、iOS 真机发布链、实际分发渠道最低直升版本和量化性能记录不由 Simulator/CI 自动检查代替。前三项已由用户确认通过；用户明确豁免本轮 Android 真机性能采集，iOS Release 真机性能记录已通过固定样本自动化与记录校验。当前要求和证据见 [P8 System Validation](architecture/p8-system-validation.md)。

详细交互清单按平台区分：Android 见 [features/reader.md](features/reader.md)，iOS 见 [features/ios.md](features/ios.md)。Android 与跨端同步目标见 [features/sync-client.md](features/sync-client.md)。
