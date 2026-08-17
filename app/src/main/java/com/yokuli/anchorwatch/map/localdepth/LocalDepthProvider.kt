package com.yokuli.anchorwatch.map.localdepth

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid: Boolean get() = latitude in -90.0..90.0 && longitude in -180.0..180.0
}

interface LocalDepthProvider {
    val id: String
    val displayName: String
    val attribution: String
    val licenseUrl: String
    fun supports(point: GeoPoint): Boolean
}

/**
 * Conservative regional gate for the LINZ raster chart sets used by the app.
 * Rectangles are intentionally regional rather than one South-Pacific-wide box.
 */
object LinzNewZealandDepthProvider : LocalDepthProvider {
    override val id = "linz-new-zealand"
    override val displayName = "LINZ · New Zealand"
    override val attribution = "LINZ · CC BY 4.0"
    override val licenseUrl = "https://www.linz.govt.nz/data/licensing-and-using-data/attributing-linz-data"

    private data class Bounds(
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double,
    ) {
        fun contains(point: GeoPoint) = point.latitude in south..north && point.longitude in west..east
    }

    private val regions = listOf(
        Bounds(-48.8, -33.0, 164.0, 180.0), // North, South, Stewart and nearby offshore islands
        Bounds(-45.5, -41.0, -179.5, -173.0), // Chatham Islands across the antimeridian
        Bounds(-32.8, -25.0, -179.5, -174.0), // Kermadec Islands chart set
    )

    override fun supports(point: GeoPoint): Boolean = point.valid && regions.any { it.contains(point) }
}

object LocalDepthProviderRegistry {
    val providers: List<LocalDepthProvider> = listOf(LinzNewZealandDepthProvider)
    fun providerFor(point: GeoPoint): LocalDepthProvider? = providers.firstOrNull { it.supports(point) }
}

sealed interface LocalDepthAvailability {
    data class Available(val provider: LocalDepthProvider) : LocalDepthAvailability
    data object UnsupportedArea : LocalDepthAvailability
    data object PositionUnknown : LocalDepthAvailability
    data class ProviderNotConfigured(val provider: LocalDepthProvider) : LocalDepthAvailability
}

data class MapChartUiState(
    val inspectionPoint: GeoPoint?,
    val availability: LocalDepthAvailability,
    val localDepthPreferenceEnabled: Boolean,
    val localDepthVisible: Boolean,
    val localDepthOpacity: Double,
)

/** Visualization-only state. Nothing here is allowed to arm, stop or alarm an anchor watch. */
object MapChartPolicy {
    fun resolve(
        mapLockedToBoat: Boolean,
        acceptedBoatPosition: GeoPoint?,
        cameraTarget: GeoPoint?,
        providerConfigured: Boolean,
        localDepthPreferenceEnabled: Boolean,
        localDepthOpacity: Double,
    ): MapChartUiState {
        val point = (if (mapLockedToBoat) acceptedBoatPosition else cameraTarget)?.takeIf { it.valid }
        val provider = point?.let(LocalDepthProviderRegistry::providerFor)
        val availability = when {
            point == null -> LocalDepthAvailability.PositionUnknown
            provider == null -> LocalDepthAvailability.UnsupportedArea
            !providerConfigured -> LocalDepthAvailability.ProviderNotConfigured(provider)
            else -> LocalDepthAvailability.Available(provider)
        }
        return MapChartUiState(
            inspectionPoint = point,
            availability = availability,
            localDepthPreferenceEnabled = localDepthPreferenceEnabled,
            localDepthVisible = localDepthPreferenceEnabled && availability is LocalDepthAvailability.Available,
            localDepthOpacity = localDepthOpacity.coerceIn(.30, 1.0),
        )
    }
}
