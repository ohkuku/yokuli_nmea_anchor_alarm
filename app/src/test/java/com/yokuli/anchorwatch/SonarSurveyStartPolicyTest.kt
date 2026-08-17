package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartDecision
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarMapDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SonarSurveyStartPolicyTest {
    @Test fun savedSonarMapDisplayIsIndependentOfLiveNmea() {
        assertEquals(false,SonarMapDisplayPolicy.isVisible(enabled=false,hasStoredCells=true))
        assertEquals(false,SonarMapDisplayPolicy.isVisible(enabled=true,hasStoredCells=false))
        assertEquals(true,SonarMapDisplayPolicy.isVisible(enabled=true,hasStoredCells=true))
    }

    @Test fun demoDoesNotNeedNmeaOrRealDepth() {
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(true,NmeaConnectionState.DISCONNECTED,false))
    }

    @Test fun realSurveyRequiresConnectedNmeaAndFreshDepth() {
        assertEquals(SonarSurveyStartDecision.NMEA_NOT_CONNECTED,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED_NO_DATA,true,true))
        assertEquals(SonarSurveyStartDecision.DEPTH_NOT_FRESH,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED,false,true))
        assertEquals(SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED,true,false))
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED,true,true))
    }
}
