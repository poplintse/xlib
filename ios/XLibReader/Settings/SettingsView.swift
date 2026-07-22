import SwiftUI

struct SettingsView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @State private var numericSetting: NumericSetting?
    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "设置", theme: theme) {
            SettingsSection(title: "常规", theme: theme) {
                SettingsNavigationRow(
                    title: "应用主题",
                    value: themeTitle,
                    accessibilityIdentifier: "settings.theme",
                    theme: theme
                ) {
                    SettingsChoiceView(
                        title: "应用主题",
                        sectionTitle: "外观",
                        note: "主题会应用到书架、阅读、搜索和设置页面。",
                        options: [
                            SettingsOption(value: AppTheme.light, title: "浅色"),
                            SettingsOption(value: AppTheme.dark, title: "深色")
                        ],
                        store: store,
                        keyPath: \.theme
                    )
                }
            }

            SettingsSection(title: "阅读", theme: theme) {
                SettingsToggleRow(
                    title: "阅读时锁屏",
                    isOn: binding(\.keepScreenAwake),
                    accessibilityIdentifier: "settings.keepScreenAwake",
                    theme: theme
                )
                SettingsDivider(theme: theme)
                SettingsActionRow(
                    title: "自动翻页间隔",
                    value: "\(store.settings.autoPageSeconds) 秒",
                    accessibilityIdentifier: "settings.autoPageSeconds",
                    theme: theme,
                    action: { numericSetting = .autoPageSeconds }
                )
                SettingsDivider(theme: theme)
                SettingsNavigationRow(
                    title: "触摸灵敏度",
                    value: sensitivityTitle,
                    accessibilityIdentifier: "settings.turnSensitivity",
                    theme: theme
                ) {
                    SettingsChoiceView(
                        title: "触摸灵敏度",
                        sectionTitle: "灵敏度",
                        note: "灵敏度越高，点击和滑动翻页越容易触发。",
                        options: [
                            SettingsOption(value: 0.30, title: "高"),
                            SettingsOption(value: 0.45, title: "中"),
                            SettingsOption(value: 0.60, title: "低")
                        ],
                        store: store,
                        keyPath: \.turnSensitivity
                    )
                }
                SettingsDivider(theme: theme)
                SettingsNavigationRow(
                    title: "字体",
                    value: fontTitle,
                    accessibilityIdentifier: "settings.font",
                    theme: theme
                ) {
                    SettingsChoiceView(
                        title: "字体",
                        sectionTitle: "正文字体",
                        note: "当前字体会应用到所有书籍；字体不可用时自动回退到系统字体。",
                        options: [
                            SettingsOption(value: ".AppleSystemUIFont", title: "系统"),
                            SettingsOption(value: "PingFangSC-Regular", title: "黑体"),
                            SettingsOption(value: "Songti SC", title: "宋体"),
                            SettingsOption(value: "FangSong", title: "仿宋"),
                            SettingsOption(value: "Menlo", title: "等宽")
                        ],
                        store: store,
                        keyPath: \.fontName
                    )
                }
                SettingsDivider(theme: theme)
                SettingsActionRow(
                    title: "字号",
                    value: "\(Int(store.settings.fontSize)) pt",
                    accessibilityIdentifier: "settings.fontSize",
                    theme: theme,
                    action: { numericSetting = .fontSize }
                )
                SettingsDivider(theme: theme)
                SettingsActionRow(
                    title: "行间距",
                    value: formattedLineSpacing,
                    accessibilityIdentifier: "settings.lineSpacing",
                    theme: theme,
                    action: { numericSetting = .lineSpacing }
                )
            }

            SettingsSection(title: "同步", theme: theme) {
                SettingsNavigationRow(
                    title: "阅读进度同步",
                    value: sync.statusTitle,
                    accessibilityIdentifier: "settings.progressSync",
                    theme: theme
                ) {
                    SyncSettingsView(store: store)
                }
            }

            Text(appVersion)
                .font(.caption)
                .foregroundStyle(theme.secondaryText)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 4)
        }
        .overlay {
            if let numericSetting {
                ZStack(alignment: .bottom) {
                    Color.black.opacity(0.34)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture {
                            self.numericSetting = nil
                        }
                        .accessibilityLabel("关闭数值选择")
                        .accessibilityAddTraits(.isButton)

                    SettingsNumericPanel(setting: numericSetting, store: store) {
                        self.numericSetting = nil
                    }
                }
                .zIndex(10)
            }
        }
    }

    private func binding<Value>(_ keyPath: WritableKeyPath<ReaderSettings, Value>) -> Binding<Value> {
        Binding(
            get: { store.settings[keyPath: keyPath] },
            set: { value in store.update { $0[keyPath: keyPath] = value } }
        )
    }

    private var themeTitle: String { theme == .light ? "浅色" : "深色" }

    private var sensitivityTitle: String {
        switch store.settings.turnSensitivity {
        case ..<0.38: "高"
        case 0.38..<0.53: "中"
        default: "低"
        }
    }

    private var fontTitle: String {
        switch store.settings.fontName {
        case "PingFangSC-Regular": "黑体"
        case "Songti SC": "宋体"
        case "FangSong": "仿宋"
        case "Menlo": "等宽"
        default: "系统"
        }
    }

    private var formattedLineSpacing: String { formatDecimal(store.settings.lineSpacing) }

    private func formatDecimal(_ value: Double) -> String {
        value.rounded() == value ? "\(Int(value))" : String(format: "%.1f", value)
    }

    private var appVersion: String {
        AppVersion.displayText
    }
}

