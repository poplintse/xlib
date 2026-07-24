import SwiftUI
import UIKit

enum AppTheme: String, Codable, CaseIterable, Identifiable, Sendable {
    case light
    case dark

    var id: String { rawValue }
    var colorScheme: ColorScheme { self == .light ? .light : .dark }
    var background: Color { self == .light ? Color.rgb(246, 247, 244) : Color.rgb(16, 19, 18) }
    var surface: Color { self == .light ? Color.rgb(255, 255, 252) : Color.rgb(28, 32, 30) }
    var surfaceVariant: Color { self == .light ? Color.rgb(232, 239, 236) : Color.rgb(42, 49, 46) }
    var text: Color { self == .light ? Color.rgb(28, 31, 29) : Color.rgb(237, 241, 237) }
    var secondaryText: Color { self == .light ? Color.rgb(99, 107, 102) : Color.rgb(174, 183, 178) }
    var accent: Color { self == .light ? Color.rgb(26, 104, 91) : Color.rgb(137, 216, 197) }
    var accentContainer: Color { self == .light ? Color.rgb(210, 239, 230) : Color.rgb(30, 79, 69) }
    var dangerContainer: Color { self == .light ? Color.rgb(255, 226, 225) : Color.rgb(86, 37, 38) }
    var danger: Color { self == .light ? Color.rgb(150, 24, 27) : Color.rgb(255, 180, 178) }
    var uiBackground: UIColor { UIColor(background) }
    var uiText: UIColor { UIColor(text) }
}

extension Color {
    static func rgb(_ red: Double, _ green: Double, _ blue: Double) -> Color {
        Color(red: red / 255, green: green / 255, blue: blue / 255)
    }
}

struct ReaderSettings: Codable, Equatable, Sendable {
    var theme: AppTheme = .light
    var fontName = ".AppleSystemUIFont"
    var fontSize = 20.0
    var lineSpacing = 3.6
    var keepScreenAwake = false
    var autoPageSeconds = 8
    var turnSensitivity = 0.45

    mutating func normalize() {
        fontSize = min(34, max(14, fontSize))
        lineSpacing = min(12, max(0, lineSpacing))
        autoPageSeconds = min(30, max(3, autoPageSeconds))
        turnSensitivity = min(0.8, max(0.2, turnSensitivity))
    }

    var screenLockIconName: String {
        keepScreenAwake ? "lock.fill" : "lock.open"
    }
}
