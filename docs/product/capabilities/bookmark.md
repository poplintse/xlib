# CAP-BOOKMARK

Status: active
Type: Product Capability
Introduced From: 既有产品愿景/功能文档及当前实现，初始抽象于 2026-09-14；见 Source。

## Purpose

保存并重新访问用户标记的阅读位置。

## Business Behavior

添加、列出、跳转和单独删除本地书签；删除书籍时清理其关联书签。

## Rules

书签归属具体本地书籍，位置使用原始文件字节 offset。同一本书、同一 offset 只保留一个书签；重复添加提示“当前位置已有书签”，不新建或替换原记录。不以显示百分比、页码或摘要判重，见 [Decision 0008](../decisions/0008-bookmark-position-uniqueness.md)。支持单独删除选中的书签，不影响书籍、其他书签、本地或云端阅读进度及阅读时间；具体交互由各端决定，见 [Decision 0009](../decisions/0009-delete-individual-bookmark.md)。

## Data

书签标识、书籍标识、offset、创建时间；摘要是可选展示数据。

## API / Contract

无书签同步 API。

HTTP 字段以 [OpenAPI](../../../contracts/openapi.yaml) 为准；能力描述不复制 Schema。

## Edge Cases

重复添加同一位置、单条删除、删除最后一条后的空列表、书籍删除；同位置唯一与单条删除规则均已确认，当前实现缺口见客户端说明。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 同位置去重、单条删除确认及最后一条删除后的空列表 |
| iOS | implemented | 同位置判重并保留原记录、重复提示、单条删除及空列表；历史重复记录不自动清理 |
| Backend | unsupported | 服务端职责，不是阅读客户端 |

未评估端见 [矩阵](../matrix/capability-matrix.md)；partial 表示存在范围缺口或未决业务差异，不等于整个功能不可用。

## Source

- [apps/android/app/src/main/java/com/xlib/txtreader/BookmarkStore.java](../../../apps/android/app/src/main/java/com/xlib/txtreader/BookmarkStore.java)
- [apps/ios/XLibReader/Persistence/LibraryStore.swift](../../../apps/ios/XLibReader/Persistence/LibraryStore.swift)
- [apps/ios/XLibReader/Catalog/CatalogView.swift](../../../apps/ios/XLibReader/Catalog/CatalogView.swift)

## Related Capabilities

CAP-LIBRARY、CAP-READING，参见 [能力索引](README.md)；未决差异见 [初始化决策](../decisions/0001-capability-baseline-and-open-questions.md)。
