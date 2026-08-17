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
}
