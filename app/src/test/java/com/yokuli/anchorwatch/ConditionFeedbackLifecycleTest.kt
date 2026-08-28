package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.condition.ConditionRuntimeSnapshot
import com.yokuli.anchorwatch.domain.condition.DepthGuardSnapshot
import com.yokuli.anchorwatch.domain.condition.DepthGuardStatus
import com.yokuli.anchorwatch.domain.condition.WindShiftGuardSnapshot
import com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus
import com.yokuli.anchorwatch.domain.condition.WindSpeedGuardSnapshot
import com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus
import com.yokuli.anchorwatch.runtime.ConditionFeedbackLifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionFeedbackLifecycleTest{
    @Test fun windLossCreatesOneEpisodeAndUnrelatedChangesDoNotRecreateIt(){
        val healthy=snapshot(wind=WindSpeedGuardStatus.MONITORING)
        val lost=snapshot(wind=WindSpeedGuardStatus.DATA_UNAVAILABLE)
        val entered=ConditionFeedbackLifecycle.between(healthy,lost)
        assertTrue(entered.windBecameUnavailable)
        assertFalse(entered.windRecovered)

        val depthChangedWhileStillLost=snapshot(
            depth=DepthGuardStatus.MONITORING,
            wind=WindSpeedGuardStatus.DATA_UNAVAILABLE,
        )
        val unchangedEpisode=ConditionFeedbackLifecycle.between(lost,depthChangedWhileStillLost)
        assertFalse(unchangedEpisode.windBecameUnavailable)
        assertFalse(unchangedEpisode.windRecovered)
    }

    @Test fun restoredWindOrDisablingTheGuardClearsTheLossEpisode(){
        val lost=snapshot(wind=WindSpeedGuardStatus.DATA_UNAVAILABLE)
        assertTrue(ConditionFeedbackLifecycle.between(lost,snapshot(wind=WindSpeedGuardStatus.MONITORING)).windRecovered)
        assertTrue(ConditionFeedbackLifecycle.between(lost,snapshot(wind=WindSpeedGuardStatus.OFF)).windRecovered)
    }

    @Test fun eitherWindGuardOwnsTheSharedDataLossEpisode(){
        val speedLost=snapshot(
            wind=WindSpeedGuardStatus.DATA_UNAVAILABLE,
            shift=WindShiftGuardStatus.MONITORING,
        )
        val shiftStillLost=snapshot(
            wind=WindSpeedGuardStatus.MONITORING,
            shift=WindShiftGuardStatus.DATA_UNAVAILABLE,
        )
        val handoff=ConditionFeedbackLifecycle.between(speedLost,shiftStillLost)
        assertFalse(handoff.windBecameUnavailable)
        assertFalse(handoff.windRecovered)
        assertTrue(ConditionFeedbackLifecycle.between(shiftStillLost,snapshot()).windRecovered)
    }

    @Test fun warningIsAnnouncedOnlyOnEntryAndNeverOverAnUnavailableWindSource(){
        val monitoring=snapshot(wind=WindSpeedGuardStatus.MONITORING)
        val warning=snapshot(wind=WindSpeedGuardStatus.WARNING)
        assertTrue(ConditionFeedbackLifecycle.between(monitoring,warning).windWarningStarted)
        assertFalse(ConditionFeedbackLifecycle.between(warning,warning).windWarningStarted)
        assertFalse(ConditionFeedbackLifecycle.between(monitoring,snapshot(
            wind=WindSpeedGuardStatus.WARNING,
            shift=WindShiftGuardStatus.DATA_UNAVAILABLE,
        )).windWarningStarted)
    }

    private fun snapshot(
        depth:DepthGuardStatus=DepthGuardStatus.OFF,
        wind:WindSpeedGuardStatus=WindSpeedGuardStatus.OFF,
        shift:WindShiftGuardStatus=WindShiftGuardStatus.OFF,
    )=ConditionRuntimeSnapshot(
        depth=DepthGuardSnapshot(depth),
        windSpeed=WindSpeedGuardSnapshot(wind),
        windShift=WindShiftGuardSnapshot(shift),
    )
}
