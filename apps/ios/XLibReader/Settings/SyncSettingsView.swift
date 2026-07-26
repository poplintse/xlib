import SwiftUI

struct SyncSettingsView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync

    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "阅读进度同步", theme: theme) {
            SettingsSection(title: "状态", theme: theme) {
                SyncValueRow(title: "同步状态", value: sync.statusTitle, theme: theme)
                SettingsDivider(theme: theme)
                SyncValueRow(title: "最后同步", value: lastSyncText, theme: theme)
                SettingsDivider(theme: theme)
                SyncCenteredActionRow(
                    title: sync.isWorking ? "正在同步…" : "同步刷新",
                    theme: theme,
                    disabled: sync.isWorking,
                    accessibilityIdentifier: "sync.startStop"
                ) {
                    Task { await sync.syncRefresh() }
                }
                if let failure = sync.lastFailureMessage {
                    Text(failure)
                        .font(.footnote)
                        .foregroundStyle(theme.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 6)
                        .accessibilityIdentifier("sync.statusError")
                }
            }

            SettingsSection(title: "同步设置", theme: theme) {
                SettingsNavigationRow(
                    title: "邮箱",
                    value: sync.configuredEmail ?? "未设置",
                    accessibilityIdentifier: "sync.emailSettings",
                    theme: theme
                ) {
                    SyncEmailAddressView(store: store)
                }
                SettingsDivider(theme: theme)
                SettingsNavigationRow(
                    title: "当前设备",
                    value: sync.currentDeviceName,
                    accessibilityIdentifier: "sync.deviceNameSettings",
                    theme: theme
                ) {
                    SyncDeviceNameView(store: store)
                }
                SettingsDivider(theme: theme)
                SettingsNavigationRow(
                    title: "服务器地址",
                    value: sync.serverAddress,
                    accessibilityIdentifier: "sync.serverAddress",
                    theme: theme
                ) {
                    SyncServerAddressView(store: store)
                }
            }

            SettingsSection(title: "同步设备管理", theme: theme) {
                SettingsNavigationRow(
                    title: "设备管理",
                    value: nil,
                    accessibilityIdentifier: "sync.devices",
                    theme: theme
                ) {
                    SyncDevicesView(store: store)
                }
            }
        }
    }

    private var lastSyncText: String {
        guard let date = sync.lastSuccessAt else { return "尚未同步" }
        return date.formatted(date: .abbreviated, time: .shortened)
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

private struct SyncEmailAddressView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var localError: String?
    @FocusState private var emailIsFocused: Bool

    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "邮箱", theme: theme) {
            SettingsSection(title: "账户信息", theme: theme) {
                TextField("邮箱", text: $email)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.emailAddress)
                    .focused($emailIsFocused)
                    .submitLabel(.done)
                    .onSubmit { save() }
                    .frame(maxWidth: .infinity, minHeight: 58)
                    .foregroundStyle(theme.text)
                    .accessibilityIdentifier("sync.emailField")
            }
            if let localError {
                SettingsNote(text: localError, theme: theme)
                    .accessibilityIdentifier("sync.emailError")
            }
            Button { save() } label: {
                HStack(spacing: 8) {
                    if sync.isWorking { ProgressView().tint(theme.surface) }
                    Text(sync.isWorking ? "正在保存…" : "保存").font(.headline)
                }
                .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(XLPrimaryButtonStyle(theme: theme))
            .disabled(!Self.isValidEmail(email))
            .opacity(!Self.isValidEmail(email) ? 0.55 : 1)
            .accessibilityIdentifier("sync.emailSave")
            SettingsNote(text: "保存后请返回同步设置页启动同步。", theme: theme)
        }
        .onAppear {
            email = sync.configuredEmail ?? ""
            emailIsFocused = true
        }
    }

    private func save() {
        guard Self.isValidEmail(email) else {
            localError = "请输入有效的邮箱地址。"
            return
        }
        localError = nil
        if sync.saveConfiguredEmail(email) {
            dismiss()
        } else {
            localError = "邮箱无法保存。"
        }
    }

    private static func isValidEmail(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = trimmed.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty else { return false }
        return parts[1].contains(".") && !parts[1].hasPrefix(".") && !parts[1].hasSuffix(".")
    }
}

