import SwiftUI

@main
struct XLibReaderApp: App {
    @State private var settings: SettingsStore
    @State private var library: LibraryModel
    @State private var sync: ProgressSyncCoordinator
    private let store: LibraryStore
    #if DEBUG
    private var prepareFixture: (() async throws -> Void)?
    #endif

    init() {
        #if DEBUG
        if let value = ProcessInfo.processInfo.environment["XLIB_UI_TEST_ID"],
           let id = UUID(uuidString: value) {
            let root = FileManager.default.temporaryDirectory.appending(path: "XLibUITests-\(id.uuidString)")
            let defaults = UserDefaults(suiteName: "com.xlib.uitests.\(id.uuidString)")!
            let database = try? LocalDatabase.open(root: root, defaults: defaults)
            let store = LibraryStore(root: root, database: database)
            let sync = ProgressSyncCoordinator(
                api: Self.fixtureAPI,
                vault: SyncCredentialVault(load: { nil }, save: { _ in }, clear: {}),
                stateStore: SyncStateStore(root: root.appending(path: "Sync"), database: database),
                connectivity: SyncConnectivityMonitor(started: false),
                defaults: defaults,
                database: database
            )
            self.store = store
            _settings = State(initialValue: SettingsStore(defaults: defaults, database: database))
            _library = State(initialValue: LibraryModel(store: store))
            _sync = State(initialValue: sync)
            let scenario = ProcessInfo.processInfo.environment["XLIB_UI_TEST_SCENARIO"]
            prepareFixture = {
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
            return
        }
        #endif
        let database = try? LocalDatabase.open()
        let store = LibraryStore(database: database)
        self.store = store
        _settings = State(initialValue: SettingsStore(database: database))
        _library = State(initialValue: LibraryModel(store: store))
        _sync = State(initialValue: ProgressSyncCoordinator(
            stateStore: SyncStateStore(database: database),
            database: database
        ))
    }

    var body: some Scene {
        WindowGroup {
            LibraryView(model: library, store: store, settings: settings)
                .environment(sync)
                .tint(settings.settings.theme.accent)
                .preferredColorScheme(settings.settings.theme.colorScheme)
                .task {
                    #if DEBUG
                    do { try await prepareFixture?() }
                    catch { assertionFailure("UI fixture setup failed: \(error)") }
                    #endif
                    await library.load()
                    await sync.start()
                }
                .modifier(SyncAppLifecycleModifier(sync: sync))
        }
    }
}

#if DEBUG
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
                case .active:
                    await sync.appBecameActive()
                case .background:
                    await sync.appEnteredBackground()
                case .inactive:
                    break
                @unknown default:
                    break
                }
            }
        }
    }
}
