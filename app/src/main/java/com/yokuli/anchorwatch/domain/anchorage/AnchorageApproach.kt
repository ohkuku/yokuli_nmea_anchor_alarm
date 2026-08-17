package com.yokuli.anchorwatch.domain.anchorage

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class SavedAnchorageReference(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val preferredAlarmRadiusMeters: Double?,
    val typicalWaterDepthMeters: Double?,
    val typicalRodeLengthMeters: Double?,
    val seabedType: String,
    val rating: Int?,
    val notes: String,
    val sourceSessionId: Long?,
    val updatedAt: Long,
    val lastVisitedAt: Long?,
)

data class AnchorageCluster(
    val id: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val savedAnchorageIds: List<Long>,
    val displayName: String,
    val savedPointCount: Int,
    val minDepthMeters: Double?,
    val maxDepthMeters: Double?,
    val minRodeMeters: Double?,
    val maxRodeMeters: Double?,
    val minAlarmRadiusMeters: Double?,
    val maxAlarmRadiusMeters: Double?,
    val lastVisitedAt: Long?,
    val radiusEstimated: Boolean,
)

object AnchorageClusterer {
    const val LINK_DISTANCE_METERS = 100.0
    const val MAX_DIAMETER_METERS = 180.0
    const val FALLBACK_RADIUS_METERS = 40.0

    fun cluster(values: List<SavedAnchorageReference>): List<AnchorageCluster> {
        val groups = mutableListOf<MutableList<SavedAnchorageReference>>()
        values.sortedWith(compareBy<SavedAnchorageReference> { it.id }.thenBy { it.updatedAt }).forEach { point ->
            val target = groups.mapNotNull { group ->
                val distances = group.map { distance(point, it) }
                val linked = distances.any { it <= LINK_DISTANCE_METERS }
                val withinDiameter = distances.all { it <= MAX_DIAMETER_METERS }
                group.takeIf { linked && withinDiameter }?.let { it to distances.minOrNull()!! }
            }.minByOrNull { it.second }?.first
            if (target == null) groups += mutableListOf(point) else target += point
        }
        return groups.map(::toCluster).sortedBy { it.id }
    }

    private fun toCluster(points: List<SavedAnchorageReference>): AnchorageCluster {
        val (latitude, longitude) = centroid(points)
        val savedRadii = points.mapNotNull { it.preferredAlarmRadiusMeters?.takeIf { value -> value.isFinite() && value > 0.0 } }
        val radius = points.maxOf { point ->
            AnchorGeometry.distanceMeters(latitude, longitude, point.latitude, point.longitude) +
                (point.preferredAlarmRadiusMeters?.takeIf { it.isFinite() && it > 0.0 } ?: FALLBACK_RADIUS_METERS)
        }
        val recent = points.filter { it.name.isNotBlank() }
            .maxByOrNull { it.lastVisitedAt ?: it.updatedAt }
        val ids = points.map { it.id }.sorted()
        return AnchorageCluster(
            id = "saved:${ids.joinToString("-")}",
            centerLatitude = latitude,
            centerLongitude = longitude,
            radiusMeters = radius.coerceAtLeast(FALLBACK_RADIUS_METERS),
            savedAnchorageIds = ids,
            displayName = recent?.name?.trim().orEmpty().ifBlank { "Saved anchorage" },
            savedPointCount = points.size,
            minDepthMeters = points.mapNotNull { it.typicalWaterDepthMeters.validMeasurement() }.minOrNull(),
            maxDepthMeters = points.mapNotNull { it.typicalWaterDepthMeters.validMeasurement() }.maxOrNull(),
            minRodeMeters = points.mapNotNull { it.typicalRodeLengthMeters.validMeasurement() }.minOrNull(),
            maxRodeMeters = points.mapNotNull { it.typicalRodeLengthMeters.validMeasurement() }.maxOrNull(),
            minAlarmRadiusMeters = savedRadii.minOrNull(),
            maxAlarmRadiusMeters = savedRadii.maxOrNull(),
            lastVisitedAt = points.mapNotNull { it.lastVisitedAt }.maxOrNull(),
            radiusEstimated = savedRadii.isEmpty(),
        )
    }

