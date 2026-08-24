package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchorage.*
import org.junit.Assert.*
import org.junit.Test

class AnchorageIntelligenceTest {
    @Test fun conditionFitReportsCoverageWithoutInventingSafetyPercentage(){
        val result=AnchorageConditionFitEngine.evaluate(
            AnchorageForecastInput(315.0,22.0,28.0,45.0,0.8),
            listOf(AnchorageProtectionObservation(AnchorageProtectionMedium.WIND,AnchorageCompassSector.NW,AnchorageProtectionRating.GOOD,AnchorageInformationSource.USER)),
        )
        assertEquals(AnchorageProtectionRating.GOOD,result.windFit)
        assertEquals(AnchorageProtectionRating.UNKNOWN,result.swellFit)
        assertEquals(0.5,result.sourceCoverage,0.0)
    }

    @Test fun summaryKeepsCoverageAndDoesNotGeneralizeOneVisit(){
        val summary=PersonalAnchorageSummaryEngine.summarize(listOf(AnchorageVisitObservation(0,3_600_000,6.0,5.5,6.4,40.0,35.0,1,17.0,28.0,7.0,19.0,270.0)))
        assertEquals(1,summary.visitCount);assertTrue(summary.coverage.lowSample)
        assertEquals(5.5,summary.observedDepthMinMeters!!,0.0)
        assertEquals(AnchorageCompassSector.W,summary.windObservationGroups.single().sector)
    }
}
