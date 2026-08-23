package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.PositionIntegrityFilter
import com.yokuli.anchorwatch.location.PositionIntegrityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun missingReportedQualityIsAcceptedButNeverMarkedTrusted() {
        val result=PositionIntegrityFilter().evaluate(fix(0.0,0).copy(horizontalAccuracyMeters=null,hdop=null)) as PositionIntegrityResult.Accepted
        assertEquals(FixTrust.DEGRADED,result.fixes.single().trust)
        assertEquals("QUALITY_NOT_REPORTED",result.fixes.single().reason)
    }

    @Test fun heldNmeaQualityRemainsDiagnosticButCannotMasqueradeAsFreshEvidence() {
        val result=PositionIntegrityFilter().evaluate(fix(0.0,10_000).copy(
            positionProvider=PositionProvider.NMEA,
            hdop=.8,
            fixQuality=1,
            satellites=12,
            hdopReceivedElapsedRealtime=1_000,
            fixQualityReceivedElapsedRealtime=1_000,
            satellitesReceivedElapsedRealtime=1_000,
            horizontalAccuracyMeters=null,
        )) as PositionIntegrityResult.Accepted
        assertNull(result.fixes.single().fix.hdop)
        assertNull(result.fixes.single().fix.fixQuality)
        assertNull(result.fixes.single().fix.satellites)
        assertEquals(FixTrust.DEGRADED,result.fixes.single().trust)
    }

    @Test fun freshNmeaQualityStillParticipatesInIntegrity() {
        val result=PositionIntegrityFilter().evaluate(fix(0.0,10_000).copy(
            positionProvider=PositionProvider.NMEA,
            hdop=.8,
            fixQuality=1,
            satellites=12,
            hdopReceivedElapsedRealtime=9_500,
            fixQualityReceivedElapsedRealtime=9_500,
            satellitesReceivedElapsedRealtime=9_500,
            horizontalAccuracyMeters=2.5,
        )) as PositionIntegrityResult.Accepted
        assertEquals(.8,result.fixes.single().fix.hdop!!,.001)
        assertEquals(FixTrust.TRUSTED,result.fixes.single().trust)
    }

    @Test fun heldCourseAndHeadingRemainDiagnosticButCannotBecomeCurrentAcceptedEvidence() {
        val result=PositionIntegrityFilter().evaluate(fix(0.0,20_001).copy(
            positionProvider=PositionProvider.NMEA,
            sogKnots=2.2,
            cogTrueDegrees=91.0,
            headingTrueDegrees=88.0,
            sogReceivedElapsedRealtime=10_000,
            cogReceivedElapsedRealtime=10_000,
            headingReceivedElapsedRealtime=10_000,
            headingSource=HeadingSource.NMEA_PHYSICAL,
            headingQuality=HeadingQuality.STABLE,
        )) as PositionIntegrityResult.Accepted
        val accepted=result.fixes.single().fix
        assertNull(accepted.sogKnots)
        assertNull(accepted.cogTrueDegrees)
        assertNull(accepted.headingTrueDegrees)
        assertEquals(HeadingSource.NONE,accepted.headingSource)
        assertEquals(HeadingQuality.UNAVAILABLE,accepted.headingQuality)
    }

    private fun fix(northMeters:Double,time:Long):NavigationFix {
        val coordinate=AnchorGeometry.project(-36.8485,174.7633,0.0,northMeters)
        return NavigationFix(coordinate.first,coordinate.second,receivedElapsedRealtime=time,horizontalAccuracyMeters=3.0,positionProvider=PositionProvider.ANDROID_GNSS,sourceSentence="test",valid=true)
    }
}
