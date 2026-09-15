# macOS Client

## Role

规划中的原生桌面 TXT 阅读客户端。当前只有项目约束，无可构建或发布的应用 target。

## Implemented Capabilities

无。共享 Swift 包不能计作客户端实现。

## Partial Capabilities

无实际应用实现。

## Planned Capabilities

CAP-LIBRARY、CAP-READING：由现有 TXT 阅读器愿景推导的基础方向，具体验收及里程碑仍待定义。其余能力 not-evaluated，不把移动端全部能力自动列为计划。

## Unsupported Capabilities

无明确不支持决策。

## Client-specific Features

暂无已确认功能。Menu Bar、Spotlight、App Intent 均不能因平台示例而视为计划。

## Implementation Notes

现有 [macOS 项目约束](../../../apps/macos/AGENTS.md) 要求复用 UI 无关 Apple 核心。窗口、菜单和系统交互在开发时独立设计；新增同步平台还需更新当前仅允许 ios/android 的 [API 合同](../../../contracts/openapi.yaml)。
