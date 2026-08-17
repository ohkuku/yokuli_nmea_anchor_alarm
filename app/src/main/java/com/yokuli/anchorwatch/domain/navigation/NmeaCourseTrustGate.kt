package com.yokuli.anchorwatch.domain.navigation

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider

/** A COG that has remained usable long enough to act as a presentation heading. */
data class TrustedNmeaCourse(
    val trueDegrees: Double,
    val sogKnots: Double,
    val receivedElapsedRealtime: Long,
) {
    fun isFresh(nowElapsed: Long): Boolean =
        nowElapsed - receivedElapsedRealtime in 0L..NmeaCourseTrustGate.FRESH_MILLIS
}

/**
 * Prevents low-speed GPS wander from masquerading as the boat's bow direction.
 * Physical HDT/HDG remains authoritative and is handled by the caller.
 */
class NmeaCourseTrustGate {
    private var enteredBandAt: Long? = null
    private var exitedBandAt: Long? = null
    private var trusted = false
    private var lastElapsed: Long? = null

    fun update(fix: NavigationFix?, nowElapsed: Long): TrustedNmeaCourse? {
        if (lastElapsed?.let { nowElapsed < it } == true) reset()
        lastElapsed = nowElapsed

        if (fix?.positionProvider != PositionProvider.NMEA) {
            resetTrustState()
            return null
        }
        val degrees = fix.cogTrueDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }
        val speed = fix.sogKnots?.takeIf { it.isFinite() && it >= 0.0 }
        val received = fix.cogReceivedElapsedRealtime ?: fix.receivedElapsedRealtime
        if (degrees == null || speed == null || nowElapsed - received !in 0L..FRESH_MILLIS) {
            resetTrustState()
            return null
        }

        when {
            speed >= ENTER_SPEED_KNOTS -> {
                exitedBandAt = null
                if (enteredBandAt == null) enteredBandAt = nowElapsed
                if (nowElapsed - enteredBandAt!! >= ENTER_HOLD_MILLIS) trusted = true
            }
            speed < EXIT_SPEED_KNOTS -> {
                enteredBandAt = null
                if (exitedBandAt == null) exitedBandAt = nowElapsed
                if (nowElapsed - exitedBandAt!! >= EXIT_HOLD_MILLIS) trusted = false
            }
            else -> {
                enteredBandAt = null
                exitedBandAt = null
            }
        }

        // TODO(v1.0.x): Calibrate these thresholds and add a Raymarine RMC/VTG
        // source-switch story using real Lotus 10.6 logs before changing alarm logic.
        return if (trusted) TrustedNmeaCourse(degrees % 360.0, speed, received) else null
    }

    fun reset() {
        lastElapsed = null
        resetTrustState()
    }

    private fun resetTrustState() {
        enteredBandAt = null
        exitedBandAt = null
        trusted = false
    }

    companion object {
        const val ENTER_SPEED_KNOTS = 1.2
        const val EXIT_SPEED_KNOTS = 0.6
        const val ENTER_HOLD_MILLIS = 2_000L
        const val EXIT_HOLD_MILLIS = 3_000L
        const val FRESH_MILLIS = 3_000L
    }
}
