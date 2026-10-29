// Port of core/src/test/kotlin/fr/toolmap/core/geo/ProjectionsTest.kt: reference values computed with PROJ
// (EPSG:326xx / 327xx, EPSG:2154) and the `mgrs` library.

import Testing
@testable import ToolMapCore

private struct Ref {
    let lat: Double
    let lon: Double
    let zone: Int
    let north: Bool
    let e: Double
    let n: Double
    let lx: Double?
    let ly: Double?
    let mgrs: String?
}

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

struct ProjectionsTests {
    private let refs: [Ref] = [
        Ref(lat: 44.57641126591504, lon: -2.6143853259437257, zone: 30, north: true, e: 530615.4369868224, n: 4935969.122838298, lx: 254620.158826229, ly: 6402210.31516703, mgrs: "30TWQ3061535969"),
        Ref(lat: 47.68387749387861, lon: -3.5945464166557155, zone: 30, north: true, e: 455379.3188569226, n: 5281336.683753321, lx: 205822.91391553095, ly: 6752146.39294708, mgrs: "30TVT5537981336"),
        Ref(lat: 46.59087904091355, lon: 0.07111146140731961, zone: 31, north: true, e: 275640.8961094668, n: 5163869.287719299, lx: 475810.0405752151, ly: 6614251.0303879315, mgrs: "31TBM7564063869"),
        Ref(lat: 42.05098978535972, lon: 1.8429466648677533, zone: 31, north: true, e: 404251.4513891748, n: 4656085.183202932, lx: 604024.9565818071, ly: 6106316.872536584, mgrs: "31TDG0425156085"),
        Ref(lat: 41.85620875519886, lon: 0.9205710457798233, zone: 31, north: true, e: 327395.26014702994, n: 4635901.912799291, lx: 526958.789332901, ly: 6086211.263679601, mgrs: "31TCG2739535901"),
        Ref(lat: 42.16362652395888, lon: -3.366087333201687, zone: 30, north: true, e: 469759.2929602551, n: 4668008.600434748, lx: 173509.48983554344, ly: 6139383.837439148, mgrs: "30TVM6975968008"),
        Ref(lat: 45.532932296853886, lon: 5.8356515584004764, zone: 31, north: true, e: 721409.6272025686, n: 5046066.853321635, lx: 921274.5336638116, ly: 6496579.03902305, mgrs: "31TGL2140946066"),
        Ref(lat: 42.676118630921636, lon: -1.709512942412318, zone: 30, north: true, e: 605736.9953005228, n: 4725656.581152934, lx: 313719.56915726786, ly: 6186688.254454249, mgrs: "30TXN0573625656"),
        Ref(lat: 47.4606156128531, lon: 7.34636178071257, zone: 32, north: true, e: 375367.18758763274, n: 5257678.085108612, lx: 1027283.2046186705, ly: 6715705.10363569, mgrs: "32TLT7536757678"),
        Ref(lat: 46.982478011866235, lon: 0.4585059331347523, zone: 31, north: true, e: 306722.60119526053, n: 5206352.1156490855, lx: 506851.48572097754, ly: 6656693.328993984, mgrs: "31TCN0672206352"),
        Ref(lat: 50.77442350313274, lon: -3.9177164922780463, zone: 30, north: true, e: 435293.00817233406, n: 5625141.871390565, lx: 211748.73975231516, ly: 7096719.934598374, mgrs: "30UVB3529325141"),
        Ref(lat: 49.655450360962455, lon: -0.8798839208540468, zone: 30, north: true, e: 653020.0176101903, n: 5502481.463747837, lx: 419797.53958057775, ly: 6957588.811496689, mgrs: "30UXA5302002481"),
        Ref(lat: -55.63067649609512, lon: -136.83037876794413, zone: 8, north: false, e: 384764.141810646, n: 3833504.346678555, lx: nil, ly: nil, mgrs: "08FLD8476433504"),
        Ref(lat: -29.025944495486634, lon: 113.17323656497126, zone: 49, north: false, e: 711647.2653620611, n: 6787191.784302976, lx: nil, ly: nil, mgrs: "49JGH1164787191"),
        Ref(lat: -49.72232645232213, lon: 29.212858591162927, zone: 35, north: false, e: 659494.1573907305, n: 4489891.261901166, lx: nil, ly: nil, mgrs: "35FPE5949489891"),
        Ref(lat: 24.50398196604182, lon: -45.68167970418821, zone: 23, north: true, e: 430937.2367500436, n: 2710196.6019575745, lx: nil, ly: nil, mgrs: "23RMH3093710196"),
        Ref(lat: 9.734603444948362, lon: -156.5215469595503, zone: 4, north: true, e: 771925.3395823163, n: 1077064.4561773653, lx: nil, ly: nil, mgrs: "04PGR7192577064"),
        Ref(lat: -69.34461046547031, lon: -105.2667808106811, zone: 13, north: false, e: 489497.56530176825, n: 2307188.583943354, lx: nil, ly: nil, mgrs: "13DDD8949707188"),
        Ref(lat: 31.224795655449313, lon: -25.921954570353762, zone: 26, north: true, e: 602677.3587005178, n: 3455016.1607920085, lx: nil, ly: nil, mgrs: "26RPV0267755016"),
        Ref(lat: -28.108158398959773, lon: 30.631147135734665, zone: 36, north: false, e: 267284.2492543245, n: 6888548.923602009, lx: nil, ly: nil, mgrs: "36JTP6728488548"),
        Ref(lat: -5.58413102793439, lon: -71.68341512280172, zone: 19, north: false, e: 202703.3230642698, n: 9382088.3378922, lx: nil, ly: nil, mgrs: "19MBP0270382088"),
        Ref(lat: 49.68947600664356, lon: 71.24000727518651, zone: 42, north: true, e: 661559.7172433021, n: 5506514.950862923, lx: nil, ly: nil, mgrs: "42UXA6155906514"),
        Ref(lat: -39.456365263011236, lon: 26.64368827260421, zone: 35, north: false, e: 469345.48388897465, n: 5632517.510745985, lx: nil, ly: nil, mgrs: "35HMS6934532517"),
        Ref(lat: 6.081833617455132, lon: 134.29922341528754, zone: 53, north: true, e: 422455.1926101782, n: 672300.8918448151, lx: nil, ly: nil, mgrs: "53NMG2245572300"),
        Ref(lat: 48.8566, lon: 2.3522, zone: 31, north: true, e: 452482.5327026278, n: 5411717.1768689, lx: 652469.0227091359, ly: 6862035.259420079, mgrs: "31UDQ5248211717"),
        Ref(lat: 45.0, lon: 3.0, zone: 31, north: true, e: 500000.0000000007, n: 4982950.400226552, lx: 700000.0, ly: 6433418.985424661, mgrs: "31TEK0000082950"),
        Ref(lat: 0.0, lon: 0.0, zone: 31, north: true, e: 166021.44308054057, n: 0.0, lx: nil, ly: nil, mgrs: "31NAA6602100000"),
        Ref(lat: -33.8688, lon: 151.2093, zone: 56, north: false, e: 334368.633648097, n: 6250948.345385009, lx: nil, ly: nil, mgrs: "56HLH3436850948"),
        Ref(lat: 45.188, lon: 5.724, zone: 31, north: true, e: 713989.1938340532, n: 5007445.233328477, lx: 913886.7726731828, ly: 6457988.30676883, mgrs: "31TGL1398907445"),
        Ref(lat: 78.2, lon: 15.6, zone: 33, north: true, e: 513696.94541704346, n: 8680760.053195585, lx: nil, ly: nil, mgrs: "33XWG1369680760"),
    ]

