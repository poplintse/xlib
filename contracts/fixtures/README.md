# 跨端契约与行为样例

本目录是 P1 的唯一合成样例源，不含真实账户、书籍正文或凭据。样例是已确认能力与现行 API 的可执行验收输入，不是新的需求来源。

| 文件 | 用途 | 消费方 |
|---|---|---|
| progress-behavior.json | 较新回读、旧远端、同时间、同设备、提示阈值、未知时间与安全整数边界 | Android/iOS 验证是否提示；Backend 验证同一对进度的时间裁决 |
| arbitration.json | 同时间设备排序、幂等、未来时间调整边界 | Backend；客户端不承担服务端裁决，明确不适用 |
| wire.json | 两类请求、成功响应、设备 null 字段、错误信封；位置接近 JS 安全整数上限、version 超出该上限仍为字符串 | OpenAPI 校验、Backend 输入/路由、Android 实际 HTTP、iOS Codable 与实际 HTTP |
| invalid-wire.json | 缺失字段、null、类型错误、负数、非法哈希、空数组、额外请求字段、非法决定 | OpenAPI；其中上传请求另经 Backend 实际 Zod Schema 拒绝 |

Android 通过 Gradle test resources 指向此目录；iOS 仅在 XCTest target 复制本目录为测试 bundle 资源；Backend 直接读取源文件。不手工维护平台副本，不打包进正式应用。资源缺失或解析失败必须失败，不能跳过。

## 执行

- `ruby scripts/check-contract.rb`：路由覆盖。
- `ruby scripts/check-contract-fixtures.rb`：当前 OpenAPI Schema 样例与反例验证。
- `make check`：以上检查、Backend/Android 测试及构建；iOS 在此入口仅构建。
- iOS 执行 `XLibReaderTests/ContractFixturesTests`，CI 的完整 iOS 测试也包含它。

后端路由测试使用服务替身，验证真实路由的请求解析和响应信封，不证明数据库 SQL/事务符合契约；专用 PostgreSQL 集成测试另行执行。Android 用本机临时端口，通过测试范围的静态 mock 放行该地址；生产 HTTPS 校验不变。iOS 使用 URLProtocol 截获所有测试请求，不访问外部服务。

`contract-schema.rb` 是仅供测试使用的受限 OpenAPI 3.0 Schema 校验器，覆盖当前使用的 `$ref`、allOf、类型、required、additionalProperties:false、枚举、数值/长度/数组边界、pattern、nullable、int64/uuid/email 格式。新增未知断言或格式会失败，需扩展校验器或迁移至完整工具后才能通过。email 格式仅作基本结构校验，完整输入规则仍由 Zod 测试负责；它不声称是通用 OpenAPI 验证器。

## 已知差异与适用边界

- OpenAPI 的 fileSize/offset 标为 int64，但尚未通过 maximum 表达服务端 JS 安全整数上限；offset <= fileSize、bookKey 不重复也未用 Schema 表达。Backend 测试单独验证这些业务约束。本阶段不改变协议或用较弱 Schema 覆盖服务端规则。
- Android `parseRemote` 拒绝超过 20 字符的来源设备名称，OpenAPI DeviceSummary 未规定该上限，而登记允许 80。该差异需后续单独修复和回归；不能通过缩窄产品规则掩盖。
- Swift Codable 与 Android 手动解析不是完整 OpenAPI 验证器；不要求客户端拒绝所有 Schema 反例。正例必须兼容，字段错误的 API 防线由服务端负责；反例不被假装成三端完全相同的解析策略。
- 同时间服务端裁决不等于客户端一定上传或提示；Android 的新鲜度与重复提示拦截、iOS 会话状态门控在各端测试中覆盖。只有适用行为使用共同断言。
- P0 的完整会话/生命周期测试继续保留；本目录不取代 UI、数据库或真实跨设备验收。
