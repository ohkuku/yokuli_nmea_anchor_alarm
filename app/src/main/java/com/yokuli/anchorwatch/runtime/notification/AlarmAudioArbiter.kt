package com.yokuli.anchorwatch.runtime.notification

import com.yokuli.anchorwatch.domain.condition.ConditionAlarmSource

data class AlarmSourceState(val active:Boolean=false,val snoozedUntil:Long?=null)
data class AlarmArbitration(val audibleSources:Set<ConditionAlarmSource>,val shouldSound:Boolean)

/** Pure multi-source ownership. A source can never silence another source. */
class AlarmAudioArbiter{
    private val states=ConditionAlarmSource.entries.associateWith{AlarmSourceState()}.toMutableMap()
    @Synchronized fun setActive(source:ConditionAlarmSource,active:Boolean){states[source]=if(active)states.getValue(source).copy(active=true)else AlarmSourceState()}
    @Synchronized fun snoozeActive(now:Long,until:Long){states.replaceAll{_,state->if(state.active)state.copy(snoozedUntil=until.coerceAtLeast(now))else state}}
    @Synchronized fun snooze(source:ConditionAlarmSource,until:Long){states[source]=states.getValue(source).copy(snoozedUntil=until)}
    @Synchronized fun clear(source:ConditionAlarmSource){states[source]=AlarmSourceState()}
    @Synchronized fun clearAll(){states.keys.forEach{states[it]=AlarmSourceState()}}
    @Synchronized fun snapshot(now:Long):AlarmArbitration{
        val audible=states.filter{(_,value)->value.active&&(value.snoozedUntil==null||value.snoozedUntil<=now)}.keys
        return AlarmArbitration(audible,audible.isNotEmpty())
    }
}