    @Test("UTM matches PROJ to a millimeter")
    func utm() {
        for r in refs {
            let u = Utm.toUtm(r.lat, r.lon)
            #expect(u.zone == r.zone)
            #expect(u.north == r.north)
            #expect(near(u.easting, r.e, 0.001), "easting at \(r.lat),\(r.lon): \(u.easting)")
            #expect(near(u.northing, r.n, 0.001), "northing at \(r.lat),\(r.lon): \(u.northing)")
        }
    }

    @Test("UTM inverse brings the position back")
    func utmInverse() {
        for r in refs {
            let back = Utm.toLonLat(UtmCoordinate(zone: r.zone, north: r.north, easting: r.e, northing: r.n))
            #expect(near(back.lat, r.lat, 1e-8), "lat at \(r.lat),\(r.lon)")
            #expect(near(back.lon, r.lon, 1e-8), "lon at \(r.lat),\(r.lon)")
        }
    }

    @Test("an extended zone keeps working past the zone edge")
    func extendedZone() {
        // Paris is at 2.35 E: zone 31 covers it, zone 30 (the neighbour to the west) extends its grid eastwards.
        let extended = Utm.toUtm(48.8566, 2.3522, zone: 30)
        #expect((850_000.0 ... 950_000.0).contains(extended.easting), "east of the neighbour's central meridian")
        let back = Utm.toLonLat(extended)
        #expect(near(back.lon, 2.3522, 1e-8))
        #expect(near(back.lat, 48.8566, 1e-8))
    }

    @Test("Lambert 93 matches PROJ to a millimeter and round trips")
    func lambert93() {
        var checked = 0
        for r in refs {
            guard let lx = r.lx, let ly = r.ly else { continue }
            let p = Lambert93.toLambert(r.lat, r.lon)
            #expect(near(p.easting, lx, 0.001), "easting at \(r.lat),\(r.lon)")
            #expect(near(p.northing, ly, 0.001), "northing at \(r.lat),\(r.lon)")
            let back = Lambert93.toLonLat(p)
            #expect(near(back.lat, r.lat, 1e-8))
            #expect(near(back.lon, r.lon, 1e-8))
            checked += 1
        }
        #expect(checked >= 10, "only \(checked) French points")
    }

    @Test("MGRS matches the reference library")
    func mgrs() throws {
        var checked = 0
        for r in refs {
            guard let expected = r.mgrs else { continue }
            let reference = try #require(Mgrs.of(r.lat, r.lon))
            // The library writes zones 1 to 9 with a leading zero (`08F`), ToolMap without.
            let trimmed = String(expected.drop { $0 == "0" })
            #expect(try reference.format(digits: 5, separators: false) == trimmed, "at \(r.lat),\(r.lon)")
            checked += 1
        }
        #expect(checked >= 20, "only \(checked) compared")
    }

    @Test("MGRS is truncated like a printed map and has no value near the poles")
    func mgrsTruncated() throws {
        let reference = try #require(Mgrs.of(48.8566, 2.3522))
        #expect(try reference.format() == "31U DQ 52482 11717")
        #expect(try reference.format(digits: 3) == "31U DQ 524 117")
        #expect(try reference.format(digits: 0) == "31U DQ")
        #expect(Mgrs.of(89.0, 0.0) == nil)
        #expect(Mgrs.of(-85.0, 0.0) == nil)
    }

    @Test("zones and bands")
    func zonesAndBands() {
        #expect(Utm.zoneOf(2.35) == 31)
        #expect(Utm.zoneOf(-179.9) == 1)
        #expect(Utm.zoneOf(179.9) == 60)
        #expect(Utm.bandLetter(48.8566) == "U")
        #expect(Utm.bandLetter(-79.0) == "C")
        #expect(Utm.bandLetter(84.0) == "X")
        #expect(Utm.bandLetter(85.0) == nil)
    }
}
