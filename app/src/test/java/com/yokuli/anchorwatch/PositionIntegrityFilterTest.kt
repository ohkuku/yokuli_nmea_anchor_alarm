package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.PositionIntegrityFilter
import com.yokuli.anchorwatch.location.PositionIntegrityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionIntegrityFilterTest {
    @Test fun singleEightyMetreSpikeReturnsToTrackAndNeverGetsAccepted() {
        val filter=PositionIntegrityFilter()
        assertTrue(filter.evaluate(fix(0.0,0)) is PositionIntegrityResult.Accepted)
        assertTrue(filter.evaluate(fix(80.0,1_000)) is PositionIntegrityResult.Quarantined)
        val recovered=filter.evaluate(fix(1.0,2_000)) as PositionIntegrityResult.Accepted
        assertEquals(1,recovered.fixes.size)
        assertEquals("GPS_SPIKE_CLEARED",recovered.fixes.single().reason)
    }

    @Test fun coherentLargeDisplacementIsReleasedFromItsFirstSuspiciousFix() {
        val filter=PositionIntegrityFilter()
        filter.evaluate(fix(0.0,0))
        assertTrue(filter.evaluate(fix(80.0,1_000)) is PositionIntegrityResult.Quarantined)
        assertTrue(filter.evaluate(fix(84.0,2_000)) is PositionIntegrityResult.Quarantined)
        val confirmed=filter.evaluate(fix(89.0,3_100)) as PositionIntegrityResult.Accepted
        assertEquals(3,confirmed.fixes.size)
        assertEquals(1_000,confirmed.fixes.first().fix.receivedElapsedRealtime)
        assertTrue(confirmed.fixes.all{it.wasQuarantined})
    }

    @Test fun coarseNetworkLocationNeverEntersSafetyChain() {
        val result=PositionIntegrityFilter().evaluate(fix(0.0,0).copy(positionProvider=PositionProvider.ANDROID_NETWORK,horizontalAccuracyMeters=120.0))
        assertTrue(result is PositionIntegrityResult.Rejected)
    }

    @Test fun sourceTimestampMovingBackwardsIsRejected() {
        val filter=PositionIntegrityFilter()
        assertTrue(filter.evaluate(fix(0.0,0).copy(timestampUtcMillis=10_000)) is PositionIntegrityResult.Accepted)
        val result=filter.evaluate(fix(1.0,1_000).copy(timestampUtcMillis=9_000))
        assertTrue(result is PositionIntegrityResult.Rejected)
        assertEquals("SOURCE_TIMESTAMP_MOVED_BACKWARDS",(result as PositionIntegrityResult.Rejected).reason)
    }

    private fun fix(northMeters:Double,time:Long):NavigationFix {
        val coordinate=AnchorGeometry.project(-36.8485,174.7633,0.0,northMeters)
        return NavigationFix(coordinate.first,coordinate.second,receivedElapsedRealtime=time,horizontalAccuracyMeters=3.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="test",valid=true)
    }
}
