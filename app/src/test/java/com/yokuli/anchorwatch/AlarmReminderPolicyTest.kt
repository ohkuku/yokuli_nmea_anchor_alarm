package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AlarmReminderPolicy
import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmReminderPolicyTest {
    private val alarm = AlarmSnapshot(state = AlarmState.ALARM, type = AlarmType.ANCHOR_RADIUS_EXCEEDED)

    @Test fun snoozeSuppressesTheCurrentAlarmButAllowsALaterReminder() {
        val now = 1_000_000L
        val until = AlarmReminderPolicy.snoozeUntil(now, 5)
        assertFalse(AlarmReminderPolicy.shouldSound(alarm, paused = false, snoozedUntil = until, nowMillis = now + 299_999L))
        assertTrue(AlarmReminderPolicy.shouldSound(alarm, paused = false, snoozedUntil = until, nowMillis = now + 300_000L))
    }

    @Test fun pausedWatchNeverSoundsEvenAfterSnoozeExpires() {
        assertFalse(AlarmReminderPolicy.shouldSound(alarm, paused = true, snoozedUntil = null, nowMillis = 0L))
    }
}
