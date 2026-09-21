# 当前实现与验收状态

更新：2026-09-21。本文记录当前工作树和已核实发布状态；产品规则以 [docs/product](product/README.md) 为准。

## 组件与版本

| 组件 | 当前源码 | 状态依据 |
|---|---|---|
| Android | 0.11.0 / build 69 | [version.properties](../apps/android/version.properties) |
| iOS | 0.11.0 / build 45 | [Xcode 配置](../apps/ios/XLibReader.xcodeproj/project.pbxproj) |
| Backend | 0.10.0 | [package.json](../services/backend/package.json) |
| macOS | 无构建目标 | planned，仅项目约束 |

[当前 0.11.0 清单](../releases/0.11.0.yaml) 状态是 draft；0.10.0 清单是 released/tagged 的 SQLite 强制迁移基线。0.11.0 不包含 legacy migrator，只允许从 0.10.0 直接升级。API 对外路径仍为 v1，Backend 组件保持 0.10.0。

## 本轮文档和接口清理

- 9 项 active 能力、2 项 candidate（自动翻页、正文复制），各端状态见 [矩阵](product/matrix/capability-matrix.md)。用户已确认的规则集中于能力文件，决策解释理由。
- 旧同步长文中的 offset 优先算法、手动应用配置要求、全量删除及身份删除说明已移除；保留稳定文件入口及有用的平台实现细节。
- 后端与 OpenAPI 新增 `DELETE /v1/progress/{bookHash}/{fileSize}`，只删除认证身份下指定文件的进度，不存在记录也返回 204。
- 旧的全量进度清空与身份删除路由、服务方法已移除。两端传输层改为 deleteBookProgress；旧全量删除协调器方法及未被调用的 Android 确认方法已删除。初次盘点将这个 Android 方法误计为用户入口，此处纠正。
- Android/iOS 均已补齐单书删除用户入口、在途上传排序及阅读会话暂停；真实服务与跨设备行为仍待验收。
- 根检查、CI 默认清单和发布回归夹具已切换到 0.11.0，匹配当前移动端源码版本。构建脚本不自动改写版本或递增 build；Gradle 缺少版本文件时直接报错。检查入口在构建后再次校验清单；CI 缺少清单时失败，不再跳过。

## 已确认能力的实现及待验收边界

| 范围 | Android | iOS |
|---|---|---|
| 搜索 | 已补齐匹配/计数，包含跨 segment、续查和手动回绕测试 | 已补齐 200 条续查、主动回绕至原起点、当前页起搜、Unicode 测试及临时阅读同步隔离 |
| 书签 | 已补齐单条删除及空列表，保持位置唯一 | 已补齐同位置唯一及重复提示；历史重复记录未自动清理 |
| 同步配置 | 已补齐自动应用、凭据/缓存/旧提示失效 | 已补齐旧异步响应隔离和凭据串行写入；延迟登录/拉取测试通过 |
| 阅读阶段 | 已实现定位/比较门控及仅实际位移生成时间；导入与缺失时间不生成阅读事件，需真机验证 | 已有准备门控及恢复时间保留；新导入时间为 0，未知时间禁止上传，先比较真实云端状态，实际位移才生成时间 |
| 同步范围 | 仅活动正式阅读书籍；离线恢复比较最新状态，退出不补传 | 已移除全书库上传；仅活动正式会话，离线恢复先比较，失败不上传 |
| 跳转提示 | 已显示当前本地与云端进度；旧提示失效有回归测试 | 已显示双方进度；模拟器可见，旧提示随配置/网络失效 |
| 单书云端删除 | 已补齐入口、串行请求、暂停及关闭重开恢复测试 | 已补齐入口、确认、串行请求、同会话暂停和重开恢复；失败不暂停 |

后端时间优先裁决已有；客户端最新阅读时间的生成与上传行为仍需整体联调。服务端不能判断 UI 阅读阶段，不能代替客户端保证这些规则。

## 尚未作产品决定

