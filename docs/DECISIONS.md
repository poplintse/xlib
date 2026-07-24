# 架构与产品决策

## D-001 原始字节 offset 是阅读位置真值

状态：accepted。

所有保存、恢复、搜索和同步位置使用原始 TXT 文件的绝对字节 offset。字符索引和百分比不能反向成为持久化真值。

## D-002 原生客户端、窄共享核心

状态：accepted。

Android、iOS 和 macOS 使用各自原生 UI。只有不依赖 UIKit、SwiftUI 或 AppKit 的 Apple 核心进入 `packages/apple-shared`。

## D-003 OpenAPI 是跨组件 HTTP 合同

状态：accepted。

后端路由、Android/iOS 客户端和文档必须与 `contracts/openapi.yaml` 同步变更。

## D-004 Alpha 固定同步 Token

状态：accepted with risk。

Alpha 允许通过规范化邮箱创建或恢复同一个固定 Token。这不是强身份认证，因此服务不得接收 TXT 正文、书名或本地路径，并必须保持限流、Token 加密和最小数据原则。

## D-005 Monorepo 发布与组件版本分离

状态：accepted。

`releases/<version>.yaml` 描述统一发布；Android、iOS 和 Backend 保留自己的版本，不因迁移到 monorepo 而重置。

## D-006 构建不得自动递增版本

状态：accepted。

Debug、test 和普通 release build 都不得修改受 Git 管理的文件。版本变更只能通过明确的发布准备操作进行。
