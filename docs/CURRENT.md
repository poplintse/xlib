# 当前实现与验收状态

更新：2026-09-23。本文记录当前工作树和已核实发布状态；产品规则以 [docs/product](product/README.md) 为准。

## 组件与版本

| 组件 | 当前源码 | 状态依据 |
|---|---|---|
| Android | 0.11.0 / build 69 | [version.properties](../apps/android/version.properties) |
| iOS | 0.11.0 / build 45 | [Xcode 配置](../apps/ios/XLibReader.xcodeproj/project.pbxproj) |
| Backend | 0.10.0 | [package.json](../services/backend/package.json) |
| macOS | 无构建目标 | planned，仅项目约束 |

[当前 0.11.0 清单](../releases/0.11.0.yaml) 状态是 draft；0.10.0 清单是 released/tagged 的 SQLite 强制迁移基线。0.11.0 不包含 legacy migrator，只允许从 0.10.0 直接升级。API 对外路径仍为 v1，Backend 组件保持 0.10.0。

## 当前产品能力与接口

- 9 项 active 能力、2 项 candidate（自动翻页、正文复制），各端状态见 [矩阵](product/matrix/capability-matrix.md)。用户已确认的规则集中于能力文件，决策解释理由。
- 旧同步长文中的 offset 优先算法、手动应用配置要求、全量删除及身份删除说明已移除；保留稳定文件入口及有用的平台实现细节。
- 后端与 OpenAPI 新增 `DELETE /v1/progress/{bookHash}/{fileSize}`，只删除认证身份下指定文件的进度，不存在记录也返回 204。
- 旧的全量进度清空与身份删除路由、服务方法已移除。两端传输层改为 deleteBookProgress；旧全量删除协调器方法及未被调用的 Android 确认方法已删除。初次盘点将这个 Android 方法误计为用户入口，此处纠正。
- Android/iOS 均已补齐单书删除用户入口、在途上传排序及阅读会话暂停；0.11.0 真实服务与跨设备行为由用户确认通过。
- 根检查、CI 默认清单和发布回归夹具已切换到 0.11.0，匹配当前移动端源码版本。构建脚本不自动改写版本或递增 build；Gradle 缺少版本文件时直接报错。检查入口在构建后再次校验清单；CI 缺少清单时失败，不再跳过。

## 已确认能力的实现

| 范围 | Android | iOS |
|---|---|---|
| 搜索 | 已补齐匹配/计数，包含跨 segment、续查和手动回绕测试 | 已补齐 200 条续查、主动回绕至原起点、当前页起搜、Unicode 测试及临时阅读同步隔离 |
| 书签 | 已补齐单条删除及空列表，保持位置唯一 | 已补齐同位置唯一及重复提示；历史重复记录未自动清理 |
| 同步配置 | 已补齐自动应用、凭据/缓存/旧提示失效 | 已补齐旧异步响应隔离和凭据串行写入；延迟登录/拉取测试通过 |
| 阅读阶段 | 已实现定位/比较门控及仅实际位移生成时间；导入与缺失时间不生成阅读事件，真机流程由用户确认通过 | 已有准备门控及恢复时间保留；新导入时间为 0，未知时间禁止上传，先比较真实云端状态，实际位移才生成时间；真机流程由用户确认通过 |
| 同步范围 | 仅活动正式阅读书籍；离线恢复比较最新状态，退出不补传 | 已移除全书库上传；仅活动正式会话，离线恢复先比较，失败不上传 |
| 跳转提示 | 已显示当前本地与云端进度；旧提示失效有回归测试，真机接受/拒绝流程由用户确认通过 | 已显示双方进度；旧提示随配置/网络失效，真机接受/拒绝流程由用户确认通过 |
| 单书云端删除 | 已补齐入口、串行请求、暂停及关闭重开恢复测试 | 已补齐入口、确认、串行请求、同会话暂停和重开恢复；失败不暂停 |

后端时间优先裁决已有；客户端最新阅读时间生成与上传的 0.11.0 双端真机联调由用户确认通过。服务端仍不能判断 UI 阅读阶段，客户端回归继续负责保护这些规则。

## 尚未作产品决定

