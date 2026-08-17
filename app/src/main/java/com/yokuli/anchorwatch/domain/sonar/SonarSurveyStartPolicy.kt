package com.yokuli.anchorwatch.domain.sonar

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState

enum class SonarSurveyStartDecision { ALLOWED, NMEA_NOT_CONNECTED, DEPTH_NOT_FRESH, NMEA_POSITION_NOT_FRESH }

/** One safety rule shared by UI and service; the service remains authoritative. */
object SonarSurveyStartPolicy {
    /** A real sonar chart is meaningful only while its same-vessel NMEA source is live. */
    fun canEnableLayer(demoMode:Boolean,connection:NmeaConnectionState):Boolean =
        demoMode||connection==NmeaConnectionState.CONNECTED

    fun evaluate(
        demoMode: Boolean,
        connection: NmeaConnectionState,
        hasFreshDepth: Boolean,
        hasFreshNmeaPosition: Boolean = true,
    ): SonarSurveyStartDecision = when {
        demoMode -> SonarSurveyStartDecision.ALLOWED
        connection != NmeaConnectionState.CONNECTED -> SonarSurveyStartDecision.NMEA_NOT_CONNECTED
        !hasFreshDepth -> SonarSurveyStartDecision.DEPTH_NOT_FRESH
        !hasFreshNmeaPosition -> SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH
        else -> SonarSurveyStartDecision.ALLOWED
    }
}
