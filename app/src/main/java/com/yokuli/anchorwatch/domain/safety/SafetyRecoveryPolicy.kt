package com.yokuli.anchorwatch.domain.safety

import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.GpsDataSource

enum class SafetyRecoveryDestination { NONE, NMEA, SYSTEM_GPS }

/** Keeps an alarm action tied to the data source that actually failed. */
object SafetyRecoveryPolicy {
    fun destination(
        positionSource: GpsDataSource,
        alarmType: AlarmType?,
        instrumentDataLost: Boolean,
    ): SafetyRecoveryDestination {
        if (instrumentDataLost) return SafetyRecoveryDestination.NMEA
        val positionProblem = alarmType in setOf(
            AlarmType.GPS_DATA_LOST,
            AlarmType.GPS_QUALITY_BAD,
            AlarmType.NMEA_CONNECTION_LOST,
        )
        if (!positionProblem) return SafetyRecoveryDestination.NONE
        return when (positionSource) {
            GpsDataSource.NMEA -> SafetyRecoveryDestination.NMEA
            GpsDataSource.SYSTEM -> SafetyRecoveryDestination.SYSTEM_GPS
            GpsDataSource.DEMO -> SafetyRecoveryDestination.NONE
        }
    }
}
