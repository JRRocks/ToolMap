// Port of core/src/test/kotlin/fr/toolmap/core/AppInfoTest.kt.

import Testing
@testable import ToolMapCore

struct AppInfoTests {
    @Test("app name is ToolMap")
    func appName() {
        #expect(AppInfo.name == "ToolMap")
    }

    @Test("tagline is not blank")
    func tagline() {
        #expect(!AppInfo.tagline.allSatisfy(\.isWhitespace))
    }
}
