package com.yokuli.anchorwatch.domain.anchor

import com.yokuli.anchorwatch.domain.model.AlarmSnapshot
import com.yokuli.anchorwatch.domain.model.AlarmState

object AlarmReminderPolicy {
    fun snoozeUntil(nowMillis: Long, minutes: Int): Long =
        nowMillis + minutes.coerceIn(1, 30) * 60_000L

    fun isSnoozed(snoozedUntil: Long?, nowMillis: Long): Boolean =
        snoozedUntil != null && snoozedUntil > nowMillis

    fun shouldSound(snapshot: AlarmSnapshot, paused: Boolean, snoozedUntil: Long?, nowMillis: Long): Boolean =
        !paused && snapshot.state in setOf(AlarmState.WARNING, AlarmState.ALARM) &&
            !isSnoozed(snoozedUntil, nowMillis)

    /** A pre-alarm snooze must never suppress a later full alarm. */
    fun snoozeAfterTransition(previous:AlarmSnapshot?,current:AlarmSnapshot,snoozedUntil:Long?):Long? =
        if(previous?.state==AlarmState.WARNING&&current.state==AlarmState.ALARM)null else snoozedUntil
}
