# CAP-READING-PREFERENCES

Status: active
Type: Product Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

让用户调整阅读可读性与外观。

## Business Behavior

本地保存字体、字号、行距和明暗外观偏好，并在阅读中应用。

## Rules

偏好改变不改变原始位置意义；不要求各客户端拥有相同字体清单、范围、默认值或控件。

## Data

阅读外观偏好及其当前值。

## API / Contract

本地能力；不在进度同步协议中传输。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

字体不可用、历史设置迁移、排版重建；具体取值在客户端实现中维护。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 以客户端文档及已知差异为准 |
| iOS | implemented | 以客户端文档及已知差异为准 |
| Backend | unsupported | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [apps/ios/XLibReader/Settings/SettingsStore.swift](../../../apps/ios/XLibReader/Settings/SettingsStore.swift)
- [apps/android/app/src/main/java/com/xlib/txtreader/ReaderSettingsOptions.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/ReaderSettingsOptions.java)

## Related Capabilities

CAP-READING，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
