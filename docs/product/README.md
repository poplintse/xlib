# XLib 产品能力

## Product Overview

XLib 帮助用户导入本地 TXT、离线阅读和精确恢复阅读位置，并可选择在设备间同步阅读进度。正文、书名和本地路径不上传。当前有 Android、iOS 和 Backend；macOS 已规划但无构建目标，Web、Windows 尚未立项。

## Development Model

本项目采用渐进式多客户端开发：先完成一个客户端，再从已验证行为中提炼产品能力，逐步开发其他客户端。已实现客户端只能作为业务理解、已验证行为和 UI/交互参考，不能自动成为其他客户端的完整需求。

需求来源优先级：

1. [Product Capability](capabilities/README.md)：业务语义。
2. [Capability Matrix](matrix/capability-matrix.md)：客户端承诺与覆盖。
3. [Client-specific 文档](clients/README.md)：平台差异。
4. [API / 数据契约](../../contracts/openapi.yaml)：协议约束。
5. 已实现客户端代码：最后的实现参考，不自动传播 UI、快捷方式或系统集成。

发现文档与代码冲突时记录证据，不以优先级掩盖实现缺陷，也不擅自把现状改写成新需求。

## 分类与维护

- **Product Capability**：跨客户端成立的业务能力，不包含平台专属 UI。
- **Shared Optional Capability**：多端可用但并非每端必须交付；active 不等于全端必做。
- **Client-specific Feature**：平台或客户端形态专属的用户功能，仅在客户端文档维护。
- **Client Implementation / Implementation Detail**：能力的客户端实现、存储、渲染、缓存、调度等，不单独制造业务能力。
- **candidate** 是定义成熟度，不是第五种功能类型；功能存在也不意味着已确认为跨端要求。

新增功能先分类；正式业务变更同时更新能力、矩阵、相关客户端和必要的契约。重要升格、拆分、差异取舍写入 [decisions](decisions/README.md)。实际发布后才写入 [releases](releases/README.md)。ID 保持稳定，废弃使用 deprecated 而非删除历史定义。

## 实现与文档边界

初始盘点于 2026-09-14，整理更新于 2026-09-15。实现与验证状态集中在 [CURRENT](../CURRENT.md)，不代表已发布版本。重要规则与来源可从 [决策索引](decisions/README.md) 追溯；初始化错误和后续确认结果已在 [0001](decisions/0001-capability-baseline-and-open-questions.md) 纠正。

[愿景](../VISION.md) 提供方向；docs/features 只保留平台交互或实现索引；[API](../API.md) 集中维护现行接口。发布清单保留历史，当前 draft 不能描述为已发布。
