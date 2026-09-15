# CAP-READING

Status: active
Type: Product Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

无需网络即可阅读 TXT，并可靠恢复阅读位置。

## Business Behavior

分页阅读、前后翻页、按位置跳转、保存并恢复正式阅读进度。

## Rules

原始文件绝对字节 offset 为位置真值；百分比只用于展示和粗跳转。同步不可成为本地阅读前提；排版变化不得将进度解释为字符索引。

## Data

文件大小、编码、正式 offset、更新时间；显示百分比由 offset 派生。

## API / Contract

与 CAP-PROGRESS-SYNC 共用位置意义；本地阅读不需要 HTTP。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

多字节字符边界、文件首尾、排版变化、文件损坏或替换；大文件应避免整体载入，具体性能指标尚未统一。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 以客户端文档及已知差异为准 |
| iOS | implemented | 以客户端文档及已知差异为准 |
| macOS | planned | 仅有基础阅读/书库方向，尚无 target |
| Backend | unsupported | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [docs/VISION.md](../../../docs/VISION.md)
- [docs/DECISIONS.md](../../../docs/DECISIONS.md)
- [apps/ios/XLibReader/Reader/ReaderEngine.swift](../../../apps/ios/XLibReader/Reader/ReaderEngine.swift)
- [apps/android/app/src/main/java/com/xlib/txtreader/ReaderPosition.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/ReaderPosition.java)

## Related Capabilities

CAP-LIBRARY、CAP-PROGRESS-SYNC，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