正文复制和自动翻页是否成为正式跨端可选能力；Android 批量导入上限、字节去重与起搜前选择书首是否形成共同规则；历史导入伪阅读时间如何处理；章节识别的一致性验收；空文件、重复导入和历史重复书签处理；大文件量化指标；新客户端同步范围；更强认证方案。它们是未明确范围，不作为已确认需求，也不应按某个已有客户端自行补全。

## 验证

### P5：Backend PostgreSQL 边界与集成

Backend 保持 PostgreSQL，不改为 SQLite。身份和进度 SQL 已分别收口到 `IdentityRepository`、`ProgressRepository`；Service 继续控制事务和业务顺序。身份级 user lock 串行化容量检查、上传、单书删除与设备撤销，并在等待后重新查询设备状态，避免排队请求使用撤销前快照。没有修改 OpenAPI、路由、响应、PostgreSQL migration、同步规则或组件版本。

`make test-backend-postgres` 使用临时 PostgreSQL 17、私有 Unix socket、独立 owner 与受限应用角色执行完整 Backend 检查：lint、typecheck、build 和 71 项测试通过，其中 13 项真实数据库集成测试覆盖受限角色、Token/身份隔离、并发注册与上传、批次回滚、10,000 条容量并发、上传/删除顺序及撤销后排队请求拒绝。此前各节记载的“PostgreSQL 测试跳过”是当时的历史验证记录，不再代表当前 P5 状态。

安全复核未发现新增凭据输出或权限扩大；应用角色无 superuser、createdb、createrole、bypassrls 和 schema DDL 权限。P5 没有生产数据库迁移，也没有访问或部署真实服务。真实双端服务联调和发布验收仍未执行，因此当前不建议部署。

### P6：Mobile Local Storage Consolidation

[本地存储审计](architecture/local-storage-audit.md) 保留迁移前事实；[Local Storage Contract](architecture/local-storage-contract.md) 是当前共享数据语义和 Schema v2 约束。Android/iOS 生产路径现已把书库、正式进度、书签、目录、非敏感设置与同步状态写入各端 `xlib.db`。TXT 仍在文件系统；Android Token 保持 Keystore 保护，iOS Credential 保持原 Keychain service/account。Backend 继续使用 PostgreSQL。

0.10.0 首次迁移曾在业务 Store 写入前解析 legacy，并在一个 SQLite 事务中提交正式数据、设置、缓存和 migration ledger。0.11.0 已删除这些旧格式读取器；生产路径只使用 SQLite。删书继续在数据库事务中登记正文待删除路径，失败会在下次书库加载重试。

当前 Android 167 项单元测试全部通过；iOS Simulator 87 项单元测试全部通过，包含 Schema v1 → v2、无 ledger 不清理、allowlist、清理中断恢复、来源变化和正文缺失保护。`make check` 通过契约与 0.11.0 draft 发布门禁、Backend lint/typecheck/build 与 58 项非 PostgreSQL 测试、AppleShared 2 项测试、Android 测试/lint 和 iOS Debug 构建。P5 的独立 PostgreSQL 17 历史检查为 71 项全部通过，其中 13 项使用真实数据库；本轮没有重跑该专项。存储工作没有修改 OpenAPI、Backend PostgreSQL Schema、认证、跨设备规则或 Product Capability。磁盘耗尽、性能与真实跨设备发布验收属于 P8。

### P7：Legacy Persistence Cleanup

[P7 清理合同](architecture/legacy-persistence-cleanup.md) 现描述已实现状态。P7.1 删除双端旧运行时 Store 回退；数据库打开失败时保留数据并显示不可用状态。P7.2 把本地 Schema 升至 v2，只有存在 0.10.0 migration ledger、fingerprint/数据库/TXT 校验通过时，才按显式 allowlist 幂等删除旧业务数据；Token、Keystore、Keychain、TXT、SQLite 和未知项不在删除范围。

P7.3 已删除一次性 legacy parser、旧模型及 `0.9.0 → 当前工作区` 安装覆盖脚本。0.11.0 draft 清单声明 `contains_legacy_migrator: false`、`minimum_direct_from: 0.10.0`、`required_intermediate: 0.10.0`；未经过 0.10.0 的设备不能直接升级。用户已确认 Android 0.10.0 完成真机测试和发布；iOS 被明确接受为不阻塞本阶段，Simulator/构建/单元测试结果不表述为真机证据。

