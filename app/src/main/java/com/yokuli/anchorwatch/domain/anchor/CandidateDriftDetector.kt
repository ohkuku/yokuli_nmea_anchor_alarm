package com.yokuli.anchorwatch.domain.anchor

import kotlin.math.abs
import kotlin.math.max

data class CandidateCenterObservation(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val uncertaintyMeters: Double,
) {
    fun encode(): String = "timestamp=$timestamp;lat=$latitude;lon=$longitude;uncertainty=$uncertaintyMeters"

    companion object {
        fun decode(value: String): CandidateCenterObservation? {
            val fields = value.split(';').mapNotNull { item ->
                val parts = item.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            return CandidateCenterObservation(
                timestamp = fields["timestamp"]?.toLongOrNull() ?: return null,
                latitude = fields["lat"]?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null,
                longitude = fields["lon"]?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null,
                uncertaintyMeters = fields["uncertainty"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null,
            )
        }
    }
}

enum class CandidateDriftUpdate { IGNORED, RECORDED, POSSIBLE_DRAG }

/**
 * Conservative P1 trend detector for a fitted anchor centre that keeps moving.
 * It never changes AlarmEngine state; callers may surface only an advisory event.
 */
class CandidateDriftDetector(
    private val minimumIntervalMillis: Long = 60_000L,
    private val minimumDurationMillis: Long = 8 * 60_000L,
    private val minimumObservations: Int = 5,
) {
    private val observations = ArrayDeque<CandidateCenterObservation>()
    private var warningReported = false

    fun reset() {
        observations.clear()
        warningReported = false
    }

    fun restore(history: List<CandidateCenterObservation>, alreadyReported: Boolean) {
        reset()
        history.sortedBy { it.timestamp }.forEach(::recordWithoutEvaluation)
        warningReported = alreadyReported
    }

    fun add(observation: CandidateCenterObservation): CandidateDriftUpdate {
        val last = observations.lastOrNull()
        if (last != null && observation.timestamp <= last.timestamp) return CandidateDriftUpdate.IGNORED
        if (last != null && observation.timestamp - last.timestamp < minimumIntervalMillis) return CandidateDriftUpdate.IGNORED
        recordWithoutEvaluation(observation)
        if (warningReported || !isPersistentDirectionalDrift()) return CandidateDriftUpdate.RECORDED
        warningReported = true
        return CandidateDriftUpdate.POSSIBLE_DRAG
    }

    private fun recordWithoutEvaluation(observation: CandidateCenterObservation) {
        observations += observation
        while (observations.size > 12) observations.removeFirst()
        val cutoff = observation.timestamp - 20 * 60_000L
        while (observations.firstOrNull()?.timestamp?.let { it < cutoff } == true) observations.removeFirst()
    }

    private fun isPersistentDirectionalDrift(): Boolean {
        if (observations.size < minimumObservations) return false
        val sample = observations.toList()
        if (sample.last().timestamp - sample.first().timestamp < minimumDurationMillis) return false
        if (sample.last().uncertaintyMeters > sample.first().uncertaintyMeters * 1.25) return false

        val segments = sample.zipWithNext().map { (a, b) ->
            val distance = AnchorGeometry.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val bearing = AnchorGeometry.bearingDegrees(a.latitude, a.longitude, b.latitude, b.longitude)
            distance to bearing
        }
        val pathLength = segments.sumOf { it.first }
        val netDistance = AnchorGeometry.distanceMeters(
            sample.first().latitude,
            sample.first().longitude,
            sample.last().latitude,
            sample.last().longitude,
        )
        val minimumDistance = max(12.0, sample.map { it.uncertaintyMeters }.average() * 2.5)
        if (netDistance < minimumDistance || pathLength <= 0.0 || netDistance / pathLength < 0.80) return false

        val overallBearing = AnchorGeometry.bearingDegrees(
            sample.first().latitude,
            sample.first().longitude,
            sample.last().latitude,
            sample.last().longitude,
        )
        return segments.filter { it.first >= 1.0 }.all { (_, bearing) -> angularDifference(bearing, overallBearing) <= 35.0 }
    }

    private fun angularDifference(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)
}
