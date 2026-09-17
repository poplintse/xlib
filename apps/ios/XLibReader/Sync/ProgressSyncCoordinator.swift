import Foundation
import Observation
import UIKit

@MainActor
@Observable
final class ProgressSyncCoordinator {
    private struct ReaderSession {
        let id: UUID
        let bookID: UUID
        var local: LocalProgressSnapshot
        var comparisonState: ReaderSyncComparisonState
        var promptedRemoteVersion: String?
        var lastObservedSequence: UInt64?
        var positionReady: Bool
    }

    private var api: SyncAPIClient
    private let vault: SyncCredentialVault
    private let stateStore: SyncStateStore
    private let connectivity: SyncConnectivityMonitor
    private let defaults: UserDefaults
    private let managesLiveAPI: Bool
    private let syncInterval: Duration
    private let healthProbeDelays: [Duration]
    private let now: @Sendable () -> Date
    private var deviceRegistration: SyncDeviceRegistration
    private var configuredEmailValue: String?

    private var credentials: SyncCredentials?
    private var remoteByKey: [SyncBookKey: RemoteProgressSnapshot] = [:]
    private var currentSession: ReaderSession?
    private var syncTimerTask: Task<Void, Never>?
    private var healthProbeTask: Task<Void, Never>?
    private var started = false
    private var appIsActive = true
    private var freshPullCompleted = false
    private var configurationGeneration = UUID()
    private var pullGeneration = UUID()
    private var pausedReadingSessions: Set<UUID> = []
    private var deletingKeys: Set<SyncBookKey> = []
    private var mutationBusy = false
    private var mutationWaiters: [CheckedContinuation<Void, Never>] = []
    private var credentialWrite: Task<Void, Never>?

    private(set) var availability: SyncAvailability = .available
    private(set) var lastSuccessAt: Date?
    private(set) var lastFailureMessage: String?
    private(set) var isWorking = false
    private(set) var devices: [SyncDevice] = []
    private(set) var serverAddress: String
    var jumpSuggestion: SyncJumpSuggestion?

    init(
        api: SyncAPIClient? = nil,
        vault: SyncCredentialVault = .live(),
        stateStore: SyncStateStore = SyncStateStore(),
        connectivity: SyncConnectivityMonitor = SyncConnectivityMonitor(),
        defaults: UserDefaults = .standard,
        syncInterval: Duration = .seconds(20),
        healthProbeDelays: [Duration] = [.seconds(30), .seconds(60), .seconds(120), .seconds(300)],
        now: @escaping @Sendable () -> Date = { .now }
    ) {
        let address = SyncServerConfiguration.resolvedAddress(defaults: defaults)
        serverAddress = address
        if let api {
            self.api = api
            managesLiveAPI = false
        } else {
            self.api = .live(address: address)
            managesLiveAPI = true
        }
        self.vault = vault
        self.stateStore = stateStore
        self.connectivity = connectivity
        self.defaults = defaults
        self.syncInterval = syncInterval
        self.healthProbeDelays = healthProbeDelays
        self.now = now
        deviceRegistration = Self.loadDeviceRegistration(defaults: defaults)
        configuredEmailValue = Self.loadConfiguredEmail(defaults: defaults)

        connectivity.setCallback { [weak self] online in
            Task { @MainActor [weak self] in
                await self?.networkChanged(isOnline: online)
            }
        }
    }

    var isServiceConfigured: Bool { api.isConfigured }
    var isSyncEnabled: Bool { credentials != nil }
    var email: String? { credentials?.email }
    var configuredEmail: String? { configuredEmailValue ?? credentials?.email }
    var currentDeviceName: String { deviceRegistration.deviceName }
    var currentDeviceID: UUID { credentials?.device.deviceId ?? deviceRegistration.deviceId }

    var statusTitle: String {
        guard api.isConfigured else { return "服务未配置" }
        guard credentials != nil else { return "未同步" }
        return availability.title
    }

