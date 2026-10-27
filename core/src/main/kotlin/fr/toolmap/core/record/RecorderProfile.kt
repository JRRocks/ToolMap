package fr.toolmap.core.record

/** Ready-made recording settings, so that nobody has to think in milliseconds and meters before a hike. */
enum class RecorderProfile(
    val settings: RecorderSettings,
) {
    /** Hiking: a point every 2 s and 5 m. */
    WALK(RecorderSettings(minIntervalMillis = 2_000, minDistanceMeters = 5.0)),

    /** Cycling: fewer points on straight roads. */
    BIKE(RecorderSettings(minIntervalMillis = 2_000, minDistanceMeters = 10.0)),

    /** Every second, 2 m: dense tracks, more battery and storage. */
    PRECISE(RecorderSettings(minIntervalMillis = 1_000, minDistanceMeters = 2.0)),

    /** A point every 10 s and 25 m: days of recording on one charge. */
    SAVER(RecorderSettings(minIntervalMillis = 10_000, minDistanceMeters = 25.0, maxAccuracyMeters = 80.0)),
    ;

    companion object {
        fun fromName(name: String?): RecorderProfile = entries.firstOrNull { it.name == name } ?: WALK
    }
}
