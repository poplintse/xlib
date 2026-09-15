# CAP-TOC

Status: active
Type: Product Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

从 TXT 中提取章节，支持按章节定位。

## Business Behavior

生成、保存和浏览本地目录，选择章节后进入对应正式阅读位置。

## Rules

目录位置使用原始字节 offset；源文件变化后旧目录不可直接复用。章节识别规则和误识别处理尚未统一为跨端验收。

## Data

章节标题、层级、offset，以及用于判断目录是否仍适用的文件信息。

## API / Contract

本地能力；不通过进度接口同步目录。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

无章节、目录失效、生成取消、文件变更；不能将生成失败描述为空目录成功。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 以客户端文档及已知差异为准 |
| iOS | implemented | 以客户端文档及已知差异为准 |
| Backend | unsupported | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [apps/ios/XLibReader/Catalog/TocService.swift](../../../apps/ios/XLibReader/Catalog/TocService.swift)
- [apps/ios/XLibReader/Catalog/CatalogView.swift](../../../apps/ios/XLibReader/Catalog/CatalogView.swift)
- [apps/android/app/src/main/java/com/xlib/txtreader/TocGenerator.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/TocGenerator.java)

## Related Capabilities

CAP-READING，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