    func start() async {
        guard !started else { return }
        started = true
        let generation = configurationGeneration
        let cached = await stateStore.cachedRemote()
        guard generation == configurationGeneration else { return }
        remoteByKey = Dictionary(uniqueKeysWithValues: cached.map { ($0.key, $0) })
        let loaded = await vault.load()
        guard generation == configurationGeneration else { return }
        credentials = loaded
        if configuredEmailValue == nil, let email = credentials?.email {
            configuredEmailValue = email
            defaults.set(email, forKey: Self.emailKey)
        }
        if credentials != nil {
            if let credentialServer = defaults.string(
                forKey: SyncServerConfiguration.credentialServerKey
            ), credentialServer != serverAddress {
                credentials = nil
                await vault.clear()
                remoteByKey.removeAll()
                try? await stateStore.clearRemote()
            } else {
                // Tokens created before server-address configuration are treated as
                // belonging to the currently resolved server, then scoped from here on.
                defaults.set(serverAddress, forKey: SyncServerConfiguration.credentialServerKey)
            }
        }
        if credentials != nil {
            defaults.set(true, forKey: Self.hasStartedSyncKey)
        }
        if credentials != nil {
            guard connectivity.isOnline() else {
                availability = .offline
                startSyncTimerIfNeeded()
                return
            }
            await pullOnly()
        }
        startSyncTimerIfNeeded()
    }

    func appBecameActive() async {
        appIsActive = true
        guard started else { return }
        startSyncTimerIfNeeded()
        guard credentials != nil else { return }
        guard connectivity.isOnline() else {
            availability = .offline
            return
        }
        freshPullCompleted = false
        await pullOnly()
        await prepareCurrentSessionAfterPull()
    }

    func appEnteredBackground() async {
        appIsActive = false
        syncTimerTask?.cancel()
        syncTimerTask = nil
        await syncCurrentProgress(forceLatest: true)
    }

    func startSync(email: String, deviceName: String? = nil) async -> Bool {
        guard !isWorking else { return false }
        let generation = configurationGeneration
        guard api.isConfigured else {
            lastFailureMessage = SyncAPIError.notConfigured.localizedDescription
            return false
        }
        guard connectivity.isOnline() else {
            availability = .offline
            lastFailureMessage = "当前网络不可用。"
            return false
        }

        let trimmedDeviceName = deviceName?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedDeviceName.map({ !$0.isEmpty }) ?? true else {
            lastFailureMessage = "请输入设备名称。"
            return false
        }
        guard trimmedDeviceName.map({ $0.count <= Self.maximumDeviceNameLength }) ?? true else {
            lastFailureMessage = "设备名称不能超过20个字符。"
            return false
        }
        var registration = deviceRegistration
        if let trimmedDeviceName { registration.deviceName = trimmedDeviceName }

        isWorking = true
        lastFailureMessage = nil
        defer { if generation == configurationGeneration { isWorking = false } }
        let request = SyncStartRequest(
            email: email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            device: registration
        )
        do {
            let response = try await api.startSync(request)
            guard generation == configurationGeneration, !Task.isCancelled else { return false }
            deviceRegistration = registration
            defaults.set(registration.deviceName, forKey: Self.deviceNameKey)
            credentials = response.credentials
            configuredEmailValue = response.credentials.email
            defaults.set(response.credentials.email, forKey: Self.emailKey)
            await saveCredentials(response.credentials)
            guard generation == configurationGeneration else { return false }
            defaults.set(serverAddress, forKey: SyncServerConfiguration.credentialServerKey)
            defaults.set(true, forKey: Self.hasStartedSyncKey)
            availability = .available
            freshPullCompleted = false
            await pullOnly()
            guard generation == configurationGeneration else { return false }
            await prepareCurrentSessionAfterPull()
            startSyncTimerIfNeeded()
            return true
        } catch is CancellationError {
            return false
        } catch {
            guard generation == configurationGeneration else { return false }
            handle(error)
            return false
        }
    }

