package com.yokuli.anchorwatch.map

import com.google.android.gms.maps.model.UrlTileProvider
import java.net.URL

object MapOverlayZ {
    const val LINZ_CHART=.2f
    const val TRAIL=1f
    const val ALARM_GEOMETRY=1.5f
    const val ANCHOR=2f
    const val BOAT=3f
}

object LinzHydroConfiguration {
    const val ATTRIBUTION = "Contains data sourced from the LINZ Data Service licensed for reuse under CC BY 4.0"

    fun isUsable(template: String): Boolean = template.startsWith("https://") &&
        listOf("{z}", "{x}", "{y}").all(template::contains)

    fun isOverlayVisible(configured:Boolean,enabled:Boolean)=configured&&enabled
    fun transparency(opacity:Double)=(1.0-opacity.coerceIn(.30,1.0)).toFloat()

    fun tileUrl(template: String, x: Int, y: Int, zoom: Int): String = template
        .replace("{s}", listOf("a", "b", "c", "d")[(x + y + zoom).mod(4)])
        .replace("{z}", zoom.toString())
        .replace("{x}", x.toString())
        .replace("{y}", y.toString())
}

class LinzHydroTileProvider(private val template: String) : UrlTileProvider(256, 256) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (!LinzHydroConfiguration.isUsable(template) || zoom !in 0..24 || x < 0 || y < 0) return null
        val extent=1L shl zoom
        if(x.toLong()>=extent||y.toLong()>=extent)return null
        return runCatching { URL(LinzHydroConfiguration.tileUrl(template, x, y, zoom)) }.getOrNull()
    }
}
