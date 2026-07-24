# XLib

XLib 是一个原生多端 TXT 阅读器 monorepo。当前包含 Android、iOS、阅读进度同步后端，以及供 Apple 平台复用的纯 Swift 阅读核心；macOS 客户端处于规划状态。

## 仓库结构

```text
xlib/
├── apps/
│   ├── android/              # Java / Gradle Android 应用
│   ├── ios/                  # SwiftUI / UIKit iOS 应用
│   └── macos/                # planned，尚无可构建应用
├── services/backend/         # TypeScript / Fastify / PostgreSQL 同步服务
├── contracts/openapi.yaml    # HTTP API 合同
├── packages/apple-shared/    # iOS/macOS 共用的纯 Swift 核心
├── docs/                     # 产品、架构、API 和功能文档
├── releases/                 # monorepo 发布清单
├── scripts/                  # 可重复的开发、构建和发布检查
└── .github/workflows/        # CI 与 release workflow
```

## 当前组件

| 组件 | 状态 | 版本 |
| --- | --- | --- |
| Android | active | `0.9.0` / build `54` |
| iOS | active | `0.9.0` / build `31` |
| Backend | active | `0.9.0` |
| macOS | planned | — |
| API contract | active | `v1` |

`releases/0.9.0.yaml` 是当前统一 Alpha 发布清单；它不重置各组件自己的版本。

## 快速开始

需要 Java 17、Android SDK 35、Xcode/Swift 6、Node.js 22、pnpm 11 和 Ruby。

```bash
make bootstrap
make check
```

常用入口：

```bash
make test-backend
make test-apple-shared
make build-android-debug
make build-ios-debug
make check-alpha
make release-check RELEASE=0.9.0
```

Android 与 iOS 的普通构建不会修改版本文件。版本变化必须通过明确的发布准备流程完成。

## 环境与安全

根目录 `.env.example` 是同步后端环境变量模板：

```bash
cp .env.example .env
```

`.env`、签名密钥、keystore、Token 和构建产物不得提交。同步服务的固定 Token 模型属于 Alpha 阶段已接受的产品风险，详情见 [API 文档](docs/API.md) 和 [同步设计](docs/features/sync-server.md)。

## 文档

- [产品愿景](docs/VISION.md)
- [当前状态](docs/CURRENT.md)
- [架构](docs/ARCHITECTURE.md)
- [API](docs/API.md)
- [Alpha 标准](docs/ALPHA.md)
- [决策记录](docs/DECISIONS.md)
- [功能文档](docs/features/)

迁移前本地恢复副本存放在 `backup/`，该目录被 Git 忽略，不应提交或发布。
