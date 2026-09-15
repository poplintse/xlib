# CAP-LIBRARY

Status: active
Type: Product Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

管理可离线访问的个人 TXT 书库。

## Business Behavior

导入 TXT 到本地书库，查看书籍、修改书名/作者并删除本地书籍。

## Rules

书库是本地数据；删除本地书籍不等于删除云端进度。重复导入的合并策略尚未统一。

## Data

书籍标识、书名、来源文件名、作者、文件大小、编码、阅读位置与最后阅读时间。

## API / Contract

无书库同步 API；不可借进度 API 上传书名或文件。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

文件不可读、导入中断、空文件、重复导入、删除关联目录与书签。空文件阅读体验未形成统一验收。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 以客户端文档及已知差异为准 |
| iOS | implemented | 以客户端文档及已知差异为准 |
| macOS | planned | 仅有基础阅读/书库方向，尚无 target |
| Backend | unsupported | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [apps/ios/XLibReader/Persistence/LibraryStore.swift](../../../apps/ios/XLibReader/Persistence/LibraryStore.swift)
- [apps/android/app/src/main/java/com/xlib/txtreader/BookStore.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/BookStore.java)
- [docs/features/reader.md](../../../docs/features/reader.md)

## Related Capabilities

CAP-READING，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
