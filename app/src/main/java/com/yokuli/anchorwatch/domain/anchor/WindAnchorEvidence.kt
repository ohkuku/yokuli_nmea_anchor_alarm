package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Converts noisy heading/wind streams into temporally independent heading evidence.
 * Wind-derived headings are never emitted from a single sentence: each observation
 * represents a stable circular-mean window, and several windows must repeat.
 */
object WindAnchorEvidence {
    enum class Source { NMEA_PHYSICAL_HEADING, PHONE_HEADING, TRUE_WIND_ANGLE, APPARENT_TRUE_MATCH, STATIONARY_APPARENT, BACKDOWN_COG }

    data class Sample(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val sogKnots: Double?,
        val cogTrueDegrees: Double?,
        val headingTrueDegrees: Double?,
        val trueWindDirectionDegrees: Double?,
        val trueWindAngleDegrees: Double?,
        val apparentWindAngleDegrees: Double?,
        val trueWindSpeedKnots: Double?,
        val apparentWindSpeedKnots: Double?,
        val headingSampleSequence: Long? = null,
        val windSampleSequence: Long? = null,
        val headingSource: HeadingSource = HeadingSource.NONE,
        val headingQuality: HeadingQuality = HeadingQuality.UNAVAILABLE,
    )

    data class Observation(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val headingToAnchorDegrees: Double,
        val weight: Double,
        val source: Source,
        val sampleCount: Int,
    )

    data class Summary(val observations: List<Observation>) {
        val physicalCount get() = observations.count { it.source == Source.NMEA_PHYSICAL_HEADING }
        val phoneCount get() = observations.count { it.source == Source.PHONE_HEADING }
        val windCount get() = observations.count { it.source == Source.TRUE_WIND_ANGLE || it.source == Source.APPARENT_TRUE_MATCH || it.source == Source.STATIONARY_APPARENT }
        val independentWindCount get() = observations.count { it.source == Source.APPARENT_TRUE_MATCH || it.source == Source.STATIONARY_APPARENT }
        val hasPhysicalEvidence get() = physicalCount >= 3
        val hasRepeatedWindEvidence get() = windCount >= 4
        val hasRepeatedIndependentWindEvidence get() = independentWindCount >= 4
    }

    data class CentreMatch(val sampleCount: Int, val matchRatio: Double, val medianErrorDegrees: Double) {
        val consistent get() = sampleCount >= 3 && matchRatio >= .70 && medianErrorDegrees <= 22.0
    }

    private data class Raw(val sample: Sample, val heading: Double, val weight: Double, val source: Source, val independentSequence: Long?)

    fun summarize(samples: List<Sample>): Summary {
        if (samples.isEmpty()) return Summary(emptyList())
        val raw = samples.flatMap(::rawEvidence)
        val physical = stableWindows(raw.filter { it.source == Source.NMEA_PHYSICAL_HEADING }, minimumWindows = 3, minimumSpanMillis = 30_000L)
        val phone = stableWindows(raw.filter { it.source == Source.PHONE_HEADING }, minimumWindows = 4, minimumSpanMillis = 45_000L)
        val wind = stableWindows(raw.filter { it.source !in setOf(Source.NMEA_PHYSICAL_HEADING, Source.PHONE_HEADING, Source.BACKDOWN_COG) }, minimumWindows = 4, minimumSpanMillis = 45_000L)
        val backdownCog = stableWindows(raw.filter { it.source == Source.BACKDOWN_COG }, minimumWindows = 2, minimumSpanMillis = 15_000L)
        return Summary((physical + phone + wind + backdownCog).sortedBy { it.timestamp })
    }

    fun centreMatch(summary: Summary, centreLatitude: Double, centreLongitude: Double): CentreMatch {
        if (summary.observations.isEmpty()) return CentreMatch(0, 0.0, 180.0)
        val errors = summary.observations.map { observation ->
            angularDifference(observation.headingToAnchorDegrees, AnchorGeometry.bearingDegrees(observation.latitude, observation.longitude, centreLatitude, centreLongitude))
        }
        val matchingWeight = summary.observations.zip(errors).filter { it.second <= 32.0 }.sumOf { it.first.weight }
        val totalWeight = summary.observations.sumOf { it.weight }.coerceAtLeast(.001)
        return CentreMatch(errors.size, matchingWeight / totalWeight, median(errors))
    }

    fun candidateScore(summary: Summary, centreLatitude: Double, centreLongitude: Double): Double {
        if (summary.observations.isEmpty()) return 0.0
        val weighted = summary.observations.map { observation ->
            val error = angularDifference(observation.headingToAnchorDegrees, AnchorGeometry.bearingDegrees(observation.latitude, observation.longitude, centreLatitude, centreLongitude))
            observation.weight to error
        }
        val total = weighted.sumOf { it.first }.coerceAtLeast(.001)
        val cosineScore = weighted.sumOf { (weight, error) -> weight * cos(Math.toRadians(error)) } / total
        val matchRatio = weighted.filter { it.second <= 35.0 }.sumOf { it.first } / total
        return (cosineScore * .75 + (matchRatio - .5) * .50).coerceIn(-1.0, 1.0)
    }

