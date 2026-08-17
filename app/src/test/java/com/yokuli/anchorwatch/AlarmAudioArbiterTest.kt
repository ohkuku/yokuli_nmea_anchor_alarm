package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.condition.ConditionAlarmSource
import com.yokuli.anchorwatch.domain.condition.SafetyAlert
import com.yokuli.anchorwatch.domain.condition.SafetyAlertAggregator
import com.yokuli.anchorwatch.runtime.notification.AlarmAudioArbiter
import org.junit.Assert.*
import org.junit.Test

class AlarmAudioArbiterTest{
    @Test fun clearingTestNeverSilencesRealAlarm(){val arbiter=AlarmAudioArbiter();arbiter.setActive(ConditionAlarmSource.DEPTH,true);arbiter.setActive(ConditionAlarmSource.ALARM_TEST,true);arbiter.clear(ConditionAlarmSource.ALARM_TEST);assertEquals(setOf(ConditionAlarmSource.DEPTH),arbiter.snapshot(0).audibleSources)}
    @Test fun snoozeCoversCurrentSourcesButNewSourceSounds(){val arbiter=AlarmAudioArbiter();arbiter.setActive(ConditionAlarmSource.ANCHOR,true);arbiter.setActive(ConditionAlarmSource.WIND_SPEED,true);arbiter.snoozeActive(1_000,61_000);assertFalse(arbiter.snapshot(2_000).shouldSound);arbiter.setActive(ConditionAlarmSource.DEPTH,true);assertEquals(setOf(ConditionAlarmSource.DEPTH),arbiter.snapshot(2_000).audibleSources)}
    @Test fun clearingOneSourceKeepsOtherAudible(){val arbiter=AlarmAudioArbiter();arbiter.setActive(ConditionAlarmSource.DEPTH,true);arbiter.setActive(ConditionAlarmSource.WIND_SHIFT,true);arbiter.clear(ConditionAlarmSource.DEPTH);assertTrue(arbiter.snapshot(0).shouldSound);assertEquals(setOf(ConditionAlarmSource.WIND_SHIFT),arbiter.snapshot(0).audibleSources)}
    @Test fun safetyAggregatorPrioritizesShallowDepthWithoutDroppingOtherAlerts(){val alerts=listOf(SafetyAlert(ConditionAlarmSource.WIND_SPEED,SafetyAlert.Severity.ALARM,"high wind","38 kn"),SafetyAlert(ConditionAlarmSource.ANCHOR,SafetyAlert.Severity.ALARM,"anchor","60 m"),SafetyAlert(ConditionAlarmSource.DEPTH,SafetyAlert.Severity.ALARM,"shallow","2.1 m"));val sorted=SafetyAlertAggregator.sorted(alerts);assertEquals(3,sorted.size);assertEquals(ConditionAlarmSource.DEPTH,sorted.first().source)}
}
