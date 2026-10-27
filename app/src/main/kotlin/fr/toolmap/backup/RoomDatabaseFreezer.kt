package fr.toolmap.backup

import fr.toolmap.data.tracks.TrackDatabase

/**
 * Holds the tracks database still while its file is copied: the write-ahead log is folded into the main file, then a
 * write transaction is kept open so that nothing else can write (readers go on) until the copy is done.
 */
class RoomDatabaseFreezer(
    private val database: TrackDatabase,
) : (() -> Unit) -> Unit {
    override fun invoke(copy: () -> Unit) {
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        db.beginTransaction()
        try {
            copy()
        } finally {
            // Nothing was written: end it without committing.
            db.endTransaction()
        }
    }
}