private struct SettingsOption<Value: Hashable>: Hashable {
    let value: Value
    let title: String
}

private enum NumericSetting: String, Identifiable {
    case autoPageSeconds
    case fontSize
    case lineSpacing

    var id: String { rawValue }

    var title: String {
        switch self {
        case .autoPageSeconds: "自动翻页间隔"
        case .fontSize: "字号"
        case .lineSpacing: "行间距"
        }
    }

    var note: String {
        switch self {
        case .autoPageSeconds: "设置阅读页开启自动翻页后的翻页间隔。"
        case .fontSize: "与阅读页的 A− 和 A+ 使用相同的字号范围。"
        case .lineSpacing: "调整正文行与行之间的留白。"
        }
    }
}

struct SettingsPage<Content: View>: View {
    let title: String
    let theme: AppTheme
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 10) {
            SettingsPageHeader(title: title, theme: theme)
            ScrollView {
                LazyVStack(spacing: 18) {
                    content()
                }
                .padding(.bottom, 20)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .background(theme.background.ignoresSafeArea())
        .background {
            XLInteractivePopGestureEnabler()
                .frame(width: 0, height: 0)
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden()
        .tint(theme.accent)
    }
}

private struct SettingsPageHeader: View {
    let title: String
    let theme: AppTheme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        XLGlassToolbar(theme: theme, cornerRadius: 22, padding: 6) {
            ZStack {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(theme.text)
                HStack {
                    XLIconButton(
                        theme: theme,
                        size: 44,
                        foreground: theme.text,
                        action: { dismiss() }
                    ) {
                        Image(systemName: "chevron.left")
                    }
                    .accessibilityLabel("返回")
                    Spacer()
                }
            }
            .frame(maxWidth: .infinity)
        }
        .frame(height: 56)
        .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
    }
}

struct SettingsSection<Content: View>: View {
    let title: String
    let theme: AppTheme
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(theme.secondaryText)
                .padding(.leading, 14)
            VStack(spacing: 0) {
                content()
            }
            .padding(.horizontal, 14)
            .xlGlassSurface(theme: theme, cornerRadius: 24, tint: theme.surface.opacity(0.34))
        }
    }
}

struct SettingsDivider: View {
    let theme: AppTheme

    var body: some View {
        Rectangle()
            .fill(theme.secondaryText.opacity(0.16))
            .frame(height: 0.5)
            .padding(.leading, 2)
    }
}

private struct SettingsToggleRow: View {
    let title: String
    @Binding var isOn: Bool
    let accessibilityIdentifier: String
    let theme: AppTheme

    var body: some View {
        Toggle(isOn: $isOn) {
            Text(title)
                .font(.body)
                .foregroundStyle(theme.text)
        }
        .toggleStyle(.switch)
        .tint(theme.accent)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
        .accessibilityIdentifier(accessibilityIdentifier)
    }
}

struct SettingsNavigationRow<Destination: View>: View {
    let title: String
    let value: String?
    let accessibilityIdentifier: String
    let theme: AppTheme
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink(destination: destination) {
            HStack(spacing: 10) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(theme.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let value {
                    Text(value)
                        .font(.body)
                        .foregroundStyle(theme.secondaryText)
                        .lineLimit(1)
                }
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(theme.secondaryText.opacity(0.72))
                    .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 58)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityIdentifier)
        .accessibilityValue(value ?? "")
    }
}

private struct SettingsActionRow: View {
    let title: String
    let value: String
    let accessibilityIdentifier: String
    let theme: AppTheme
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(theme.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(value)
                    .font(.body)
                    .foregroundStyle(theme.secondaryText)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(theme.secondaryText.opacity(0.72))
                    .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 58)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityIdentifier)
        .accessibilityValue(value)
    }
}

