package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import kotlin.math.abs
import kotlin.math.max

data class IntegrityAcceptedFix(
    val fix: NavigationFix,
    val trust: FixTrust,
    val wasQuarantined: Boolean = false,
    val reason: String? = null,
)

sealed interface PositionIntegrityResult {
    data class Accepted(val fixes: List<IntegrityAcceptedFix>) : PositionIntegrityResult
    data class Quarantined(val fix: NavigationFix, val reason: String) : PositionIntegrityResult
    data class Rejected(val fix: NavigationFix, val reason: String) : PositionIntegrityResult
}

/**
 * Stateful safety gate shared by the alarm and centre estimator.
 *
 * A discontinuous fix is held briefly instead of being sent to the alarm. A
 * return to the previous track proves a spike; three spatially coherent fixes
 * prove a real displacement and release the whole sequence, preserving the
 * first suspicious timestamp for alarm persistence accounting.
 */
class PositionIntegrityFilter(
    private val maximumAccuracyMeters: Double = 80.0,
    private val impossibleSpeedMetersPerSecond: Double = 12.0,
    private val confirmationFixes: Int = 3,
    private val confirmationSpanMillis: Long = 2_000L,
) {
    private data class Pending(val fix: NavigationFix, val reason: String)

    private var lastTrusted: NavigationFix? = null
    private var lastObservedElapsed: Long? = null
    private var lastSourceTimestamp: Long? = null
    private val pending = mutableListOf<Pending>()

    fun reset() {
        lastTrusted = null
        lastObservedElapsed = null
        lastSourceTimestamp = null
        pending.clear()
    }

    fun seed(fix: NavigationFix) {
        reset()
        if (fix.valid) {
            lastTrusted = fix
            lastObservedElapsed = fix.receivedElapsedRealtime
            lastSourceTimestamp = fix.timestampUtcMillis
        }
    }

    fun evaluate(fix: NavigationFix, motion: PhoneMotionState? = null): PositionIntegrityResult =
        evaluateCurrentQuality(fix.withCurrentNmeaComponents(),motion)

    private fun evaluateCurrentQuality(fix: NavigationFix, motion: PhoneMotionState?): PositionIntegrityResult {
        if (!fix.valid || !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0
        ) return PositionIntegrityResult.Rejected(fix, "INVALID_POSITION")

        val observed = lastObservedElapsed
        if (observed != null && fix.receivedElapsedRealtime <= observed) {
            return PositionIntegrityResult.Rejected(fix, "NON_MONOTONIC_OR_DUPLICATE_FIX")
        }
        lastObservedElapsed = fix.receivedElapsedRealtime
        val sourceTimestamp=fix.timestampUtcMillis
        val previousSourceTimestamp=lastSourceTimestamp
        if(sourceTimestamp!=null&&previousSourceTimestamp!=null&&sourceTimestamp<previousSourceTimestamp-250L){
            return PositionIntegrityResult.Rejected(fix,"SOURCE_TIMESTAMP_MOVED_BACKWARDS")
        }
        if(sourceTimestamp!=null&&(previousSourceTimestamp==null||sourceTimestamp>previousSourceTimestamp))lastSourceTimestamp=sourceTimestamp

        if (fix.positionProvider == PositionProvider.ANDROID_NETWORK) {
            return PositionIntegrityResult.Rejected(fix, "COARSE_NETWORK_POSITION")
        }
        if (fix.isMockLocation && fix.positionProvider != PositionProvider.DEMO) {
            return PositionIntegrityResult.Rejected(fix, "MOCK_POSITION_FEEDBACK")
        }
        if (fix.fixQuality != null && fix.fixQuality <= 0) {
            return PositionIntegrityResult.Rejected(fix, "NO_GNSS_FIX")
        }
        val accuracy = effectiveAccuracy(fix)
        if (accuracy > maximumAccuracyMeters * 2.5) {
            return PositionIntegrityResult.Rejected(fix, "POSITION_ACCURACY_UNUSABLE")
        }

        val baseline = lastTrusted
        if (baseline == null) {
            if (accuracy > maximumAccuracyMeters) {
                return quarantine(fix, "POSITION_ACCURACY_POOR")
            }
            lastTrusted = fix
            return PositionIntegrityResult.Accepted(listOf(IntegrityAcceptedFix(fix, trustFor(fix),reason=qualityReason(fix))))
        }

        if (pending.isNotEmpty()) return evaluatePending(fix, baseline)

        val suspiciousReason = suspiciousReason(baseline, fix, accuracy, motion)
        if (suspiciousReason != null) return quarantine(fix, suspiciousReason)

        lastTrusted = fix
        return PositionIntegrityResult.Accepted(listOf(IntegrityAcceptedFix(fix, trustFor(fix),reason=qualityReason(fix))))
    }

    private fun evaluatePending(fix: NavigationFix, baseline: NavigationFix): PositionIntegrityResult {
        val returnTolerance = max(12.0, effectiveAccuracy(baseline) + effectiveAccuracy(fix))
        val fromBaseline = distance(baseline, fix)
        if (fromBaseline <= returnTolerance) {
            pending.clear()
            lastTrusted = fix
            return PositionIntegrityResult.Accepted(
                listOf(IntegrityAcceptedFix(fix, trustFor(fix), reason = "GPS_SPIKE_CLEARED")),
            )
        }

        val previous = pending.last().fix
        val intervalSeconds = ((fix.receivedElapsedRealtime - previous.receivedElapsedRealtime) / 1_000.0)
            .coerceAtLeast(.001)
        val step = distance(previous, fix)
        val coherentStep = step / intervalSeconds <= impossibleSpeedMetersPerSecond &&
            bearingDifference(
                AnchorGeometry.bearingDegrees(baseline.latitude, baseline.longitude, previous.latitude, previous.longitude),
                AnchorGeometry.bearingDegrees(baseline.latitude, baseline.longitude, fix.latitude, fix.longitude),
            ) <= 35.0
        if (!coherentStep || effectiveAccuracy(fix) > maximumAccuracyMeters) {
            pending.clear()
            return quarantine(fix, if (!coherentStep) "DISCONTINUOUS_POSITION" else "POSITION_ACCURACY_POOR")
        }

        pending += Pending(fix, "SUSTAINED_POSITION_CHANGE")
        if (pending.size < confirmationFixes || fix.receivedElapsedRealtime - pending.first().fix.receivedElapsedRealtime < confirmationSpanMillis) {
            return PositionIntegrityResult.Quarantined(fix, "AWAITING_POSITION_CONFIRMATION")
        }

        val released = pending.map {
            IntegrityAcceptedFix(
                fix = it.fix,
                trust = FixTrust.DEGRADED,
                wasQuarantined = true,
                reason = "SUSTAINED_POSITION_CHANGE_CONFIRMED",
            )
        }
        pending.clear()
        lastTrusted = fix
        return PositionIntegrityResult.Accepted(released)
    }

    private fun suspiciousReason(previous: NavigationFix, current: NavigationFix, accuracy: Double, motion: PhoneMotionState?): String? {
        if (accuracy > maximumAccuracyMeters) return "POSITION_ACCURACY_POOR"
        val previousAccuracy = effectiveAccuracy(previous)
        if (accuracy > 30.0 && accuracy > previousAccuracy * 4.0) return "POSITION_ACCURACY_COLLAPSE"
        val elapsedSeconds = ((current.receivedElapsedRealtime - previous.receivedElapsedRealtime) / 1_000.0)
            .coerceAtLeast(.001)
        val step = distance(previous, current)
        val speed = step / elapsedSeconds
        val accuracyAllowance = max(12.0, previousAccuracy + accuracy + 5.0)
        if (step > accuracyAllowance && speed > impossibleSpeedMetersPerSecond) return "IMPOSSIBLE_POSITION_JUMP"
        val reportedSpeed = current.sogKnots?.times(.514444)
        if (step > accuracyAllowance && reportedSpeed != null && reportedSpeed < 2.0 && speed > 5.0) {
            return "GPS_SPEED_DISAGREEMENT"
        }
        if ((motion?.moving == true || motion?.disturbed == true ||
                current.headingQuality in setOf(HeadingQuality.MOVING, HeadingQuality.DISTURBED)) &&
            current.positionProvider == PositionProvider.ANDROID_GNSS && step > max(8.0, accuracyAllowance / 2.0)
        ) return "PHONE_MOVED"
        return null
    }

    private fun quarantine(fix: NavigationFix, reason: String): PositionIntegrityResult.Quarantined {
        pending.clear()
        pending += Pending(fix, reason)
        return PositionIntegrityResult.Quarantined(fix, reason)
    }

    private fun trustFor(fix: NavigationFix): FixTrust = when {
        fix.horizontalAccuracyMeters == null && fix.hdop == null -> FixTrust.DEGRADED
        effectiveAccuracy(fix) > 30.0 -> FixTrust.DEGRADED
        fix.hdop != null && fix.hdop > 3.0 -> FixTrust.DEGRADED
        fix.satellites != null && fix.satellites < 5 -> FixTrust.DEGRADED
        else -> FixTrust.TRUSTED
    }

    private fun effectiveAccuracy(fix: NavigationFix): Double =
        fix.horizontalAccuracyMeters ?: fix.hdop?.times(3.0) ?: UNKNOWN_ACCURACY_METERS

    private fun qualityReason(fix:NavigationFix):String?=
        if(fix.horizontalAccuracyMeters==null&&fix.hdop==null)"QUALITY_NOT_REPORTED" else null

    /**
     * NavigationRepository deliberately keeps omitted GGA fields for display
     * and diagnostics. Integrity decisions use only currently fresh quality
     * evidence, so an old good HDOP or an old bad fix-quality flag cannot be
     * refreshed by an unrelated RMC/GLL position sentence.
     */
    private fun NavigationFix.withCurrentNmeaComponents(qualityMaxAgeMillis:Long=5_000L):NavigationFix{
        if(positionProvider!=PositionProvider.NMEA)return this
        fun fresh(received:Long?,maxAgeMillis:Long)=(received?:receivedElapsedRealtime).let{receivedElapsedRealtime-it in 0L..maxAgeMillis}
        val hdopFresh=fresh(hdopReceivedElapsedRealtime,qualityMaxAgeMillis)
        val trueHeadingFresh=fresh(headingReceivedElapsedRealtime,HEADING_MAX_AGE_MILLIS)
        val magneticHeadingFresh=fresh(headingMagneticReceivedElapsedRealtime,HEADING_MAX_AGE_MILLIS)
        return copy(
            hdop=hdop.takeIf{hdopFresh},
            fixQuality=fixQuality.takeIf{fresh(fixQualityReceivedElapsedRealtime,qualityMaxAgeMillis)},
            satellites=satellites.takeIf{fresh(satellitesReceivedElapsedRealtime,qualityMaxAgeMillis)},
            altitudeMeters=altitudeMeters.takeIf{fresh(altitudeReceivedElapsedRealtime,qualityMaxAgeMillis)},
            horizontalAccuracyMeters=horizontalAccuracyMeters.takeIf{hdopFresh},
            sogKnots=sogKnots.takeIf{fresh(sogReceivedElapsedRealtime,COURSE_MAX_AGE_MILLIS)},
            cogTrueDegrees=cogTrueDegrees.takeIf{fresh(cogReceivedElapsedRealtime,COURSE_MAX_AGE_MILLIS)},
            speedThroughWaterKnots=speedThroughWaterKnots.takeIf{fresh(speedThroughWaterReceivedElapsedRealtime,HEADING_MAX_AGE_MILLIS)},
            headingTrueDegrees=headingTrueDegrees.takeIf{trueHeadingFresh},
            headingMagneticDegrees=headingMagneticDegrees.takeIf{magneticHeadingFresh},
            headingSource=if(headingSource==com.yokuli.anchorwatch.domain.model.HeadingSource.NMEA_PHYSICAL&&!trueHeadingFresh)com.yokuli.anchorwatch.domain.model.HeadingSource.NONE else headingSource,
            headingQuality=if(headingSource==com.yokuli.anchorwatch.domain.model.HeadingSource.NMEA_PHYSICAL&&!trueHeadingFresh)HeadingQuality.UNAVAILABLE else headingQuality,
            headingSampleSequence=headingSampleSequence.takeIf{trueHeadingFresh},
        )
    }

    private fun distance(a: NavigationFix, b: NavigationFix): Double =
        AnchorGeometry.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun bearingDifference(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)

    private companion object{
        const val UNKNOWN_ACCURACY_METERS=50.0
        const val COURSE_MAX_AGE_MILLIS=5_000L
        const val HEADING_MAX_AGE_MILLIS=10_000L
    }
}