本轮没有新增 Capability，没有修改同步 API、认证、服务端 PostgreSQL Schema 或跨设备业务规则。0.11.0 尚未发布、tag 或 push；实际分发仍须落实版本下限。

### P8：系统验收与维护（进行中）

[P8 验收记录](architecture/p8-system-validation.md) 是当前系统级证据和缺口清单。`make check-alpha` 现强制运行 tracked-sensitive-data 检查、隔离 PostgreSQL 17、Android 测试/lint/build、iOS 单元/UI 测试，并对 Android、iOS、Backend 和所选发布清单的版本源做全程快照；数据库不可用或构建改写版本都会失败。CI 与 release verification 也使用 PostgreSQL 17 owner/受限应用角色，release verification 在生成未签名 Debug artifact 前运行双端测试。

Android 和 iOS 都新增了真实 SQLite `SQLITE_FULL` 故障测试：容量耗尽时整笔写入回滚，原有书籍保持完整。测试暴露并修复了 Android 在 SQLite 自动回滚后再次结束事务会抛异常的问题；Android 数据库初始化失败现在关闭 helper。iOS 容量限制施加在被测连接自身，避免另一连接的 PRAGMA 无效；失败初始化继续由已初始化连接属性的析构路径关闭，避免重复关闭。

本阶段没有修改 OpenAPI、同步数据结构、认证、服务端 PostgreSQL Schema、跨设备规则或 Product Capability。应用日志专项检查未发现正文、邮箱、Token 或真实服务地址输出；Android 数据库打开失败不再把可能含本地路径的异常写入日志。

当前自动验证通过：Backend PostgreSQL 17 为 71/71；Android 为 168/168 且 lint/Debug build 通过；AppleShared 为 2/2；iOS Simulator 为 88/88 单元测试和 11/11 UI 测试。完整 Alpha 门禁通过，版本源保持不变。iOS 测试夹具也在删除临时根目录前显式关闭共享 SQLite 测试连接，避免框架把临时文件路径作为连接违规写入日志。

P8 尚未完成。当前缺少 0.11.0 Android/iOS 真机使用同一非生产身份的跨设备接续记录、iOS 物理设备发布链、实际应用分发渠道的 0.10.0 最低直升版本验证，以及固定样本/设备的时间和峰值内存对比。本次检查中 ADB 没有连接设备，已登记的 `Laguna-15PM` iPhone 处于 offline；Android 0.10.0 的既有真机发布只作为迁移前置证据，不能替代当前版本的跨端验收。

### P4：iOS 同步与进度

已拆出 ReadingSyncSession、SyncConfigurationSession、SyncRequestExecution 和 FormalReadingProgress；ReaderView 只转发准备与带会话 ID 的生命周期事件。保留 MainActor/actor 边界、现有 API 和时钟注入，未改本地 Schema、版本或其他客户端业务实现。修复边界裁剪后无实际位移仍可能递增时间、旧页面回调干扰同书新会话、启动凭据清理绕过串行写入的问题，均遵循既有产品定义。

验证：`make check` 通过（契约、发布清单、Backend 58 项测试及 lint/typecheck/build、AppleShared 2 项测试、Android 161 项测试/lint、iOS Debug 构建）。`xcodebuild test` 全部 11 项 UI 测试通过；最终代码全部 82 项 iOS 单元测试通过，受最后会话回调改动影响的 3 项 UI 复测全部通过。文档本地链接与 `git diff --check` 通过。P1/P2/P3 未提交工作保留。共享行为样例仍是同步比较回归来源，不将 Android 平台实现传播到 iOS。专项审查未发现新增凭据泄漏、权限扩大或传输安全降低；凭据写入和上传/删除仍保持顺序。PostgreSQL 5 项测试因缺少专用测试数据库跳过；真实双端服务、真实设备和性能验收未执行，发布仍受既有兼容缺陷和数据库验收限制，暂不建议部署。没有 commit、push 或发布。

### P2 / P3：Android 用例与同步核心

