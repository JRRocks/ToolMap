package fr.toolmap.core.backup

/**
 * The first bytes of an SQLite file (https://www.sqlite.org/fileformat.html § 1.3): enough to refuse, before a restore
 * swaps it in, a database the app could not open (P36 review). A newer schema than the app knows, or a file that is not
 * SQLite, would otherwise make every start crash once it replaced the phone's database.
 */
object SqliteHeader {
    const val SIZE = 100
    private const val USER_VERSION_OFFSET = 60
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xff
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** The `PRAGMA user_version` stored in [header] (the first [SIZE] bytes of the file); `null` when not SQLite. */
    fun userVersion(header: ByteArray): Int? {
        if (header.size < SIZE) return null
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return null
        var version = 0
        for (i in 0 until Int.SIZE_BYTES) {
            version = (version shl BITS_PER_BYTE) or (header[USER_VERSION_OFFSET + i].toInt() and BYTE_MASK)
        }
        return version
    }

    /** Why a restored database must be refused (`null` when it may be used), for an app at schema [appVersion]. */
    fun refusal(
        header: ByteArray,
        appVersion: Int,
    ): BackupFailure? {
        val version = userVersion(header) ?: return BackupFailure.CORRUPT
        return if (version > appVersion) BackupFailure.UNSUPPORTED_VERSION else null
    }
}
