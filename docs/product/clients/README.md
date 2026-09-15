# 客户端说明

- [Android](android.md)：已有原生阅读客户端。
- [iOS](ios.md)：已有原生阅读客户端。
- [macOS](macos.md)：规划中，无应用 target。
- [Backend](backend.md)：同步服务实现，按同样结构记录提供方职责。

Web、Windows 未立项，不建立空文档。API 合同和 Apple 共享包不是客户端。

每端固定记录 Role、Implemented Capabilities、Partial Capabilities、Planned Capabilities、Unsupported Capabilities、Client-specific Features、Implementation Notes。不重复能力文件中的业务规则；差异必须能追溯到代码或重要决策。未评估能力不填入 Planned/Unsupported。

Client-specific Feature 必须是用户可用的平台专属功能。系统文件选择器、Keychain、渲染引擎等通常是已有能力的实现手段，不因为 API 属于某平台就升级为 Feature。