    private fun centroid(points: List<SavedAnchorageReference>): Pair<Double, Double> {
        require(points.isNotEmpty())
        var x = 0.0
        var y = 0.0
        var z = 0.0
        points.forEach { point ->
            val latitude = Math.toRadians(point.latitude)
            val longitude = Math.toRadians(point.longitude)
            x += cos(latitude) * cos(longitude)
            y += cos(latitude) * sin(longitude)
            z += sin(latitude)
        }
        val horizontal = sqrt(x * x + y * y)
        return Math.toDegrees(atan2(z, horizontal)) to
            ((Math.toDegrees(atan2(y, x)) + 540.0) % 360.0 - 180.0)
    }

    private fun distance(first: SavedAnchorageReference, second: SavedAnchorageReference) =
        AnchorGeometry.distanceMeters(first.latitude, first.longitude, second.latitude, second.longitude)

    private fun Double?.validMeasurement() = this?.takeIf { it.isFinite() && it >= 0.0 }
}

data class AnchorageClusterDistance(
    val cluster: AnchorageCluster,
    val distanceToCentreMeters: Double,
    val distanceToAreaMeters: Double,
)

object AnchorageNearbyPolicy {
    const val TRIGGER_DISTANCE_METERS = 1852.0
    const val REARM_DISTANCE_METERS = 2315.0 // 1.25 NM

    fun distances(
        latitude: Double,
        longitude: Double,
        clusters: List<AnchorageCluster>,
    ): List<AnchorageClusterDistance> = clusters.map { cluster ->
        val centre = AnchorGeometry.distanceMeters(
            latitude,
            longitude,
            cluster.centerLatitude,
            cluster.centerLongitude,
        )
        AnchorageClusterDistance(cluster, centre, (centre - cluster.radiusMeters).coerceAtLeast(0.0))
    }.sortedBy { it.distanceToAreaMeters }
}

/** Keeps automatic Nearby prompts to one per cluster per approach episode. */
class AnchorageNearbyEpisodeTracker {
    private val prompted = mutableSetOf<String>()
    private val dismissed = mutableSetOf<String>()
    private var visible = emptySet<String>()

    fun update(
        distances: List<AnchorageClusterDistance>,
        automaticPromptEnabled: Boolean,
    ): List<String> {
        val known = distances.mapTo(mutableSetOf()) { it.cluster.id }
        prompted.retainAll(known)
        dismissed.retainAll(known)
        visible = visible.intersect(known)
        distances.filter { it.distanceToAreaMeters > AnchorageNearbyPolicy.REARM_DISTANCE_METERS }.forEach {
            prompted -= it.cluster.id
            dismissed -= it.cluster.id
            visible -= it.cluster.id
        }
        if (!automaticPromptEnabled) {
            visible = emptySet()
            return emptyList()
        }
        val inside = distances.filter { it.distanceToAreaMeters <= AnchorageNearbyPolicy.TRIGGER_DISTANCE_METERS }
        val newEpisode = inside.any { it.cluster.id !in prompted && it.cluster.id !in dismissed }
        if (newEpisode) {
            val ids = inside.filterNot { it.cluster.id in dismissed }.mapTo(linkedSetOf()) { it.cluster.id }
            prompted += ids
            visible = ids
        } else {
            visible = visible.intersect(inside.mapTo(mutableSetOf()) { it.cluster.id })
        }
        return inside.map { it.cluster.id }.filter { it in visible }
    }

    fun dismiss(clusterIds: Collection<String>) {
        dismissed += clusterIds
        prompted += clusterIds
        visible = emptySet()
    }
}

enum class ApproachPhase { IDLE, NEARBY, APPROACHING, NEAR, INSIDE_AREA }
enum class ApproachDirectionReference { HDT, COG, PHONE, NORTH_UP }

data class ApproachDirection(
    val reference: ApproachDirectionReference,
    val referenceHeadingDegrees: Double?,
    val relativeBearingDegrees: Double,
)

object ApproachDirectionPolicy {
    const val FRESH_MILLIS = 5_000L
    const val MIN_COG_SPEED_KNOTS = 1.0

