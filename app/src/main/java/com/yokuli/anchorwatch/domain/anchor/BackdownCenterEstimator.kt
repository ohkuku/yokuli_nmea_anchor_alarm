package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.BackdownAnchorEstimate
import com.yokuli.anchorwatch.domain.model.Confidence
import com.yokuli.anchorwatch.domain.model.NavigationFix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Learns a back-down centre in two stages. GPS fixes first intersect the
 * possible-anchor discs defined by horizontal rode. Only a broad, multi-sector
 * swing accepted by a robust circle fit can resolve the final centre.
 * Heading/wind bias the provisional region but never resolve it by themselves.
 */
class BackdownCenterEstimator {
    data class Sample(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val hdop: Double? = null,
        val headingTrueDegrees: Double? = null,
        val cogTrueDegrees: Double? = null,
        val sogKnots: Double? = null,
        val windDirectionTrueDegrees: Double? = null,
        val windSpeedKnots: Double? = null,
        val apparentWindAngleDegrees: Double? = null,
        val trueWindAngleDegrees: Double? = null,
        val trueWindSpeedKnots: Double? = null,
        val apparentWindSpeedKnots: Double? = null,
        val headingSampleSequence: Long? = null,
        val windSampleSequence: Long? = null,
    )

    private data class LocalPoint(val x: Double, val y: Double, val sample: Sample)
    private data class Region(val latitude: Double, val longitude: Double, val radius: Double)

    fun estimate(fixes: List<NavigationFix>, maximumHorizontalRodeMeters: Double? = null) = estimateSamples(
        fixes.filter { it.valid }.map {
            Sample(it.latitude, it.longitude, it.receivedElapsedRealtime, it.hdop, it.headingTrueDegrees,
                it.cogTrueDegrees, it.sogKnots, it.windDirectionTrueDegrees, it.windSpeedKnots, it.apparentWindAngleDegrees,
                it.trueWindAngleDegrees, it.trueWindSpeedKnots, it.apparentWindSpeedKnots,it.headingSampleSequence,it.windSampleSequence)
        },
        maximumHorizontalRodeMeters,
    )

    fun estimateSamples(samples: List<Sample>, maximumHorizontalRodeMeters: Double? = null): BackdownAnchorEstimate? {
        val valid = validSamples(samples)
        if (valid.size < 20 || valid.last().timestamp - valid.first().timestamp < 20_000L) return null
        val start = stableStart(valid) ?: return null
        val maximumDistance = valid.maxOf { AnchorGeometry.distanceMeters(start.first, start.second, it.latitude, it.longitude) }
        if (maximumDistance < 10.0) return null
        val maximumRode = maximumHorizontalRodeMeters?.takeIf { it >= 10.0 } ?: max(25.0, maximumDistance * 1.25)
        val directionEvidence = directionalEvidence(valid)
        val region = feasibleRegion(valid, start.first, start.second, maximumRode, directionEvidence)
        val fit = AnchorCenterEstimator(Random(0)).estimate(valid.map { AnchorCenterEstimator.Point(it.latitude, it.longitude) }, null)
        val fitUsable = fit != null && fit.radiusMeters <= maximumRode + gpsMargin(valid) && fit.angularCoverageDegrees >= 90.0
        val centreLat = if (fitUsable) fit!!.latitude else region.latitude
        val centreLon = if (fitUsable) fit!!.longitude else region.longitude
        val bearings = valid.map { AnchorGeometry.bearingDegrees(centreLat, centreLon, it.latitude, it.longitude) }
        val coverage = angularCoverage(bearings)
        val sectors = bearings.map { (it / 30.0).toInt().coerceIn(0, 11) }.distinct().size
        val reversals = meaningfulSwingReversals(valid, centreLat, centreLon, maximumRode)
        val fitUncertainty = fit?.takeIf { fitUsable }?.let {
            val missingArc = maximumRode * (1.0 - min(it.angularCoverageDegrees, 240.0) / 240.0) * .50
            max(gpsMargin(valid), it.rmsErrorMeters * 2.0 + missingArc)
        }
        val duration = valid.last().timestamp - valid.first().timestamp
        val directionMatch = WindAnchorEvidence.centreMatch(directionEvidence, centreLat, centreLon)
        val directionConsistent = directionEvidence.observations.isEmpty() || directionMatch.consistent
        val minimumDuration = when {
            directionEvidence.hasPhysicalEvidence && directionEvidence.hasRepeatedIndependentWindEvidence && directionMatch.consistent -> 300_000L
            (directionEvidence.hasPhysicalEvidence || directionEvidence.hasRepeatedWindEvidence) && directionMatch.consistent -> 480_000L
            else -> 900_000L
        }
        val minimumSamples = when (minimumDuration) { 300_000L -> 300; 480_000L -> 480; else -> 600 }
        val evidence = minOf(
            valid.size / minimumSamples.toDouble(),
            duration / minimumDuration.toDouble(),
            coverage / 220.0,
            sectors / 8.0,
            (reversals + 1) / 3.0,
            1.0,
        ).coerceIn(0.0, 1.0)
        val conservativeFloor = max(gpsMargin(valid), maximumRode * (1.0 - .90 * evidence))
        val uncertainty = max(min(region.radius, fitUncertainty ?: region.radius), conservativeFloor).coerceAtMost(maximumRode)
        val temporalConsensus = fitUsable && temporalFitConsensus(valid, maximumRode)
        val confidence = if (valid.size >= minimumSamples && duration >= minimumDuration && fit?.confidence == Confidence.HIGH &&
            coverage >= 200.0 && sectors >= 8 && reversals >= 2 && temporalConsensus &&
            directionConsistent &&
            uncertainty <= max(10.0, maximumRode * .22)
        ) Confidence.HIGH else Confidence.MEDIUM
        return BackdownAnchorEstimate(centreLat, centreLon, fit?.takeIf { fitUsable }?.radiusMeters ?: maximumDistance,
            uncertainty, confidence, valid.size, coverage, sectors)
    }

