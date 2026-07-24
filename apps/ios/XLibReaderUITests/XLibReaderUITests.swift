import XCTest

final class XLibReaderUITests: XCTestCase {
    @MainActor
    func testLaunchesLibrary() {
        let app = XCUIApplication()
        app.launch()
        let headerExists = app.staticTexts["我的书架"].waitForExistence(timeout: 10)
        let addButtonExists = app.buttons["添加 TXT"].waitForExistence(timeout: 10)
        XCTAssertTrue(headerExists)
        XCTAssertTrue(addButtonExists)
    }

    @MainActor
    func testOpensNativeGlassSettingsHierarchy() {
        let app = XCUIApplication()
        app.launch()
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
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 5))

        app.buttons["常规设置"].tap()
        let sync = app.buttons["settings.progressSync"]
        XCTAssertTrue(sync.waitForExistence(timeout: 5))
        sync.tap()

        XCTAssertTrue(app.staticTexts["阅读进度同步"].waitForExistence(timeout: 5))
        let serverAddress = app.buttons["sync.serverAddress"]
        XCTAssertTrue(serverAddress.waitForExistence(timeout: 5))
        XCTAssertEqual(serverAddress.value as? String, "https://xunit.cc/xlib/backend")
        XCTAssertTrue(app.staticTexts["未开启"].exists)
        XCTAssertFalse(app.staticTexts["服务未配置"].exists)
        serverAddress.tap()

        let addressField = app.textFields["sync.serverAddressField"]
        XCTAssertTrue(addressField.waitForExistence(timeout: 5))
        XCTAssertEqual(addressField.value as? String, "https://xunit.cc/xlib/backend")
        XCTAssertTrue(app.buttons["sync.serverAddressSave"].exists)
        XCTAssertFalse(app.secureTextFields.firstMatch.exists)
    }

    @MainActor
    func testProgressSyncSettingsContainEmailAndDeviceName() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["我的书架"].waitForExistence(timeout: 5))

        app.buttons["常规设置"].tap()
        app.buttons["settings.progressSync"].tap()
        app.buttons["sync.accountSettings"].tap()
        XCTAssertTrue(app.textFields["sync.email"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["sync.deviceName"].exists)
        XCTAssertTrue(app.buttons["sync.settingsSave"].exists)
        XCTAssertFalse(app.secureTextFields.firstMatch.exists)
        XCTAssertFalse(app.segmentedControls.firstMatch.exists)
    }

    @MainActor
    func testNumericSettingOpensSavePanel() {
        let app = XCUIApplication()
        app.launch()
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
        let app = XCUIApplication()
        app.launch()

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
        let app = XCUIApplication()
        app.launch()

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
