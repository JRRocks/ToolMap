package fr.toolmap.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteHeaderTest {
    private fun header(userVersion: Int): ByteArray {
        val bytes = ByteArray(SqliteHeader.SIZE)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        for (i in 0 until 4) bytes[60 + i] = (userVersion ushr (24 - 8 * i)).toByte()
        return bytes
    }

    @Test
    fun `the schema version is read from the header`() {
        assertEquals(3, SqliteHeader.userVersion(header(3)))
        assertEquals(258, SqliteHeader.userVersion(header(258)))
        assertNull(SqliteHeader.userVersion(ByteArray(SqliteHeader.SIZE)), "not SQLite")
        assertNull(SqliteHeader.userVersion(header(3).copyOf(50)), "too short")
    }

    @Test
    fun `a database the app cannot open is refused before the restore`() {
        assertNull(SqliteHeader.refusal(header(3), appVersion = 3))
        assertNull(SqliteHeader.refusal(header(1), appVersion = 3), "an older schema is migrated by Room")
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, SqliteHeader.refusal(header(4), appVersion = 3))
        assertEquals(BackupFailure.UNSUPPORTED_VERSION, SqliteHeader.refusal(header(99), appVersion = 3))
        assertEquals(BackupFailure.CORRUPT, SqliteHeader.refusal("not a database".toByteArray(), appVersion = 3))
    }
}