    fun provisionalEstimate(samples: List<Sample>, maximumHorizontalRodeMeters: Double? = null): BackdownAnchorEstimate? {
        val valid = validSamples(samples)
        if (valid.isEmpty()) return null
        val startWindow = valid.take(8)
        val startLat = median(startWindow.map { it.latitude })
        val startLon = median(startWindow.map { it.longitude })
        val travelled = valid.maxOf { AnchorGeometry.distanceMeters(startLat, startLon, it.latitude, it.longitude) }
        val maximumRode = maximumHorizontalRodeMeters?.takeIf { it >= 10.0 } ?: max(25.0, travelled * 1.25)
        val region = feasibleRegion(valid, startLat, startLon, maximumRode, directionalEvidence(valid))
        val resolved = estimateSamples(valid, maximumRode)
        val duration = valid.last().timestamp - valid.first().timestamp
        val provisionalConfidence = when {
            resolved?.confidence == Confidence.HIGH -> Confidence.HIGH
            resolved != null && duration >= 120_000L && resolved.angularCoverageDegrees >= 120.0 &&
                resolved.angularSectorCount >= 5 && meaningfulSwingReversals(valid, resolved.latitude, resolved.longitude, maximumRode) >= 1 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return BackdownAnchorEstimate(
            resolved?.latitude ?: region.latitude,
            resolved?.longitude ?: region.longitude,
            resolved?.distanceMeters ?: travelled,
            resolved?.uncertaintyRadiusMeters ?: region.radius,
            provisionalConfidence,
            valid.size,
            resolved?.angularCoverageDegrees ?: 0.0,
            resolved?.angularSectorCount ?: 0,
        )
    }

    private fun stableStart(samples: List<Sample>): Pair<Double, Double>? {
        val first = samples.first().timestamp
        val window = samples.filter { it.timestamp - first <= 4_000L }.take(5)
        if (window.size < 2) return null
        val latitude = median(window.map { it.latitude })
        val longitude = median(window.map { it.longitude })
        return latitude to longitude
    }

    /** Approximate the intersection of all maximum-rode discs on a local grid. */
    private fun feasibleRegion(samples: List<Sample>, originLat: Double, originLon: Double, maximumRode: Double, directionEvidence: WindAnchorEvidence.Summary): Region {
        val cosLat = cos(Math.toRadians(originLat)).coerceAtLeast(.01)
        val selected = evenlySample(samples, 160).map {
            LocalPoint((it.longitude - originLon) * 111_320.0 * cosLat, (it.latitude - originLat) * 110_540.0, it)
        }
        val margin = gpsMargin(samples)
        val limit = maximumRode + margin
        val minX = -maximumRode; val maxX = maximumRode
        val minY = -maximumRode; val maxY = maximumRode
        val candidates = mutableListOf<Triple<Double, Double, Double>>()
        val divisions = 42
        for (xi in 0..divisions) for (yi in 0..divisions) {
            val x = minX + (maxX - minX) * xi / divisions
            val y = minY + (maxY - minY) * yi / divisions
            if (hypot(x, y) > maximumRode + margin || selected.count { hypot(x - it.x, y - it.y) > limit } > max(2,selected.size/20)) continue
            val latitude=originLat+y/110_540.0;val longitude=originLon+x/(111_320.0*cosLat)
            candidates += Triple(x, y, exp(WindAnchorEvidence.candidateScore(directionEvidence,latitude,longitude) * 1.60))
        }
        if (candidates.isEmpty()) return Region(originLat, originLon, maximumRode)
        val totalWeight = candidates.sumOf { it.third }.coerceAtLeast(.001)
        val centreX = candidates.sumOf { it.first * it.third } / totalWeight
        val centreY = candidates.sumOf { it.second * it.third } / totalWeight
        val centreLatitude=originLat+centreY/110_540.0;val centreLongitude=originLon+centreX/(111_320.0*cosLat)
        val matchingDirections=WindAnchorEvidence.centreMatch(directionEvidence,centreLatitude,centreLongitude).matchRatio
        var accumulatedWeight=0.0
        val credibleDistance=candidates.sortedBy{hypot(it.first-centreX,it.second-centreY)}.firstOrNull{candidate->accumulatedWeight+=candidate.third;accumulatedWeight>=totalWeight*.975}?.let{hypot(it.first-centreX,it.second-centreY)}?:maximumRode
        val regionRadius = (credibleDistance + margin)
            .coerceIn(margin, maximumRode) * (1.0 - matchingDirections.coerceIn(0.0, .75) * .12)
        return Region(centreLatitude,centreLongitude,regionRadius)
    }

    private fun directionalEvidence(samples:List<Sample>)=WindAnchorEvidence.summarize(samples.map{sample->WindAnchorEvidence.Sample(sample.timestamp,sample.latitude,sample.longitude,sample.sogKnots,sample.cogTrueDegrees,sample.headingTrueDegrees,sample.windDirectionTrueDegrees,sample.trueWindAngleDegrees,sample.apparentWindAngleDegrees,sample.trueWindSpeedKnots,sample.apparentWindSpeedKnots,sample.headingSampleSequence,sample.windSampleSequence)})

    /** A final centre must agree when the history is fitted as two independent time periods. */
    private fun temporalFitConsensus(samples: List<Sample>, maximumRode: Double): Boolean {
        if (samples.size < 300) return false
        val split = samples.size / 2
        val early = AnchorCenterEstimator(Random(11)).estimate(samples.take(split).map { AnchorCenterEstimator.Point(it.latitude, it.longitude) }, null) ?: return false
        val late = AnchorCenterEstimator(Random(29)).estimate(samples.drop(split).map { AnchorCenterEstimator.Point(it.latitude, it.longitude) }, null) ?: return false
        if (early.angularCoverageDegrees < 120.0 || late.angularCoverageDegrees < 120.0) return false
        if (early.rmsErrorMeters > max(5.0, maximumRode * .14) || late.rmsErrorMeters > max(5.0, maximumRode * .14)) return false
        val centresApart = AnchorGeometry.distanceMeters(early.latitude, early.longitude, late.latitude, late.longitude)
        return centresApart <= max(7.0, maximumRode * .15) &&
            abs(early.radiusMeters - late.radiusMeters) <= max(8.0, maximumRode * .20)
    }

    /** Counts real port/starboard swing reversals after smoothing; GPS jitter cannot satisfy this gate. */
    private fun meaningfulSwingReversals(samples: List<Sample>, centreLat: Double, centreLon: Double, maximumRode: Double): Int {
        val minimumRadius = max(5.0, maximumRode * .20)
        val bearings = samples.filter {
            AnchorGeometry.distanceMeters(centreLat, centreLon, it.latitude, it.longitude) >= minimumRadius
        }.map { AnchorGeometry.bearingDegrees(centreLat, centreLon, it.latitude, it.longitude) }
        if (bearings.size < 40) return 0
        val unwrapped = mutableListOf(bearings.first())
        for (index in 1 until bearings.size) {
            val delta = (bearings[index] - bearings[index - 1] + 540.0) % 360.0 - 180.0
            unwrapped += unwrapped.last() + delta
        }
        val window = (unwrapped.size / 30).coerceIn(7, 15)
        val step = (window / 2).coerceAtLeast(1)
        val smoothed = unwrapped.windowed(window, step, partialWindows = false).map { it.average() }
        if (smoothed.size < 4) return 0
        val legs = mutableListOf<Double>()
        var current = 0.0
        smoothed.zipWithNext { first, second -> second - first }.forEach { delta ->
            if (abs(delta) < 1.0) return@forEach
            if (current == 0.0 || current * delta > 0.0) current += delta
            else {
                if (abs(current) >= 20.0) legs += current
                current = delta
            }
        }
        if (abs(current) >= 20.0) legs += current
        return legs.zipWithNext().count { (first, second) -> first * second < 0.0 }
    }

    private fun validSamples(samples: List<Sample>) = samples.filter {
        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 && (it.hdop == null || it.hdop <= 5.0)
    }.sortedBy { it.timestamp }

    private fun gpsMargin(samples: List<Sample>) =
        (samples.mapNotNull { it.hdop }.let { if (it.isEmpty()) 4.0 else median(it) * 3.0 }).coerceIn(3.0, 15.0)

    private fun <T> evenlySample(values: List<T>, maximum: Int): List<T> = if (values.size <= maximum) values else
        (0 until maximum).map { values[(it.toLong() * values.lastIndex / (maximum - 1)).toInt()] }

    private fun median(values: List<Double>): Double { val sorted = values.sorted(); val middle = sorted.size / 2; return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle] }
    private fun angularCoverage(bearings: List<Double>): Double { if (bearings.size < 2) return 0.0; val sorted = bearings.sorted(); val gaps = sorted.zipWithNext { a, b -> b - a } + (sorted.first() + 360.0 - sorted.last()); return 360.0 - (gaps.maxOrNull() ?: 360.0) }
}
