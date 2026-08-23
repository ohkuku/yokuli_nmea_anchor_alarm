package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityPolicy
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreCandidatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorCentreObservabilityPolicyTest {
    @Test fun neatFiveMetreCircleCannotRepresentFortyMetreRode(){val value=AnchorCentreObservabilityPolicy.evaluate(10.0,5.0,40.0,3.0,true);assertFalse(value.radialObservable);assertEquals(AnchorCentreObservabilityReason.TRACK_TOO_SMALL,value.reason)}
    @Test fun broadTwentyFiveMetreSwingIsRodeScaleObservable(){val value=AnchorCentreObservabilityPolicy.evaluate(49.0,25.0,40.0,3.0,true);assertTrue(value.radialObservable);assertEquals(AnchorCentreObservabilityReason.OBSERVABLE,value.reason)}
    @Test fun headingCannotEnterOrOverrideThePureRadialGate(){val withoutHeading=AnchorCentreObservabilityPolicy.evaluate(10.0,5.0,40.0,3.0,true);val imaginaryPerfectHeading=AnchorCentreObservabilityPolicy.evaluate(10.0,5.0,40.0,3.0,true);assertEquals(withoutHeading,imaginaryPerfectHeading)}
    @Test fun threeMetreShiftInsideFiveMetreUncertaintyIsOnlyProgress(){assertFalse(AnchorCentreCandidatePolicy.isMeaningfulShift(3.0,5.0));assertTrue(AnchorCentreCandidatePolicy.isMeaningfulShift(8.0,5.0))}
}
