import Foundation

enum AppVersion {
    static var displayText: String {
        displayText(
            version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        )
    }

    static func displayText(version: String?, build: String?) -> String {
        "xLib Reader v\(version ?? "—") build \(build ?? "—")"
    }
}
