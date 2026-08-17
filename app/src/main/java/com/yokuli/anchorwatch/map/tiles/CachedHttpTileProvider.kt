package com.yokuli.anchorwatch.map.tiles

import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

object XyzTileCoordinates {
    const val MAX_ZOOM = 24
    fun valid(x: Int, y: Int, zoom: Int): Boolean {
        if (zoom !in 0..MAX_ZOOM || x < 0 || y < 0) return false
        val extent = 1L shl zoom
        return x.toLong() < extent && y.toLong() < extent
    }
}

data class HttpTileResponse(val statusCode: Int, val body: ByteArray)

fun interface HttpTileFetcher {
    @Throws(Exception::class)
    fun fetch(url: URL, userAgent: String, maxBytes: Int): HttpTileResponse
}

object UrlConnectionTileFetcher : HttpTileFetcher {
    override fun fetch(url: URL, userAgent: String, maxBytes: Int): HttpTileResponse {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS tile URLs are allowed" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val status = connection.responseCode
            require(connection.url.protocol.equals("https", ignoreCase = true)) { "Tile redirect left HTTPS" }
            val body = if (status in 200..299) connection.inputStream.use { it.readAtMost(maxBytes) } else byteArrayOf()
            HttpTileResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw TileTooLargeException()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class TileTooLargeException : IllegalStateException("Tile exceeds the 4 MB safety limit")

interface TileDiagnosticsSink {
    fun requested()
    fun memoryHit()
    fun diskHit(stale: Boolean)
    fun succeeded(httpCode: Int)
    fun failed(httpCode: Int?, message: String)
}

object NoOpTileDiagnostics : TileDiagnosticsSink {
    override fun requested() = Unit
    override fun memoryHit() = Unit
    override fun diskHit(stale: Boolean) = Unit
    override fun succeeded(httpCode: Int) = Unit
    override fun failed(httpCode: Int?, message: String) = Unit
}

/**
 * On-demand, bounded XYZ cache. It never prefetches or enumerates tiles and returns stale cache data
 * after a network failure so a map failure remains visually graceful and safety-independent.
 */
class CachedHttpTileProvider(
    private val urlFor: (x: Int, y: Int, zoom: Int) -> URL?,
    private val diskCacheDirectory: File?,
    private val userAgent: String,
    private val diagnostics: TileDiagnosticsSink = NoOpTileDiagnostics,
    private val fetcher: HttpTileFetcher = UrlConnectionTileFetcher,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val memoryEntryLimit: Int = DEFAULT_MEMORY_ENTRIES,
    private val refreshAfterMillis: Long = REFRESH_AFTER_MILLIS,
    private val maxDiskBytes: Long = MAX_DISK_BYTES,
) : TileProvider {
    private val memory = object : LinkedHashMap<String, Tile>(memoryEntryLimit, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tile>?) = size > memoryEntryLimit
    }
    private val diskWrites = AtomicInteger(0)

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        if (!XyzTileCoordinates.valid(x, y, zoom)) return TileProvider.NO_TILE
        val url = urlFor(x, y, zoom)?.takeIf { it.protocol.equals("https", ignoreCase = true) }
            ?: return TileProvider.NO_TILE
        val key = "$zoom/$x/$y"
        synchronized(memory) { memory[key] }?.let {
            diagnostics.memoryHit()
            return it
        }
        diagnostics.requested()
        val diskFile = diskCacheDirectory?.let { File(it, "$zoom/$x/$y.tile") }
        val diskBytes = readDisk(diskFile)
        val diskIsFresh = diskBytes != null && clockMillis() - (diskFile?.lastModified() ?: 0L) <= refreshAfterMillis
        if (diskIsFresh) {
            diagnostics.diskHit(false)
            return tile(diskBytes!!, key)
        }
        return try {
            val response = fetcher.fetch(url, userAgent, MAX_TILE_BYTES)
            when {
                response.statusCode !in 200..299 -> {
                    diagnostics.failed(response.statusCode, "Tile HTTP ${response.statusCode}")
                    staleOrNoTile(diskBytes, key)
                }
                response.body.isEmpty() -> {
                    diagnostics.failed(response.statusCode, "Tile response was empty")
                    staleOrNoTile(diskBytes, key)
                }
                response.body.size > MAX_TILE_BYTES -> {
                    diagnostics.failed(response.statusCode, "Tile exceeded the 4 MB safety limit")
                    staleOrNoTile(diskBytes, key)
                }
                else -> tile(response.body, key).also {
                    writeDisk(diskFile, response.body)
                    diagnostics.succeeded(response.statusCode)
                }
            }
        } catch (error: Exception) {
            diagnostics.failed(null, error.message ?: error.javaClass.simpleName)
            staleOrNoTile(diskBytes, key)
        }
    }

    private fun staleOrNoTile(bytes: ByteArray?, key: String): Tile {
        if (bytes == null) return TileProvider.NO_TILE
        diagnostics.diskHit(true)
        return tile(bytes, key)
    }

    private fun tile(bytes: ByteArray, key: String) = Tile(TILE_SIZE, TILE_SIZE, bytes).also {
        synchronized(memory) { memory[key] = it }
    }

    private fun readDisk(file: File?): ByteArray? = file
        ?.takeIf { it.isFile && it.length() in 1L..MAX_TILE_BYTES.toLong() }
        ?.let { runCatching { it.readBytes() }.getOrNull() }

    @Synchronized
    private fun writeDisk(target: File?, bytes: ByteArray) {
        target ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(temp).use { it.write(bytes) }
            if (target.exists() && !target.delete()) return@runCatching
            if (!temp.renameTo(target)) temp.delete()
            if (diskWrites.incrementAndGet() % PRUNE_EVERY_WRITES == 0) pruneDiskNow()
        }
    }

    internal fun pruneDiskNow() {
        val directory = diskCacheDirectory ?: return
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total > maxDiskBytes) {
                val length = file.length()
                if (file.delete()) total -= length
            }
        }
    }

    companion object {
        const val TILE_SIZE = 256
        const val MAX_TILE_BYTES = 4_000_000
        const val DEFAULT_MEMORY_ENTRIES = 96
        const val REFRESH_AFTER_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val MAX_DISK_BYTES = 100L * 1024L * 1024L
        const val PRUNE_EVERY_WRITES = 1
    }
}
