import Foundation
import XCTest
@testable import XLibReader

final class ContractFixturesTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json", subdirectory: "fixtures"))
        return try Data(contentsOf: url)
    }
    private struct Scenario: Decodable {
        let id: String
        let bookHash: String
        let fileSize: Int64
        let localOffset: Int64
        let localReadAtMs: Int64
        let remoteOffset: Int64
        let remoteReadAtMs: Int64
        let currentDeviceId: UUID
        let remoteDeviceId: UUID
        let expectedPrompt: Bool
    }
    @MainActor
    func testSharedProgressBehavior() throws {
        for c in try JSONDecoder().decode([Scenario].self, from: fixture("progress-behavior")) {
            let local = LocalProgressSnapshot(bookID: UUID(), key: .init(bookHash: c.bookHash, fileSize: c.fileSize),
                offset: c.localOffset, readAtMs: c.localReadAtMs, localSequence: 0)
            let remote = RemoteProgressSnapshot(bookHash: c.bookHash, fileSize: c.fileSize,
                offset: c.remoteOffset, progress: Double(c.remoteOffset) / Double(c.fileSize),
                readAtMs: c.remoteReadAtMs, version: "1",
                device: .init(deviceId: c.remoteDeviceId, deviceName: "设备", platform: "android"))
            XCTAssertEqual(ProgressSyncCoordinator.shouldSuggestJump(local: local, remote: remote,
                currentDeviceID: c.currentDeviceId), c.expectedPrompt, c.id)
        }
    }

    func testLiveTransportUsesGoldenWireAndError() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ContractFixtureURLProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let api = SyncAPIClient.live(address: "https://sync.example.com", session: session)
        let wire = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("wire")) as? [String: Any])
        let responseData = try JSONSerialization.data(withJSONObject: XCTUnwrap(wire["ProgressSyncResponse"]))
        let golden = try JSONDecoder().decode(ProgressSyncResponse.self, from: responseData)
        let state = try XCTUnwrap(golden.results.first?.state)
        let authorization = SyncAuthorization(token: "fixture-token", deviceID: state.device.deviceId)
        let response = try await api.syncProgress(.init(items: [.init(bookHash: state.bookHash,
            fileSize: state.fileSize, offset: state.offset, readAtMs: state.readAtMs)]), authorization)
        XCTAssertEqual(response.results.first?.state, state)
        do {
            _ = try await api.pullProgress(authorization)
            XCTFail("Expected forbidden device error")
        } catch SyncAPIError.http(let status, let code, _, let retryable) {
            XCTAssertEqual(status, 403)
            XCTAssertEqual(code, "DEVICE_FORBIDDEN")
            XCTAssertFalse(retryable)
        }
    }

    func testGoldenWireModelsAndRequestSerialization() throws {
        let wire = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("wire")) as? [String: Any])
        func data(_ key: String) throws -> Data { try JSONSerialization.data(withJSONObject: XCTUnwrap(wire[key])) }
        let decoder = JSONDecoder()
        let response = try decoder.decode(ProgressSyncResponse.self, from: data("ProgressSyncResponse"))
        let result = try XCTUnwrap(response.results.first)
        let state = result.state
        let request = ProgressSyncRequest(items: [.init(bookHash: state.bookHash, fileSize: state.fileSize,
            offset: state.offset, readAtMs: state.readAtMs)])
        let encoded = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? NSDictionary)
        XCTAssertEqual(encoded, wire["ProgressSyncRequest"] as? NSDictionary)
        XCTAssertEqual(state.fileSize, 9_007_199_254_740_991)
        XCTAssertEqual(state.offset, 9_007_199_254_740_990)
        XCTAssertEqual(state.readAtMs, 253_402_300_799_999)
        XCTAssertEqual(state.version, "9007199254740993")
        XCTAssertEqual(result.decision, "accepted")
        XCTAssertFalse(result.timeAdjusted)
        let pull = try decoder.decode(ProgressPullResponse.self, from: data("ProgressListResponse"))
        XCTAssertEqual(pull.items, [state])
        let start = try decoder.decode(SyncStartResponse.self, from: data("StartSyncResponse"))
        XCTAssertEqual(start.device, state.device)
        let devices = try decoder.decode(SyncDeviceListResponse.self, from: data("DeviceListResponse"))
        XCTAssertEqual(devices.items, [state.device]) // revokedAtMs:null and extra device fields stay wire-compatible.
        let error = try decoder.decode(SyncAPIErrorEnvelope.self, from: data("ErrorEnvelope"))
        XCTAssertEqual(error.error.code, "DEVICE_FORBIDDEN")
        XCTAssertFalse(error.error.retryable)
        XCTAssertEqual(error.error.requestId, "fixture-request")
        let health = try decoder.decode(SyncHealthResponse.self, from: data("HealthResponse"))
        XCTAssertEqual(health.status, "ok")
    }
}

private final class ContractFixtureURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let url = try XCTUnwrap(Bundle(for: ContractFixturesTests.self).url(forResource: "wire", withExtension: "json", subdirectory: "fixtures"))
            let wire = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Device-Id"), "10000000-0000-4000-8000-000000000001")
            let sync = request.url?.path == "/v1/progress/sync"
            if sync {
                XCTAssertEqual(request.httpMethod, "POST")
                var body = request.httpBody ?? Data()
                if let stream = request.httpBodyStream {
                    stream.open()
                    defer { stream.close() }
                    var buffer = [UInt8](repeating: 0, count: 4096)
                    while stream.hasBytesAvailable {
                        let count = stream.read(&buffer, maxLength: buffer.count)
                        if count <= 0 { break }
                        body.append(contentsOf: buffer.prefix(count))
                    }
                }
                let actual = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? NSDictionary)
                XCTAssertEqual(actual, wire["ProgressSyncRequest"] as? NSDictionary)
            } else {
                XCTAssertEqual(request.url?.path, "/v1/progress")
                XCTAssertEqual(request.httpMethod, "GET")
            }
            let data = try JSONSerialization.data(withJSONObject: XCTUnwrap(wire[sync ? "ProgressSyncResponse" : "ErrorEnvelope"]))
            let response = try XCTUnwrap(HTTPURLResponse(url: XCTUnwrap(request.url), statusCode: sync ? 200 : 403,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"]))
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
