# 当前实现与验收状态

更新：2026-09-17。本文记录当前工作树，不代表已部署或已发布；产品规则以 [docs/product](product/README.md) 为准。

## 组件与版本

| 组件 | 当前源码 | 状态依据 |
|---|---|---|
| Android | 0.9.11 / build 67 | [version.properties](../apps/android/version.properties) |
| iOS | 0.9.9 / build 43 | [Xcode 配置](../apps/ios/XLibReader.xcodeproj/project.pbxproj) |
| Backend | 0.9.9 | [package.json](../services/backend/package.json) |
| macOS | 无构建目标 | planned，仅项目约束 |

[0.9.9 清单](../releases/0.9.9.yaml) 状态是 draft。历史 release 清单保留，不根据当前工作树改写历史版本。API 对外路径仍为 v1，但本次删除接口存在破坏性变更。

## 本轮文档和接口清理

- 9 项 active 能力、1 项 candidate（自动翻页），各端状态见 [矩阵](product/matrix/capability-matrix.md)。用户已确认的规则集中于能力文件，决策解释理由。
- 旧同步长文中的 offset 优先算法、手动应用配置要求、全量删除及身份删除说明已移除；保留稳定文件入口及有用的平台实现细节。
- 后端与 OpenAPI 新增 `DELETE /v1/progress/{bookHash}/{fileSize}`，只删除认证身份下指定文件的进度，不存在记录也返回 204。
- 旧的全量进度清空与身份删除路由、服务方法已移除。两端传输层改为 deleteBookProgress；旧全量删除协调器方法及未被调用的 Android 确认方法已删除。初次盘点将这个 Android 方法误计为用户入口，此处纠正。
- Android/iOS 均已补齐单书删除用户入口、在途上传排序及阅读会话暂停；真实服务与跨设备行为仍待验收。
- 根检查及 CI 默认清单为 0.9.9，当前 Android 工作树版本已不同，因此检查被版本一致性校验阻断。构建脚本不再自动改写版本或递增 build；三个旧自动版本辅助脚本已删除，Gradle 缺少版本文件时直接报错。检查入口在构建后再次校验清单。本轮 iOS 构建保持 build 43，未修改版本文件。

## 已确认能力的实现及待验收边界

| 范围 | Android | iOS |
|---|---|---|
| 搜索 | 已补齐匹配/计数，包含跨 segment、续查和手动回绕测试 | 已补齐 200 条续查、主动回绕至原起点、当前页起搜、Unicode 测试及临时阅读同步隔离 |
| 书签 | 已补齐单条删除及空列表，保持位置唯一 | 已补齐同位置唯一及重复提示；历史重复记录未自动清理 |
| 同步配置 | 已补齐自动应用、凭据/缓存/旧提示失效 | 已补齐旧异步响应隔离和凭据串行写入；延迟登录/拉取测试通过 |
| 阅读阶段 | 已实现定位/比较门控及仅实际位移生成时间；需真机验证 | 已实现准备阶段门控、恢复不改时间、远端时间精确保留；单元测试通过 |
| 同步范围 | 仅活动正式阅读书籍；离线恢复比较最新状态，退出不补传 | 已移除全书库上传；仅活动正式会话，离线恢复先比较，失败不上传 |
| 跳转提示 | 已显示当前本地与云端进度；旧提示失效有回归测试 | 已显示双方进度；模拟器可见，旧提示随配置/网络失效 |
| 单书云端删除 | 已补齐入口、串行请求、暂停及关闭重开恢复测试 | 已补齐入口、确认、串行请求、同会话暂停和重开恢复；失败不暂停 |

后端时间优先裁决已有；客户端最新阅读时间的生成与上传行为仍需整体联调。服务端不能判断 UI 阅读阶段，不能代替客户端保证这些规则。

## 尚未作产品决定

自动翻页是否成为正式跨端可选能力；章节识别的一致性验收；空文件、重复导入和历史重复书签处理；大文件量化指标；新客户端同步范围；更强认证方案。它们是未明确范围，不作为已确认需求，也不应按某个已有客户端自行补全。

## 验证

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

本轮直接在 local 实现，未创建分支、提交、推送、签名或部署，未合入其他 worktree 的未提交修改。Android 五项已确认缺口已补齐；iOS 正式 Product Capability 与同步三项 Shared Optional Capability 的已知缺口均已补齐，修改保留未提交。删除接口是破坏性变更，专用数据库测试及发布验收前不建议部署。
