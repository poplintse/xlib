import SwiftUI

@main
struct XLibReaderApp: App {
    @State private var settings: SettingsStore
    @State private var library: LibraryModel
    @State private var sync: ProgressSyncCoordinator
    private let store: LibraryStore

    init() {
        let store = LibraryStore()
        self.store = store
        _settings = State(initialValue: SettingsStore())
        _library = State(initialValue: LibraryModel(store: store))
        _sync = State(initialValue: ProgressSyncCoordinator())
    }

    var body: some Scene {
        WindowGroup {
            LibraryView(model: library, store: store, settings: settings)
                .environment(sync)
                .tint(settings.settings.theme.accent)
                .preferredColorScheme(settings.settings.theme.colorScheme)
                .task {
                    await library.load()
                    await sync.start()
                }
                .modifier(SyncAppLifecycleModifier(sync: sync))
        }
    }
}

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