P2 已完成导入、书库、搜索、目录/书签、偏好及阅读任务/缓存边界拆分；原生页面和持久格式保留。修复未发布导入文件的销毁清理、删除后异步目录/缓存重建，以及书首搜索误用正式返回位置的问题，均按已有能力定义处理。P3 已完成唯一比较状态、配置会话、可注入网络/时钟/执行器及统一正式进度提交。具体职责见 [architecture](architecture.md) 和 [重构计划](refactoring-plan.md)。

验证通过：`make check`（契约/共享样例、发布清单、Backend lint/typecheck/58 项测试/build、AppleShared 2 项测试、Android 单元测试/lint、iOS Debug 构建）；`./gradlew :app:testDebugUnitTest lintDebug assembleDebug`。新增应用层与同步回归覆盖生命周期、旧任务隔离、旧存储兼容、正式时间、配置失效与删除暂停。Android 最终 161 项测试全部通过，lint 与 Debug 构建通过。首次新增偏好兼容测试因测试替身缺少 int/float 支持失败，补齐替身后通过，产品代码未因此改变。

P2/P3 不改变 HTTP/Data/Auth/Sync 契约、权限、签名或组件版本；未改其他客户端业务代码，保留此前 P0/P1 工作。专项检查未发现新增凭据泄漏、明文传输放行或跨身份删除权限。PostgreSQL 5 项集成测试因缺少专用测试数据库跳过；Android 真机 UI、真实跨设备同步和性能比较未执行，iOS 仅构建未重复运行其单元/UI 测试。P1 已发现的来源设备名称兼容缺陷仍未解决，不建议据此直接部署或发布。没有 commit、push 或部署。

### P1：共享行为样例与契约校验

新增 `contracts/fixtures/` 唯一合成样例源，由三端按职责消费。OpenAPI 当前 Schema 子集校验覆盖正例、反例及额外边界，接入 `make check` 与 CI；Android 本机测试传输及 iOS URLProtocol 经过实际客户端编码/解码与错误映射，不访问真实账户。

`make check` 通过：Backend 58 项测试、Android 142 项测试及 lint、AppleShared 2 项、iOS Debug 构建与合同/发布清单检查。iOS 完整单元测试 75 项通过，随后新增传输测试，定向复跑 ContractFixturesTests 的 3 项全部通过。PostgreSQL 5 项仍因无专用数据库跳过；未运行 UI/真机/真实跨设备或远程 CI。首轮检查的测试类型声明和 Android JDK/JSON API 兼容问题已修正。

已知契约差异：Android 进度响应限制来源设备名 20 字符，后端登记允许 80，矩阵标为 partial；OpenAPI 的数值安全上限和跨字段约束表达不足，Backend 另有规则测试。P1 未修改 API、认证/权限或业务代码，未引入依赖；HTTP 放行仅存在于本机测试范围，未发现新增安全风险。上述兼容缺陷与真实服务验收完成前不建议发布。P2/P3 的当前结果见本节前的重构验收说明。

### P0：阅读时间基线与修复

新增三项回归测试在修复前全部失败；修复后 `xcodebuild test -only-testing:XLibReaderTests` 完整 73 项通过。覆盖隔离书库导入/编辑/保存/重开、未知时间禁止上传、准备期与原地停留、首次实际位移、旧真实云端进度的提示及接受/拒绝。`make check` 通过，包含 Backend 54 项测试、AppleShared 2 项、Android 单元测试/lint（缓存任务）、iOS Debug 构建和版本清单校验。PostgreSQL 5 项测试因缺少专用数据库跳过；本轮未运行 UI 自动化、真机、真实跨设备联调或 Alpha 发布验收。

数据 Schema、HTTP 契约和组件版本均未修改；历史时间不清零。同步修改只收紧未知时间上传，未放宽身份或删除权限，未访问真实账户；未发现新增安全风险。真实服务及发布验收前不建议直接发布。文档链接及 `git diff --check` 通过，P1 结果见下方。

