package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartDecision
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SonarSurveyStartPolicyTest {
    @Test fun demoDoesNotNeedNmeaOrRealDepth() {
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(true,NmeaConnectionState.DISCONNECTED,false))
    }

    @Test fun realSurveyRequiresConnectedNmeaAndFreshDepth() {
        assertEquals(SonarSurveyStartDecision.NMEA_NOT_CONNECTED,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED_NO_DATA,true))
        assertEquals(SonarSurveyStartDecision.DEPTH_NOT_FRESH,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED,false))
        assertEquals(SonarSurveyStartDecision.ALLOWED,SonarSurveyStartPolicy.evaluate(false,NmeaConnectionState.CONNECTED,true))
    }
}
