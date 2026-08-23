package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartDecision
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarMapDisplayPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyContinuityPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyContinuityState
import org.junit.Assert.assertEquals
import org.junit.Test

class SonarSurveyStartPolicyTest {
    @Test fun savedSonarMapDisplayIsIndependentOfLiveNmea() {
        assertEquals(false,SonarMapDisplayPolicy.isVisible(enabled=false,hasStoredCells=true))
        assertEquals(false,SonarMapDisplayPolicy.isVisible(enabled=true,hasStoredCells=false))
        assertEquals(true,SonarMapDisplayPolicy.isVisible(enabled=true,hasStoredCells=true))
    }

    @Test fun demoNeedsItsRunningAnchorTrackButNotNmeaOrRealDepth() {
        assertEquals(SonarSurveyStartDecision.DEMO_WATCH_REQUIRED,SonarSurveyStartPolicy.evaluate(true,false,NmeaConnectionState.DISCONNECTED,SonarDepthHoldState.NO_DEPTH))
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(true,true,NmeaConnectionState.DISCONNECTED,SonarDepthHoldState.NO_DEPTH))
    }

    @Test fun realSurveyRequiresConnectedNmeaSeenDepthAndFreshPosition() {
        assertEquals(SonarSurveyStartDecision.NMEA_NOT_CONNECTED,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED_NO_DATA,SonarDepthHoldState.LIVE,true))
        assertEquals(SonarSurveyStartDecision.DEPTH_NOT_SEEN,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED,SonarDepthHoldState.NO_DEPTH,true))
        assertEquals(SonarSurveyStartDecision.DEPTH_HOLD_EXPIRED,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED,SonarDepthHoldState.EXPIRED_TIME,true))
        assertEquals(SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED,SonarDepthHoldState.HELD,false))
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED,SonarDepthHoldState.LIVE,true))
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED,SonarDepthHoldState.HELD,true))
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(false,false,NmeaConnectionState.CONNECTED,SonarDepthHoldState.WARNING,true))
    }

    @Test fun anOpenSurveyNeverPretendsItIsRecordingWithoutItsTrajectorySource() {
        assertEquals(SonarSurveyContinuityState.IDLE,SonarSurveyContinuityPolicy.evaluate(false,false,false,NmeaConnectionState.CONNECTED,true))
        assertEquals(SonarSurveyContinuityState.REAL_RECORDING,SonarSurveyContinuityPolicy.evaluate(true,false,false,NmeaConnectionState.CONNECTED,true))
        assertEquals(SonarSurveyContinuityState.REAL_INTERRUPTED,SonarSurveyContinuityPolicy.evaluate(true,false,false,NmeaConnectionState.RECONNECTING,false))
        assertEquals(SonarSurveyContinuityState.REAL_INTERRUPTED,SonarSurveyContinuityPolicy.evaluate(true,false,false,NmeaConnectionState.CONNECTED,false))
        assertEquals(SonarSurveyContinuityState.DEMO_WAITING,SonarSurveyContinuityPolicy.evaluate(true,true,false,NmeaConnectionState.DISCONNECTED,false))
        assertEquals(SonarSurveyContinuityState.DEMO_RECORDING,SonarSurveyContinuityPolicy.evaluate(true,true,true,NmeaConnectionState.DISCONNECTED,false))
    }
}
