import Foundation
import Observation

@MainActor
@Observable
final class SettingsStore {
    private(set) var settings: ReaderSettings
    private let defaults: UserDefaults
    private let database: LocalDatabase?
    private static let key = "reader.settings.v1"

    init(defaults: UserDefaults = .standard, database: LocalDatabase? = nil) {
        self.defaults = defaults
        self.database = database
        if let database, let stored = try? database.readerSettings() {
            settings = stored
        } else if let data = defaults.data(forKey: Self.key),
           var decoded = try? JSONDecoder().decode(ReaderSettings.self, from: data) {
            decoded.normalize()
            settings = decoded
        } else {
            settings = ReaderSettings()
        }
    }

    func update(_ mutation: (inout ReaderSettings) -> Void) {
        mutation(&settings)
        settings.normalize()
        if let database {
            try? database.saveReaderSettings(settings)
        } else if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: Self.key)
        }
    }
}