    private fun rawEvidence(sample: Sample): List<Raw> = buildList {
        sample.headingTrueDegrees?.takeIf { it.isFinite() && sample.headingQuality == HeadingQuality.STABLE }?.let { heading ->
            when (sample.headingSource) {
                HeadingSource.NMEA_PHYSICAL -> add(Raw(sample, normalize(heading), 1.0, Source.NMEA_PHYSICAL_HEADING, sample.headingSampleSequence))
                HeadingSource.PHONE -> add(Raw(sample, normalize(heading), .35, Source.PHONE_HEADING, sample.headingSampleSequence))
                HeadingSource.NONE -> Unit
            }
        }
        if(sample.cogTrueDegrees!=null&&(sample.sogKnots?:0.0)>=.8)add(Raw(sample,normalize(sample.cogTrueDegrees+180.0),.42,Source.BACKDOWN_COG,null))
        val direction = sample.trueWindDirectionDegrees ?: return@buildList
        val trueAngle = sample.trueWindAngleDegrees
        val apparentAngle = sample.apparentWindAngleDegrees
        val trueSpeed = sample.trueWindSpeedKnots
        val apparentSpeed = sample.apparentWindSpeedKnots
        val usableSpeed = max(trueSpeed ?: 0.0, apparentSpeed ?: 0.0)
        if (usableSpeed < 3.5) return@buildList
        val angleMatch = trueAngle != null && apparentAngle != null && angularDifference(trueAngle, apparentAngle) <= 15.0
        val speedMatch = trueSpeed == null || apparentSpeed == null || abs(trueSpeed - apparentSpeed) <= max(2.0, trueSpeed * .25)
        when {
            trueAngle != null -> {
                val source = if (angleMatch && speedMatch) Source.APPARENT_TRUE_MATCH else Source.TRUE_WIND_ANGLE
                val matchBoost = if (source == Source.APPARENT_TRUE_MATCH) .12 else 0.0
                add(Raw(sample, normalize(direction - trueAngle), (.68 + matchBoost + (usableSpeed / 40.0).coerceAtMost(.12)), source,sample.windSampleSequence))
            }
            apparentAngle != null && apparentSpeed != null && (sample.sogKnots ?: 0.0) <= .35 && speedMatch -> {
                add(Raw(sample, normalize(direction - apparentAngle), (.48 + (apparentSpeed / 35.0).coerceAtMost(.15)), Source.STATIONARY_APPARENT,sample.windSampleSequence))
            }
        }
    }

    private fun stableWindows(raw: List<Raw>, minimumWindows: Int, minimumSpanMillis: Long): List<Observation> {
        val independent=raw.distinctBy{it.source to (it.independentSequence?:it.sample.timestamp)}
        if (independent.size < minimumWindows * 5) return emptyList()
        val windows = independent.groupBy { it.sample.timestamp / 20_000L }.values.mapNotNull { values ->
            if (values.size < 5 || values.last().sample.timestamp - values.first().sample.timestamp < 6_000L) return@mapNotNull null
            val totalWeight = values.sumOf { it.weight }.coerceAtLeast(.001)
            val x = values.sumOf { cos(Math.toRadians(it.heading)) * it.weight } / totalWeight
            val y = values.sumOf { sin(Math.toRadians(it.heading)) * it.weight } / totalWeight
            val concentration = hypot(x, y)
            if (concentration < .88) return@mapNotNull null
            val source = values.groupingBy { it.source }.eachCount().maxBy { it.value }.key
            Observation(
                timestamp = values.map { it.sample.timestamp }.sorted()[values.size / 2],
                latitude = median(values.map { it.sample.latitude }),
                longitude = median(values.map { it.sample.longitude }),
                headingToAnchorDegrees = normalize(Math.toDegrees(atan2(y, x))),
                weight = values.map { it.weight }.average() * concentration,
                source = source,
                sampleCount = values.size,
            )
        }.sortedBy { it.timestamp }
        if (windows.size < minimumWindows || windows.last().timestamp - windows.first().timestamp < minimumSpanMillis) return emptyList()
        return windows
    }

    private fun angularDifference(first: Double, second: Double) = abs((first - second + 540.0) % 360.0 - 180.0)
    private fun normalize(value: Double) = (value % 360.0 + 360.0) % 360.0
    private fun median(values: List<Double>): Double { val sorted=values.sorted();val middle=sorted.size/2;return if(sorted.size%2==0)(sorted[middle-1]+sorted[middle])/2 else sorted[middle] }
}
