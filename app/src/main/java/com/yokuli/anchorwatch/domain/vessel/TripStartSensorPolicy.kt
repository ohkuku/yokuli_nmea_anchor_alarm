package com.yokuli.anchorwatch.domain.vessel

/**
 * Decides whether phone motion may be recorded for a new trip.
 *
 * A missing mount calibration is never silently treated as the current phone
 * pose. Pressure is deliberately excluded: a barometer remains useful without
 * attitude sensors or a vessel-mount calibration.
 */
object TripStartSensorPolicy {
    fun phoneMotionEnabled(
        requested:Boolean,
        attitudeAvailable:Boolean,
        calibratedAt:Long,
    ):Boolean = requested && attitudeAvailable && calibratedAt>0L
}
