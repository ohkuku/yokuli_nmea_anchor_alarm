package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorCentreEvidenceBaseline
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreEvidenceGrowthPolicy
import com.yokuli.anchorwatch.domain.model.BackdownAnchorEstimate
import com.yokuli.anchorwatch.domain.model.Confidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorCentreEvidenceGrowthPolicyTest {
    private val baseline=AnchorCentreEvidenceBaseline(1_000,210.0,10.0,3)
    private fun estimate(samples:Int=1_120,coverage:Double=210.0,span:Double=10.0,reversals:Int=3)=BackdownAnchorEstimate(0.0,0.0,5.0,30.0,Confidence.HIGH,samples,angularCoverageDegrees=coverage,swingReversalCount=reversals,trackDiameterMeters=span)
    @Test fun repeatedOldLocalGeometryCannotImmediatelyReturnCandidate(){assertFalse(AnchorCentreEvidenceGrowthPolicy.hasMeaningfulGrowth(baseline,estimate(),40.0))}
    @Test fun sampleCountWithoutGeometryGrowthIsNotEnough(){assertFalse(AnchorCentreEvidenceGrowthPolicy.hasMeaningfulGrowth(baseline,estimate(samples=2_000),40.0))}
    @Test fun newCoverageSpanOrReversalCanUnlockReanalysis(){assertTrue(AnchorCentreEvidenceGrowthPolicy.hasMeaningfulGrowth(baseline,estimate(coverage=236.0),40.0));assertTrue(AnchorCentreEvidenceGrowthPolicy.hasMeaningfulGrowth(baseline,estimate(span=16.1),40.0));assertTrue(AnchorCentreEvidenceGrowthPolicy.hasMeaningfulGrowth(baseline,estimate(reversals=4),40.0))}
}
