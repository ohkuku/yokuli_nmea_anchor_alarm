package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.BackdownCenterEstimator
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdownCenterEstimatorTest {
    private fun fix(northMeters:Double,eastMeters:Double=0.0,time:Long)=NavigationFix(northMeters/110_540.0,eastMeters/111_320.0,receivedElapsedRealtime=time,hdop=.8,sourceSentence="test",valid=true)
    private fun gpsOnlySwing(lastSecond:Int,radiusMeters:Double=25.0)=(0..lastSecond).map{second->
        val angle=Math.toRadians(110.0+110.0*kotlin.math.sin(2.0*Math.PI*second/180.0))
        val radius=radiusMeters+.35*kotlin.math.sin(second*.37)
        fix(radius*kotlin.math.cos(angle),radius*kotlin.math.sin(angle),second*1_000L)
    }

    @Test fun aShortSingleArcStillDoesNotResolve(){
        val stable=(0..8).map{index->fix(if(index%2==0)0.3 else -0.3,time=index*1_000L)}
        val swing=(9..70).map{index->val angle=Math.toRadians(180.0-(index-9)*120.0/61.0);fix(25.0*kotlin.math.cos(angle),25.0*kotlin.math.sin(angle),index*1_000L)}
        val fixes=stable+swing
        val estimate=BackdownCenterEstimator().estimate(fixes,40.0)!!
        assertTrue(kotlin.math.abs(estimate.latitude) < 3.0/110_540.0)
        assertEquals(25.0,estimate.distanceMeters,2.0)
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.MEDIUM,estimate.confidence)
        assertTrue(estimate.angularCoverageDegrees>=80.0)
        assertTrue(estimate.angularSectorCount>=4)
        assertTrue(estimate.uncertaintyRadiusMeters>=25.0)
    }

    @Test fun gpsOnlyCannotResolveBeforeFifteenMinutes(){
        val estimate=BackdownCenterEstimator().estimate(gpsOnlySwing(899),40.0)!!
        assertTrue(estimate.debugSummary(),estimate.confidence!=com.yokuli.anchorwatch.domain.model.Confidence.HIGH)
    }

    @Test fun gpsOnlyCanResolveAfterFifteenMinutesWithFullGeometry(){
        val estimate=BackdownCenterEstimator().estimate(gpsOnlySwing(900),40.0)!!
        assertEquals(estimate.debugSummary(),com.yokuli.anchorwatch.domain.model.Confidence.HIGH,estimate.confidence)
        assertTrue(estimate.debugSummary(),estimate.radialObservable)
        assertEquals(AnchorCentreObservabilityReason.OBSERVABLE,estimate.observabilityReason)
        assertTrue(kotlin.math.abs(estimate.latitude)<3.0/110_540.0)
        assertTrue(kotlin.math.abs(estimate.longitude)<3.0/111_320.0)
        assertTrue(estimate.angularCoverageDegrees>=200.0)
        assertTrue(estimate.angularSectorCount>=8)
        assertTrue(estimate.uncertaintyRadiusMeters<=10.0)
    }

    @Test fun repeatedlyMatchedPhysicalAndWindHeadingsCanResolveAfterFiveMinutes(){
        val stable=(0..10).map{index->BackdownCenterEstimator.Sample(0.0,0.0,index*1_000L,.8)}
        val swings=(11..360).map{index->
            val angles=listOf(0.0,30.0,60.0,90.0,120.0,150.0,180.0,210.0,220.0,210.0,180.0,150.0,120.0,90.0,60.0,30.0,0.0,0.0)
            val angleDegrees=angles[((index/10)%angles.size)]
            val angle=Math.toRadians(angleDegrees)
            val heading=(angleDegrees+180.0)%360.0
            BackdownCenterEstimator.Sample(latitude=25.0*kotlin.math.cos(angle)/110_540.0,longitude=25.0*kotlin.math.sin(angle)/111_320.0,timestamp=index*1_000L,hdop=.8,headingTrueDegrees=heading,sogKnots=.15,windDirectionTrueDegrees=(heading+12.0)%360.0,apparentWindAngleDegrees=12.5,trueWindAngleDegrees=12.0,trueWindSpeedKnots=12.0,apparentWindSpeedKnots=12.4,headingSource=HeadingSource.NMEA_PHYSICAL,headingQuality=HeadingQuality.STABLE)
        }
        val estimate=BackdownCenterEstimator().estimateSamples(stable+swings,40.0)!!
        assertEquals("$estimate",com.yokuli.anchorwatch.domain.model.Confidence.HIGH,estimate.confidence)
    }

    @Test fun stableButGeometricallyWrongWindEvidenceCannotAccelerateResolution(){
        val samples=(0..360).map{index->
            val angles=listOf(0.0,30.0,60.0,90.0,120.0,150.0,180.0,210.0,220.0,210.0,180.0,150.0,120.0,90.0,60.0,30.0,0.0,0.0);val angleDegrees=angles[(index/10)%angles.size];val angle=Math.toRadians(angleDegrees);val wrongHeading=(angleDegrees+90.0)%360.0
            BackdownCenterEstimator.Sample(latitude=25.0*kotlin.math.cos(angle)/110_540.0,longitude=25.0*kotlin.math.sin(angle)/111_320.0,timestamp=index*1_000L,hdop=.8,sogKnots=.1,windDirectionTrueDegrees=(wrongHeading+10.0)%360.0,apparentWindAngleDegrees=10.0,trueWindAngleDegrees=10.0,trueWindSpeedKnots=14.0,apparentWindSpeedKnots=14.0)
        }
        val estimate=BackdownCenterEstimator().estimateSamples(samples,40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.MEDIUM,estimate.confidence)
    }

    @Test fun aLongStraightBackdownNeverResolvesTheCentre(){
        val stable=(0..8).map{index->fix(if(index%2==0)0.2 else -0.2,time=index*1_000L)}
        val straight=(9..90).map{index->fix((index-8).coerceAtMost(30).toDouble(),time=index*1_000L)}
        val estimate=BackdownCenterEstimator().estimate(stable+straight,40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.MEDIUM,estimate.confidence)
        assertTrue(estimate.angularSectorCount<4)
    }

    @Test fun provisionalEstimateStartsAtTheHorizontalRodeInsteadOfGpsScatter(){
        val estimator=BackdownCenterEstimator()
        val first=estimator.provisionalEstimate(listOf(fix(0.0,time=0)).map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)},40.0)!!
        val stable=(0..7).map{index->fix(if(index%2==0)0.3 else -0.3,time=index*1_000L)}
        val later=estimator.provisionalEstimate(stable.map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)},40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.LOW,first.confidence)
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.LOW,later.confidence)
        assertTrue(first.uncertaintyRadiusMeters>=35.0)
        assertTrue(later.uncertaintyRadiusMeters>=first.uncertaintyRadiusMeters*.8)
        assertNull(estimator.estimate(stable))
    }

    @Test fun straightBackdownNarrowsTheRegionButCannotResolveIt(){
        val estimator=BackdownCenterEstimator();val stable=(0..8).map{fix(0.0,time=it*1_000L)}
        val line=(9..70).map{index->fix((index-8).coerceAtMost(35).toDouble(),time=index*1_000L)}
        val initial=estimator.provisionalEstimate(stable.take(1).map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)},40.0)!!
        val later=estimator.provisionalEstimate((stable+line).map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)},40.0)!!
        assertTrue(later.uncertaintyRadiusMeters<initial.uncertaintyRadiusMeters)
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.LOW,later.confidence)
    }

    @Test fun rejectsAStationaryTrack(){
        val estimate=BackdownCenterEstimator().estimate((0..20).map{fix(0.2,time=it*1_000L)},40.0)!!
        assertTrue(!estimate.radialObservable)
        assertTrue(estimate.confidence!=com.yokuli.anchorwatch.domain.model.Confidence.HIGH)
    }

    @Test fun doesNotGuessFromTooFewSamples(){
        val stable=(0..5).map{index->fix(0.0,time=index*1_000L)}
        val moving=(6..10).map{index->fix((index-5)*4.0,time=index*1_000L)}
        assertNull(BackdownCenterEstimator().estimate(stable+moving))
    }

    @Test fun anImmediatelyMovingStartStillCannotResolveFromOneLine(){
        val estimate=BackdownCenterEstimator().estimate((0..70).map{index->fix(index.coerceAtMost(35).toDouble(),time=index*1_000L)},40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.MEDIUM,estimate.confidence)
    }

    @Test fun pausedWallClockGapDoesNotCountAsGpsOnlyLearningTime(){
        val fixes=(0..700).map{index->
            val angle=Math.toRadians((index*7.0)%360.0)
            val timestamp=index*1_000L+if(index>350)30*60_000L else 0L
            fix(25.0*kotlin.math.cos(angle),25.0*kotlin.math.sin(angle),timestamp)
        }
        val estimate=BackdownCenterEstimator().estimate(fixes,40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.MEDIUM,estimate.confidence)
    }

    @Test fun lateStartDoesNotBiasProvisionalCentreTowardFirstFix(){
        val first=fix(42.0,0.0,0L)
        val swing=(1..240).map{index->
            val angle=Math.toRadians(105.0+115.0*kotlin.math.sin(2.0*Math.PI*index/180.0))
            val radius=27.0+.5*kotlin.math.sin(index*.31)
            fix(radius*kotlin.math.cos(angle),radius*kotlin.math.sin(angle),index*1_000L)
        }
        val estimate=BackdownCenterEstimator().provisionalEstimate((listOf(first)+swing).map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)},45.0)!!
        assertTrue(estimate.debugSummary(),kotlin.math.abs(estimate.latitude)<8.0/110_540.0)
        assertTrue(estimate.debugSummary(),kotlin.math.abs(estimate.longitude)<8.0/111_320.0)
    }

    @Test fun lateStartCanBecomeHighAfterFullIndependentEvidence(){
        val first=fix(42.0,0.0,0L)
        val swing=(1..900).map{index->
            val angle=Math.toRadians(105.0+115.0*kotlin.math.sin(2.0*Math.PI*index/180.0))
            val radius=27.0+.5*kotlin.math.sin(index*.31)
            fix(radius*kotlin.math.cos(angle),radius*kotlin.math.sin(angle),index*1_000L)
        }
        val estimate=BackdownCenterEstimator().estimate(listOf(first)+swing,45.0)!!
        assertEquals(estimate.debugSummary(),com.yokuli.anchorwatch.domain.model.Confidence.HIGH,estimate.confidence)
        assertTrue(kotlin.math.abs(estimate.latitude)<3.0/110_540.0)
        assertTrue(kotlin.math.abs(estimate.longitude)<3.0/111_320.0)
    }

    private fun localCircle(radius:Double=5.0,lastSecond:Int=1_000,headingSource:HeadingSource=HeadingSource.NONE)=(0..lastSecond).map{second->
        val angle=2.0*Math.PI*second/120.0
        val heading=(Math.toDegrees(angle)+180.0)%360.0
        BackdownCenterEstimator.Sample(latitude=radius*kotlin.math.cos(angle)/110_540.0,longitude=radius*kotlin.math.sin(angle)/111_320.0,timestamp=second*1_000L,hdop=.8,headingTrueDegrees=heading.takeIf{headingSource!=HeadingSource.NONE},headingSource=headingSource,headingQuality=if(headingSource==HeadingSource.NONE)HeadingQuality.UNAVAILABLE else HeadingQuality.STABLE,sogKnots=.1)
    }

    @Test fun fortyMetreRodeRejectsAThousandSamplesOnFiveMetreLocalCircle(){
        val estimate=BackdownCenterEstimator().estimateSamples(localCircle(),40.0)!!
        assertTrue(estimate.debugSummary(),!estimate.radialObservable)
        assertTrue(estimate.observabilityReason in setOf(AnchorCentreObservabilityReason.TRACK_TOO_SMALL,AnchorCentreObservabilityReason.FIT_RADIUS_TOO_SMALL))
        assertTrue(estimate.confidence!=com.yokuli.anchorwatch.domain.model.Confidence.HIGH)
    }

    @Test fun perfectPhoneHeadingCannotMakeLocalCircleObservable(){
        val estimate=BackdownCenterEstimator().estimateSamples(localCircle(headingSource=HeadingSource.PHONE),40.0)!!
        assertEquals(0,estimate.nmeaPhysicalHeadingEvidenceCount)
        assertTrue(estimate.phoneHeadingEvidenceCount>0)
        assertTrue(!estimate.radialObservable)
        assertTrue(estimate.confidence!=com.yokuli.anchorwatch.domain.model.Confidence.HIGH)
    }

    @Test fun physicalHeadingAndWindCannotBypassRadialGate(){
        val samples=localCircle(headingSource=HeadingSource.NMEA_PHYSICAL).map{sample->sample.copy(windDirectionTrueDegrees=((sample.headingTrueDegrees?:0.0)+12.0)%360.0,trueWindAngleDegrees=12.0,apparentWindAngleDegrees=12.0,trueWindSpeedKnots=14.0,apparentWindSpeedKnots=14.0)}
        val estimate=BackdownCenterEstimator().estimateSamples(samples,40.0)!!
        assertTrue(estimate.nmeaPhysicalHeadingEvidenceCount>0)
        assertTrue(!estimate.radialObservable)
        assertTrue(estimate.confidence!=com.yokuli.anchorwatch.domain.model.Confidence.HIGH)
    }

    @Test fun gpsJitterNeverBecomesObservable(){
        val samples=(0..1_000).map{second->val north=3.0*kotlin.math.sin(second*.71);val east=3.0*kotlin.math.cos(second*.93);BackdownCenterEstimator.Sample(north/110_540.0,east/111_320.0,second*1_000L,hdop=1.0)}
        val estimate=BackdownCenterEstimator().estimateSamples(samples,40.0)!!
        assertTrue(!estimate.radialObservable)
        assertTrue(estimate.confidence!=com.yokuli.anchorwatch.domain.model.Confidence.HIGH)
    }
}
