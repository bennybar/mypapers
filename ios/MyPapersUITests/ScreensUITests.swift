import XCTest

/** Taps through the main screens with made-up papers (-demo) and saves screenshots for review. */
final class ScreensUITests: XCTestCase {
    private func save(_ name: String) {
        let dir = ProcessInfo.processInfo.environment["SHOTS_DIR"] ?? NSTemporaryDirectory()
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }

    @MainActor
    func testSearchTab() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launch()
        app.tabBars.buttons["Search"].firstMatch.tap()
        sleep(1)
        save("ui-search-empty")
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5), "no search field in the Search tab")
        field.tap()
        field.typeText("bill")
        sleep(1)
        save("ui-search-typed")
    }

    @MainActor
    func testPapersScreens() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-demo", "-style", "cards"]
        app.launch()
        sleep(1)
        save("ui-papers")
        app.buttons["Filter"].firstMatch.tap()
        sleep(1)
        save("ui-filter-menu")
        app.tap()
        app.buttons["Washing machine warranty"].firstMatch.tap()
        sleep(2)
        save("ui-paper")
        app.swipeUp()
        sleep(1)
        save("ui-paper-scrolled")
        app.navigationBars.buttons.firstMatch.tap()
        app.tabBars.buttons["Gmail"].firstMatch.tap()
        sleep(1)
        save("ui-gmail")
        app.tabBars.buttons["Settings"].firstMatch.tap()
        sleep(1)
        save("ui-settings")
    }
}