正文复制和自动翻页是否成为正式跨端可选能力；Android 批量导入上限、字节去重与起搜前选择书首是否形成共同规则；历史导入伪阅读时间如何处理；章节识别的一致性验收；空文件、重复导入和历史重复书签处理；大文件量化指标；新客户端同步范围；更强认证方案。它们是未明确范围，不作为已确认需求，也不应按某个已有客户端自行补全。

## 验证

### P5：Backend PostgreSQL 边界与集成

Backend 保持 PostgreSQL，不改为 SQLite。身份和进度 SQL 已分别收口到 `IdentityRepository`、`ProgressRepository`；Service 继续控制事务和业务顺序。身份级 user lock 串行化容量检查、上传、单书删除与设备撤销，并在等待后重新查询设备状态，避免排队请求使用撤销前快照。没有修改 OpenAPI、路由、响应、PostgreSQL migration、同步规则或组件版本。

`make test-backend-postgres` 使用临时 PostgreSQL 17、私有 Unix socket、独立 owner 与受限应用角色执行完整 Backend 检查：lint、typecheck、build 和 71 项测试通过，其中 13 项真实数据库集成测试覆盖受限角色、Token/身份隔离、并发注册与上传、批次回滚、10,000 条容量并发、上传/删除顺序及撤销后排队请求拒绝。此前各节记载的“PostgreSQL 测试跳过”是当时的历史验证记录，不再代表当前 P5 状态。

安全复核未发现新增凭据输出或权限扩大；应用角色无 superuser、createdb、createrole、bypassrls 和 schema DDL 权限。P5 没有生产数据库迁移，也没有访问或部署真实服务。随后 P8 的指定双端真机接续由用户确认通过；0.11.0 仍为 draft，本任务没有生产部署或发布。

### P6：Mobile Local Storage Consolidation

[本地存储审计](architecture/local-storage-audit.md) 保留迁移前事实；[Local Storage Contract](architecture/local-storage-contract.md) 是当前共享数据语义和 Schema v2 约束。Android/iOS 生产路径现已把书库、正式进度、书签、目录、非敏感设置与同步状态写入各端 `xlib.db`。TXT 仍在文件系统；Android Token 保持 Keystore 保护，iOS Credential 保持原 Keychain service/account。Backend 继续使用 PostgreSQL。

0.10.0 首次迁移曾在业务 Store 写入前解析 legacy，并在一个 SQLite 事务中提交正式数据、设置、缓存和 migration ledger。0.11.0 已删除这些旧格式读取器；生产路径只使用 SQLite。删书继续在数据库事务中登记正文待删除路径，失败会在下次书库加载重试。

P6 阶段的 Android 167 项单元测试和 iOS Simulator 87 项单元测试全部通过，包含 Schema v1 → v2、无 ledger 不清理、allowlist、清理中断恢复、来源变化和正文缺失保护。当前更完整的测试计数见下文 P8 状态。存储工作没有修改 OpenAPI、Backend PostgreSQL Schema、认证、跨设备规则或 Product Capability。磁盘耗尽、性能与真实跨设备发布验收归入 P8；其中磁盘耗尽自动化和真实双端验收现已完成。

### P7：Legacy Persistence Cleanup

[P7 清理合同](architecture/legacy-persistence-cleanup.md) 现描述已实现状态。P7.1 删除双端旧运行时 Store 回退；数据库打开失败时保留数据并显示不可用状态。P7.2 把本地 Schema 升至 v2，只有存在 0.10.0 migration ledger、fingerprint/数据库/TXT 校验通过时，才按显式 allowlist 幂等删除旧业务数据；Token、Keystore、Keychain、TXT、SQLite 和未知项不在删除范围。

P7.3 已删除一次性 legacy parser、旧模型及 `0.9.0 → 当前工作区` 安装覆盖脚本。0.11.0 draft 清单声明 `contains_legacy_migrator: false`、`minimum_direct_from: 0.10.0`、`required_intermediate: 0.10.0`；未经过 0.10.0 的设备不能直接升级。用户已确认 Android 0.10.0 完成真机测试和发布；P7 阶段的 iOS Simulator/构建/单元测试不表述为真机分发证据，之后 P8 的 iOS 真机发布链与实际渠道版本下限由用户另行确认通过。

本轮没有新增 Capability，没有修改同步 API、认证、服务端 PostgreSQL Schema 或跨设备业务规则。0.11.0 尚未由本任务发布、tag 或 push；实际分发版本下限由用户确认已落实。

