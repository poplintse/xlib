# 当前状态

更新时间：2026-07-24。

## 已实现

- Android 原生 TXT 阅读器：大文件读取、精确定位、缓存窗口、搜索、目录、书签、设置和进度同步。
- iOS 原生阅读器：书架、阅读、搜索、目录、设置和进度同步。
- TypeScript/PostgreSQL 同步后端：固定 Token、设备管理、进度裁决、限流、指标、备份与恢复演练。
- Apple 共享 Swift Package：文本编码识别和 UTF-16/文件字节 offset 映射。
- OpenAPI v1 合同和统一仓库检查入口。

## 计划中

- macOS 客户端。当前 `apps/macos` 只有项目约束，不存在可构建 target。
- 已签名的多平台自动发布。当前 release workflow 只验证并生成非商店发布构建产物。
- 更强的同步身份认证。Alpha 固定 Token 模型仍然允许仅凭规范化邮箱恢复 Token。

## 当前版本

- Android：0.9.0 / build 54
- iOS：0.9.0 / build 31
- Backend：0.9.0
- Monorepo Alpha：0.9.0
