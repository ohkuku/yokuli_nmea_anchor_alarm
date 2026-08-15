package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.Confidence
import com.yokuli.anchorwatch.domain.model.DemoScenario
import com.yokuli.anchorwatch.domain.anchor.BackdownCenterEstimator
import com.yokuli.anchorwatch.location.DemoTrajectory
import kotlin.math.hypot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTrajectoryTest {
    @Test fun centerDropStartsSwingingWhileBackdownKeepsAStableDropCluster() {
        val center=DemoTrajectory.point(6_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.SAFE_SWING,70.0,1)
        val backdown=DemoTrajectory.point(6_000,AnchorPlacementMode.BACKDOWN,DemoScenario.SAFE_SWING,70.0,1)
        assertTrue(hypot(center.northMeters,center.eastMeters)>5.0)
        assertTrue(hypot(backdown.northMeters,backdown.eastMeters)<1.0)
    }

    @Test fun backdownThenMovesInALineBeforeSwinging() {
        val point=DemoTrajectory.point(24_000,AnchorPlacementMode.BACKDOWN,DemoScenario.SAFE_SWING,70.0,1)
        assertTrue(point.northMeters < -12.0)
        assertTrue(kotlin.math.abs(point.eastMeters)<1.0)
    }

    @Test fun anchorDragEventuallyCrossesTheConfiguredBoundary() {
        val point=DemoTrajectory.point(90_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.ANCHOR_DRAG,50.0,1)
        assertTrue(hypot(point.northMeters,point.eastMeters)>50.0)
    }

    @Test fun gpsDropoutStopsPublishingAfterTwentyFiveSeconds() {
        assertTrue(DemoTrajectory.point(24_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.GPS_DROPOUT,50.0,1).signalAvailable)
        assertFalse(DemoTrajectory.point(25_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.GPS_DROPOUT,50.0,1).signalAvailable)
    }

    @Test fun backdownDemoProducesEnoughEvidenceForTheRealCentreEstimator() {
        val samples=(0..35).map{second->
            val point=DemoTrajectory.point(second*1_000L,AnchorPlacementMode.BACKDOWN,DemoScenario.SAFE_SWING,70.0,1)
            BackdownCenterEstimator.Sample(point.northMeters/110_540.0,point.eastMeters/111_320.0,second*1_000L,.8)
        }
        val estimate=BackdownCenterEstimator().estimateSamples(samples)!!
        assertTrue(estimate.confidence==Confidence.HIGH)
    }
}
