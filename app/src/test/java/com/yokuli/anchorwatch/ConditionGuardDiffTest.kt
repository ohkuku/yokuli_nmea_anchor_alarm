package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.domain.condition.hasMeaningfulDiff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionGuardDiffTest {
    private val saved = ConditionGuardConfig(
        depthGuardEnabled = true,
        shallowDepthAlarmMeters = 2.5,
        deepDepthAlarmMeters = 15.0,
        windGuardEnabled = true,
        windWarningKnots = 25.0,
        windAlarmKnots = 35.0,
        windShiftEnabled = true,
        windShiftThresholdDegrees = 70.0,
    )

    @Test fun unchangedValuesDoNotEnableSave() {
        assertFalse(saved.hasMeaningfulDiff(saved.copy()))
    }

    @Test fun aChangedThresholdEnablesSave() {
        assertTrue(saved.hasMeaningfulDiff(saved.copy(windAlarmKnots = 38.0)))
    }

    @Test fun validationEquivalentValuesDoNotCountAsAChange() {
        val invalidDeep = saved.copy(deepDepthAlarmMeters = 3.0)
        val noDeep = saved.copy(deepDepthAlarmMeters = null)
        assertFalse(invalidDeep.hasMeaningfulDiff(noDeep))
    }
}
