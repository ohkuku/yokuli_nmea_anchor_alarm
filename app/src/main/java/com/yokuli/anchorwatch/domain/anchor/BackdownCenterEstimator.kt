package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.BackdownAnchorEstimate
import com.yokuli.anchorwatch.domain.model.Confidence
import com.yokuli.anchorwatch.domain.model.NavigationFix

/**
 * Resolves the drop point from the stable cluster recorded immediately after
 * the user presses Start, then requires a sustained, measurable back-down.
 * A straight back-down track cannot identify a circle centre by circle fit;
 * the time-ordered drop cluster is the observable that makes it identifiable.
 */
class BackdownCenterEstimator {
    data class Sample(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val hdop: Double? = null,
    )

    fun estimate(fixes: List<NavigationFix>): BackdownAnchorEstimate? = estimateSamples(
        fixes.filter { it.valid }.map {
            Sample(it.latitude, it.longitude, it.receivedElapsedRealtime, it.hdop)
        }
    )

    fun estimateSamples(samples: List<Sample>): BackdownAnchorEstimate? {
        val valid = validSamples(samples)
        if (valid.size < 12) return null
        val duration = valid.last().timestamp - valid.first().timestamp
        if (duration < 12_000L) return null

        val startWindow = valid.filter { it.timestamp - valid.first().timestamp <= 8_000L }.take(5)
        if (startWindow.size < 5) return null
        val startLat = median(startWindow.map { it.latitude })
        val startLon = median(startWindow.map { it.longitude })
        val startDistances = startWindow.map { AnchorGeometry.distanceMeters(startLat, startLon, it.latitude, it.longitude) }.sorted()
        val startSpread = startDistances[(startDistances.lastIndex * 4 / 5).coerceAtLeast(0)]
        if (startSpread > 5.0) return null

        val tail = valid.takeLast(3)
        val tailLat = median(tail.map { it.latitude })
        val tailLon = median(tail.map { it.longitude })
        val separation = AnchorGeometry.distanceMeters(startLat, startLon, tailLat, tailLon)
        if (separation < 8.0) return null

        val distances = valid.map { AnchorGeometry.distanceMeters(startLat, startLon, it.latitude, it.longitude) }
        val recentProgress = distances.takeLast(4).average() - distances.take(4).average()
        if (recentProgress < 6.0) return null

        val uncertaintyRadius = uncertainty(startWindow, startLat, startLon)
        val confidence = if (valid.size >= 20 && duration >= 20_000L && separation >= 15.0 && startSpread <= 3.0 && uncertaintyRadius <= 20.0) {
            Confidence.HIGH
        } else {
            Confidence.MEDIUM
        }
        return BackdownAnchorEstimate(startLat, startLon, separation, uncertaintyRadius, confidence, valid.size)
    }

    /**
     * Produces a visible, deliberately conservative estimate from the earliest
     * fixes. It is not used as the final anchor until [estimateSamples] reaches
     * high confidence, but lets the map show the estimate tightening meanwhile.
     */
    fun provisionalEstimate(samples: List<Sample>): BackdownAnchorEstimate? {
        val valid = validSamples(samples)
        if (valid.isEmpty()) return null
        val first = valid.first().timestamp
        val startWindow = valid.filter { it.timestamp - first <= 8_000L }.take(8)
        val startLat = median(startWindow.map { it.latitude })
        val startLon = median(startWindow.map { it.longitude })
        val tail = valid.takeLast(minOf(3, valid.size))
        val tailLat = median(tail.map { it.latitude })
        val tailLon = median(tail.map { it.longitude })
        val resolved = estimateSamples(valid)
        return BackdownAnchorEstimate(
            latitude = resolved?.latitude ?: startLat,
            longitude = resolved?.longitude ?: startLon,
            distanceMeters = AnchorGeometry.distanceMeters(startLat, startLon, tailLat, tailLon),
            uncertaintyRadiusMeters = resolved?.uncertaintyRadiusMeters ?: uncertainty(startWindow, startLat, startLon),
            confidence = resolved?.confidence ?: if (startWindow.size >= 5) Confidence.MEDIUM else Confidence.LOW,
            sampleCount = valid.size,
        )
    }

    private fun validSamples(samples: List<Sample>) = samples
        .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && (it.hdop == null || it.hdop <= 5.0) }
        .sortedBy { it.timestamp }

    private fun uncertainty(samples: List<Sample>, latitude: Double, longitude: Double): Double {
        val spread = samples.map { AnchorGeometry.distanceMeters(latitude, longitude, it.latitude, it.longitude) }.sorted()
        val percentile = spread.getOrElse((spread.lastIndex * 4 / 5).coerceAtLeast(0)) { 0.0 }
        val hdopMetres = (samples.mapNotNull { it.hdop }.let { if (it.isEmpty()) 3.0 else median(it) } * 3.0).coerceIn(3.0, 15.0)
        val samplePenalty = 18.0 / kotlin.math.sqrt(samples.size.coerceAtLeast(1).toDouble())
        return (percentile * 2.0 + hdopMetres + samplePenalty).coerceIn(5.0, 50.0)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}