private struct SettingsChoiceView<Value: Hashable>: View {
    let title: String
    let sectionTitle: String
    let note: String
    let options: [SettingsOption<Value>]
    @Bindable var store: SettingsStore
    let keyPath: WritableKeyPath<ReaderSettings, Value>
    private var theme: AppTheme { store.settings.theme }
    private var selection: Value { store.settings[keyPath: keyPath] }

    var body: some View {
        SettingsPage(title: title, theme: theme) {
            SettingsSection(title: sectionTitle, theme: theme) {
                ForEach(options.indices, id: \.self) { index in
                    let option = options[index]
                    Button {
                        store.update { $0[keyPath: keyPath] = option.value }
                    } label: {
                        HStack(spacing: 12) {
                            Text(option.title)
                                .font(.body)
                                .foregroundStyle(theme.text)
                            Spacer()
                            Image(systemName: "checkmark")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(theme.accent)
                                .opacity(selection == option.value ? 1 : 0)
                                .accessibilityHidden(true)
                        }
                        .frame(maxWidth: .infinity, minHeight: 58)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(option.title)
                    .accessibilityValue(selection == option.value ? "已选择" : "")
                    .accessibilityAddTraits(selection == option.value ? .isSelected : [])
                    if index < options.count - 1 {
                        SettingsDivider(theme: theme)
                    }
                }
            }
            SettingsNote(text: note, theme: theme)
        }
    }
}

private struct SettingsNumericPanel: View {
    let setting: NumericSetting
    @Bindable var store: SettingsStore
    let onSave: () -> Void
    @State private var autoPageSeconds: Int
    @State private var fontSize: Double
    @State private var lineSpacing: Double
    private var theme: AppTheme { store.settings.theme }

    init(setting: NumericSetting, store: SettingsStore, onSave: @escaping () -> Void) {
        self.setting = setting
        self.store = store
        self.onSave = onSave
        _autoPageSeconds = State(initialValue: store.settings.autoPageSeconds)
        _fontSize = State(initialValue: store.settings.fontSize)
        _lineSpacing = State(initialValue: store.settings.lineSpacing)
    }

    var body: some View {
        VStack(spacing: 0) {
            picker
                .padding(.horizontal, 28)
                .frame(height: 250)

            Button("保存") {
                save()
                onSave()
            }
            .font(.title3.weight(.semibold))
            .foregroundStyle(theme.accent)
            .frame(maxWidth: .infinity, minHeight: 68)
            .contentShape(Rectangle())
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.numericSave")
        }
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial, in: UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .background(theme.surface.opacity(0.76), in: UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .contentShape(UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28))
        .tint(theme.accent)
    }

    @ViewBuilder private var picker: some View {
        switch setting {
        case .autoPageSeconds:
            Picker("秒数", selection: $autoPageSeconds) {
                ForEach(3...30, id: \.self) { value in
                    Text("\(value) 秒").tag(value)
                }
            }
            .pickerStyle(.wheel)
            .labelsHidden()
            .frame(maxWidth: .infinity, minHeight: 230)
            .clipped()
            .accessibilityIdentifier("settings.numericPicker")

        case .fontSize:
            Picker("字号", selection: $fontSize) {
                ForEach(stride(from: 14.0, through: 34.0, by: 2.0).map { $0 }, id: \.self) { value in
                    Text("\(Int(value)) pt").tag(value)
                }
            }
            .pickerStyle(.wheel)
            .labelsHidden()
            .frame(maxWidth: .infinity, minHeight: 230)
            .clipped()
            .accessibilityIdentifier("settings.numericPicker")

        case .lineSpacing:
            Picker("行间距", selection: $lineSpacing) {
                ForEach(stride(from: 0.0, through: 12.0, by: 0.5).map { $0 }, id: \.self) { value in
                    Text(value.rounded() == value ? "\(Int(value))" : String(format: "%.1f", value)).tag(value)
                }
            }
            .pickerStyle(.wheel)
            .labelsHidden()
            .frame(maxWidth: .infinity, minHeight: 230)
            .clipped()
            .accessibilityIdentifier("settings.numericPicker")
        }
    }

    private func save() {
        store.update { settings in
            switch setting {
            case .autoPageSeconds:
                settings.autoPageSeconds = autoPageSeconds
            case .fontSize:
                settings.fontSize = fontSize
            case .lineSpacing:
                settings.lineSpacing = lineSpacing
            }
        }
    }
}

struct SettingsNote: View {
    let text: String
    let theme: AppTheme

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(theme.secondaryText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.top, -10)
    }
}
