# Alpha 范围与退出标准

## 范围

Alpha 0.9.0 包含 Android、iOS、同步 Backend、OpenAPI v1 和 Apple 共享核心。macOS 标记为 planned，不属于本次可交付范围。

## 必须通过

- OpenAPI 文件可解析，已声明的所有 `/v1` 路由与后端实现一致。
- Backend lint、typecheck、unit test 和 build 通过。
- Apple 共享包测试通过。
- Android unit test、lint 和 Debug build 通过，且构建不修改版本文件。
- iOS unit/UI test 和 Simulator Debug build 通过，且构建不修改工程版本文件。
- 发布清单中的组件版本与源码一致。
- 仓库不包含 `.env`、Token、签名密钥、keystore、真实数据库凭据或生产域名。

真实 PostgreSQL 集成测试需要专用 `TEST_DATABASE_URL`。缺少该环境时必须明确记录为 skipped，不能使用生产数据库代替。

## 人工验收

Android 与 iOS 至少完成一次：导入大 TXT、连续翻页、退出恢复、搜索跳转、目录跳转、书签、设置切换、离线阅读、开启同步、跨设备进度比较和破坏性删除确认。

详细交互清单见 [features/reader.md](features/reader.md)，同步完成标准见 [features/sync-client.md](features/sync-client.md)。
