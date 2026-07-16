# xlib

xlib 是一款原生 Android TXT 阅读器（包名 `com.xlib.txtreader`），主打大文件（30–100 MB）的精确阅读位置、低内存下稳定的翻页体验，以及不把后台缓存边界暴露给显示链路。

主要页面：书架、阅读、搜索、目录/书签、设置。

## 特性

- **绝对字节 offset 权威位置**：保存、恢复、跳转都基于绝对 offset；百分比只用于显示和粗粒度跳转，不能反过来用做精确恢复
- **多页滑动窗口 + cache combine stack 双流水线**：
  - `ReaderPageWindow` 最多保留 17 页，左右各 8 页预排版；普通翻页只移动窗口索引，不再读盘、不再 cache 换绑
  - `CacheCombineStack` 后台维护约 256 KiB 连续缓存，由两个 128 KiB segment 合并后重建统一 `ByteOffsetMap`
  - 两条流水线各自单线程执行器独立推进，cache 发布不修改当前显示页
- **流式搜索 / 目录生成**：64 KiB 字节块扫描；搜索每批最多 200 条，从当前阅读位置向文件末尾排序；目录按实际标题结构归一化到 1–3 级（卷/章/节）
- **持久化最近阅读窗口**：源文件大小/修改时间校验后才生效；覆盖目标 offset 时不重复初始化，不覆盖时也不闪旧页再跳
- **全局设置**：浅色 / 深色双主题（不跟随系统）、TXT 自动生成目录、阅读时锁屏、自动翻页间隔（10–30 秒）、触摸灵敏度、字体（系统/黑体/宋体/仿宋/等宽）、字号（14–34sp）、行间距（10–40%）
- **JUnit 单元测试** 覆盖 cache stack、page window、refill policy、display/runtime policy、settings 归一化、TOC 生成等关键阅读逻辑

## 架构

应用以单个 `MainActivity` 为入口，按页面切分；持久化和设置以 `SharedPreferences` 与应用内部文件为主。

### 持久化

| 类别 | 存储位置 | 关键类 |
| --- | --- | --- |
| 书籍列表 | `SharedPreferences` 的 `books` JSON 数组 | `Book`, `BookStore` |
| 全局设置 | `SharedPreferences` | — |
| 目录 | 应用内部 `files/toc/<bookId>.json` | `TocDocument`, `TocEntry`, `TocGenerator`, `TocStore` |
| 书签 | `SharedPreferences` 的 `bookmarks` JSON 数组 | `Bookmark`, `BookmarkStore` |
| 最近阅读窗口 | 应用内部文件（窗口范围 + 解码文本 + 校验信息） | — |

删除书籍时同步清理对应目录、书签、阅读缓存和 TXT 本地副本。

### 设置参数三层切分

- `ReaderSettingsOptions` — 范围归一化（边界、默认值、步长）
- `ReaderDisplayPolicy` — 把设置映射为触摸阈值、字体、行距等显示参数
- `ReaderRuntimePolicy` — 自动翻页和亮屏的运行条件（前台 / 正式阅读 / 无搜索临时阅读 / 无动画 / 无窗口加载 / 未冻结进度）

### 阅读缓存流水线

- **Cache segment**：磁盘读取单位，~128 KiB，按完整字符边界对齐；LRU 内存最多保留 6 段
- **CacheCombineStack**：对阅读框架只暴露不可变 cache 快照；前后各两段合并后重建统一 `ByteOffsetMap`，阅读框架不得观察到 segment 边界
- **ReaderPageWindow**：17 页滑动窗口；翻页只移动 current index 并显示已就绪的当前页
- **CombinedCacheSnapshot**：cache 流水线到 page window 的原子 handoff；批次提交前校验书籍、快照、排版代次、请求边界；空洞 / 重叠 / 旧代次直接丢弃

### 并发

