# CAP-TEXT-COPY

Status: candidate
Type: Shared Optional Capability
Introduced From: Android 正文选取复制实现（fcd06a1）。

## Purpose

让用户提取正在阅读的部分原文，用于粘贴到其他位置。

## Business Behavior

用户选择原文范围并主动执行复制，获得对应文本。复制不改变正式阅读位置或阅读时间。

## Rules

当前候选范围保留选中文本及换行，不附加书名、页码或位置信息，不向 XLib 后端上传正文。跨页选取和其他导出形式未纳入范围。

## Data

临时选区及其原文内容；不是持久化阅读进度或同步数据。

## API / Contract

无 HTTP 接口，不改变进度同步契约。

## Edge Cases

空正文、字符边界、取消选择、页面变化；选区不得混淆为持久化字节进度。

## Promotion Condition

若第二个客户端明确需要此能力，重新评估共同复制语义、选区范围和产品交付承诺；不能因 Android 已有实现就要求其他端复制其交互。

## Client Implementations

| Client | Status | Notes |
|---|---|---|
| Android | implemented | 当前页内选取和复制，交互及真机验收范围见客户端说明 |
| Backend | unsupported | 本地文本操作，不承担正文复制职责 |

其他客户端尚未评估，见矩阵。

## Source

- [Android 阅读交互](../../../apps/android/README.md)
- [ReaderPageTextView](../../../apps/android/app/src/main/java/com/xlib/txtreader/ReaderPageTextView.java)
- [ReaderTextSelection](../../../apps/android/app/src/main/java/com/xlib/txtreader/ReaderTextSelection.java)

## Related Capabilities

[CAP-READING](reading.md)、[CAP-AUTO-PAGING](auto-paging.md)。
