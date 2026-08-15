package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.BackdownCenterEstimator
import com.yokuli.anchorwatch.domain.model.NavigationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdownCenterEstimatorTest {
    private fun fix(northMeters:Double,time:Long)=NavigationFix(northMeters/110_540.0,0.0,receivedElapsedRealtime=time,sourceSentence="test",valid=true)

    @Test fun resolvesTheStableDropClusterAfterSustainedBackdown(){
        val stable=(0..8).map{index->fix(if(index%2==0)0.3 else -0.3,index*1_000L)}
        val moving=(9..29).map{index->fix((index-8).toDouble(),index*1_000L)}
        val fixes=stable+moving
        val estimate=BackdownCenterEstimator().estimate(fixes)!!
        assertTrue(kotlin.math.abs(estimate.latitude) < 3.0/110_540.0)
        assertEquals(21.0,estimate.distanceMeters,2.0)
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.HIGH,estimate.confidence)
        assertTrue(estimate.uncertaintyRadiusMeters in 5.0..20.0)
    }

    @Test fun provisionalEstimateExistsImmediatelyAndTightensWithoutResolvingEarly(){
        val estimator=BackdownCenterEstimator()
        val first=estimator.provisionalEstimate(listOf(fix(0.0,0)).map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)})!!
        val stable=(0..7).map{index->fix(if(index%2==0)0.3 else -0.3,index*1_000L)}
        val later=estimator.provisionalEstimate(stable.map{BackdownCenterEstimator.Sample(it.latitude,it.longitude,it.receivedElapsedRealtime,it.hdop)})!!
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.LOW,first.confidence)
        assertEquals(com.yokuli.anchorwatch.domain.model.Confidence.MEDIUM,later.confidence)
        assertTrue(later.uncertaintyRadiusMeters<first.uncertaintyRadiusMeters)
        assertNull(estimator.estimate(stable))
    }

    @Test fun rejectsAStationaryTrack(){
        assertNull(BackdownCenterEstimator().estimate((0..20).map{fix(0.2,it*1_000L)}))
    }

    @Test fun doesNotGuessFromTooFewSamples(){
        val stable=(0..5).map{index->fix(0.0,index*1_000L)}
        val moving=(6..10).map{index->fix((index-5)*4.0,index*1_000L)}
        assertNull(BackdownCenterEstimator().estimate(stable+moving))
    }

    @Test fun rejectsAStartThatWasAlreadyMoving(){
        assertNull(BackdownCenterEstimator().estimate((0..25).map{index->fix(index*4.0,index*1_000L)}))
    }
}
