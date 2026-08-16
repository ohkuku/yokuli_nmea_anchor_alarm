package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.HeadingQuality
import kotlin.math.abs

data class PhoneHeadingObservation(
    val quality: HeadingQuality,
    val headingTrueDegrees: Double?,
    val headingEpoch: Long,
)

/** Pure state machine used by the Android sensor adapter and unit tests. */
class PhoneHeadingIntegrityMonitor(
    private val recoveryMillis: Long = 20_000L,
) {
    private var quality = HeadingQuality.RECOVERING
    private var recoveryStarted: Long? = null
    private var recoveryTilt: Double? = null
    private var referenceTilt: Double? = null
    private var lastStableHeading: Double? = null
    private var epoch = 0L

    fun reset() {
        quality = HeadingQuality.RECOVERING
        recoveryStarted = null
        recoveryTilt = null
        referenceTilt = null
        lastStableHeading = null
        epoch = 0L
    }

    fun observe(
        nowElapsed: Long,
        headingTrueDegrees: Double?,
        tiltDegrees: Double?,
        angularVelocityRadPerSecond: Double,
        accelerationMetersPerSecondSquared: Double,
        sensorAccuracy: Int,
    ): PhoneHeadingObservation {
        if (headingTrueDegrees == null || tiltDegrees == null) {
            quality = HeadingQuality.UNAVAILABLE
            recoveryStarted = null
            return snapshot(null)
        }
        if (referenceTilt == null) referenceTilt = tiltDegrees
        val dynamicDisturbance = sensorAccuracy <= 0 || angularVelocityRadPerSecond > .7 ||
            abs(accelerationMetersPerSecondSquared - 9.81) > 3.0
        if (dynamicDisturbance) {
            quality = if (angularVelocityRadPerSecond > .7 || abs(accelerationMetersPerSecondSquared - 9.81) > 3.0) {
                HeadingQuality.MOVING
            } else {
                HeadingQuality.DISTURBED
            }
            recoveryStarted = null
            recoveryTilt = null
            return snapshot(null)
        }

        val tiltChanged = abs(tiltDegrees - (referenceTilt ?: tiltDegrees)) > 18.0
        if (quality == HeadingQuality.STABLE && tiltChanged) {
            quality = HeadingQuality.DISTURBED
            recoveryStarted = null
            recoveryTilt = tiltDegrees
            return snapshot(null)
        }

        if (quality != HeadingQuality.STABLE) {
            val candidateTilt = recoveryTilt ?: tiltDegrees.also { recoveryTilt = it }
            if (abs(tiltDegrees - candidateTilt) > 5.0) {
                recoveryTilt = tiltDegrees
                recoveryStarted = nowElapsed
                quality = HeadingQuality.RECOVERING
                return snapshot(null)
            }
            val started = recoveryStarted ?: nowElapsed.also { recoveryStarted = it }
            quality = HeadingQuality.RECOVERING
            if (nowElapsed - started < recoveryMillis) return snapshot(null)
            val prior = lastStableHeading
            if (prior != null && circularDifference(prior, headingTrueDegrees) > 25.0) epoch++
            referenceTilt = tiltDegrees
            recoveryTilt = null
            lastStableHeading = headingTrueDegrees
            quality = HeadingQuality.STABLE
        } else {
            lastStableHeading = circularBlend(lastStableHeading ?: headingTrueDegrees, headingTrueDegrees, .15)
        }
        return snapshot(lastStableHeading)
    }

    private fun snapshot(heading: Double?) = PhoneHeadingObservation(quality, heading, epoch)
    private fun circularDifference(a: Double, b: Double) = abs((a - b + 540.0) % 360.0 - 180.0)
    private fun circularBlend(a: Double, b: Double, amount: Double): Double {
        val delta = (b - a + 540.0) % 360.0 - 180.0
        return (a + delta * amount + 360.0) % 360.0
    }
}
