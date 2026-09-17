import XCTest

final class XLibReaderUITests: XCTestCase {
    @MainActor
    private func launchApp(scenario: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["XLIB_UI_TEST_ID"] = UUID().uuidString
        app.launchEnvironment["XLIB_UI_TEST_SCENARIO"] = scenario
        app.launch()
        return app
    }

    @MainActor
    private func openFixtureReader(_ app: XCUIApplication) {
        let book = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "能力验收")).firstMatch
        XCTAssertTrue(book.waitForExistence(timeout: 10))
        book.tap()
        showReaderMenu(app)
    }

    @MainActor
    private func showReaderMenu(_ app: XCUIApplication) {
        let progress = app.buttons["reader.progressButton"]
        if !progress.exists {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.5)).tap()
        }
        XCTAssertTrue(progress.waitForExistence(timeout: 5))
    }

    @MainActor
    func testBookmarkDuplicateAndSingleDeletion() {
        let app = launchApp(scenario: "reading")
        openFixtureReader(app)
        app.buttons["目录"].tap()
        let add = app.buttons["添加当前书签"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(app.staticTexts["书签已保存"].waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(app.staticTexts["当前位置已有书签"].waitForExistence(timeout: 5))
        let marks = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "catalog.bookmark."))
        XCTAssertEqual(marks.count, 1)
        marks.firstMatch.press(forDuration: 1)
        let delete = app.buttons["删除书签"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "还没有书签")).firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(marks.count, 0)
    }

    @MainActor
    func testSearchTemporaryReadingPreservesFormalProgress() {
        let app = launchApp(scenario: "reading")
        openFixtureReader(app)
        let original = app.buttons["reader.progressButton"].value as? String
        app.buttons["搜索当前书籍"].tap()
        let query = app.textFields["search.query"]
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        query.tap()
        query.typeText("NEEDLE")
        app.buttons["开始搜索"].tap()
        let status = app.staticTexts["search.status"]
        expectation(for: NSPredicate(format: "label CONTAINS %@", "30 条结果"), evaluatedWith: status)
        waitForExpectations(timeout: 10)
        let result = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "search.result.")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        result.tap()
        showReaderMenu(app)
        XCTAssertNotEqual(app.buttons["reader.progressButton"].value as? String, original)
        app.buttons["回到书架"].tap()
        XCTAssertTrue(query.waitForExistence(timeout: 5))
        app.buttons["返回阅读"].tap()
        showReaderMenu(app)
        XCTAssertEqual(app.buttons["reader.progressButton"].value as? String, original)
        app.buttons["回到书架"].tap()
        openFixtureReader(app)
        XCTAssertEqual(app.buttons["reader.progressButton"].value as? String, original)
    }

    @MainActor
    func testCloudDeletionConfirmationAndLocalDataSurvive() {
        let app = launchApp(scenario: "sync")
        openFixtureReader(app)
        let original = app.buttons["reader.progressButton"].value as? String
        app.buttons["目录"].tap()
        let add = app.buttons["添加当前书签"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        let delete = app.buttons["catalog.deleteCloudProgress"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        let confirm = app.buttons["删除本书云端进度"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        if app.buttons["取消"].exists {
            app.buttons["取消"].tap()
        } else {
            // iOS may present this as a popover; tapping outside is its cancel action.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.1)).tap()
        }
        XCTAssertFalse(confirm.exists)
        delete.tap()
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()
        XCTAssertTrue(app.staticTexts["本书云端进度已删除，本次阅读暂停上传。"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "catalog.bookmark.")).count, 1)
        app.buttons["返回阅读"].tap()
        showReaderMenu(app)
        XCTAssertEqual(app.buttons["reader.progressButton"].value as? String, original)
    }

    @MainActor
    func testLaunchesLibrary() {
        let app = launchApp()
        let headerExists = app.staticTexts["我的书架"].waitForExistence(timeout: 10)
        let addButtonExists = app.buttons["添加 TXT"].waitForExistence(timeout: 10)
        XCTAssertTrue(headerExists)
        XCTAssertTrue(addButtonExists)
    }

    @MainActor
    func testOpensNativeGlassSettingsHierarchy() {
        let app = launchApp()
        let settings = app.buttons["常规设置"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        XCTAssertTrue(app.staticTexts["应用主题"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["TXT 自动生成目录"].exists)
        XCTAssertTrue(app.switches["settings.keepScreenAwake"].exists)
        XCTAssertTrue(app.buttons["settings.autoPageSeconds"].exists)
        XCTAssertTrue(app.buttons["settings.progressSync"].exists)
        XCTAssertFalse(app.buttons["常规"].exists)
        XCTAssertFalse(app.buttons["阅读"].exists)

        app.buttons["settings.theme"].tap()
        XCTAssertTrue(app.staticTexts["外观"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["浅色"].exists)
        XCTAssertTrue(app.buttons["深色"].exists)
    }

    @MainActor
    func testProgressSyncUsesEditableDefaultServerAddress() {
        let app = launchApp()
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 5))

        app.buttons["常规设置"].tap()
        let sync = app.buttons["settings.progressSync"]
        XCTAssertTrue(sync.waitForExistence(timeout: 5))
        sync.tap()

        XCTAssertTrue(app.staticTexts["阅读进度同步"].waitForExistence(timeout: 5))
        let serverAddress = app.buttons["sync.serverAddress"]
        XCTAssertTrue(serverAddress.waitForExistence(timeout: 5))
        XCTAssertEqual(serverAddress.value as? String, "https://xunit.cc/xlib/backend")
        XCTAssertTrue(app.staticTexts["未同步"].exists)
        XCTAssertFalse(app.staticTexts["服务未配置"].exists)
        serverAddress.tap()

        let addressField = app.textFields["sync.serverAddressField"]
        XCTAssertTrue(addressField.waitForExistence(timeout: 5))
        XCTAssertEqual(addressField.value as? String, "https://xunit.cc/xlib/backend")
        XCTAssertFalse(app.buttons["sync.serverAddressSave"].exists)
        XCTAssertFalse(app.secureTextFields.firstMatch.exists)
    }

    @MainActor
    func testSyncEmailAutoSavesWhenLeavingField() {
        let app = launchApp()
        app.buttons["常规设置"].tap()
        app.buttons["settings.progressSync"].tap()

        let emailSettings = app.buttons["sync.emailSettings"]
        XCTAssertTrue(emailSettings.waitForExistence(timeout: 5))
        emailSettings.tap()

        let emailField = app.textFields["sync.emailField"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 5))
        emailField.tap()
        emailField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 100))
        emailField.typeText("autosave@example.com")

        edgeSwipeBack(in: app)
        XCTAssertTrue(emailSettings.waitForExistence(timeout: 5))
        let savedEmail = NSPredicate(format: "value == %@", "autosave@example.com")
        expectation(for: savedEmail, evaluatedWith: emailSettings)
        waitForExpectations(timeout: 5)
    }

    @MainActor
    func testProgressSyncUsesStatusAndCombinedSettingsCards() {
        let app = launchApp()
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 5))

        app.buttons["常规设置"].tap()
        app.buttons["settings.progressSync"].tap()
        let status = app.staticTexts["同步状态"]
        let serverAddress = app.buttons["sync.serverAddress"]
        let emailSettings = app.buttons["sync.emailSettings"]
        let deviceNameSettings = app.buttons["sync.deviceNameSettings"]
        let startStop = app.buttons["sync.startStop"]
        let devices = app.buttons["sync.devices"]
        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["最后同步"].exists)
        XCTAssertTrue(startStop.exists)
        XCTAssertTrue(serverAddress.exists)
        XCTAssertTrue(emailSettings.exists)
        XCTAssertTrue(deviceNameSettings.exists)
        XCTAssertTrue(devices.exists)
        XCTAssertLessThan(status.frame.minY, emailSettings.frame.minY)
        XCTAssertLessThan(emailSettings.frame.minY, deviceNameSettings.frame.minY)
        XCTAssertLessThan(deviceNameSettings.frame.minY, serverAddress.frame.minY)
        XCTAssertLessThan(serverAddress.frame.minY, devices.frame.minY)
        XCTAssertFalse(app.secureTextFields.firstMatch.exists)
        XCTAssertFalse(app.segmentedControls.firstMatch.exists)

        emailSettings.tap()
        XCTAssertTrue(app.staticTexts["邮箱"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["sync.emailField"].exists)
        XCTAssertFalse(app.buttons["sync.emailSave"].exists)

        edgeSwipeBack(in: app)
        XCTAssertTrue(deviceNameSettings.waitForExistence(timeout: 5))
        deviceNameSettings.tap()
        XCTAssertTrue(app.staticTexts["设备名称"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["sync.deviceNameField"].exists)
        XCTAssertFalse(app.buttons["sync.deviceNameSave"].exists)

        edgeSwipeBack(in: app)
        XCTAssertTrue(devices.waitForExistence(timeout: 5))
        devices.tap()
        XCTAssertTrue(app.staticTexts["当前设备"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testNumericSettingOpensSavePanel() {
        let app = launchApp()
        let settings = app.buttons["常规设置"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()

        let interval = app.buttons["settings.autoPageSeconds"]
        XCTAssertTrue(interval.waitForExistence(timeout: 5))
        interval.tap()

        let picker = app.pickers["settings.numericPicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["settings.numericSave"].exists)
        let originalValue = interval.value as? String
        let targetValue = originalValue == "3 秒" ? "4 秒" : "3 秒"
        let pickerWheel = app.pickerWheels.firstMatch
        XCTAssertTrue(pickerWheel.exists)
        pickerWheel.adjust(toPickerWheelValue: targetValue)

        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
        XCTAssertFalse(app.pickers["settings.numericPicker"].exists)
        XCTAssertTrue(app.buttons["settings.autoPageSeconds"].exists)
        XCTAssertEqual(interval.value as? String, originalValue)

        app.buttons["settings.autoPageSeconds"].tap()
        XCTAssertTrue(app.pickers["settings.numericPicker"].waitForExistence(timeout: 5))
        app.pickerWheels.firstMatch.adjust(toPickerWheelValue: targetValue)
        app.buttons["settings.numericSave"].tap()
        XCTAssertFalse(app.pickers["settings.numericPicker"].exists)
        XCTAssertTrue(app.buttons["settings.autoPageSeconds"].exists)
        XCTAssertEqual(interval.value as? String, targetValue)
    }

    @MainActor
    func testButtonFramesAcceptEdgeTaps() {
        let app = launchApp()

        let settings = app.buttons["常规设置"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["应用主题"].waitForExistence(timeout: 5))

        let theme = app.buttons["settings.theme"]
        XCTAssertTrue(theme.exists)
        theme.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["外观"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testSettingsPagesSupportNativeEdgeSwipeBack() {
        let app = launchApp()

        XCTAssertTrue(app.buttons["常规设置"].waitForExistence(timeout: 5))
        app.buttons["常规设置"].tap()
        XCTAssertTrue(app.buttons["settings.theme"].waitForExistence(timeout: 5))
        app.buttons["settings.theme"].tap()
        XCTAssertTrue(app.staticTexts["外观"].waitForExistence(timeout: 5))

        edgeSwipeBack(in: app)
        XCTAssertTrue(app.buttons["settings.theme"].waitForExistence(timeout: 5))

        edgeSwipeBack(in: app)
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 5))
    }

    @MainActor
    private func edgeSwipeBack(in app: XCUIApplication) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.001, dy: 0.5))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.78, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: end)
    }
}
