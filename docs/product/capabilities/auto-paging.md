# CAP-AUTO-PAGING

Status: candidate
Type: Shared Optional Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

减少连续阅读时的重复翻页操作。

## Business Behavior

按用户选择的间隔自动前翻，可停止。

## Rules

两个客户端已有实现，但是否列入新客户端范围尚未确认；间隔、停止时机和后台行为未形成独立产品承诺。

## Data

间隔偏好与运行状态；运行状态不应被误认为跨设备同步数据。

## API / Contract

无 API。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

文件末尾、离开阅读、后台、临时阅读及用户手动翻页。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 以客户端文档及已知差异为准 |
| iOS | implemented | 以客户端文档及已知差异为准 |
| Backend | unsupported | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [apps/ios/XLibReader/Reader/ReaderEngine.swift](../../../apps/ios/XLibReader/Reader/ReaderEngine.swift)
- [apps/ios/XLibReader/Reader/ReaderView.swift](../../../apps/ios/XLibReader/Reader/ReaderView.swift)
- [apps/android/app/src/main/java/com/xlib/txtreader/ReaderRuntimePolicy.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/ReaderRuntimePolicy.java)

## Related Capabilities

CAP-READING、CAP-READING-PREFERENCES，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