当前文档审查覆盖产品基线 `30af692`、其后的 Android `fcd06a1`、iOS `b54600c` 及未提交工作区。仅修改文档。`make check` 首次因 Swift 缓存沙箱权限中断，重跑通过：合同/发布清单、Backend 54 项测试及 lint/typecheck/build、AppleShared 2 项测试、Android 单元测试/lint（任务复用缓存）、iOS Debug 构建。PostgreSQL 5 项集成测试仍跳过，未重跑 UI/真机/真实服务测试。文档本地链接、能力结构和状态及 `git diff --check` 通过。未发现本次文档修改引入权限或凭据风险；该轮发现的导入时间冲突已在 P0 修复；真实服务验收仍待执行。

### Android 正文复制菜单隔离（2026-09-17）

- 旧实现主动启动系统浮动 ActionMode；现改为应用内“复制 / 取消”PopupWindow，正文关闭原生文本选择与智能分类，并拒绝原生 ActionMode/context 菜单入口。邮箱等普通输入框不受影响。
- 保留当前页内选词、高亮与双锚点拖动；拖动时隐藏菜单、松手重定位，菜单限定在可见窗口内。返回键或选区外正文点击只取消选择；窗口失焦、换页/换书清理选区。正文仍仅在用户点击复制后写剪贴板，不上传，也不修改阅读进度。
- `./gradlew testDebugUnitTest lintDebug assembleDebug`、最终 Debug 构建及 `make check` 通过。新增 5 项系统菜单入口/浮层边界单元测试，Android 共 140 项通过；Backend PostgreSQL 5 项因缺少测试数据库跳过。
- 单元测试不模拟真实 OEM 手势分发。当前无连接 Android 设备，目标手机的浅深色和拖动/复制/取消交互仍须真机验收。系统复制成功后的剪贴板通知不属于文本选择菜单，不屏蔽；厂商独立识屏或辅助服务在应用之外的行为不保证可被应用阻止。
- 无新增权限或正文网络传输，未发现新增安全风险；版本保持 0.9.11/build 67，修改未提交。目标手机验证前不建议作为已验收修复发布。

### 发布清单一致性修复（2026-09-17）

- 保留 Android 0.9.11/build 67、iOS 0.9.9/build 43 和 Backend 0.9.9，新增 `releases/0.9.11.yaml` draft；0.9.9 及其他旧清单未修改。Make、本地校验和 CI 统一使用当前清单；显式 `RELEASE`/`RELEASE_VERSION` 仍可覆盖，缺少清单不得跳过。
- `ruby scripts/test-release-check.rb`：11 项回归检查通过，覆盖默认/覆盖参数、缺少清单、各组件版本与 build 不匹配，以及检查不得修改文件。测试只在临时目录修改清单，不改真实组件版本。
- `make release-check`、脚本 `sh -n`、YAML 解析、文档本地链接与 `git diff --check` 通过。
- `make check` 完整通过：8 个合同路由、11 项清单回归、Backend lint/typecheck/build 与 54 项测试、AppleShared 2 项测试、Android 135 项单元测试及 lint、iOS Simulator Debug 构建；Android 本轮复用此前已通过的 Gradle 测试输出。构建后再次确认 0.9.11 清单与源码一致。下方版本不一致失败记录为修复前历史，不再是当前阻断。
- Backend PostgreSQL 集成测试 5 项因缺少 TEST_DATABASE_URL 跳过；未运行 `make check-alpha`、真实设备/服务或远程 GitHub Actions。本轮未涉及凭据、签名或权限放宽，未发现新增安全风险；新增 draft 不等于发布验收通过，暂不建议直接部署。

### Android 能力边界修复（2026-09-17）

