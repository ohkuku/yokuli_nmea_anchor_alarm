package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorRangeCalculator
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorRangeCalculatorTest {
    @Test fun advancedRangeUsesTautRodeGeometryAndMargins() {
        val result = AnchorRangeCalculator.advanced(8.5, 40.0, 10.0, AnchorPlacementMode.CENTER_DROP, AnchorSafetyPreset.BALANCED)!!
        assertEquals(10.0, result.gpsMarginMeters, 0.01)
        assertTrue(result.radiusMeters in 58.0..59.0)
        assertEquals(0.0, result.learningMarginMeters, 0.01)
    }

    @Test fun backdownAddsExplicitLearningUncertainty() {
        val centre = AnchorRangeCalculator.advanced(8.5, 40.0, 10.0, AnchorPlacementMode.CENTER_DROP, AnchorSafetyPreset.BALANCED)!!
        val backdown = AnchorRangeCalculator.advanced(8.5, 40.0, 10.0, AnchorPlacementMode.BACKDOWN, AnchorSafetyPreset.BALANCED)!!
        assertEquals(10.0, backdown.radiusMeters - centre.radiusMeters, 0.01)
    }

    @Test fun rejectsRodeShorterThanVerticalDrop() {
        assertNull(AnchorRangeCalculator.advanced(20.0, 15.0, 8.0, AnchorPlacementMode.CENTER_DROP, AnchorSafetyPreset.STRICT))
    }
}
