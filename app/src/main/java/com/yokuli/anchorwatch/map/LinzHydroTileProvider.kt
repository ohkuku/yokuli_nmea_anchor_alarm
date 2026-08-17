package com.yokuli.anchorwatch.map

import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.map.tiles.CachedHttpTileProvider
import com.yokuli.anchorwatch.map.tiles.TileDiagnosticsSink
import com.yokuli.anchorwatch.map.tiles.XyzTileCoordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URL

object MapOverlayZ {
    const val OFFLINE_CHART = .1f
    const val LINZ_CHART = .2f
    const val SONAR = .6f
    const val NAUTICAL_SEAMARKS = .8f
    const val TRAIL = 1f
    const val ALARM_GEOMETRY = 1.5f
    const val ANCHOR = 2f
    const val BOAT = 3f
}

object LinzHydroConfiguration {
    const val ATTRIBUTION = "LINZ · CC BY 4.0"

    fun isUsable(template: String): Boolean = template.startsWith("https://") &&
        listOf("{z}", "{x}", "{y}").all(template::contains)

    fun isOverlayVisible(configured: Boolean, enabled: Boolean) = configured && enabled
    fun transparency(opacity: Double) = (1.0 - opacity.coerceIn(.30, 1.0)).toFloat()

    fun tileUrl(template: String, x: Int, y: Int, zoom: Int): String = template
        .replace("{s}", listOf("a", "b", "c", "d")[(x + y + zoom).mod(4)])
        .replace("{z}", zoom.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
}

data class LinzTileDiagnostics(
    val requests: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val memoryHits: Long = 0,
    val diskHits: Long = 0,
    val staleDiskHits: Long = 0,
    val lastHttpCode: Int? = null,
    val message: String = "Waiting for a chart tile request",
)

object LinzHydroDiagnostics : TileDiagnosticsSink {
    private val mutableState = MutableStateFlow(LinzTileDiagnostics())
    val state = mutableState.asStateFlow()

    @Synchronized override fun requested() = update { copy(requests = requests + 1, message = "Requesting LINZ chart tiles") }
    @Synchronized override fun memoryHit() = update { copy(memoryHits = memoryHits + 1, message = "LINZ memory cache hit") }
    @Synchronized override fun diskHit(stale: Boolean) = update {
        copy(
            diskHits = diskHits + 1,
            staleDiskHits = staleDiskHits + if (stale) 1 else 0,
            message = if (stale) "LINZ is using a cached tile while offline" else "LINZ disk cache hit",
        )
    }
    @Synchronized override fun succeeded(httpCode: Int) = update {
        copy(successes = successes + 1, lastHttpCode = httpCode, message = "LINZ chart tile loaded")
    }
    @Synchronized override fun failed(httpCode: Int?, message: String) = update {
        copy(failures = failures + 1, lastHttpCode = httpCode, message = message)
    }

    private fun update(block: LinzTileDiagnostics.() -> LinzTileDiagnostics) {
        mutableState.value = mutableState.value.block()
    }
}

class LinzHydroTileProvider(
    private val template: String,
    diskCacheDirectory: File? = null,
    maxDiskBytes: Long = MAX_DISK_BYTES,
) : TileProvider {
    private val delegate = CachedHttpTileProvider(
        urlFor = ::getTileUrl,
        diskCacheDirectory = diskCacheDirectory,
        userAgent = "Anchor-Watch/${BuildConfig.VERSION_NAME} Android",
        diagnostics = LinzHydroDiagnostics,
        maxDiskBytes = maxDiskBytes,
    )

    fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (!LinzHydroConfiguration.isUsable(template) || !XyzTileCoordinates.valid(x, y, zoom)) return null
        return runCatching { URL(LinzHydroConfiguration.tileUrl(template, x, y, zoom)) }.getOrNull()
    }

    override fun getTile(x: Int, y: Int, zoom: Int): Tile = delegate.getTile(x, y, zoom)

    companion object {
        const val REFRESH_AFTER_MILLIS = CachedHttpTileProvider.REFRESH_AFTER_MILLIS
        const val MAX_DISK_BYTES = CachedHttpTileProvider.MAX_DISK_BYTES
    }
}
