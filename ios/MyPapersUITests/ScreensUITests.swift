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
        app.cells.containing(.staticText, identifier: "Washing machine warranty").firstMatch.tap()
        sleep(2)
        save("ui-paper")
        app.swipeUp()
        sleep(1)
        save("ui-paper-scrolled")
        XCTAssertTrue(app.navigationBars["Washing machine warranty"].waitForExistence(timeout: 3), "tapping a paper didn't open it")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["Gmail"].firstMatch.tap()
        sleep(1)
        save("ui-gmail")
        app.buttons["Settings"].firstMatch.tap()
        sleep(1)
        save("ui-settings")
    }

    @MainActor
    func testTapOpensPaper() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-demo", "-style", "cards"]
        app.launch()
        let row = app.cells.containing(.staticText, identifier: "Washing machine warranty").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        sleep(2)
        save("ui-tap")
        XCTAssertTrue(app.navigationBars["Washing machine warranty"].exists, "tapping a paper didn't open it")
    }

    @MainActor
    func testFileOpensFullScreen() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-demo", "-open", "paper"]
        app.launch()
        let page = app.buttons["Open the file full screen"].firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 5))
        page.tap()
        sleep(6)
        save("ui-quicklook")
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5) || app.navigationBars.count > 1, "the file didn't open")
    }
}