- 修复导入生成虚假阅读时间的问题：新书阅读时间为 0，缺失的历史时间不补为当前时刻；未读书不上传，云端存在进度时正常比较，真实位移后才记录阅读事件。已有历史时间保留，不猜测清零，不能自动恢复此前已被覆盖的云端状态。书架对未知时间显示“暂无阅读记录”。
- `403 DEVICE_FORBIDDEN` 进入手动重新开启路径，“同步刷新”可重新登记设备；不自动重新登记，也不把身份级 `SYNC_UNAVAILABLE` 当作设备恢复。
- 单书同步校验失败后重开仍允许本地阅读；独立阅读会话标识阻止切书/重开同书时旧拉取触发上传或旧跳转提示。已发出的上传不承诺撤回；单书删除排序与暂停保持原规则。
- `./gradlew testDebugUnitTest lintDebug assembleDebug` 通过：135 项测试，0 失败、0 跳过，含本轮 9 项新增回归测试；Java 编译、lint 和 Debug APK 构建通过。产物为 `apps/android/app/build/outputs/apk/debug/xlib-debug.apk`，版本保持 0.9.11 / build 67。
- `make check`：OpenAPI 的 8 个路由检查通过，随后因已有 `Android version_name mismatch` 停止，未修改版本或发布清单；后续整仓检查未执行，不沿用此前通过记录。
- 本轮未执行 Android 真机/UI 自动化或真实服务联调；测试使用本地替身，未访问生产账户或删除真实云端数据。凭据、接口权限和删除范围未放宽，未发现新增安全风险；Alpha 固定 Token 的既有风险不变。补齐版本清单一致性和真实设备验收前不建议发布。

### iOS 界面与生命周期验收补齐（2026-09-17）

- 修复阅读页加载被导航中断后、相同布局返回不重启加载的问题；离开阅读时同时复位自动翻页状态。新增确定性回归测试，不改变正式阅读时间。
- 所有 UI 测试改用 Debug 专用隔离书库、配置和内存同步服务，不访问日常书库、Keychain 或真实后端。按现有 SwiftUI 依赖注入方式接入，没有引入发布版测试入口。
- XLib Test iPhone 上执行 `xcodebuild test ... -parallel-testing-enabled NO`：70 项单元测试、10 项 UI 测试通过；另 1 项云端删除 UI 测试因系统采用点弹窗外取消、没有独立取消按钮而失败。测试适配后定向复跑该项通过，因此最终 70 项单元测试与 11 项 UI 测试均有通过记录。更早一轮还修正了测试同步会话初始化顺序。
- 新 UI 场景覆盖重复书签反馈与单条删除/空列表、大小写不敏感搜索和临时阅读返回/重开后的正式进度保留、单书云端删除取消/确认/成功反馈及本地书签和进度保留；原有设置导航、自动保存和触控测试均通过。
- Debug 模拟器构建/启动通过；`xcodebuild build ... -configuration Release -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO` 通过，Release 二进制不包含 UI 测试入口标记。未执行签名归档或发布。构建工具首次 Release 调用因重复 derivedDataPath 参数被拒，改用独立输出目录的直接构建后通过。
- `git diff --check`、修改文档的本地链接检查通过；iOS 工程版本文件没有变化。`make check` 再次通过 8 个合同路由检查后，仍因已有 `Android version_name mismatch` 停止，未改动 Android 版本。
- 安全边界：删除测试使用内存服务，不删除真实云端数据；测试入口仅 Debug 编译。真机、真实服务、跨设备并发和专用 PostgreSQL 验收仍需测试环境，不能以本地替身通过代替发布验收。

### iOS 剩余能力补齐（2026-09-16）

- `xcodebuild test -project apps/ios/XLibReader.xcodeproj -scheme XLibReader -destination 'platform=iOS Simulator,id=D25B4A0D-44D6-4909-ADEB-B18ADEBA9CB7' -only-testing:XLibReaderTests -parallel-testing-enabled NO`：最终 69 项通过，0 失败、0 跳过；包含应用与测试构建。
- 新增阅读准备期禁止上传、远端回读与精确时间、恢复/原地不改时间、旧登录/拉取隔离、离线恢复先比较、上传/删除排序、删除后暂停及重开恢复、拉取/删除失败边界测试。
- 模拟器确认跳转提示包含当前/云端进度，并检查单书删除原生确认和取消；没有执行真实云端删除。完整 UI 自动化、真机、真实服务和跨设备并发仍未验收。
- `make check`：8 个合同路由检查通过，随后 `Android version_name mismatch` 停止；保留已有 Android 版本修改，未扩大修改范围。
- 安全复核：配置代际隔离旧响应，服务器切换不复用旧凭据；删除限定单书身份，不删除本地数据。未发现本轮新增的凭据暴露或扩大删除范围问题；Alpha 固定 Token 的既有风险不变。

