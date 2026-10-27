package fr.toolmap.data.tracks

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder of the library. Deleting it deletes what is inside (the user is asked first). */
@Entity(
    tableName = "folders",
    foreignKeys =
        [
            ForeignKey(
                FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["parentId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("parentId")],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val name: String,
    val visible: Boolean = true,
    val createdAt: Long,
    /** XML namespace prefixes the raw extensions of the folder's items rely on (top-level import folders only). */
    val namespacesJson: String? = null,
)

/**
 * A track. Its points live in [TrackChunkEntity] rows; what a list needs (size, bounds, distance, climb, dates) is
 * kept here so that showing the library never decodes a point.
 */
@Entity(
    tableName = "tracks",
    foreignKeys =
        [
            ForeignKey(
                FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folderId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("folderId")],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val name: String? = null,
    val cmt: String? = null,
    val desc: String? = null,
    val src: String? = null,
    val number: Int? = null,
    val type: String? = null,
    val linksJson: String? = null,
    val extrasJson: String? = null,
    val colorArgb: Int,
    val widthDp: Float,
    val dashed: Boolean = false,
    val arrows: Boolean = false,
    val visible: Boolean = true,
    val createdAt: Long,
    /** Format of the file it came from (`GPX`, `KML`...), or `null` when drawn or recorded in the app. */
    val format: String? = null,
    val pointCount: Int,
    val segmentCount: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val distanceMeters: Double,
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null,
)

/**
 * A run of consecutive points of one segment of a track, in [fr.toolmap.core.track.PointCodec] form. Chunks are small
 * enough (a few hundred kilobytes at most) to stay under Android's 2 MB cursor window, and a recorder can append
 * chunks to a track without rewriting it.
 */
@Entity(
    tableName = "track_chunks",
    primaryKeys = ["trackId", "segment", "chunk"],
    foreignKeys =
        [
            ForeignKey(
                TrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
class TrackChunkEntity(
    val trackId: Long,
    val segment: Int,
    val chunk: Int,
    val pointCount: Int,
    val data: ByteArray,
)

/** A route: its few points (with names) are kept as JSON. */
@Entity(
    tableName = "routes",
    foreignKeys =
        [
            ForeignKey(
                FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folderId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("folderId")],
)
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val name: String? = null,
    val cmt: String? = null,
    val desc: String? = null,
    val src: String? = null,
    val number: Int? = null,
    val type: String? = null,
    val linksJson: String? = null,
    val extrasJson: String? = null,
    val colorArgb: Int,
    val widthDp: Float,
    val dashed: Boolean = false,
    val arrows: Boolean = false,
    val visible: Boolean = true,
    val createdAt: Long,
    val format: String? = null,
    val pointCount: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val distanceMeters: Double,
    val pointsJson: String,
)

@Entity(
    tableName = "waypoints",
    foreignKeys =
        [
            ForeignKey(
                FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folderId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("folderId")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val name: String? = null,
    val cmt: String? = null,
    val desc: String? = null,
    val src: String? = null,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMillis: Long? = null,
    val sym: String? = null,
    val type: String? = null,
    val linksJson: String? = null,
    val extrasJson: String? = null,
    val visible: Boolean = true,
    val createdAt: Long,
    val format: String? = null,
)
