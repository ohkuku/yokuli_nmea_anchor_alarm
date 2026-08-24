package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.trip.TripSampleFreshness
import org.junit.Assert.*
import org.junit.Test

class TripSampleFreshnessTest{
    @Test fun apparentOnlyWindRemainsAvailableWithoutInventingTrueWind(){
        assertTrue(TripSampleFreshness.apparentWindAvailable(18.4,400))
        assertFalse(TripSampleFreshness.trueWindAvailable(null,null))
    }

    @Test fun degradedOrMountSuspectAttitudeIsStoredButExcludedFromExtrema(){
        assertFalse(TripSampleFreshness.attitudeUsable(100,"DEGRADED",false))
        assertFalse(TripSampleFreshness.attitudeUsable(100,"GOOD",true))
        assertTrue(TripSampleFreshness.attitudeUsable(100,"GOOD",false))
    }
}
