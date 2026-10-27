package fr.toolmap.core.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertStateTest {
    @Test
    fun `an empty state is idle and a damaged one reads as empty`() {
        assertTrue(AlertState().isIdle)
        assertEquals(AlertState(), AlertStateCodec.decodeOrEmpty(null))
        assertEquals(AlertState(), AlertStateCodec.decodeOrEmpty("  "))
        assertEquals(AlertState(), AlertStateCodec.decodeOrEmpty("{ not json"))
        assertEquals(AlertState(), AlertStateCodec.decodeOrEmpty("[1, 2]"))
    }

    @Test
    fun `a state survives the file`() {
        val state =
            AlertState(
                follow = FollowRef(FollowKind.ROUTE, 12L, reversed = true),
                watches = listOf(WatchEntry(3L, 250), WatchEntry(4L)),
            )
        assertEquals(state, AlertStateCodec.decodeOrEmpty(AlertStateCodec.encode(state)))
    }

    @Test
    fun `unknown fields and values from another version are tolerated`() {
        val text =
            """{"follow":{"kind":"TRACK","id":5,"future":true},"watches":[{"waypointId":9,"radiusMeters":80}],"x":1}"""
        val state = AlertStateCodec.decodeOrEmpty(text)
        assertEquals(FollowRef(FollowKind.TRACK, 5L), state.follow)
        assertEquals(80, state.watching(9L)?.radiusMeters)
    }

    @Test
    fun `watching a waypoint twice replaces the radius`() {
        val state = AlertState().withWatch(1L, null).withWatch(2L, 50).withWatch(1L, 300)
        assertEquals(2, state.watches.size)
        assertEquals(300, state.watching(1L)?.radiusMeters)
        assertEquals(50, state.watching(2L)?.radiusMeters)
        assertNull(state.withoutWatch(1L).watching(1L))
        assertEquals(1, state.withoutWatch(1L).watches.size)
        assertTrue(AlertState().withWatch(1L, null).withoutWatch(1L).isIdle)
    }

    @Test
    fun `removing forgets the given items only`() {
        val state =
            AlertState(
                follow = FollowRef(FollowKind.ROUTE, 5L),
                watches = listOf(WatchEntry(1L), WatchEntry(2L, 40)),
            )
        // A track with the same id does not stand in for the route.
        assertEquals(state, state.removing(setOf(5L), emptySet(), setOf(9L)))
        val without = state.removing(emptySet(), setOf(5L), setOf(1L))
        assertNull(without.follow)
        assertEquals(listOf(WatchEntry(2L, 40)), without.watches)
    }

    @Test
    fun `pruning forgets deleted items only`() {
        val state =
            AlertState(
                follow = FollowRef(FollowKind.TRACK, 5L),
                watches = listOf(WatchEntry(1L), WatchEntry(2L, 40)),
            )
        val kept = state.pruned(trackIds = setOf(5L), routeIds = emptySet(), waypointIds = setOf(2L))
        assertEquals(FollowRef(FollowKind.TRACK, 5L), kept.follow)
        assertEquals(listOf(WatchEntry(2L, 40)), kept.watches)
        val gone = state.pruned(trackIds = emptySet(), routeIds = setOf(5L), waypointIds = emptySet())
        assertTrue(gone.isIdle)
        // A route with the same id does not stand in for the track.
        assertNull(gone.follow)
    }
}
