package com.yokuli.anchorwatch.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class OfflineMapInfo(
    val installed: Boolean = false,
    val name: String? = null,
    val attribution: String? = null,
    val format: String? = null,
    val scheme: String = "tms",
    val minZoom: Int? = null,
    val maxZoom: Int? = null,
    val bounds: String? = null,
    val center: String? = null,
    val description: String? = null,
    val tileCount: Long = 0,
    val sizeBytes: Long = 0,
    val revision: Long = 0,
    val message: String? = null,
)

data class OfflineMapImportResult(val info: OfflineMapInfo)

/**
 * Imports a user-supplied raster MBTiles archive into app-private storage.
 * Google map tiles are never intercepted, copied or cached by this component.
 */
@Singleton
class OfflineMapRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory = File(context.filesDir, "offline_maps")
    private val target = File(directory, FILE_NAME)
    private val _state = MutableStateFlow(inspect(target).getOrElse { OfflineMapInfo(message = it.message) })
    val state = _state.asStateFlow()

    fun installedFile(): File? = target.takeIf { it.isFile && it.length() > 0 }

    suspend fun import(uri: Uri): Result<OfflineMapImportResult> = withContext(Dispatchers.IO) { runCatching {
        directory.mkdirs()
        val temp = File(directory, "importing.mbtiles")
        if (temp.exists()) temp.delete()
        val free = directory.usableSpace
        var written = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    require(written <= MAX_ARCHIVE_BYTES) { "Offline map is larger than the 4 GB safety limit." }
                    require(written + MIN_FREE_AFTER_IMPORT <= free) { "Not enough free storage to safely import this map." }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("Android could not read the selected MBTiles file.")
        require(written > 0) { "The selected MBTiles file is empty." }
        val info = inspect(temp).getOrThrow()
        val backup=File(directory,"previous.mbtiles")
        if(backup.exists())backup.delete()
        val movedPrevious=!target.exists()||target.renameTo(backup)
        if(!movedPrevious)error("The previous offline map could not be preserved for replacement.")
        if(!temp.renameTo(target)){
            if(backup.exists())backup.renameTo(target)
            error("The validated offline map could not be installed; the previous map was restored.")
        }
        backup.delete()
        target.setLastModified(System.currentTimeMillis())
        val installed = info.copy(installed = true, sizeBytes = target.length(), revision=target.lastModified(), message = "Offline map installed.")
        _state.value = installed
        OfflineMapImportResult(installed)
    }.onFailure { error ->
        File(directory, "importing.mbtiles").delete()
        _state.value = _state.value.copy(message = error.message ?: "Offline map import failed.")
    } }

    suspend fun remove(): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        if (target.exists() && !target.delete()) error("Offline map could not be removed.")
        _state.value = OfflineMapInfo(message = "Offline map removed.")
    } }

    fun provider(): MbTilesTileProvider? = installedFile()?.let { file ->
        val info = _state.value
        MbTilesTileProvider(file, info.scheme, info.minZoom, info.maxZoom)
    }

    private fun inspect(file: File): Result<OfflineMapInfo> = runCatching {
        if (!file.isFile || file.length() == 0L) return@runCatching OfflineMapInfo()
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        db.use { database ->
            val hasTiles = database.rawQuery("SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name='tiles'", null).use { it.moveToFirst() }
            require(hasTiles) { "This is not a valid MBTiles archive: the tiles table is missing." }
            val columns = mutableSetOf<String>()
            database.rawQuery("PRAGMA table_info(tiles)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            require(columns.containsAll(setOf("zoom_level", "tile_column", "tile_row", "tile_data"))) { "The MBTiles tiles table has an unsupported schema." }
            val metadata = linkedMapOf<String, String>()
            val hasMetadata = database.rawQuery("SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name='metadata'", null).use { it.moveToFirst() }
            if (hasMetadata) database.rawQuery("SELECT name,value FROM metadata LIMIT 256", null).use { cursor ->
                while (cursor.moveToNext()) metadata[cursor.getString(0)] = cursor.getString(1)
            }
            val format = metadata["format"]?.lowercase()
            require(format == null || format in SUPPORTED_FORMATS) { "Only raster PNG, JPG or WEBP MBTiles are supported; vector PBF is not supported." }
            val stats = database.rawQuery("SELECT MIN(zoom_level),MAX(zoom_level),COUNT(*) FROM tiles", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getLong(2) > 0L) { "The MBTiles archive contains no tiles." }
                Triple(cursor.getInt(0), cursor.getInt(1), cursor.getLong(2))
            }
            require(stats.first in 0..24 && stats.second in stats.first..24) { "The MBTiles zoom range is invalid." }
            val sample = database.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { cursor ->
                require(cursor.moveToFirst()) { "The MBTiles archive contains no tile image." }
                cursor.getBlob(0)
            }
            require(isRasterImage(sample)) { "The MBTiles archive does not contain supported raster image tiles." }
            metadata["bounds"]?.let{require(parseBounds(it)!=null){"The MBTiles bounds metadata is invalid."}}
            metadata["center"]?.let{require(parseCenter(it)!=null){"The MBTiles center metadata is invalid."}}
            OfflineMapInfo(
                installed = file == target,
                name = metadata["name"]?.take(120) ?: file.nameWithoutExtension,
                attribution = metadata["attribution"]?.take(500),
                format = format ?: detectedFormat(sample),
                scheme = metadata["scheme"]?.lowercase()?.takeIf { it == "xyz" } ?: "tms",
                minZoom = stats.first,
                maxZoom = stats.second,
                bounds = metadata["bounds"]?.take(160),
                center = metadata["center"]?.take(120),
                description = metadata["description"]?.take(1_000),
                tileCount = stats.third,
                sizeBytes = file.length(),
                revision = file.lastModified(),
            )
        }
    }

    private fun isRasterImage(data: ByteArray) = detectedFormat(data) != null
    private fun parseBounds(value:String):List<Double>?=value.split(',').map{it.trim().toDoubleOrNull()?:return null}.takeIf{it.size==4&&it[0] in -180.0..180.0&&it[2] in -180.0..180.0&&it[1] in -85.0..85.0&&it[3] in -85.0..85.0&&it[0]<it[2]&&it[1]<it[3]}
    private fun parseCenter(value:String):List<Double>?=value.split(',').map{it.trim().toDoubleOrNull()?:return null}.takeIf{it.size in 2..3&&it[0] in -180.0..180.0&&it[1] in -85.0..85.0&&(it.size==2||it[2] in 0.0..24.0)}
    private fun detectedFormat(data: ByteArray): String? = when {
        data.size >= 8 && data.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpg"
        data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }

    companion object {
        const val FILE_NAME = "current.mbtiles"
        const val MAX_ARCHIVE_BYTES = 4_000_000_000L
        const val MIN_FREE_AFTER_IMPORT = 250_000_000L
        private val SUPPORTED_FORMATS = setOf("png", "jpg", "jpeg", "webp")
    }
}

class MbTilesTileProvider(
    file: File,
    private val scheme: String,
    private val minZoom: Int?,
    private val maxZoom: Int?,
) : TileProvider, Closeable {
    private val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    @Synchronized
    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        if (zoom !in 0..24 || minZoom?.let { zoom < it } == true || maxZoom?.let { zoom > it } == true) return TileProvider.NO_TILE
        val extent = 1L shl zoom
        if (x < 0 || y < 0 || x.toLong() >= extent || y.toLong() >= extent) return TileProvider.NO_TILE
        val row = if (scheme == "xyz") y.toLong() else extent - 1L - y
        val bytes = database.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            arrayOf(zoom.toString(), x.toString(), row.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        return bytes?.let { Tile(256, 256, it) } ?: TileProvider.NO_TILE
    }

    override fun close() { database.close() }
}
