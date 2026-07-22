import Foundation
import Network

final class SyncConnectivityMonitor: @unchecked Sendable {
    private let monitor: NWPathMonitor?
    private let queue = DispatchQueue(label: "com.xlib.txtreader.sync.network")
    private let lock = NSLock()
    private var online: Bool
    private var callback: (@Sendable (Bool) -> Void)?

    init(started: Bool = true, initialOnline: Bool = true) {
        online = initialOnline
        if started {
            let monitor = NWPathMonitor()
            self.monitor = monitor
            monitor.pathUpdateHandler = { [weak self] path in
                self?.publish(path.status == .satisfied)
            }
            monitor.start(queue: queue)
        } else {
            monitor = nil
        }
    }

    deinit { monitor?.cancel() }

    func setCallback(_ callback: @escaping @Sendable (Bool) -> Void) {
        lock.lock()
        self.callback = callback
        let current = online
        lock.unlock()
        callback(current)
    }

    func isOnline() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return online
    }

    func setOnlineForTesting(_ value: Bool) { publish(value) }

    private func publish(_ value: Bool) {
        lock.lock()
        let changed = value != online
        online = value
        let callback = self.callback
        lock.unlock()
        if changed { callback?(value) }
    }
}
