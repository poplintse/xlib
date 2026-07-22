import SwiftUI

struct SyncSettingsView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @State private var confirmation: SyncSettingsConfirmation?

    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "阅读进度同步", theme: theme) {
            SettingsSection(title: "同步服务器", theme: theme) {
                SettingsNavigationRow(
                    title: "服务器地址",
                    value: sync.serverAddress,
                    accessibilityIdentifier: "sync.serverAddress",
                    theme: theme
                ) {
                    SyncServerAddressView(store: store)
                }
            }

            SettingsSection(title: "状态", theme: theme) {
                SyncValueRow(title: "同步状态", value: sync.statusTitle, theme: theme)
                if sync.isSyncEnabled {
                    SettingsDivider(theme: theme)
                    SyncValueRow(title: "邮箱", value: sync.email ?? "—", theme: theme)
                    SettingsDivider(theme: theme)
                    SyncValueRow(title: "当前设备", value: sync.currentDeviceName, theme: theme)
                    SettingsDivider(theme: theme)
                    SyncValueRow(title: "最后同步", value: lastSyncText, theme: theme)
                }
            }

            if !sync.isServiceConfigured {
                SettingsNote(
                    text: "当前服务器地址无效，请修改后保存。本地书架和阅读功能不受影响。",
                    theme: theme
                )
            } else if sync.isSyncEnabled {
                SettingsSection(title: "同步管理", theme: theme) {
                    SyncActionRow(
                        title: sync.isWorking ? "正在刷新…" : "刷新云端状态",
                        systemImage: "arrow.clockwise",
                        theme: theme,
                        disabled: sync.isWorking
                    ) {
                        Task { await sync.refreshRemoteStates() }
                    }
                    SettingsDivider(theme: theme)
                    SettingsNavigationRow(
                        title: "设备管理",
                        value: nil,
                        accessibilityIdentifier: "sync.devices",
                        theme: theme
                    ) {
                        SyncDevicesView(store: store)
                    }
                    SettingsDivider(theme: theme)
                    SyncActionRow(
                        title: "关闭本机同步",
                        systemImage: "rectangle.portrait.and.arrow.right",
                        theme: theme
                    ) {
                        confirmation = .disableSync
                    }
                }

                SettingsSection(title: "云端数据", theme: theme) {
                    SyncActionRow(
                        title: "删除云端阅读进度",
                        systemImage: "trash",
                        theme: theme,
                        destructive: true
                    ) {
                        confirmation = .deleteProgress
                    }
                }
                SettingsNote(text: "删除云端进度不会删除本机书籍和本机阅读位置。", theme: theme)
            } else {
                SettingsSection(title: "可选功能", theme: theme) {
                    SettingsNavigationRow(
                        title: "开始同步",
                        value: nil,
                        accessibilityIdentifier: "sync.start",
                        theme: theme
                    ) {
                        SyncStartView(store: store)
                    }
                }
                SettingsNote(
                    text: "同步只保存阅读时间和精确进度。TXT 文件、书架、目录和书签不会上传。",
                    theme: theme
                )
            }

            if let failure = sync.lastFailureMessage, sync.isSyncEnabled {
                SettingsNote(text: failure, theme: theme)
                    .accessibilityIdentifier("sync.statusMessage")
            }
        }
        .confirmationDialog(
            confirmation?.title ?? "确认操作",
            isPresented: Binding(
                get: { confirmation != nil },
                set: { if !$0 { confirmation = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let confirmation {
                Button(confirmation.actionTitle, role: confirmation.role) {
                    perform(confirmation)
                }
                Button("取消", role: .cancel) { self.confirmation = nil }
            }
        } message: {
            Text(confirmation?.message ?? "")
        }
    }

    private var lastSyncText: String {
        guard let date = sync.lastSuccessAt else { return "尚未同步" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private func perform(_ confirmation: SyncSettingsConfirmation) {
        self.confirmation = nil
        switch confirmation {
        case .disableSync:
            Task { await sync.disableSync() }
        case .deleteProgress:
            Task { _ = await sync.deleteCloudProgress() }
        }
    }
}

private struct SyncServerAddressView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var localError: String?
    @State private var isSaving = false
    @FocusState private var addressIsFocused: Bool

    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "服务器地址", theme: theme) {
            SettingsSection(title: "HTTPS 地址", theme: theme) {
                TextField("https://example.com/xlib/backend", text: $address)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.URL)
                    .focused($addressIsFocused)
                    .submitLabel(.done)
                    .onSubmit { save() }
                    .frame(minHeight: 58)
                    .foregroundStyle(theme.text)
                    .accessibilityIdentifier("sync.serverAddressField")
            }

            if let localError {
                SettingsNote(text: localError, theme: theme)
                    .accessibilityIdentifier("sync.serverAddressError")
            }

            Button { save() } label: {
                HStack(spacing: 8) {
                    if isSaving { ProgressView().tint(theme.surface) }
                    Text(isSaving ? "正在保存…" : "保存")
                        .font(.headline)
                }
                .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(XLPrimaryButtonStyle(theme: theme))
            .disabled(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSaving)
            .opacity(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSaving ? 0.55 : 1)
            .accessibilityIdentifier("sync.serverAddressSave")

            SettingsNote(
                text: sync.isSyncEnabled
                    ? "修改服务器后，本机同步 Token 会被清除，需要重新输入邮箱开启同步。"
                    : "默认地址为 \(SyncServerConfiguration.defaultAddress)，只支持 HTTPS。",
                theme: theme
            )
        }
        .onAppear {
            address = sync.serverAddress
            addressIsFocused = true
        }
    }

    private func save() {
        guard !isSaving else { return }
        isSaving = true
        localError = nil
        Task {
            if await sync.saveServerAddress(address) {
                dismiss()
            } else {
                localError = sync.lastFailureMessage ?? "服务器地址无法保存。"
            }
            isSaving = false
        }
    }
}

