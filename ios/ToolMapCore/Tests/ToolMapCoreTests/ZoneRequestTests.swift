// Port of core/src/test/kotlin/fr/toolmap/core/download/ZoneRequestTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct ZoneRequestTests {
    private func request(_ shape: ZoneShape) throws -> ZoneRequest {
        try ZoneRequest(
            id: "job1",
            name: "Mont Blanc",
            sourceIds: ["ign-scan25", "ign-pentes"],
            minZoom: 10,
            maxZoom: 16,
            shape: shape,
            createdAtMillis: 42
        )
    }

    @Test("every shape survives a round trip through JSON")
    func roundTrip() throws {
        let shapes: [DownloadArea] = [
            .rectangle(bounds: Bounds(minX: 6.8, minY: 45.8, maxX: 6.9, maxY: 45.9)),
            .polygon(ring: [LonLat(lon: 6.8, lat: 45.8), LonLat(lon: 6.9, lat: 45.8), LonLat(lon: 6.85, lat: 45.9)]),
            .corridor(path: [LonLat(lon: 6.8, lat: 45.8), LonLat(lon: 6.9, lat: 45.9)], radiusMeters: 1000.0),
        ]
        for area in shapes {
            let zone = try request(ZoneShape.of(area))
            let job = ZoneJob(
                request: zone,
                status: .running,
                progress: ZoneProgress(total: 100, downloaded: 40, alreadyStored: 10, missing: 1, failed: 2, bytes: 5000),
                message: "msg",
                updatedAtMillis: 7
            )
            let text = try ZoneJobCodec.encode(job)
            let back = try #require(ZoneJobCodec.decodeOrNull(text))
            #expect(back == job)
            #expect(back.request.shape.toArea() == area)
        }
    }

    @Test("the shape is written as kotlinx.serialization writes a sealed class")
    func kotlinShape() throws {
        let json = #"""
        {"request":{"id":"j","name":"n","sourceIds":["osm"],"minZoom":3,"maxZoom":5,
        "shape":{"type":"fr.toolmap.core.download.ZoneShape.Corridor","points":[{"lon":6.8,"lat":45.8},{"lon":6.9,"lat":45.9}],"radiusMeters":500.0},
        "createdAtMillis":1},"status":"PAUSED","progress":{"total":10,"downloaded":3,"alreadyStored":0,"missing":0,"failed":0,"bytes":99},
        "message":null,"updatedAtMillis":2}
        """#
        let job = try #require(ZoneJobCodec.decodeOrNull(json))
        let points = [ZoneShape.Point(lon: 6.8, lat: 45.8), ZoneShape.Point(lon: 6.9, lat: 45.9)]
        #expect(job.request.shape == .corridor(ZoneShape.Corridor(points: points, radiusMeters: 500.0)))
        #expect(job.status == .paused)
        #expect(job.progress.bytes == 99)
        #expect(job.request.zoomLimits.isEmpty)
        let written = try ZoneJobCodec.encode(job)
        #expect(written.contains(#""type":"fr.toolmap.core.download.ZoneShape.Corridor""#))
    }

    @Test("a damaged job file reads as null")
    func damaged() {
        #expect(ZoneJobCodec.decodeOrNull("{not json") == nil)
        #expect(ZoneJobCodec.decodeOrNull("") == nil)
    }

    @Test("progress fraction counts everything processed")
    func fraction() {
        let progress = ZoneProgress(total: 200, downloaded: 50, alreadyStored: 30, missing: 10, failed: 10)
        #expect(progress.processed == 100)
        #expect(progress.fraction == 0.5)
        #expect(ZoneProgress().fraction == 1)
    }

    @Test("invalid requests are refused")
    func invalid() {
        let shape = ZoneShape.rectangle(ZoneShape.Rectangle(west: 0.0, south: 0.0, east: 1.0, north: 1.0))
        #expect(throws: ToolMapError.self) {
            try ZoneRequest(id: "a", name: "a", sourceIds: [], minZoom: 1, maxZoom: 2, shape: shape)
        }
        #expect(throws: ToolMapError.self) {
            try ZoneRequest(id: "a", name: "a", sourceIds: ["s"], minZoom: 5, maxZoom: 4, shape: shape)
        }
        #expect(throws: ToolMapError.self) {
            try ZoneRequest(id: "a", name: "a", sourceIds: ["s"], minZoom: 0, maxZoom: 40, shape: shape)
        }
    }

    @Test("zoom limits include their bounds and an absent limit is unbounded")
    func zoomLimits() {
        let limit = ZoomLimit(min: 8, max: 14)
        #expect([7, 8, 14, 15].map { limit.contains($0) } == [false, true, true, false])
        #expect(ZoomLimit().contains(3))
        #expect(ZoomLimit(min: 8).contains(20))
        #expect(!ZoomLimit(max: 12).contains(20))
    }

    @Test("a job file written before zoom limits existed still loads")
    func beforeZoomLimits() throws {
        let shape = ZoneShape.rectangle(ZoneShape.Rectangle(west: 6.8, south: 45.8, east: 6.9, north: 45.9))
        let limited = try ZoneRequest(
            id: "job1",
            name: "Mont Blanc",
            sourceIds: ["ign-scan25", "ign-pentes"],
            minZoom: 10,
            maxZoom: 16,
            shape: shape,
            createdAtMillis: 42,
            zoomLimits: ["ign-scan25": ZoomLimit(min: 8, max: 14)]
        )
        let withLimits = ZoneJob(request: limited, status: .queued)
        let withLimitsText = try ZoneJobCodec.encode(withLimits)
        #expect(ZoneJobCodec.decodeOrNull(withLimitsText) == withLimits)

        let plainRequest = try request(shape)
        let plain = ZoneJob(request: plainRequest, status: .queued)
        let json = try ZoneJobCodec.encode(plain)
        let regex = try NSRegularExpression(pattern: ",?[ \\t\\n\\r]*\"zoomLimits\"[ \\t\\n\\r]*:[ \\t\\n\\r]*\\{[ \\t\\n\\r]*\\}")
        let legacy = regex.stringByReplacingMatches(
            in: json,
            options: [],
            range: NSRange(location: 0, length: (json as NSString).length),
            withTemplate: ""
        )
        #expect(!legacy.contains("zoomLimits"), "the field was removed from the JSON")
        #expect(ZoneJobCodec.decodeOrNull(legacy) == plain)
    }
}