### P8：系统验收与维护（完成）

[P8 验收记录](architecture/p8-system-validation.md) 是当前系统级证据和缺口清单。`make check-alpha` 现强制运行 tracked-sensitive-data 检查、隔离 PostgreSQL 17、Android 测试/lint/build、iOS 单元/UI 测试，并对 Android、iOS、Backend 和所选发布清单的版本源做全程快照；数据库不可用或构建改写版本都会失败。CI 与 release verification 也使用 PostgreSQL 17 owner/受限应用角色，release verification 在生成未签名 Debug artifact 前运行双端测试。

Android 和 iOS 都新增了真实 SQLite `SQLITE_FULL` 故障测试：容量耗尽时整笔写入回滚，原有书籍保持完整。测试暴露并修复了 Android 在 SQLite 自动回滚后再次结束事务会抛异常的问题；Android 数据库初始化失败现在关闭 helper。iOS 容量限制施加在被测连接自身，避免另一连接的 PRAGMA 无效；失败初始化继续由已初始化连接属性的析构路径关闭，避免重复关闭。

本阶段没有修改 OpenAPI、同步数据结构、认证、服务端 PostgreSQL Schema、跨设备规则或 Product Capability。应用日志专项检查未发现正文、邮箱、Token 或真实服务地址输出；Android 数据库打开失败不再把可能含本地路径的异常写入日志。

当前自动验证通过：Backend PostgreSQL 17 为 71/71；Android 为 170/170 且 lint/Debug build 通过；AppleShared 为 3/3；iOS Simulator 为 89/89 单元测试和 11/11 UI 测试。双端阅读字节边界修复后重新运行的完整 Alpha 门禁通过，版本源保持不变。iOS 测试夹具也在删除临时根目录前显式关闭共享 SQLite 测试连接，避免框架把临时文件路径作为连接违规写入日志。

P8 已完成定义范围。用户已确认 0.11.0 Android/iOS 使用同一非生产身份的真机跨设备接续、iOS 物理设备发布链，以及实际应用分发渠道的 0.10.0 最低直升版本验证均已通过；未提供的运行日志明细不补写。用户还确认 iPhone 上此前报错的书籍经 UTF-8 BOM 字节映射修复后已能打开，Android 在 UTF-16 字符边界修复后完成真机测试且无问题。用户明确豁免本轮 Android 真机性能采集，记录和校验器均保留该豁免且不声称 Android 性能结果。

iOS 0.11.0/build 45 已在 iPhone 15 Pro Max/iOS 26.5.2 上完成 Release 自动化性能采集。固定样本覆盖 12 个小/大文件、UTF-8/UTF-16LE/GB18030、冷/缓存打开组合各 5 轮，双向翻页各 30 次，超过 200 条搜索 5 轮及取消，以及 20 本批量导入和确定性中断状态恢复各 5 轮。打开首屏中位数为 77.9–102.9 ms；翻页的接受输入至过渡完成中位数为 378.7/378.6 ms；搜索首结果和完成中位数为 34.2/68.3 ms；批量导入和恢复中位数为 86.5/78.5 ms。批量场景无残留临时文件，恢复测试通过。当前 XCTest 只提供逻辑写入量，不提供读取字节；正式门禁校验真实逻辑写入指标，已有 Activity Monitor 读盘样本仅作探索证据，不以 TXT 大小或请求缓冲伪造读盘值。

`make check-p8-performance-record` 已通过并生成 median/p95 报告；最终 `make check-alpha` 也已通过，包含 Backend/PostgreSQL 17 的 71 项测试、AppleShared 3 项、Android 170 项及 lint/build、iOS 89 项单元测试和 11 项正常 UI 测试，以及契约、安全扫描、发布清单和版本不可变检查。P8 的 18 项真机性能测试为独立 opt-in 套件，普通 Simulator 门禁会明确跳过。自动采集、证据边界和可重复命令见 [P8 验收记录](architecture/p8-system-validation.md)。本阶段没有发布、tag、push、部署或商店上传。

P0–P4 与早期版本的逐轮测试记录由 Git 历史保留；当前验收以本文件上述结果、[重构计划](refactoring-plan.md)及[P8 验收记录](architecture/p8-system-validation.md)为准。
