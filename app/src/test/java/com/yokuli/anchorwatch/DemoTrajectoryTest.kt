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
    @Test fun everyScenarioStartsExactlyAtTheFreshSystemOrigin() {
        DemoScenario.entries.forEach { scenario ->
            val point=DemoTrajectory.point(0,AnchorPlacementMode.BACKDOWN,scenario,70.0,1,1234)
            assertTrue(hypot(point.northMeters,point.eastMeters)<.001)
        }
    }

    @Test fun centerDropStartsSwingingWhileBackdownKeepsAStableDropCluster() {
        val center=DemoTrajectory.point(6_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.SAFE_SWING,70.0,1)
        val backdown=DemoTrajectory.point(6_000,AnchorPlacementMode.BACKDOWN,DemoScenario.SAFE_SWING,70.0,1)
        assertTrue(hypot(center.northMeters,center.eastMeters)>.5)
        assertTrue(hypot(backdown.northMeters,backdown.eastMeters)<1.0)
    }

    @Test fun backdownThenMovesInALineBeforeSwinging() {
        val point=DemoTrajectory.point(24_000,AnchorPlacementMode.BACKDOWN,DemoScenario.SAFE_SWING,70.0,1)
        assertTrue(hypot(point.northMeters,point.eastMeters)>5.0)
    }

    @Test fun anchorDragEventuallyCrossesTheConfiguredBoundary() {
        val point=DemoTrajectory.point(90_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.ANCHOR_DRAG,50.0,1)
        assertTrue(hypot(point.northMeters,point.eastMeters)>50.0)
    }

    @Test fun gpsDropoutHasASeededOutageAndRecovery() {
        val states=(0..120).map{DemoTrajectory.point(it*1_000L,AnchorPlacementMode.CENTER_DROP,DemoScenario.GPS_DROPOUT,50.0,1,44).signalAvailable}
        assertTrue(states.any{!it});assertTrue(states.drop(states.indexOfFirst{!it}+1).any{it})
    }

    @Test fun windShiftNeverTeleportsButCannotResolveBeforeFiveMinutes() {
        val points=(0..240).map{second->DemoTrajectory.point(second*1_000L,AnchorPlacementMode.BACKDOWN,DemoScenario.WIND_SHIFT,70.0,1,77)}
        assertTrue(points.zipWithNext().all{(a,b)->hypot(b.northMeters-a.northMeters,b.eastMeters-a.eastMeters)<5.0})
        val samples=points.mapIndexed{second,point->
            BackdownCenterEstimator.Sample(point.northMeters/110_540.0,point.eastMeters/111_320.0,second*1_000L,.8)
        }
        val estimate=BackdownCenterEstimator().estimateSamples(samples,45.0)!!
        assertTrue(estimate.confidence!=Confidence.HIGH)
    }

    @Test fun differentSessionsKeepTheScenarioButChangeItsNaturalMotion() {
        val first=DemoTrajectory.point(100_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.SAFE_SWING,70.0,1,10)
        val second=DemoTrajectory.point(100_000,AnchorPlacementMode.CENTER_DROP,DemoScenario.SAFE_SWING,70.0,1,11)
        assertTrue(hypot(first.northMeters-second.northMeters,first.eastMeters-second.eastMeters)>1.0)
    }
}
