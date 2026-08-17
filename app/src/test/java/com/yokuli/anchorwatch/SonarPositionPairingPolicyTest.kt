package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.sonar.SonarPositionPairingDecision
import com.yokuli.anchorwatch.data.sonar.SonarPositionPairingPolicy
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SonarPositionPairingPolicyTest {
    private fun fix(provider:PositionProvider,elapsed:Long=10_000L)=NavigationFix(
        latitude=-36.8,
        longitude=174.7,
        receivedElapsedRealtime=elapsed,
        positionProvider=provider,
        sourceSentence=provider.name,
        valid=true,
    )

    @Test fun realSonarAcceptsOnlySameStreamNmeaPosition(){
        assertEquals(SonarPositionPairingDecision.ALLOWED,SonarPositionPairingPolicy.evaluate(false,fix(PositionProvider.NMEA),10_500L))
        assertEquals(SonarPositionPairingDecision.WRONG_POSITION_PROVIDER,SonarPositionPairingPolicy.evaluate(false,fix(PositionProvider.ANDROID_GNSS),10_500L))
        assertEquals(SonarPositionPairingDecision.WRONG_POSITION_PROVIDER,SonarPositionPairingPolicy.evaluate(false,fix(PositionProvider.DEMO),10_500L))
    }

    @Test fun anchorGpsSelectionCannotLeakSystemGpsIntoSonar(){
        val systemAnchorPosition=fix(PositionProvider.ANDROID_GNSS)
        assertEquals(SonarPositionPairingDecision.WRONG_POSITION_PROVIDER,SonarPositionPairingPolicy.evaluate(false,systemAnchorPosition,10_500L))
    }

    @Test fun demoSonarAcceptsOnlyDemoPosition(){
        assertEquals(SonarPositionPairingDecision.ALLOWED,SonarPositionPairingPolicy.evaluate(true,fix(PositionProvider.DEMO),10_500L))
        assertEquals(SonarPositionPairingDecision.WRONG_POSITION_PROVIDER,SonarPositionPairingPolicy.evaluate(true,fix(PositionProvider.NMEA),10_500L))
    }

    @Test fun depthAndPositionMustBeWithinTwoSeconds(){
        assertEquals(SonarPositionPairingDecision.POSITION_STALE,SonarPositionPairingPolicy.evaluate(false,fix(PositionProvider.NMEA,10_000L),12_001L))
    }
}