private enum SyncSettingsConfirmation {
    case disableSync
    case deleteProgress

    var title: String {
        switch self {
        case .disableSync: "关闭本机同步？"
        case .deleteProgress: "删除全部云端阅读进度？"
        }
    }

    var actionTitle: String {
        switch self {
        case .disableSync: "关闭同步"
        case .deleteProgress: "删除云端进度"
        }
    }

    var message: String {
        switch self {
        case .disableSync: "本机将删除同步 Token 并停止同步，其他设备和云端进度不受影响。"
        case .deleteProgress: "此操作只删除服务器上的阅读进度，无法撤销。"
        }
    }

    var role: ButtonRole? {
        switch self {
        case .disableSync: nil
        case .deleteProgress: .destructive
        }
    }
}

private struct SyncStartView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var localError: String?
    @FocusState private var emailIsFocused: Bool

    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "开始同步", theme: theme) {
            SettingsSection(title: "同步邮箱", theme: theme) {
                TextField("邮箱", text: $email)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.emailAddress)
                    .focused($emailIsFocused)
                    .submitLabel(.go)
                    .onSubmit { submit() }
                    .frame(minHeight: 58)
                    .foregroundStyle(theme.text)
                    .accessibilityIdentifier("sync.email")
            }

            if let message = localError ?? sync.lastFailureMessage {
                SettingsNote(text: message, theme: theme)
                    .accessibilityIdentifier("sync.authError")
            }

            Button {
                submit()
            } label: {
                HStack(spacing: 8) {
                    if sync.isWorking { ProgressView().tint(theme.surface) }
                    Text(sync.isWorking ? "正在开启…" : "开始同步")
                        .font(.headline)
                }
                .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(XLPrimaryButtonStyle(theme: theme))
            .disabled(!canSubmit || sync.isWorking)
            .opacity(!canSubmit || sync.isWorking ? 0.55 : 1)
            .accessibilityIdentifier("sync.startSubmit")

            SettingsNote(
                text: "服务端会为邮箱创建或返回已有同步 Token，App 会把 Token 保存在本机安全存储中。同步仍是可选功能。",
                theme: theme
            )
        }
        .onAppear { emailIsFocused = true }
    }

    private var canSubmit: Bool {
        Self.isValidEmail(email)
    }

    private func submit() {
        guard canSubmit else {
            localError = "请输入有效的邮箱地址。"
            return
        }
        localError = nil
        Task {
            if await sync.startSync(email: email) {
                dismiss()
            }
        }
    }

    private static func isValidEmail(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = trimmed.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty else { return false }
        return parts[1].contains(".") && !parts[1].hasPrefix(".") && !parts[1].hasSuffix(".")
    }
}

private struct SyncDevicesView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "设备管理", theme: theme) {
            SettingsSection(title: "同步设备", theme: theme) {
                if sync.devices.isEmpty {
                    SyncValueRow(title: "设备", value: sync.isWorking ? "正在加载…" : "暂无设备", theme: theme)
                } else {
                    ForEach(Array(sync.devices.enumerated()), id: \.element.id) { index, device in
                        HStack(spacing: 12) {
                            Image(systemName: device.platform == "ios" ? "iphone" : "smartphone")
                                .foregroundStyle(theme.accent)
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(device.deviceName).foregroundStyle(theme.text)
                                Text(device.deviceId == sync.currentDeviceID ? "当前设备" : device.platform.uppercased())
                                    .font(.caption)
                                    .foregroundStyle(theme.secondaryText)
                            }
                            Spacer()
                            if device.deviceId != sync.currentDeviceID {
                                Button("移除", role: .destructive) {
                                    Task { await sync.removeDevice(device) }
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                        .frame(minHeight: 58)
                        if index < sync.devices.count - 1 { SettingsDivider(theme: theme) }
                    }
                }
            }
            SettingsNote(text: "移除设备会停止该设备使用同步服务，不删除阅读进度。再次输入邮箱即可重新启用。", theme: theme)
        }
        .task { await sync.loadDevices() }
    }
}

private struct SyncValueRow: View {
    let title: String
    let value: String
    let theme: AppTheme

    var body: some View {
        HStack(spacing: 12) {
            Text(title).foregroundStyle(theme.text)
            Spacer()
            Text(value)
                .foregroundStyle(theme.secondaryText)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
        }
        .frame(minHeight: 58)
    }
}

private struct SyncActionRow: View {
    let title: String
    let systemImage: String
    let theme: AppTheme
    var destructive = false
    var disabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .foregroundStyle(destructive ? theme.danger : theme.accent)
                    .frame(width: 24)
                Text(title)
                    .foregroundStyle(destructive ? theme.danger : theme.text)
                Spacer()
            }
            .frame(minHeight: 58)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.55 : 1)
    }
}
