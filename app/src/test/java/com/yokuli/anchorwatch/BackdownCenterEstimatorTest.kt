package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.BackdownCenterEstimator
import com.yokuli.anchorwatch.domain.model.NavigationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdownCenterEstimatorTest {
    private fun fix(northMeters:Double,eastMeters:Double=0.0,time:Long)=NavigationFix(northMeters/110_540.0,eastMeters/111_320.0,receivedElapsedRealtime=time,sourceSentence="test",valid=true)

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

    @Test fun gpsOnlyResolutionWaitsForAFullFifteenMinuteEvidenceWindow(){
        val stable=(0..10).map{index->fix(if(index%2==0)0.2 else -0.2,time=index*1_000L)}
        val swings=(11..960).map{index->
            val elapsed=index-11
            val angle=Math.toRadians(110.0+110.0*kotlin.math.sin(2.0*Math.PI*elapsed/180.0))
            val radius=25.0+.35*kotlin.math.sin(elapsed*.37)
            fix(radius*kotlin.math.cos(angle),radius*kotlin.math.sin(angle),index*1_000L)
        }
        val estimate=BackdownCenterEstimator().estimate(stable+swings,40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.HIGH,estimate.confidence)
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
            BackdownCenterEstimator.Sample(latitude=25.0*kotlin.math.cos(angle)/110_540.0,longitude=25.0*kotlin.math.sin(angle)/111_320.0,timestamp=index*1_000L,hdop=.8,headingTrueDegrees=heading,sogKnots=.15,windDirectionTrueDegrees=(heading+12.0)%360.0,apparentWindAngleDegrees=12.5,trueWindAngleDegrees=12.0,trueWindSpeedKnots=12.0,apparentWindSpeedKnots=12.4)
        }
        val estimate=BackdownCenterEstimator().estimateSamples(stable+swings,40.0)!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.HIGH,estimate.confidence)
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
        assertNull(BackdownCenterEstimator().estimate((0..20).map{fix(0.2,time=it*1_000L)}))
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
}
