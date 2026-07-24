import Foundation
import Observation

@MainActor
@Observable
final class SettingsStore {
    private(set) var settings: ReaderSettings
    private let defaults: UserDefaults
    private static let key = "reader.settings.v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.key),
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
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: Self.key)
        }
    }
}
