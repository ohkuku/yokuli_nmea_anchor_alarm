package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchor.CandidateCenterObservation
import com.yokuli.anchorwatch.domain.anchor.CandidateDriftDetector
import com.yokuli.anchorwatch.domain.anchor.CandidateDriftUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CandidateDriftDetectorTest {
    @Test fun sustainedDirectionalCentreMotionProducesOneAdvisory() {
        val detector = CandidateDriftDetector()
        val results = (0..4).map { index ->
            val point = AnchorGeometry.project(-36.8485, 174.7633, 70.0, index * 4.0)
            detector.add(CandidateCenterObservation(index * 120_000L, point.first, point.second, 3.0))
        }
        assertEquals(CandidateDriftUpdate.POSSIBLE_DRAG, results.last())
        val later = AnchorGeometry.project(-36.8485, 174.7633, 70.0, 20.0)
        assertEquals(CandidateDriftUpdate.RECORDED, detector.add(CandidateCenterObservation(600_000L, later.first, later.second, 3.0)))
    }

    @Test fun normalConvergenceAndSwingDoNotProduceTrendWarning() {
        val detector = CandidateDriftDetector()
        val results = listOf(0.0, 5.0, 2.0, 6.0, 1.0, 4.0).mapIndexed { index, eastMeters ->
            val point = AnchorGeometry.project(-36.8485, 174.7633, 90.0, eastMeters)
            detector.add(CandidateCenterObservation(index * 120_000L, point.first, point.second, 3.0))
        }
        assertEquals(false, results.contains(CandidateDriftUpdate.POSSIBLE_DRAG))
    }

    @Test fun persistedHistoryRoundTrips() {
        val original = CandidateCenterObservation(1234L, -36.8485, 174.7633, 4.2)
        assertEquals(original, CandidateCenterObservation.decode(original.encode()))
        assertNotNull(CandidateCenterObservation.decode(original.encode()))
    }
}
