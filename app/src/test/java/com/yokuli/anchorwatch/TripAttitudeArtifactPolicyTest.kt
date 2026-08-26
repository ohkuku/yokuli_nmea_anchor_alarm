package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.report.TripAttitudeArtifactPolicy
import com.yokuli.anchorwatch.domain.report.TripAttitudeFilterPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAttitudeArtifactPolicyTest{
    private fun point(time:Long,heel:Double,pitch:Double=0.0,rate:Double=2.0,cog:Double=90.0,usable:Boolean=true)=TripAttitudeFilterPoint(time,heel,pitch,rate,rate,cog,true,usable)

    @Test fun isolatedFastSpikeBetweenAgreeingSamplesIsReportOnlyArtifact(){
        assertTrue(TripAttitudeArtifactPolicy.isShortHandlingArtifact(point(0,8.0),point(1_000,42.0,rate=50.0),point(2_000,9.0)))
    }

    @Test fun sustainedOrLinearHeelIsSailingEvidence(){
        assertFalse(TripAttitudeArtifactPolicy.isShortHandlingArtifact(point(0,8.0),point(1_000,18.0,rate=10.0),point(2_000,28.0)))
        assertFalse(TripAttitudeArtifactPolicy.isShortHandlingArtifact(point(0,24.0),point(1_000,27.0,rate=8.0),point(2_000,25.0)))
    }

    @Test fun gpsEvidencedTackProtectsAQuickAttitudeChange(){
        assertFalse(TripAttitudeArtifactPolicy.isShortHandlingArtifact(point(0,8.0,cog=40.0),point(1_000,35.0,rate=60.0,cog=80.0),point(2_000,9.0,cog=125.0)))
    }

    @Test fun pausedOrInvalidSampleIsNotInventedAsAnArtifact(){
        assertFalse(TripAttitudeArtifactPolicy.isShortHandlingArtifact(point(0,8.0),point(1_000,42.0,rate=50.0,usable=false),point(2_000,9.0)))
    }
}
