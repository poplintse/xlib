import Foundation

/// MainActor owns ordering; awaited network and vault operations remain asynchronous.
/// A canceled HTTP task may still execute on the server, so mutations must finish in order.
@MainActor
final class SyncRequestExecution {
    private let vault: SyncCredentialVault
    private var mutationBusy = false
    private var mutationWaiters: [CheckedContinuation<Void, Never>] = []
    private var credentialWrite: Task<Void, Never>?

    init(vault: SyncCredentialVault) { self.vault = vault }

    func acquireMutation() async {
        if !mutationBusy {
            mutationBusy = true
            return
        }
        await withCheckedContinuation { mutationWaiters.append($0) }
    }

    func enqueueCredentialWrite(_ value: SyncCredentials?) -> Task<Void, Never> {
        let previous = credentialWrite
        let task = Task { [vault] in
            await previous?.value
            if let value { await vault.save(value) }
            else { await vault.clear() }
        }
        credentialWrite = task
        return task
    }

    func saveCredentials(_ value: SyncCredentials) async {
        await enqueueCredentialWrite(value).value
    }

    func releaseMutation() {
        if mutationWaiters.isEmpty { mutationBusy = false }
        else { mutationWaiters.removeFirst().resume() }
    }

}
