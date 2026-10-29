// Port of core/src/test/kotlin/fr/toolmap/core/geo/PointLinksTest.kt.

import Testing
@testable import ToolMapCore

struct PointLinksTests {
    @Test("the generic link is a pin at the position, with a point as decimal separator")
    func generic() {
        #expect(PointLinks.geo(48.5, 2.25) == "geo:48.500000,2.250000?q=48.500000,2.250000")
    }

    // Kotlin sets the JVM default locale to French; Swift's `String(format:)` has no default locale.
    @Test("the decimals stay points on a phone set to French")
    func frenchPhone() {
        #expect(PointLinks.geo(-33.8688, 151.2093) == "geo:-33.868800,151.209300?q=-33.868800,151.209300")
    }

    @Test("Waze gets its own link, which starts the guidance to the position")
    func waze() {
        #expect(
            PointLinks.target(PointLinks.wazePackage, 48.5, 2.25)
                == PointTarget(uri: "https://waze.com/ul?ll=48.500000,2.250000&navigate=yes", packageName: "com.waze")
        )
    }

    @Test("another app gets the generic link, and the system's choice gets it without a package")
    func otherApps() {
        #expect(
            PointLinks.target("org.osmand", 48.5, 2.25)
                == PointTarget(uri: "geo:48.500000,2.250000?q=48.500000,2.250000", packageName: "org.osmand")
        )
        #expect(
            PointLinks.target(nil, 48.5, 2.25)
                == PointTarget(uri: "geo:48.500000,2.250000?q=48.500000,2.250000", packageName: nil)
        )
    }
}
