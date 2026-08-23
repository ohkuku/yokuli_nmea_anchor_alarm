package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.nmea.NmeaUpdate
import com.yokuli.anchorwatch.data.nmea.WindSnapshotAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindSnapshotAccumulatorTest {
    @Test fun componentsWithinSkewBecomeOneEvidenceSnapshot() {
        val accumulator = WindSnapshotAccumulator(maximumAgeMillis = 10_000, maximumSkewMillis = 2_500)
        accumulator.update(NmeaUpdate(trueWindDirection = 210.0, trueWindSpeedKnots = 13.0, type = "MWD"), 1_000)
        accumulator.update(NmeaUpdate(apparentWindAngle = 12.0, apparentWindSpeedKnots = 13.4, type = "MWV"), 3_000)

        val snapshot = accumulator.snapshot(3_100)
        assertEquals(210.0, snapshot.trueDirectionDegrees!!, 0.0)
        assertEquals(12.0, snapshot.apparentAngleDegrees!!, 0.0)
        assertEquals(13.4, snapshot.apparentSpeedKnots!!, 0.0)
        assertTrue(snapshot.sampleSequence != null)
    }

    @Test fun temporallyUnrelatedSentencesAreNeverMerged() {
        val accumulator = WindSnapshotAccumulator(maximumAgeMillis = 10_000, maximumSkewMillis = 2_500)
        accumulator.update(NmeaUpdate(trueWindDirection = 210.0, trueWindSpeedKnots = 13.0, type = "MWD"), 1_000)
        accumulator.update(NmeaUpdate(apparentWindAngle = 12.0, apparentWindSpeedKnots = 13.4, type = "MWV"), 4_000)

        val snapshot = accumulator.snapshot(4_100)
        assertNull(snapshot.trueDirectionDegrees)
        assertNull(snapshot.trueSpeedKnots)
        assertEquals(12.0, snapshot.apparentAngleDegrees!!, 0.0)
        assertEquals(13.4, snapshot.apparentSpeedKnots!!, 0.0)
    }

    @Test fun staleWindIsRemovedFromPositionFix() {
        val accumulator = WindSnapshotAccumulator(maximumAgeMillis = 10_000, maximumSkewMillis = 2_500)
        accumulator.update(NmeaUpdate(trueWindDirection = 90.0, trueWindSpeedKnots = 8.0, type = "MWD"), 1_000)

        val snapshot = accumulator.snapshot(11_001)
        assertNull(snapshot.trueDirectionDegrees)
        assertNull(snapshot.trueSpeedKnots)
        assertNull(snapshot.sampleSequence)
    }

    @Test fun omittedFieldsDoNotEraseLastObservationOrRefreshItsClock() {
        val accumulator = WindSnapshotAccumulator(maximumAgeMillis = 10_000, maximumSkewMillis = 2_500)
        accumulator.update(NmeaUpdate(trueWindDirection = 135.0, trueWindSpeedKnots = 9.5, type = "MWD"), 1_000)

        // A valid but unrelated/partial sentence means "no update". It must
        // neither clear the previous measurement nor make that value younger.
        accumulator.update(NmeaUpdate(type = "MWD"), 8_000)
        val held = accumulator.snapshot(8_100)
        assertEquals(135.0, held.trueDirectionDegrees!!, 0.0)
        assertEquals(9.5, held.trueSpeedKnots!!, 0.0)

        val stale = accumulator.snapshot(11_001)
        assertNull(stale.trueDirectionDegrees)
        assertNull(stale.trueSpeedKnots)
    }
}
