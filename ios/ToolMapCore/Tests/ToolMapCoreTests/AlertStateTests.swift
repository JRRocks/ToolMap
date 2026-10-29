// Port of core/src/test/kotlin/fr/toolmap/core/alert/AlertStateTest.kt.

import Testing
@testable import ToolMapCore

struct AlertStateTests {
    @Test("an empty state is idle and a damaged one reads as empty")
    func emptyAndDamaged() {
        #expect(AlertState().isIdle)
        #expect(AlertStateCodec.decodeOrEmpty(nil) == AlertState())
        #expect(AlertStateCodec.decodeOrEmpty("  ") == AlertState())
        #expect(AlertStateCodec.decodeOrEmpty("{ not json") == AlertState())
        #expect(AlertStateCodec.decodeOrEmpty("[1, 2]") == AlertState())
    }

    @Test("a state survives the file")
    func survivesTheFile() {
        let state = AlertState(
            follow: FollowRef(kind: .route, id: 12, reversed: true),
            watches: [WatchEntry(waypointId: 3, radiusMeters: 250), WatchEntry(waypointId: 4)]
        )
        #expect(AlertStateCodec.decodeOrEmpty(AlertStateCodec.encode(state)) == state)
    }

    @Test("unknown fields and values from another version are tolerated")
    func unknownFieldsTolerated() {
        let text = #"{"follow":{"kind":"TRACK","id":5,"future":true},"watches":[{"waypointId":9,"radiusMeters":80}],"x":1}"#
        let state = AlertStateCodec.decodeOrEmpty(text)
        #expect(state.follow == FollowRef(kind: .track, id: 5))
        #expect(state.watching(9)?.radiusMeters == 80)
    }

    @Test("watching a waypoint twice replaces the radius")
    func watchingTwice() {
        let state = AlertState().withWatch(1, nil).withWatch(2, 50).withWatch(1, 300)
        #expect(state.watches.count == 2)
        #expect(state.watching(1)?.radiusMeters == 300)
        #expect(state.watching(2)?.radiusMeters == 50)
        #expect(state.withoutWatch(1).watching(1) == nil)
        #expect(state.withoutWatch(1).watches.count == 1)
        #expect(AlertState().withWatch(1, nil).withoutWatch(1).isIdle)
    }

    @Test("removing forgets the given items only")
    func removing() {
        let state = AlertState(
            follow: FollowRef(kind: .route, id: 5),
            watches: [WatchEntry(waypointId: 1), WatchEntry(waypointId: 2, radiusMeters: 40)]
        )
        // A track with the same id does not stand in for the route.
        #expect(state.removing(trackIds: [5], routeIds: [], waypointIds: [9]) == state)
        let without = state.removing(trackIds: [], routeIds: [5], waypointIds: [1])
        #expect(without.follow == nil)
        #expect(without.watches == [WatchEntry(waypointId: 2, radiusMeters: 40)])
    }

    @Test("pruning forgets deleted items only")
    func pruning() {
        let state = AlertState(
            follow: FollowRef(kind: .track, id: 5),
            watches: [WatchEntry(waypointId: 1), WatchEntry(waypointId: 2, radiusMeters: 40)]
        )
        let kept = state.pruned(trackIds: [5], routeIds: [], waypointIds: [2])
        #expect(kept.follow == FollowRef(kind: .track, id: 5))
        #expect(kept.watches == [WatchEntry(waypointId: 2, radiusMeters: 40)])
        let gone = state.pruned(trackIds: [], routeIds: [5], waypointIds: [])
        #expect(gone.isIdle)
        // A route with the same id does not stand in for the track.
        #expect(gone.follow == nil)
    }
}
