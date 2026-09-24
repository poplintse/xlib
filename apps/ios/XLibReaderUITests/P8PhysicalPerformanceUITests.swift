import XCTest

/// Physical-device measurements for the deterministic P8 fixtures.
///
/// These tests deliberately use the library already installed on the device instead of the
/// isolated `XLIB_UI_TEST_ID` store used by the normal UI suite. They are opt-in because a normal
/// simulator run does not contain the P8 fixtures. Run them through `scripts/test-p8-ios-device.sh`.
final class P8PhysicalPerformanceUITests: XCTestCase {
    private static let enabledEnvironmentKey = "XLIB_P8_DEVICE_PERFORMANCE"

    @MainActor
    private func requirePerformanceBuild() throws {
#if !XLIB_P8_DEVICE_PERFORMANCE
        guard ProcessInfo.processInfo.environment[Self.enabledEnvironmentKey] == "1" else {
            throw XCTSkip("P8 physical-device measurements are opt-in")
        }
#endif
    }

    @MainActor
    private func measureOpen(title: String, cold: Bool) throws {
        try requirePerformanceBuild()

        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 20))

        let options = XCTMeasureOptions.default
        options.iterationCount = 5
        options.invocationOptions = [.manuallyStart, .manuallyStop]
        let metrics: [any XCTMetric] = [
            XCTOSSignpostMetric(
                subsystem: "com.xlib.txtreader",
                category: "ReaderPerformance",
                name: "OpenToFirstTextDraw"
            ),
            XCTMemoryMetric(application: app),
            XCTStorageMetric(application: app),
        ]

        measure(metrics: metrics, options: options) {
            if cold {
                app.terminate()
                app.launch()
                XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 20))
            } else {
                returnToLibraryIfNeeded(app)
            }

            let book = findBook(title: title, in: app)
            startMeasuring()
            book.tap()
            let reader = app.descendants(matching: .any)["阅读正文"]
            XCTAssertTrue(reader.waitForExistence(timeout: 20))
            stopMeasuring()
            resolveProgressPromptIfNeeded(app)
            returnToLibraryIfNeeded(app)
        }
    }

    @MainActor
    private func findBook(title: String, in app: XCUIApplication) -> XCUIElement {
        let predicate = NSPredicate(format: "label CONTAINS[c] %@", title)
        let book = app.buttons.containing(predicate).firstMatch
        // The library preserves its scroll position when a reader closes. Return to the top before
        // searching so a fixture above the previous selection is not skipped.
        for _ in 0..<2 {
            app.swipeDown()
        }
        for _ in 0..<10 {
            if book.exists { break }
            app.swipeUp()
        }
        XCTAssertTrue(book.waitForExistence(timeout: 5), "Missing imported P8 fixture: \(title)")
        return book
    }

    @MainActor
    private func resolveProgressPromptIfNeeded(_ app: XCUIApplication) {
        let keepLocal = app.buttons["暂不跳转"]
        if keepLocal.waitForExistence(timeout: 0.25) {
            keepLocal.tap()
        }
    }

    @MainActor
    private func returnToLibraryIfNeeded(_ app: XCUIApplication) {
        let library = app.staticTexts["我的书架"]
        if library.exists { return }

        for _ in 0..<3 {
            let keepLocal = app.buttons["暂不跳转"]
            if keepLocal.exists { keepLocal.tap() }

            let back = app.buttons["回到书架"]
            if !back.exists {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            }
            if back.waitForExistence(timeout: 2) {
                back.tap()
            }
            if library.waitForExistence(timeout: 5) { return }
        }
        XCTFail("Could not return to the library after three attempts")
    }

    @MainActor
    private func openReader(title: String, in app: XCUIApplication) -> XCUIElement {
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 20))
        findBook(title: title, in: app).tap()
        let reader = app.descendants(matching: .any)["阅读正文"]
        XCTAssertTrue(reader.waitForExistence(timeout: 20))
        resolveProgressPromptIfNeeded(app)
        return reader
    }

    @MainActor
    private func pageTurn(_ direction: CGVector, reader: XCUIElement, app: XCUIApplication) {
        let previous = reader.value as? String
        app.coordinate(withNormalizedOffset: direction).tap()
        let changed = NSPredicate { evaluated, _ in
            (evaluated as? XCUIElement)?.value as? String != previous
        }
        expectation(for: changed, evaluatedWith: reader)
        waitForExpectations(timeout: 3)
        // The accessibility value changes when the target page is installed. Wait for the page
        // transition completion signpost before allowing the next turn to enter the queue.
        Thread.sleep(forTimeInterval: 0.45)
    }

    @MainActor
    private func measurePageTurns(forward: Bool) throws {
        try requirePerformanceBuild()
        let app = XCUIApplication()
        app.launch()
        let reader = openReader(title: "p8-large-utf8", in: app)
        if !forward {
            for _ in 0..<35 {
                pageTurn(CGVector(dx: 0.85, dy: 0.5), reader: reader, app: app)
            }
        }

        let options = XCTMeasureOptions.default
        options.iterationCount = 30
        options.invocationOptions = [.manuallyStart, .manuallyStop]
        let signpostName = forward ? "PageTurnForward" : "PageTurnBackward"
        measure(metrics: [
            XCTOSSignpostMetric(
                subsystem: "com.xlib.txtreader",
                category: "ReaderPerformance",
                name: signpostName
            ),
            XCTMemoryMetric(application: app),
        ], options: options) {
            startMeasuring()
            pageTurn(
                CGVector(dx: forward ? 0.85 : 0.15, dy: 0.5),
                reader: reader,
                app: app
            )
            stopMeasuring()
        }
        returnToLibraryIfNeeded(app)
    }

    @MainActor
    private func showReaderMenu(_ app: XCUIApplication) {
        let search = app.buttons["搜索当前书籍"]
        if !search.exists {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertTrue(search.waitForExistence(timeout: 5))
    }

    @MainActor
    private func openSearch(_ app: XCUIApplication) -> XCUIElement {
        showReaderMenu(app)
        app.buttons["搜索当前书籍"].tap()
        let query = app.textFields["search.query"]
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        return query
    }

    @MainActor
    func testPageForward() throws { try measurePageTurns(forward: true) }

    @MainActor
    func testPageBackward() throws { try measurePageTurns(forward: false) }

    @MainActor
    func testSearchOver200() throws {
        try requirePerformanceBuild()
        let app = XCUIApplication()
        app.launchEnvironment["XLIB_P8_AUTO_CONTINUE_SEARCH"] = "1"
        app.launch()
        _ = openReader(title: "p8-large-utf8", in: app)

        let options = XCTMeasureOptions.default
        options.iterationCount = 5
        options.invocationOptions = [.manuallyStart, .manuallyStop]
        measure(metrics: [
            XCTOSSignpostMetric(
                subsystem: "com.xlib.txtreader",
                category: "ReaderPerformance",
                name: "SearchFirstResult"
            ),
            XCTOSSignpostMetric(
                subsystem: "com.xlib.txtreader",
                category: "ReaderPerformance",
                name: "SearchOver200Completion"
            ),
            XCTMemoryMetric(application: app),
        ], options: options) {
            let query = openSearch(app)
            query.tap()
            query.typeText("P8NEEDLE")
            startMeasuring()
            let keyboardSearch = app.keyboards.buttons["Search"]
            if keyboardSearch.exists {
                keyboardSearch.tap()
            } else {
                app.buttons["开始搜索"].tap()
            }

            let status = app.staticTexts["search.status"]
            expectation(
                for: NSPredicate(format: "label CONTAINS %@", "已加载 400 条结果"),
                evaluatedWith: status
            )
            waitForExpectations(timeout: 20)
            stopMeasuring()

            app.buttons["返回阅读"].tap()
            XCTAssertTrue(app.descendants(matching: .any)["阅读正文"].waitForExistence(timeout: 5))
        }
        returnToLibraryIfNeeded(app)
    }

    @MainActor
    func testSearchCancellation() throws {
        try requirePerformanceBuild()
        let app = XCUIApplication()
        app.launch()
        _ = openReader(title: "p8-large-utf8", in: app)
        let query = openSearch(app)
        query.tap()
        query.typeText("P8NEEDLE")
        app.buttons["开始搜索"].tap()
        app.buttons["返回阅读"].tap()
        let reader = app.descendants(matching: .any)["阅读正文"]
        XCTAssertTrue(reader.waitForExistence(timeout: 5))
        if app.buttons["搜索当前书籍"].exists {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            XCTAssertFalse(app.buttons["搜索当前书籍"].waitForExistence(timeout: 1))
        }
        let previous = reader.value as? String
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).tap()
        let changed = NSPredicate { evaluated, _ in
            (evaluated as? XCUIElement)?.value as? String != previous
        }
        expectation(for: changed, evaluatedWith: reader)
        waitForExpectations(timeout: 5)
        returnToLibraryIfNeeded(app)
    }

    @MainActor
    private func launchBulkPhase(
        _ mode: String,
        id: UUID,
        app: XCUIApplication,
        expectedBookCount: Int
    ) {
        app.launchEnvironment["XLIB_P8_BULK_TEST_ID"] = id.uuidString
        app.launchEnvironment["XLIB_P8_BULK_MODE"] = mode
        app.launch()
        if expectedBookCount == 0 {
            XCTAssertTrue(app.buttons["导入第一本书"].waitForExistence(timeout: 30))
        } else {
            XCTAssertTrue(
                app.staticTexts["\(expectedBookCount) 本本地书籍"].waitForExistence(timeout: 30)
            )
        }
    }

    @MainActor
    func testBulkImport() throws {
        try requirePerformanceBuild()
        let app = XCUIApplication()
        let options = XCTMeasureOptions.default
        options.iterationCount = 5
        options.invocationOptions = [.manuallyStart, .manuallyStop]
        measure(metrics: [
            XCTOSSignpostMetric(
                subsystem: "com.xlib.txtreader",
                category: "ReaderPerformance",
                name: "BulkImport"
            ),
            XCTMemoryMetric(application: app),
        ], options: options) {
            let id = UUID()
            launchBulkPhase("prepare", id: id, app: app, expectedBookCount: 0)
            app.terminate()
            startMeasuring()
            launchBulkPhase("import", id: id, app: app, expectedBookCount: 20)
            stopMeasuring()
            app.terminate()
        }
    }

    @MainActor
    func testBulkImportInterruptedRecovery() throws {
        try requirePerformanceBuild()
        let app = XCUIApplication()
        let options = XCTMeasureOptions.default
        options.iterationCount = 5
        options.invocationOptions = [.manuallyStart, .manuallyStop]
        measure(metrics: [
            XCTOSSignpostMetric(
                subsystem: "com.xlib.txtreader",
                category: "ReaderPerformance",
                name: "BulkImportRecovery"
            ),
            XCTMemoryMetric(application: app),
        ], options: options) {
            let id = UUID()
            launchBulkPhase("interrupted", id: id, app: app, expectedBookCount: 5)
            app.terminate()
            startMeasuring()
            launchBulkPhase("recover", id: id, app: app, expectedBookCount: 20)
            stopMeasuring()
            app.terminate()
        }
    }

    @MainActor func testOpenSmallUTF8Cold() throws { try measureOpen(title: "p8-small-utf8", cold: true) }
    @MainActor func testOpenSmallUTF8Cached() throws { try measureOpen(title: "p8-small-utf8", cold: false) }
    @MainActor func testOpenSmallUTF16LECold() throws { try measureOpen(title: "p8-small-utf16le", cold: true) }
    @MainActor func testOpenSmallUTF16LECached() throws { try measureOpen(title: "p8-small-utf16le", cold: false) }
    @MainActor func testOpenSmallGB18030Cold() throws { try measureOpen(title: "p8-small-gb18030", cold: true) }
    @MainActor func testOpenSmallGB18030Cached() throws { try measureOpen(title: "p8-small-gb18030", cold: false) }
    @MainActor func testOpenLargeUTF8Cold() throws { try measureOpen(title: "p8-large-utf8", cold: true) }
    @MainActor func testOpenLargeUTF8Cached() throws { try measureOpen(title: "p8-large-utf8", cold: false) }
    @MainActor func testOpenLargeUTF16LECold() throws { try measureOpen(title: "p8-large-utf16le", cold: true) }
    @MainActor func testOpenLargeUTF16LECached() throws { try measureOpen(title: "p8-large-utf16le", cold: false) }
    @MainActor func testOpenLargeGB18030Cold() throws { try measureOpen(title: "p8-large-gb18030", cold: true) }
    @MainActor func testOpenLargeGB18030Cached() throws { try measureOpen(title: "p8-large-gb18030", cold: false) }
}
