# 当前实现与验收状态

更新：2026-09-15。本文记录当前工作树，不代表已部署或已发布；产品规则以 [docs/product](product/README.md) 为准。

## 组件与版本

| 组件 | 当前源码 | 状态依据 |
|---|---|---|
| Android | 0.9.9 / build 65 | [version.properties](../apps/android/version.properties) |
| iOS | 0.9.9 / build 37 | [Xcode 配置](../apps/ios/XLibReader.xcodeproj/project.pbxproj) |
| Backend | 0.9.9 | [package.json](../services/backend/package.json) |
| macOS | 无构建目标 | planned，仅项目约束 |

[0.9.9 清单](../releases/0.9.9.yaml) 状态是 draft。历史 release 清单保留，不根据当前工作树改写历史版本。API 对外路径仍为 v1，但本次删除接口存在破坏性变更。

## 本轮文档和接口清理

- 9 项 active 能力、1 项 candidate（自动翻页），各端状态见 [矩阵](product/matrix/capability-matrix.md)。用户已确认的规则集中于能力文件，决策解释理由。
- 旧同步长文中的 offset 优先算法、手动应用配置要求、全量删除及身份删除说明已移除；保留稳定文件入口及有用的平台实现细节。
- 后端与 OpenAPI 新增 `DELETE /v1/progress/{bookHash}/{fileSize}`，只删除认证身份下指定文件的进度，不存在记录也返回 204。
- 旧的全量进度清空与身份删除路由、服务方法已移除。两端传输层改为 deleteBookProgress；旧全量删除协调器方法及未被调用的 Android 确认方法已删除。初次盘点将这个 Android 方法误计为用户入口，此处纠正。
- Android 已补齐单书删除用户入口、在途上传排序及阅读会话暂停；iOS 仍仅有传输层适配。不能因为传输方法存在就在能力表标为客户端 done。
- 根检查及 CI 默认清单已更新为与源码匹配的 0.9.9。构建脚本不再自动改写版本或递增 build；三个旧自动版本辅助脚本已删除，Gradle 缺少版本文件时直接报错。检查入口在构建后再次校验清单。首次检查产生的 iOS build 增量已精确撤销，源码版本仍为 37。

## 已确认能力的实现及待验收边界

| 范围 | Android | iOS |
|---|---|---|
| 搜索 | 已补齐匹配/计数，包含跨 segment、续查和手动回绕测试 | 超过 200 条的续查、书末后手动从书首搜索至原起点待补齐 |
| 书签 | 已补齐单条删除及空列表，保持位置唯一 | 同位置唯一待调整；历史重复记录未自动清理 |
| 同步配置 | 已补齐自动应用、凭据/缓存/旧提示失效 | 方向一致，旧响应隔离与重建过程需验证 |
| 阅读阶段 | 已实现定位/比较门控及仅实际位移生成时间；需真机验证 | 页面恢复和时间生成需验证；打开书籍路径不能先刷新时间再比较 |
| 同步范围 | 仅活动正式阅读书籍；离线恢复比较最新状态，退出不补传 | 无会话时全书库上传路径仍存在，按 Decision 0005 应移除 |
| 跳转提示 | 已显示当前本地与云端进度；旧提示失效有回归测试 | 当前本地进度提示待补齐 |
| 单书云端删除 | 已补齐入口、串行请求、暂停及关闭重开恢复测试 | 仅传输层已适配；入口和会话行为待实现 |

后端时间优先裁决已有；客户端最新阅读时间的生成与上传行为仍需整体联调。服务端不能判断 UI 阅读阶段，不能代替客户端保证这些规则。

## 尚未作产品决定

自动翻页是否成为正式跨端可选能力；章节识别的一致性验收；空文件、重复导入和历史重复书签处理；大文件量化指标；新客户端同步范围；更强认证方案。它们是未明确范围，不作为已确认需求，也不应按某个已有客户端自行补全。

## 验证

- `make check`：通过。OpenAPI 与 8 个路由一致；0.9.9 清单构建前后均一致；Backend lint/typecheck/build 及 54 项测试通过；AppleShared 测试通过；Android 单元测试及 lint 通过；iOS Simulator Debug 构建通过且 build 保持 37。首次沙箱执行因系统缓存不可写停止，获准使用系统缓存后完成检查。
- Android 本轮新增搜索、书签、正式阅读门控及同步协调器回归测试；覆盖离线拉取期间继续阅读/退出、旧提示失效、最新配置重建、在途上传后删除及单书删除暂停。`./gradlew testDebugUnitTest lintDebug assembleDebug` 通过：105 项测试、0 失败、0 跳过，lint 和 Debug 构建通过，不依赖真实后端。最终包位于 `apps/android/app/build/outputs/apk/debug/xlib-debug.apk`。
- `make build-android-debug`：通过，产物版本 0.9.9 / build 65，版本文件不变。
- `xcodebuild ... -only-testing:XLibReaderTests/ProgressSyncTests test`：iPhone 17 Pro Simulator 上 16 项同步测试通过，含单书删除请求路径及无效身份不发送请求。
- 五个 Android/iOS/Backend 构建脚本传入不同 VERSION 的校验：均返回错误且未改动版本文件；Shell 语法检查通过。签名 Release 构建未运行。
- 源码 Markdown 本地链接、Git 路径大小写、能力结构/矩阵状态及 `git diff --check`：通过。
- PostgreSQL 集成测试 5 项跳过：未配置专用 TEST_DATABASE_URL，环境也无本地 PostgreSQL；未访问生产数据库。完整 UI/e2e 与 `make check-alpha` 未运行。Android 无连接设备且未安装模拟器，新增书签删除、云端删除及进度提示需真机验证浅/深色、取消、错误和生命周期行为；真实服务及跨设备并发尚未验收。

接口安全检查：认证先于参数校验；单书删除 SQL 同时限制 user_id、book_hash、file_size，并在事务内重新检查设备权限；旧全量路径返回 404，缺失或无效书籍身份不能扩大删除范围。未发现本轮新增的凭据泄露或越权删除问题，但真实 PostgreSQL 测试尚待执行；Alpha 邮箱恢复固定 Token 的既有风险仍存在。

本轮直接在 local 实现，未提交、推送、签名或部署，未合入其他 worktree 的未提交修改。Android 五项已确认缺口已补齐，iOS 缺口仍保留；删除接口是破坏性变更，专用数据库测试及发布验收前不建议部署。
