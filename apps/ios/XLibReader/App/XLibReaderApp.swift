import SwiftUI

@main
struct XLibReaderApp: App {
    private struct StartupContext {
        let settings: SettingsStore
        let library: LibraryModel
        let sync: ProgressSyncCoordinator
        let store: LibraryStore
        #if DEBUG || XLIB_P8_DEVICE_PERFORMANCE
        let prepareFixture: (() async throws -> Void)?
        #endif
    }

    private let startup: Result<StartupContext, Error>

    init() {
        do {
            #if XLIB_P8_DEVICE_PERFORMANCE
            if let value = ProcessInfo.processInfo.environment["XLIB_P8_BULK_TEST_ID"],
               let id = UUID(uuidString: value) {
                let root = FileManager.default.temporaryDirectory.appending(path: "XLibP8Bulk-\(id.uuidString)")
                let defaults = UserDefaults(suiteName: "com.xlib.p8bulk.\(id.uuidString)")!
                let database = try LocalDatabase.open(root: root, defaults: defaults)
                let store = LibraryStore(root: root, database: database)
                let sync = ProgressSyncCoordinator(
                    api: Self.fixtureAPI,
                    vault: SyncCredentialVault(load: { nil }, save: { _ in }, clear: {}),
                    stateStore: SyncStateStore(database: database),
                    connectivity: SyncConnectivityMonitor(started: false),
                    database: database
                )
                let mode = ProcessInfo.processInfo.environment["XLIB_P8_BULK_MODE"]
                startup = .success(StartupContext(
                    settings: SettingsStore(database: database),
                    library: LibraryModel(store: store),
                    sync: sync,
                    store: store,
                    prepareFixture: {
                        try await P8BulkPerformanceHarness.prepare(mode: mode, root: root, store: store)
                    }
                ))
                return
            }
            #endif

            #if DEBUG
            if let value = ProcessInfo.processInfo.environment["XLIB_UI_TEST_ID"],
               let id = UUID(uuidString: value) {
                let root = FileManager.default.temporaryDirectory.appending(path: "XLibUITests-\(id.uuidString)")
                let defaults = UserDefaults(suiteName: "com.xlib.uitests.\(id.uuidString)")!
                let database = try LocalDatabase.open(root: root, defaults: defaults)
                let store = LibraryStore(root: root, database: database)
                let sync = ProgressSyncCoordinator(
                    api: Self.fixtureAPI,
                    vault: SyncCredentialVault(load: { nil }, save: { _ in }, clear: {}),
                    stateStore: SyncStateStore(database: database),
                    connectivity: SyncConnectivityMonitor(started: false),
                    database: database
                )
                let scenario = ProcessInfo.processInfo.environment["XLIB_UI_TEST_SCENARIO"]
                let prepareFixture = {
                    if scenario != nil, try await store.load().isEmpty {
                        let source = root.appending(path: "能力验收.txt")
                        let text = (1...30).map { "第\($0)章\n这是第\($0)段测试正文。needle 用于验证搜索与临时阅读。\n" }.joined()
                        try Data(text.utf8).write(to: source)
                        let book = try await store.importBook(from: source)
                        try await store.saveTOC([], for: book)
                    }
                    if scenario == "sync" {
                        await sync.start()
                        _ = await sync.saveConfiguredEmail("fixture@example.com")
                        _ = await sync.startConfiguredSync()
                    }
                }
                startup = .success(StartupContext(
                    settings: SettingsStore(database: database),
                    library: LibraryModel(store: store),
                    sync: sync,
                    store: store,
                    prepareFixture: prepareFixture
                ))
                return
            }
            #endif

            let database = try LocalDatabase.open()
            let store = LibraryStore(database: database)
            #if DEBUG || XLIB_P8_DEVICE_PERFORMANCE
            startup = .success(StartupContext(
                settings: SettingsStore(database: database),
                library: LibraryModel(store: store),
                sync: ProgressSyncCoordinator(database: database),
                store: store,
                prepareFixture: nil
            ))
            #else
            startup = .success(StartupContext(
                settings: SettingsStore(database: database),
                library: LibraryModel(store: store),
                sync: ProgressSyncCoordinator(database: database),
                store: store
            ))
            #endif
        } catch {
            startup = .failure(error)
        }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                switch startup {
                case .success(let context):
                    LibraryView(model: context.library, store: context.store, settings: context.settings)
                        .environment(context.sync)
                        .tint(context.settings.settings.theme.accent)
                        .preferredColorScheme(context.settings.settings.theme.colorScheme)
                        .task {
                            #if DEBUG || XLIB_P8_DEVICE_PERFORMANCE
                            do { try await context.prepareFixture?() }
                            catch { assertionFailure("UI fixture setup failed: \(error)") }
                            #endif
                            await context.library.load()
                            await context.sync.start()
                        }
                        .modifier(SyncAppLifecycleModifier(sync: context.sync))
                case .failure:
                    ContentUnavailableView(
                        "本地书库暂时无法打开",
                        systemImage: "externaldrive.badge.exclamationmark",
                        description: Text("原有书籍和凭据未被修改，请重新启动应用后再试。")
                    )
                }
            }
        }
    }
}

#if DEBUG || XLIB_P8_DEVICE_PERFORMANCE
private extension XLibReaderApp {
    // In-memory responses only: UI tests never contact a live server or Keychain.
    static var fixtureAPI: SyncAPIClient {
        SyncAPIClient(
            isConfigured: true,
            startSync: { request in
                SyncStartResponse(token: "ui-fixture", user: .init(userId: UUID(), email: request.email),
                                  device: .init(deviceId: request.device.deviceId, deviceName: request.device.deviceName, platform: "ios"), serverTimeMs: 0)
            },
            pullProgress: { _ in .init(serverTimeMs: 0, items: []) },
            syncProgress: { _, _ in .init(serverTimeMs: 0, results: []) },
            deleteBookProgress: { _, _ in },
            listDevices: { _ in [] },
            deleteDevice: { _, _ in },
            health: { true }
        )
    }
}
#endif

private struct SyncAppLifecycleModifier: ViewModifier {
    let sync: ProgressSyncCoordinator
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.onChange(of: scenePhase) { _, phase in
            Task {
                switch phase {
                case .active: await sync.appBecameActive()
                case .background: await sync.appEnteredBackground()
                case .inactive: break
                @unknown default: break
                }
            }
        }
    }
}
