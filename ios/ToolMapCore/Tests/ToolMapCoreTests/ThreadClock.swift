// The clock of the speed tests (GpxTests, TrackGeoJsonTests).

import Foundation

/// CPU time of the calling thread, in seconds. A test of speed measures this rather than the wall clock: the iPhone
/// simulator runs the thousand tests at once on few cores, where 6 s of work took 27 s of wall time (2026-10-29).
enum ThreadClock {
    static func now() -> Double {
        Double(clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)) / 1e9
    }
}