    @discardableResult
    func saveConfiguredEmail(_ value: String) async -> Bool {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let parts = normalized.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2,
              !parts[0].isEmpty,
              parts[1].contains("."),
              !parts[1].hasPrefix("."),
              !parts[1].hasSuffix(".") else { return false }
        let changed = normalized != configuredEmailValue
        configuredEmailValue = normalized
        defaults.set(normalized, forKey: Self.emailKey)
        if changed {
            await clearSyncInformationAfterConfigurationChange()
            startSyncTimerIfNeeded()
        }
        return true
    }

    @discardableResult
    func saveDeviceName(_ value: String) async -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= Self.maximumDeviceNameLength else { return false }
        let changed = trimmed != deviceRegistration.deviceName
        deviceRegistration.deviceName = trimmed
        defaults.set(trimmed, forKey: Self.deviceNameKey)
        if changed {
            await clearSyncInformationAfterConfigurationChange()
            startSyncTimerIfNeeded()
        }
        return true
    }

    func startConfiguredSync() async -> Bool {
        guard let configuredEmail else {
            lastFailureMessage = "请先设置邮箱。"
            return false
        }
        return await startSync(email: configuredEmail, deviceName: currentDeviceName)
    }

    func disableSync() async {
        await clearSyncInformation()
    }

    private func clearSyncInformationAfterConfigurationChange() async {
        await clearSyncInformation()
    }

    private func clearSyncInformation() async {
        configurationGeneration = UUID()
        pullGeneration = UUID()
        isWorking = false
        syncTimerTask?.cancel()
        healthProbeTask?.cancel()
        credentials = nil
        currentSession?.comparisonState = .pending
        currentSession?.promptedRemoteVersion = nil
        jumpSuggestion = nil
        devices = []
        freshPullCompleted = false
        remoteByKey.removeAll()
        defaults.removeObject(forKey: SyncServerConfiguration.credentialServerKey)
        availability = .available
        lastSuccessAt = nil
        lastFailureMessage = nil
        // Enqueue the credential clear before yielding, so a new login always
        // saves after this clear even if persistence suspends.
        let clearing = enqueueCredentialWrite(nil)
        try? await stateStore.clearRemote()
        await clearing.value
    }

    func saveServerAddress(_ value: String) async -> Bool {
        guard let normalized = SyncServerConfiguration.normalizedAddress(value) else {
            lastFailureMessage = "请输入有效的 HTTPS 服务器地址。"
            return false
        }
        if normalized == serverAddress {
            defaults.set(normalized, forKey: SyncServerConfiguration.storageKey)
            lastFailureMessage = nil
            return true
        }

        serverAddress = normalized
        defaults.set(normalized, forKey: SyncServerConfiguration.storageKey)
        if managesLiveAPI { api = .live(address: normalized) }
        await clearSyncInformation()
        lastSuccessAt = nil
        lastFailureMessage = nil
        startSyncTimerIfNeeded()
        return true
    }

    func refreshRemoteStates() async {
        guard credentials != nil else { return }
        freshPullCompleted = false
        currentSession?.comparisonState = .pending
        jumpSuggestion = nil
        await pullOnly()
        compareCurrentSession()
    }

    func syncRefresh() async -> Bool {
        guard !isWorking else { return false }
        guard isServiceConfigured,
              configuredEmail != nil,
              !currentDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            lastFailureMessage = "请先完成同步配置。"
            return false
        }
        if availability == .tokenRequired { await clearSyncInformation() }
        guard isSyncEnabled else { return await startConfiguredSync() }

        isWorking = true
        lastFailureMessage = nil
        let generation = configurationGeneration
        defer { if generation == configurationGeneration { isWorking = false } }
        await refreshRemoteStates()
        return generation == configurationGeneration && lastFailureMessage == nil
    }

    func deleteCloudProgress(book: Book, fileURL: URL, readingSessionID: UUID? = nil) async -> Bool {
        let generation = configurationGeneration
        guard credentials != nil else {
            lastFailureMessage = "请先开启同步。"
            return false
        }
        do {
            let identity = try await stateStore.identity(for: book, fileURL: fileURL)
            guard generation == configurationGeneration else { return false }
            let key = identity.key
            guard key.fileSize > 0, !deletingKeys.contains(key) else { return false }
            deletingKeys.insert(key)
            defer { deletingKeys.remove(key) }
            // Waiting for the previous upload is essential: cancellation alone
            // cannot ensure the server has finished processing that request.
            await acquireMutation()
            defer { releaseMutation() }
            guard generation == configurationGeneration, !Task.isCancelled else { return false }
            try await authorized { authorization in
                try await self.api.deleteBookProgress(key, authorization)
            }
            if let readingSessionID { pausedReadingSessions.insert(readingSessionID) }
            if let current = currentSession, current.local.key == key {
                pausedReadingSessions.insert(current.id)
            }
            pullGeneration = UUID()
            remoteByKey.removeValue(forKey: key)
            if jumpSuggestion?.remote.key == key {
                jumpSuggestion = nil
                currentSession?.comparisonState = .completed
            }
            try? await stateStore.replaceRemote(Array(remoteByKey.values))
            guard generation == configurationGeneration else { return false }
            lastFailureMessage = nil
            lastSuccessAt = now()
            return true
        } catch is CancellationError {
            return false
        } catch {
            guard generation == configurationGeneration else { return false }
            handle(error)
            return false
        }
    }

    private func acquireMutation() async {
        if !mutationBusy {
            mutationBusy = true
            return
        }
        await withCheckedContinuation { mutationWaiters.append($0) }
    }

    private func enqueueCredentialWrite(_ value: SyncCredentials?) -> Task<Void, Never> {
        let previous = credentialWrite
        let task = Task { [vault] in
            await previous?.value
            if let value { await vault.save(value) }
            else { await vault.clear() }
        }
        credentialWrite = task
        return task
    }

    private func saveCredentials(_ value: SyncCredentials) async {
        await enqueueCredentialWrite(value).value
    }

    private func releaseMutation() {
        if mutationWaiters.isEmpty { mutationBusy = false }
        else { mutationWaiters.removeFirst().resume() }
    }

    func loadDevices() async {
        guard credentials != nil else { return }
        do {
            devices = try await authorized { authorization in try await self.api.listDevices(authorization) }
            lastFailureMessage = nil
        } catch {
            handle(error)
        }
    }

    func removeDevice(_ device: SyncDevice) async -> Bool {
        guard device.deviceId != currentDeviceID else { return false }
        lastFailureMessage = nil
        do {
            try await authorized { authorization in
                try await self.api.deleteDevice(device.deviceId, authorization)
            }
            devices.removeAll { $0.deviceId == device.deviceId }
            return true
        } catch {
            handle(error)
            return false
        }
    }

    func beginReading(book: Book, fileURL: URL, sessionID: UUID = UUID(), positionReady: Bool = true) async {
        syncTimerTask?.cancel()
        jumpSuggestion = nil
        currentSession = ReaderSession(
            id: sessionID,
            bookID: book.id,
            local: LocalProgressSnapshot(
                bookID: book.id,
                key: nil,
                offset: book.offset,
                readAtMs: Self.milliseconds(book.updatedAt),
                localSequence: 0
            ),
            comparisonState: .pending,
            promptedRemoteVersion: nil,
            lastObservedSequence: nil,
            positionReady: positionReady
        )

        do {
            let identity = try await stateStore.identity(for: book, fileURL: fileURL)
            guard var session = currentSession, session.id == sessionID else { return }
            session.local = LocalProgressSnapshot(
                bookID: book.id,
                key: identity.key,
                offset: session.local.offset,
                readAtMs: session.local.readAtMs,
                localSequence: session.local.localSequence
            )
            currentSession = session
        } catch is CancellationError {
            return
        } catch {
            guard currentSession?.id == sessionID else { return }
            currentSession?.comparisonState = .unavailable
            lastFailureMessage = "暂时无法计算当前书籍的同步标识。"
            return
        }

        guard credentials != nil, api.isConfigured, connectivity.isOnline() else {
            availability = .offline
            currentSession?.comparisonState = .unavailable
            startSyncTimerIfNeeded()
            return
        }
        freshPullCompleted = false
        await pullOnly()
        guard currentSession?.id == sessionID else { return }
        compareCurrentSession()
    }

    func recordLocalProgress(bookID: UUID, offset: Int64, changedAt: Date) {
        guard var session = currentSession, session.bookID == bookID else { return }
        guard session.positionReady,
              session.comparisonState == .completed || session.comparisonState == .unavailable,
              offset != session.local.offset else { return }
        let previous = session.local.readAtMs
        session.local.offset = max(0, min(session.local.key?.fileSize ?? .max, offset))
        session.local.readAtMs = max(Self.milliseconds(changedAt), previous + 1)
        session.local.localSequence &+= 1
        currentSession = session
    }

    @discardableResult
    func resolveJump(useRemote: Bool) -> SyncJumpSuggestion? {
        guard let suggestion = jumpSuggestion,
              var session = currentSession,
              session.bookID == suggestion.bookID else { return nil }
        if useRemote {
            session.local.offset = suggestion.remote.offset
            session.local.readAtMs = suggestion.remote.readAtMs
            session.positionReady = false
        }
        session.promptedRemoteVersion = suggestion.remote.version
        session.comparisonState = .completed
        currentSession = session
        jumpSuggestion = nil
        startSyncTimerIfNeeded()
        return suggestion
    }

    func endReading(bookID: UUID) async {
        suspendReading(bookID: bookID)
    }

    func suspendReading(bookID: UUID, sessionID: UUID? = nil) {
        guard currentSession?.bookID == bookID else { return }
        if let sessionID, currentSession?.id != sessionID { return }
        syncTimerTask?.cancel()
        syncTimerTask = nil
        currentSession = nil
        jumpSuggestion = nil
        startSyncTimerIfNeeded()
    }

    func readingPositionReady(bookID: UUID) {
        guard currentSession?.bookID == bookID else { return }
        currentSession?.positionReady = true
    }

    func isPreparingReading(bookID: UUID) -> Bool {
        guard let session = currentSession, session.bookID == bookID else { return false }
        return !session.positionReady || session.comparisonState == .pending || session.comparisonState == .awaitingJumpDecision
    }

    static func shouldSuggestJump(
        local: LocalProgressSnapshot,
        remote: RemoteProgressSnapshot,
        currentDeviceID: UUID
    ) -> Bool {
        guard let key = local.key,
              key == remote.key,
              remote.device.deviceId != currentDeviceID,
              remote.readAtMs > local.readAtMs,
              key.fileSize > 0 else { return false }
        let difference = Double(abs(remote.offset - local.offset)) / Double(key.fileSize)
        return difference > 0.000_01
    }

    private func compareCurrentSession() {
        guard var session = currentSession, let key = session.local.key else { return }
        guard freshPullCompleted else {
            session.comparisonState = .unavailable
            currentSession = session
            return
        }
        if let remote = remoteByKey[key],
           Self.shouldSuggestJump(
               local: session.local,
               remote: remote,
               currentDeviceID: deviceRegistration.deviceId
           ),
           session.promptedRemoteVersion != remote.version {
            session.comparisonState = .awaitingJumpDecision
            currentSession = session
            jumpSuggestion = SyncJumpSuggestion(bookID: session.bookID, remote: remote, localOffset: session.local.offset)
            return
        }
        session.comparisonState = .completed
        currentSession = session
        startSyncTimerIfNeeded()
    }

    private func startSyncTimerIfNeeded() {
        syncTimerTask?.cancel()
        guard appIsActive,
              defaults.bool(forKey: Self.hasStartedSyncKey),
              api.isConfigured,
              configuredEmail != nil,
              !currentDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        syncTimerTask = Task { [weak self, syncInterval] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: syncInterval) } catch { return }
                guard let self, !Task.isCancelled else { return }
                await self.performScheduledSync()
            }
        }
    }

    private func performScheduledSync() async {
        guard appIsActive,
              api.isConfigured,
              configuredEmail != nil,
              !currentDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard connectivity.isOnline() else {
            availability = .offline
            return
        }
        if credentials == nil {
            _ = await startConfiguredSync()
            return
        }
        if currentSession != nil {
            guard availability == .available else { return }
            if !freshPullCompleted {
                await pullOnly()
                compareCurrentSession()
            }
            await syncCurrentProgress(forceLatest: true)
        }
    }

    private func syncCurrentProgress(forceLatest: Bool) async {
        let generation = configurationGeneration
        let sessionID = currentSession?.id
        await acquireMutation()
        defer { releaseMutation() }
        guard generation == configurationGeneration, currentSession?.id == sessionID, !Task.isCancelled else { return }
        guard credentials != nil,
              availability == .available,
              connectivity.isOnline(),
              var session = currentSession,
              session.positionReady,
              !pausedReadingSessions.contains(session.id),
              session.comparisonState == .completed,
              freshPullCompleted,
              let key = session.local.key,
              !deletingKeys.contains(key) else { return }
        if !forceLatest, session.lastObservedSequence == session.local.localSequence { return }
        session.lastObservedSequence = session.local.localSequence
        currentSession = session

        let request = ProgressSyncRequest(items: [
            .init(
                bookHash: key.bookHash,
                fileSize: key.fileSize,
                offset: session.local.offset,
                readAtMs: session.local.readAtMs
            )
        ])
        do {
            let response = try await authorized { authorization in
                try await self.api.syncProgress(request, authorization)
            }
            guard currentSession?.id == session.id, connectivity.isOnline() else { return }
            if let state = response.results.first?.state {
                remoteByKey[state.key] = state
                try? await stateStore.replaceRemote(Array(remoteByKey.values))
            }
            guard generation == configurationGeneration else { return }
            availability = .available
            lastSuccessAt = now()
            lastFailureMessage = nil
            compareCurrentSession()
        } catch is CancellationError {
            return
        } catch {
            handle(error)
        }
    }

    private func pullOnly() async {
        guard credentials != nil, api.isConfigured else { return }
        guard connectivity.isOnline() else {
            availability = .offline
            freshPullCompleted = false
            return
        }
        let generation = configurationGeneration
        let requestID = UUID()
        pullGeneration = requestID
        do {
            let response = try await authorized { authorization in
                try await self.api.pullProgress(authorization)
            }
            guard requestID == pullGeneration else { return }
            remoteByKey = Dictionary(uniqueKeysWithValues: response.items.map { ($0.key, $0) })
            try? await stateStore.replaceRemote(response.items)
            guard generation == configurationGeneration, requestID == pullGeneration else { return }
            freshPullCompleted = true
            availability = .available
            lastSuccessAt = now()
            lastFailureMessage = nil
            healthProbeTask?.cancel()
            healthProbeTask = nil
        } catch is CancellationError {
            return
        } catch {
            guard generation == configurationGeneration, requestID == pullGeneration else { return }
            freshPullCompleted = false
            handle(error)
        }
    }

    private func prepareCurrentSessionAfterPull() async {
        guard currentSession != nil else { return }
        compareCurrentSession()
        if currentSession?.comparisonState == .completed {
            await syncCurrentProgress(forceLatest: true)
        }
    }

    private func networkChanged(isOnline: Bool) async {
        guard started else { return }
        if !isOnline {
            pullGeneration = UUID()
            jumpSuggestion = nil
            availability = .offline
            freshPullCompleted = false
            syncTimerTask?.cancel()
            syncTimerTask = nil
            healthProbeTask?.cancel()
            healthProbeTask = nil
            if currentSession != nil { currentSession?.comparisonState = .unavailable }
            return
        }
        guard credentials != nil else {
            availability = .available
            startSyncTimerIfNeeded()
            return
        }
        availability = .available
        await pullOnly()
        await prepareCurrentSessionAfterPull()
        startSyncTimerIfNeeded()
    }

    private func authorized<T>(_ operation: (SyncAuthorization) async throws -> T) async throws -> T {
        let generation = configurationGeneration
        guard let credentials else {
            throw SyncAPIError.http(
                status: 401,
                code: "SYNC_TOKEN_REQUIRED",
                message: "请重新输入邮箱开启同步。",
                retryable: false
            )
        }
        do {
            let result = try await operation(credentials.authorization)
            guard generation == configurationGeneration else { throw CancellationError() }
            return result
        } catch {
            guard generation == configurationGeneration else { throw CancellationError() }
            throw error
        }
    }

    private func handle(_ error: Error) {
        if error is CancellationError { return }
        guard let apiError = error as? SyncAPIError else {
            availability = connectivity.isOnline() ? .serviceUnavailable : .offline
            lastFailureMessage = "同步服务暂时不可用。"
            if availability == .serviceUnavailable { startHealthProbes() }
            return
        }
        lastFailureMessage = apiError.localizedDescription
        if apiError.statusCode == 401 || apiError.statusCode == 403 {
            availability = .tokenRequired
            freshPullCompleted = false
            currentSession?.comparisonState = .unavailable
            jumpSuggestion = nil
            syncTimerTask?.cancel()
        } else if apiError.marksServiceUnavailable {
            availability = connectivity.isOnline() ? .serviceUnavailable : .offline
            syncTimerTask?.cancel()
            if availability == .serviceUnavailable { startHealthProbes() }
        }
    }

    private func startHealthProbes() {
        guard healthProbeTask == nil, credentials != nil, connectivity.isOnline() else { return }
        let generation = configurationGeneration
        healthProbeTask = Task { [weak self, healthProbeDelays] in
            var index = 0
            while !Task.isCancelled {
                let delay = healthProbeDelays[min(index, healthProbeDelays.count - 1)]
                do { try await Task.sleep(for: delay) } catch { return }
                guard let self, !Task.isCancelled else { return }
                do {
                    if try await self.api.health() {
                        guard generation == self.configurationGeneration, !Task.isCancelled else { return }
                        await self.serviceRecovered()
                        return
                    }
                } catch is CancellationError {
                    return
                } catch {}
                index += 1
            }
        }
    }

    private func serviceRecovered() async {
        healthProbeTask?.cancel()
        healthProbeTask = nil
        availability = .available
        freshPullCompleted = false
        await pullOnly()
        await prepareCurrentSessionAfterPull()
    }

    private static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }

    private static func loadDeviceRegistration(defaults: UserDefaults) -> SyncDeviceRegistration {
        let key = "sync.device.id.v1"
        let deviceID: UUID
        if let raw = defaults.string(forKey: key), let existing = UUID(uuidString: raw) {
            deviceID = existing
        } else {
            deviceID = UUID()
            defaults.set(deviceID.uuidString.lowercased(), forKey: key)
        }
        let storedName = defaults.string(forKey: deviceNameKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let defaultName = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return SyncDeviceRegistration(
            deviceId: deviceID,
            deviceName: storedName.flatMap({ $0.isEmpty ? nil : $0 })
                ?? (defaultName.isEmpty ? "iOS 设备" : defaultName),
            platform: "ios",
            appVersion: AppVersion.displayText
        )
    }

    private static let deviceNameKey = "sync.device.name.v1"
    private static let emailKey = "sync.email.v1"
    private static let hasStartedSyncKey = "sync.has.started.v1"
    static let maximumDeviceNameLength = 20

    private static func loadConfiguredEmail(defaults: UserDefaults) -> String? {
        let value = defaults.string(forKey: emailKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.flatMap { $0.isEmpty ? nil : $0 }
    }
}
