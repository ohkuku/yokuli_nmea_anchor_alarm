package com.yokuli.anchorwatch.domain.sonar

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState

enum class SonarSurveyStartDecision { ALLOWED, DEMO_WATCH_REQUIRED, NMEA_NOT_CONNECTED, DEPTH_NOT_SEEN, DEPTH_HOLD_EXPIRED, NMEA_POSITION_NOT_FRESH }

enum class SonarSurveyContinuityState { IDLE, REAL_RECORDING, REAL_INTERRUPTED, DEMO_RECORDING, DEMO_WAITING }

/**
 * Describes what an already-open survey can honestly do right now. Keeping an
 * active database row is not the same as recording: a real survey must still
 * have live same-stream NMEA position, while Demo must still own a running
 * Demo watch trajectory.
 */
object SonarSurveyContinuityPolicy {
    fun evaluate(
        surveyActive:Boolean,
        demoMode:Boolean,
        demoWatchRunning:Boolean,
        connection:NmeaConnectionState,
        hasFreshNmeaPosition:Boolean,
    ):SonarSurveyContinuityState=when{
        !surveyActive->SonarSurveyContinuityState.IDLE
        demoMode&&demoWatchRunning->SonarSurveyContinuityState.DEMO_RECORDING
        demoMode->SonarSurveyContinuityState.DEMO_WAITING
        connection==NmeaConnectionState.CONNECTED&&hasFreshNmeaPosition->SonarSurveyContinuityState.REAL_RECORDING
        else->SonarSurveyContinuityState.REAL_INTERRUPTED
    }
}

/** One safety rule shared by UI and service; the service remains authoritative. */
object SonarSurveyStartPolicy {
    fun evaluate(
        demoMode: Boolean,
        demoWatchRunning:Boolean=false,
        connection: NmeaConnectionState,
        depthHoldState: SonarDepthHoldState,
        hasFreshNmeaPosition: Boolean = true,
    ): SonarSurveyStartDecision = when {
        demoMode&&!demoWatchRunning -> SonarSurveyStartDecision.DEMO_WATCH_REQUIRED
        demoMode -> SonarSurveyStartDecision.ALLOWED
        connection != NmeaConnectionState.CONNECTED -> SonarSurveyStartDecision.NMEA_NOT_CONNECTED
        depthHoldState==SonarDepthHoldState.NO_DEPTH -> SonarSurveyStartDecision.DEPTH_NOT_SEEN
        depthHoldState in setOf(SonarDepthHoldState.EXPIRED_TIME,SonarDepthHoldState.EXPIRED_DISTANCE) -> SonarSurveyStartDecision.DEPTH_HOLD_EXPIRED
        !hasFreshNmeaPosition -> SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH
        else -> SonarSurveyStartDecision.ALLOWED
    }
}

/** Viewing saved survey cells is an offline operation; only recording needs live NMEA. */
object SonarMapDisplayPolicy {
    fun isVisible(enabled:Boolean,hasStoredCells:Boolean):Boolean=enabled&&hasStoredCells
}
