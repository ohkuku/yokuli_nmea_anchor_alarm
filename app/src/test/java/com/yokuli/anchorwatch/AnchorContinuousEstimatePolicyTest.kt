package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorContinuousEstimatePolicy
import org.junit.Assert.*
import org.junit.Test

class AnchorContinuousEstimatePolicyTest{
    @Test fun resolvedCentreNeverHidesAReadyTrackEstimate(){
        val decision=AnchorContinuousEstimatePolicy.evaluate(true,false,false,true,false,false,false)
        assertTrue(decision.canCompareAndAdopt);assertFalse("Small shifts remain manually comparable but do not nag",decision.notify)
    }

    @Test fun meaningfulLaterEstimateCanNotifyWithoutAutomaticAdoption(){
        val decision=AnchorContinuousEstimatePolicy.evaluate(true,false,false,true,false,true,false)
        assertTrue(decision.canCompareAndAdopt);assertTrue(decision.notify)
    }

    @Test fun alarmOrPersistentDragSuppressesCentreAdoption(){
        assertFalse(AnchorContinuousEstimatePolicy.evaluate(true,true,false,true,false,true,true).canCompareAndAdopt)
        assertFalse(AnchorContinuousEstimatePolicy.evaluate(true,false,true,true,false,true,true).canCompareAndAdopt)
    }
}
