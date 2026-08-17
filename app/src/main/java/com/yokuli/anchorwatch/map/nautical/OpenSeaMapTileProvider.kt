package com.yokuli.anchorwatch.map.nautical

import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.yokuli.anchorwatch.map.tiles.CachedHttpTileProvider
import com.yokuli.anchorwatch.map.tiles.TileDiagnosticsSink
import com.yokuli.anchorwatch.map.tiles.XyzTileCoordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URL

object OpenSeaMapConfiguration {
    const val TILE_TEMPLATE = "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png"
    const val ATTRIBUTION = "OpenSeaMap · OpenStreetMap"
    const val LICENSE_URL = "https://www.openseamap.org/index.php?id=faq&L=1"

    fun tileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (!XyzTileCoordinates.valid(x, y, zoom)) return null
        return URL(
            TILE_TEMPLATE.replace("{z}", zoom.toString())
                .replace("{x}", x.toString())
                .replace("{y}", y.toString()),
        )
    }
}

data class TileSourceDiagnostics(
    val requests: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val memoryHits: Long = 0,
    val diskHits: Long = 0,
    val staleDiskHits: Long = 0,
    val lastHttpCode: Int? = null,
    val message: String = "Waiting for a tile request",
)

open class FlowTileDiagnostics(private val sourceName: String) : TileDiagnosticsSink {
    private val mutableState = MutableStateFlow(TileSourceDiagnostics())
    val state = mutableState.asStateFlow()

    @Synchronized override fun requested() = update { copy(requests = requests + 1, message = "Requesting $sourceName tiles") }
    @Synchronized override fun memoryHit() = update { copy(memoryHits = memoryHits + 1, message = "$sourceName memory cache hit") }
    @Synchronized override fun diskHit(stale: Boolean) = update {
        copy(
            diskHits = diskHits + 1,
            staleDiskHits = staleDiskHits + if (stale) 1 else 0,
            message = if (stale) "$sourceName is using a cached tile while offline" else "$sourceName disk cache hit",
        )
    }
    @Synchronized override fun succeeded(httpCode: Int) = update {
        copy(successes = successes + 1, lastHttpCode = httpCode, message = "$sourceName tile loaded")
    }
    @Synchronized override fun failed(httpCode: Int?, message: String) = update {
        copy(failures = failures + 1, lastHttpCode = httpCode, message = "$sourceName: $message")
    }

    private fun update(block: TileSourceDiagnostics.() -> TileSourceDiagnostics) {
        mutableState.value = mutableState.value.block()
    }
}

object OpenSeaMapDiagnostics : FlowTileDiagnostics("OpenSeaMap")

class OpenSeaMapTileProvider(cacheDirectory: File, versionName: String) : TileProvider {
    private val delegate = CachedHttpTileProvider(
        urlFor = OpenSeaMapConfiguration::tileUrl,
        diskCacheDirectory = cacheDirectory,
        userAgent = "Anchor-Watch/$versionName Android",
        diagnostics = OpenSeaMapDiagnostics,
    )

    fun getTileUrl(x: Int, y: Int, zoom: Int) = OpenSeaMapConfiguration.tileUrl(x, y, zoom)
    override fun getTile(x: Int, y: Int, zoom: Int): Tile = delegate.getTile(x, y, zoom)
}
