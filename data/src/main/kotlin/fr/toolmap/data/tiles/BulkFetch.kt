package fr.toolmap.data.tiles

/**
 * Put on the request of a zone download (`Request.Builder.tag(BulkFetch::class.java, …)`): the downloader has already
 * checked that the tile is not stored, so [TileInterceptor] neither looks it up again nor writes it. It hands the tile
 * it downloaded to [sink], which writes tiles in batches (ADR-058).
 */
class BulkFetch(
    val sink: (TileKey, StoredTile) -> Unit,
)
