import Foundation
import Observation

@MainActor
@Observable
final class SettingsStore {
    private(set) var settings: ReaderSettings
    private let database: LocalDatabase

    init(database: LocalDatabase) {
        self.database = database
        if let stored = try? database.readerSettings() {
            settings = stored
        } else {
            settings = ReaderSettings()
        }
    }

    func update(_ mutation: (inout ReaderSettings) -> Void) {
        mutation(&settings)
        settings.normalize()
        try? database.saveReaderSettings(settings)
    }
}