private struct SyncDeviceNameView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var deviceName = ""
    @State private var localError: String?
    @FocusState private var deviceNameIsFocused: Bool

    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "设备名称", theme: theme) {
            SettingsSection(title: "本机设备", theme: theme) {
                TextField("设备名称", text: $deviceName)
                    .textInputAutocapitalization(.sentences)
                    .autocorrectionDisabled()
                    .textContentType(.name)
                    .focused($deviceNameIsFocused)
                    .submitLabel(.done)
                    .onSubmit { save() }
                    .onChange(of: deviceName) { _, value in
                        if value.count > ProgressSyncCoordinator.maximumDeviceNameLength {
                            deviceName = String(value.prefix(ProgressSyncCoordinator.maximumDeviceNameLength))
                        }
                    }
                    .frame(maxWidth: .infinity, minHeight: 58)
                    .foregroundStyle(theme.text)
                    .accessibilityIdentifier("sync.deviceNameField")
            }
            if let localError {
                SettingsNote(text: localError, theme: theme)
                    .accessibilityIdentifier("sync.deviceNameError")
            }
            Button { save() } label: {
                HStack(spacing: 8) {
                    if sync.isWorking { ProgressView().tint(theme.surface) }
                    Text(sync.isWorking ? "正在保存…" : "保存").font(.headline)
                }
                .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(XLPrimaryButtonStyle(theme: theme))
            .disabled(deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .opacity(deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.55 : 1)
            .accessibilityIdentifier("sync.deviceNameSave")
            SettingsNote(text: "设备名称最多 20 个字符。保存后请返回同步设置页启动同步。", theme: theme)
        }
        .onAppear {
            deviceName = sync.currentDeviceName
            deviceNameIsFocused = true
        }
    }

    private func save() {
        guard !deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            localError = "请输入设备名称。"
            return
        }
        guard deviceName.count <= ProgressSyncCoordinator.maximumDeviceNameLength else {
            localError = "设备名称不能超过20个字符。"
            return
        }
        if sync.saveDeviceName(deviceName) {
            dismiss()
        } else {
            localError = "设备名称无法保存。"
        }
    }
}

private struct SyncDevicesView: View {
    @Bindable var store: SettingsStore
    @Environment(ProgressSyncCoordinator.self) private var sync
    @State private var removingDeviceID: UUID?
    @State private var removalError: String?
    private var theme: AppTheme { store.settings.theme }

    var body: some View {
        SettingsPage(title: "设备管理", theme: theme) {
            SettingsSection(title: "同步设备", theme: theme) {
                ForEach(Array(displayedDevices.enumerated()), id: \.element.id) { index, device in
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
                        if sync.isSyncEnabled, device.deviceId != sync.currentDeviceID {
                            Button {
                                remove(device)
                            } label: {
                                if removingDeviceID == device.deviceId {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Text("移除")
                                }
                            }
                            .buttonStyle(.borderless)
                            .tint(theme.danger)
                            .disabled(removingDeviceID != nil)
                            .accessibilityIdentifier("sync.removeDevice.\(device.deviceId.uuidString.lowercased())")
                        }
                    }
                    .frame(minHeight: 58)
                    if index < displayedDevices.count - 1 { SettingsDivider(theme: theme) }
                }
            }
            if sync.isSyncEnabled {
                SettingsNote(text: "移除设备会停止该设备使用同步服务，不删除阅读进度。", theme: theme)
            }
            if let removalError {
                Text(removalError)
                    .font(.footnote)
                    .foregroundStyle(theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .task { await sync.loadDevices() }
    }

    private var displayedDevices: [SyncDevice] {
        let current = SyncDevice(
            deviceId: sync.currentDeviceID,
            deviceName: sync.currentDeviceName,
            platform: "ios"
        )
        guard sync.isSyncEnabled else { return [current] }
        return sync.devices.contains(where: { $0.deviceId == current.deviceId })
            ? sync.devices
            : [current] + sync.devices
    }

    private func remove(_ device: SyncDevice) {
        removingDeviceID = device.deviceId
        removalError = nil
        Task {
            let removed = await sync.removeDevice(device)
            if !removed, device.deviceId != sync.currentDeviceID {
                removalError = sync.lastFailureMessage ?? "无法移除该设备，请稍后重试。"
            }
            removingDeviceID = nil
        }
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

private struct SyncCenteredActionRow: View {
    let title: String
    let theme: AppTheme
    var disabled = false
    var accessibilityIdentifier = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .foregroundStyle(theme.text)
                .frame(maxWidth: .infinity, minHeight: 58)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.55 : 1)
        .accessibilityIdentifier(accessibilityIdentifier)
    }
}
