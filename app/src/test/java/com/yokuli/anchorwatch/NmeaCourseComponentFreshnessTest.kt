package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.domain.navigation.NmeaCourseTrustGate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaCourseComponentFreshnessTest {
    private fun fix(positionAt:Long,sogAt:Long,cogAt:Long)=NavigationFix(
        latitude=-36.8,
        longitude=174.8,
        receivedElapsedRealtime=positionAt,
        sogKnots=2.4,
        cogTrueDegrees=85.0,
        sogReceivedElapsedRealtime=sogAt,
        cogReceivedElapsedRealtime=cogAt,
        positionProvider=PositionProvider.NMEA,
        sourceSentence="GGA_WITH_HELD_RMC_COMPONENTS",
        valid=true,
    )

    @Test fun heldComponentsCanEstablishTrustOnlyWhileBothRemainFresh(){
        val gate=NmeaCourseTrustGate()
        assertNull(gate.update(fix(1_000,1_000,1_000),1_000))
        assertNotNull(gate.update(fix(3_000,1_000,1_000),3_000))
    }

    @Test fun freshCogCannotKeepAnOldSogTrusted(){
        val gate=NmeaCourseTrustGate()
        gate.update(fix(1_000,1_000,1_000),1_000)
        assertNotNull(gate.update(fix(3_000,1_000,3_000),3_000))
        assertNull(gate.update(fix(4_001,1_000,4_001),4_001))
    }
}