窗口读取、缓存写入、搜索、目录生成、段补取、页面预排版 — 各自独立单线程执行器；不可变快照 + 排版代次校验保证后台结果不会推动已过期的活动窗口。

`onPause()` 停止自动翻页、撤销亮屏、保存正式进度并刷新待写缓存；`onDestroy()` 移除 Handler 回调并关闭全部执行器。

## 构建

需要 Java 17 和 Android SDK 35（`compileSdk 35`，`minSdk 23`，`targetSdk 35`）。

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# Debug APK（自动 bump versionCode）
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/xlib-debug.apk

# Release APK（需要签名配置，见下）
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/xlib-release.apk
```

成功完成 `assembleDebug` 或 `assembleRelease` 后，Gradle 的 `bumpVersion` 任务（`finalizedBy` 注册到两个 task）会把 `version.properties` 的 `versionCode` 加 1。`versionName` 不会被自动修改。

## 版本号

`version.properties` 是 `versionCode` 和 `versionName` 的唯一来源：

```properties
versionName=0.8.4
versionCode=46
```

- `versionName` — 仅在显式要求时修改
- `versionCode` — 每次成功构建后自动 +1
- 书架页面左下角显示 `v<versionName> build <versionCode>`

> 只在用户明确要求时跑 Debug / Release 构建；普通文档或代码整理不应触发版本递增。

## Release 签名

Release 构建使用 `app/release.jks`（已在 `.gitignore`），凭据在 `local.properties`（同样 gitignored）：

```properties
RELEASE_STORE_FILE=release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

`local.properties` 不在仓库内，按上面四键手动创建即可。keystore 泄漏或需要换签时重新生成：

```bash
keytool -genkey -v -keystore app/release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias xlib
```

签名方案：v1 + v2。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

测试覆盖：`AutoPageOptions`, `ByteOffsetMap`, `CacheCombineStack`, `ReaderDisplayPolicy`, `ReaderPageRefillPolicy`, `ReaderPageWindow`, `ReaderPosition`, `ReaderRuntimePolicy`, `ReaderSettingsOptions`, `TextFileUtils`, `TocGenerator`。

`docs/product-requirements.md` 第 10 节是端到端验收清单（主题、设置、书架、阅读、搜索、工程）。代码改动需按对应能力跑测试 / Lint / 真机验收；编译通过不等于视觉验收通过。

## 项目结构

```
xlib/
├── app/
│   ├── build.gradle                       # module 脚本（bumpVersion、签名配置）
│   ├── release.jks                        # release 签名密钥（gitignored）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/xlib/txtreader/   # 37 个 Java 源文件
│       │   └── res/                       # drawable / mipmap / values / values-night
│       └── test/java/com/xlib/txtreader/  # 11 个 JUnit 测试类
├── docs/
│   ├── product-requirements.md            # 产品 / 交互 / 验收清单（当前有效）
│   └── architecture.md                    # 数据归属、阅读定位、缓存、并发边界
├── release/                               # 发行 APK（gitignored）
├── version.properties                     # versionCode + versionName 唯一来源
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── local.properties                       # 签名凭据（gitignored）
└── README.md
```

### 主要类一览

- **入口**：`MainActivity`, `UiKit`, `AccessibleScrollView`
- **持久化**：`Book`, `BookStore`, `Bookmark`, `BookmarkStore`, `TocDocument`, `TocEntry`, `TocGenerator`, `TocStore`
- **设置 / 策略**：`ReaderSettingsOptions`, `ReaderDisplayPolicy`, `ReaderRuntimePolicy`, `AutoPageOptions`
- **阅读位置**：`ReaderPosition`, `ByteOffsetMap`, `TextFileUtils`

## 相关文档

- [`docs/product-requirements.md`](docs/product-requirements.md) — 产品 / 交互 / 验收清单
- [`docs/architecture.md`](docs/architecture.md) — 数据归属、阅读定位、缓存、并发边界