### iOS Product Capability 补齐（2026-09-15）

- `xcodebuild test -project apps/ios/XLibReader.xcodeproj -scheme XLibReader -destination 'platform=iOS Simulator,id=D25B4A0D-44D6-4909-ADEB-B18ADEBA9CB7' -only-testing:XLibReaderTests -parallel-testing-enabled NO`：XLib Test iPhone 上 60 项通过，0 失败、0 跳过。包含搜索跨段/跨批、不重叠、200 条结束判定、回绕范围、UTF-16 游标、组合字符，以及书签去重、持久化、单条删除测试。
- 初轮新增书签测试按完整 Date 比较，受已有 JSON 秒级日期精度影响失败；断言已按持久化精度修正，并检查 ID 与摘要保留，最终通过。
- 最终搜索起点状态调整后，`xcodebuild build` Simulator 构建通过；文档本地链接与 `git diff --check` 通过，iOS 工程版本文件无修改。
- 本次 `make check`：合同检查通过，随后因工作树已有 Android 版本与 0.9.9 清单不一致停止（`Android version_name mismatch`）；未为通过检查修改版本。下列全仓库通过记录属于此前验证，不代表本次重跑通过。
- 本次尚未执行搜索/书签页面的完整 UI 自动化、真机及跨设备验收。新增代码不改变 API 或认证，临时阅读已隔离同步事件；未发现新增的数据上传或凭据暴露风险。

### 此前验证记录

- `make check`：通过。OpenAPI 与 8 个路由一致；0.9.9 清单构建前后均一致；Backend lint/typecheck/build 及 54 项测试通过；AppleShared 测试通过；Android 单元测试及 lint 通过；iOS Simulator Debug 构建通过且 build 保持 37。首次沙箱执行因系统缓存不可写停止，获准使用系统缓存后完成检查。
- Android 本轮新增搜索、书签、正式阅读门控及同步协调器回归测试；覆盖离线拉取期间继续阅读/退出、旧提示失效、最新配置重建、在途上传后删除及单书删除暂停。`./gradlew testDebugUnitTest lintDebug assembleDebug` 通过：105 项测试、0 失败、0 跳过，lint 和 Debug 构建通过，不依赖真实后端。最终包位于 `apps/android/app/build/outputs/apk/debug/xlib-debug.apk`。
- `make build-android-debug`：通过，产物版本 0.9.9 / build 65，版本文件不变。
- `xcodebuild ... -only-testing:XLibReaderTests/ProgressSyncTests test`：iPhone 17 Pro Simulator 上 16 项同步测试通过，含单书删除请求路径及无效身份不发送请求。
- 五个 Android/iOS/Backend 构建脚本传入不同 VERSION 的校验：均返回错误且未改动版本文件；Shell 语法检查通过。签名 Release 构建未运行。
- 源码 Markdown 本地链接、Git 路径大小写、能力结构/矩阵状态及 `git diff --check`：通过。
- PostgreSQL 集成测试 5 项跳过：未配置专用 TEST_DATABASE_URL，环境也无本地 PostgreSQL；未访问生产数据库。完整 UI/e2e 与 `make check-alpha` 未运行。Android 无连接设备且未安装模拟器，新增书签删除、云端删除及进度提示需真机验证浅/深色、取消、错误和生命周期行为；真实服务及跨设备并发尚未验收。

接口安全检查：认证先于参数校验；单书删除 SQL 同时限制 user_id、book_hash、file_size，并在事务内重新检查设备权限；旧全量路径返回 404，缺失或无效书籍身份不能扩大删除范围。未发现本轮新增的凭据泄露或越权删除问题，但真实 PostgreSQL 测试尚待执行；Alpha 邮箱恢复固定 Token 的既有风险仍存在。

本文件描述源码与验收边界，不推断已部署或已发布状态。Android 进度同步仍有 P1 发现的来源设备名称长度兼容缺陷；iOS 新导入时间误作阅读时间的问题已修复，历史数据保持不变，状态以当前能力矩阵为准。删除接口是破坏性变更，专用数据库测试及发布验收前不建议部署。