    fun resolve(
        nowElapsed: Long,
        targetBearingDegrees: Double,
        nmeaTrueHeadingDegrees: Double?,
        nmeaHeadingReceivedElapsed: Long?,
        cogTrueDegrees: Double?,
        sogKnots: Double?,
        cogReceivedElapsed: Long?,
        phoneTrueHeadingDegrees: Double?,
        phoneHeadingTrusted: Boolean,
    ): ApproachDirection {
        val nmeaFresh = nmeaHeadingReceivedElapsed != null && nowElapsed - nmeaHeadingReceivedElapsed in 0..FRESH_MILLIS
        val cogFresh = cogReceivedElapsed != null && nowElapsed - cogReceivedElapsed in 0..FRESH_MILLIS
        val (reference, heading) = when {
            nmeaTrueHeadingDegrees != null && nmeaFresh -> ApproachDirectionReference.HDT to nmeaTrueHeadingDegrees
            cogTrueDegrees != null && cogFresh && (sogKnots ?: 0.0) >= MIN_COG_SPEED_KNOTS -> ApproachDirectionReference.COG to cogTrueDegrees
            phoneTrueHeadingDegrees != null && phoneHeadingTrusted -> ApproachDirectionReference.PHONE to phoneTrueHeadingDegrees
            else -> ApproachDirectionReference.NORTH_UP to null
        }
        return ApproachDirection(reference, heading, signedAngle(targetBearingDegrees - (heading ?: 0.0)))
    }

    fun signedAngle(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
}

data class AnchorageApproachState(
    val nearbyClusters: List<AnchorageClusterDistance> = emptyList(),
    val selectedClusterId: String? = null,
    val target: AnchorageCluster? = null,
    val distanceToCentreMeters: Double? = null,
    val distanceToAreaMeters: Double? = null,
    val targetBearingTrueDegrees: Double? = null,
    val relativeBearingDegrees: Double? = null,
    val directionReference: ApproachDirectionReference = ApproachDirectionReference.NORTH_UP,
    val phase: ApproachPhase = ApproachPhase.IDLE,
    val positionAvailable: Boolean = false,
)

object AnchorageApproachEngine {
    const val NEAR_DISTANCE_METERS = 463.0

    fun evaluate(
        clusters: List<AnchorageCluster>,
        selectedClusterId: String?,
        positionLatitude: Double?,
        positionLongitude: Double?,
        direction: ((Double) -> ApproachDirection)? = null,
    ): AnchorageApproachState {
        val positionAvailable = positionLatitude != null && positionLongitude != null
        val distances = if (positionAvailable) AnchorageNearbyPolicy.distances(positionLatitude!!, positionLongitude!!, clusters) else emptyList()
        val nearby = distances.filter { it.distanceToAreaMeters <= AnchorageNearbyPolicy.TRIGGER_DISTANCE_METERS }
        val target = clusters.firstOrNull { it.id == selectedClusterId }
        if (target == null) return AnchorageApproachState(
            nearbyClusters = nearby,
            phase = if (nearby.isEmpty()) ApproachPhase.IDLE else ApproachPhase.NEARBY,
            positionAvailable = positionAvailable,
        )
        if (!positionAvailable) return AnchorageApproachState(
            nearbyClusters = nearby,
            selectedClusterId = target.id,
            target = target,
            phase = ApproachPhase.APPROACHING,
            positionAvailable = false,
        )
        val targetDistance = distances.first { it.cluster.id == target.id }
        val bearing = AnchorGeometry.bearingDegrees(positionLatitude!!, positionLongitude!!, target.centerLatitude, target.centerLongitude)
        val resolvedDirection = direction?.invoke(bearing)
        val phase = when {
            targetDistance.distanceToAreaMeters == 0.0 -> ApproachPhase.INSIDE_AREA
            targetDistance.distanceToAreaMeters <= NEAR_DISTANCE_METERS -> ApproachPhase.NEAR
            else -> ApproachPhase.APPROACHING
        }
        return AnchorageApproachState(
            nearbyClusters = nearby,
            selectedClusterId = target.id,
            target = target,
            distanceToCentreMeters = targetDistance.distanceToCentreMeters,
            distanceToAreaMeters = targetDistance.distanceToAreaMeters,
            targetBearingTrueDegrees = bearing,
            relativeBearingDegrees = resolvedDirection?.relativeBearingDegrees ?: ApproachDirectionPolicy.signedAngle(bearing),
            directionReference = resolvedDirection?.reference ?: ApproachDirectionReference.NORTH_UP,
            phase = phase,
            positionAvailable = true,
        )
    }
}

object ApproachDistanceFormatter {
    private const val NAUTICAL_MILE_METERS = 1852.0
    private const val METRES_THRESHOLD = .2 * NAUTICAL_MILE_METERS

    fun format(meters: Double): String = if (meters >= METRES_THRESHOLD) {
        "%.1f NM".format(java.util.Locale.US, meters / NAUTICAL_MILE_METERS)
    } else {
        "${meters.coerceAtLeast(0.0).roundToInt()} m"
    }
}
