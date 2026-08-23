package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.report.PointOfSail
import com.yokuli.anchorwatch.domain.report.SailingAnalyticsAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test

class SailingAnalyticsTest{
    @Test fun weightsValidIntervalsAndRejectsStoppedOrLongGapData(){
        val value=SailingAnalyticsAccumulator()
        value.add(0,45.0,4.0);value.add(1_000,45.0,4.0);value.add(2_000,90.0,.2);value.add(20_000,90.0,4.0);value.add(21_000,90.0,4.0)
        val result=value.summary()
        assertEquals(2_000,result.starboardTackMillis)
        assertEquals(1_000L,result.pointOfSailMillis[PointOfSail.CLOSE_HAULED])
        assertEquals(1_000L,result.pointOfSailMillis[PointOfSail.BEAM_REACH])
    }

    @Test fun requiresPersistentSideChangeAndClassifiesTackVersusGybe(){
        val tack=SailingAnalyticsAccumulator()
        tack.add(0,45.0,4.0);tack.add(1_000,46.0,4.0)
        tack.add(2_000,-44.0,4.0);tack.add(4_000,44.0,4.0) // jitter returns to the original side
        tack.add(5_000,-45.0,4.0);tack.add(8_000,-46.0,4.0);tack.add(10_000,-47.0,4.0)
        assertEquals(1,tack.summary().tackCount)
        assertEquals(0,tack.summary().gybeCount)

        val gybe=SailingAnalyticsAccumulator()
        gybe.add(0,160.0,5.0);gybe.add(1_000,158.0,5.0);gybe.add(2_000,-160.0,5.0);gybe.add(5_000,-158.0,5.0);gybe.add(7_000,-155.0,5.0)
        assertEquals(1,gybe.summary().gybeCount)
    }
}
