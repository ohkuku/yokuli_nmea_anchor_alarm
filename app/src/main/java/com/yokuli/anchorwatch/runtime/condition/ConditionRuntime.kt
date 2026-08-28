package com.yokuli.anchorwatch.runtime.condition

import com.yokuli.anchorwatch.data.condition.LiveDepthRepository
import com.yokuli.anchorwatch.data.condition.LiveWindRepository
import com.yokuli.anchorwatch.data.database.AlarmEventEntity
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.condition.*
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.WallClock
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConditionRuntime @Inject constructor(
    private val depthRepository:LiveDepthRepository,
    private val windRepository:LiveWindRepository,
    private val dao:AnchorDao,
    private val settings:SettingsRepository,
    private val resources:RuntimeResourceManager,
    private val wallClock:WallClock,
    private val monotonicClock:MonotonicClock,
    private val nmeaRuntime:NmeaRuntime,
){
    private val depthEngine=DepthGuardEngine();private val windEngine=WindSpeedGuardEngine();private val shiftEngine=WindShiftGuardEngine()
    private val _state=MutableStateFlow(ConditionRuntimeSnapshot());val state=_state.asStateFlow()
    private var session:AnchorSessionEntity?=null;private var lastPersistElapsed=0L;private var acceptSamplesReceivedAfter=0L;private var acceptDirectionReceivedAfter=0L

    suspend fun sync(active:AnchorSessionEntity?){
        if(active==null){release();return}
        val changed=session?.id!=active.id
        val resumed=session?.paused==true&&!active.paused
        // Anchor and condition runtimes own different columns on the same row.
        // Preserve unflushed condition summaries while accepting the newest
        // anchor/session lifecycle fields supplied by the serialized owner.
        session=if(changed)active else active.withConditionFieldsFrom(session?:active)
        val current=session!!
        if(changed||resumed){
            depthEngine.reset();windEngine.reset()
            acceptSamplesReceivedAfter=monotonicClock.elapsedRealtime();acceptDirectionReceivedAfter=acceptSamplesReceivedAfter
            shiftEngine.restore(current.windBaselineDirectionDegrees,current.windBaselineEstablishedAt,current.windBaselineSource?.let{runCatching{TrueWindDirectionSource.valueOf(it)}.getOrNull()})
        }
        val config=current.conditionConfig()
        if(current.paused){releaseResources();_state.value=ConditionRuntimeSnapshot(current.id,true,config,DepthGuardSnapshot(DepthGuardStatus.PAUSED),WindSpeedGuardSnapshot(WindSpeedGuardStatus.PAUSED),WindShiftGuardSnapshot(WindShiftGuardStatus.PAUSED,current.windBaselineDirectionDegrees,current.windBaselineEstablishedAt,current.windBaselineSource?.let{runCatching{TrueWindDirectionSource.valueOf(it)}.getOrNull()}));return}
        if(changed||resumed||_state.value.config!=config)setResources(config,current.positionSource=="DEMO")
        _state.value=if(changed||resumed)ConditionRuntimeSnapshot(
            activeSessionId=current.id,paused=false,config=config,
            depth=DepthGuardSnapshot(if(config.depthGuardEnabled)DepthGuardStatus.WAITING_FOR_DATA else DepthGuardStatus.OFF),
            windSpeed=WindSpeedGuardSnapshot(if(config.windGuardEnabled)WindSpeedGuardStatus.WAITING_FOR_DATA else WindSpeedGuardStatus.OFF),
            windShift=WindShiftGuardSnapshot(if(config.windShiftEnabled)WindShiftGuardStatus.WAITING_FOR_DIRECTION else WindShiftGuardStatus.OFF,current.windBaselineDirectionDegrees,current.windBaselineEstablishedAt,current.windBaselineSource?.let{runCatching{TrueWindDirectionSource.valueOf(it)}.getOrNull()}),
        )else _state.value.copy(activeSessionId=current.id,paused=false,config=config)
    }

    suspend fun updateConfig(config:ConditionGuardConfig){
        val memory=session?:return;val current=dao.session(memory.id)?.withConditionFieldsFrom(memory)?:memory;val next=config.validated();val previous=current.conditionConfig()
        val updated=current.copy(depthGuardEnabled=next.depthGuardEnabled,shallowDepthAlarmMeters=next.shallowDepthAlarmMeters,deepDepthAlarmMeters=next.deepDepthAlarmMeters,windGuardEnabled=next.windGuardEnabled,windWarningKnots=next.windWarningKnots,windAlarmKnots=next.windAlarmKnots,windShiftEnabled=next.windShiftEnabled,windShiftThresholdDegrees=next.windShiftThresholdDegrees,windAllowApparentFallback=next.windAllowApparentFallback,depthAlarmSnoozedUntil=null,windAlarmSnoozedUntil=null,windShiftAlarmSnoozedUntil=null)
        session=updated;dao.updateSession(updated);depthEngine.reset();windEngine.reset();acceptSamplesReceivedAfter=monotonicClock.elapsedRealtime();acceptDirectionReceivedAfter=acceptSamplesReceivedAfter
        if(previous.windShiftEnabled!=next.windShiftEnabled)shiftEngine.restore(updated.windBaselineDirectionDegrees,updated.windBaselineEstablishedAt,updated.windBaselineSource?.let{runCatching{TrueWindDirectionSource.valueOf(it)}.getOrNull()})
        event("CONDITION_SETTINGS_CHANGED","depth=${next.depthGuardEnabled};shallow=${next.shallowDepthAlarmMeters};deep=${next.deepDepthAlarmMeters};wind=${next.windGuardEnabled};warning=${next.windWarningKnots};alarm=${next.windAlarmKnots};shift=${next.windShiftEnabled};shiftDegrees=${next.windShiftThresholdDegrees};apparentFallback=${next.windAllowApparentFallback}")
        event(if(next.depthGuardEnabled&&!previous.depthGuardEnabled)"DEPTH_GUARD_ENABLED" else if(!next.depthGuardEnabled&&previous.depthGuardEnabled)"DEPTH_GUARD_DISABLED" else "DEPTH_GUARD_UPDATED")
        event(if((next.windGuardEnabled||next.windShiftEnabled)&&!(previous.windGuardEnabled||previous.windShiftEnabled))"WIND_GUARD_ENABLED" else if(!(next.windGuardEnabled||next.windShiftEnabled)&&(previous.windGuardEnabled||previous.windShiftEnabled))"WIND_GUARD_DISABLED" else "WIND_GUARD_UPDATED")
        if(updated.paused){
            // Editing the remembered configuration of a paused anchor session must
            // not silently restart NMEA, Wi-Fi locks or condition alarms. Resume is
            // the single transition that reacquires sensors and fresh samples.
            releaseResources()
            _state.value=ConditionRuntimeSnapshot(
                activeSessionId=updated.id,
                paused=true,
                config=next,
                depth=DepthGuardSnapshot(DepthGuardStatus.PAUSED),
                windSpeed=WindSpeedGuardSnapshot(WindSpeedGuardStatus.PAUSED),
                windShift=WindShiftGuardSnapshot(
                    WindShiftGuardStatus.PAUSED,
                    updated.windBaselineDirectionDegrees,
                    updated.windBaselineEstablishedAt,
                    updated.windBaselineSource?.let{runCatching{TrueWindDirectionSource.valueOf(it)}.getOrNull()},
                ),
            )
        }else{
            setResources(next,updated.positionSource=="DEMO")
            // Every edit deliberately starts a fresh evidence epoch. Reflect
            // that immediately in UI/runtime state instead of leaving an old
            // DATA_UNAVAILABLE snapshot visible until the next one-second tick.
            _state.value=ConditionRuntimeSnapshot(
                activeSessionId=updated.id,
                paused=false,
                config=next,
                depth=DepthGuardSnapshot(if(next.depthGuardEnabled)DepthGuardStatus.WAITING_FOR_DATA else DepthGuardStatus.OFF),
                windSpeed=WindSpeedGuardSnapshot(if(next.windGuardEnabled)WindSpeedGuardStatus.WAITING_FOR_DATA else WindSpeedGuardStatus.OFF),
                windShift=WindShiftGuardSnapshot(
                    if(next.windShiftEnabled)WindShiftGuardStatus.WAITING_FOR_DIRECTION else WindShiftGuardStatus.OFF,
                    updated.windBaselineDirectionDegrees,
                    updated.windBaselineEstablishedAt,
                    updated.windBaselineSource?.let{runCatching{TrueWindDirectionSource.valueOf(it)}.getOrNull()},
                ),
            )
        }
    }

    suspend fun resetWindBaseline(){
        val memory=session?:return;val current=dao.session(memory.id)?.withConditionFieldsFrom(memory)?:memory;shiftEngine.reset();acceptDirectionReceivedAfter=monotonicClock.elapsedRealtime();val updated=current.copy(windBaselineDirectionDegrees=null,windBaselineEstablishedAt=null,windBaselineSource=null,windShiftAlarmSnoozedUntil=null);session=updated;dao.updateSession(updated);event("WIND_BASELINE_RESET");_state.value=_state.value.copy(windShift=WindShiftGuardSnapshot(WindShiftGuardStatus.LEARNING_BASELINE))
    }

    suspend fun snooze(until:Long){
        val memory=session?:return;val current=dao.session(memory.id)?.withConditionFieldsFrom(memory)?:memory;val snapshot=_state.value
        val updated=current.copy(
            depthAlarmSnoozedUntil=until.takeIf{snapshot.depth.alarmActive||snapshot.depth.dataUnavailable}?:current.depthAlarmSnoozedUntil,
            windAlarmSnoozedUntil=until.takeIf{snapshot.windSpeed.warningActive||snapshot.windSpeed.alarmActive||snapshot.windSpeed.dataUnavailable}?:current.windAlarmSnoozedUntil,
            windShiftAlarmSnoozedUntil=until.takeIf{snapshot.windShift.alarmActive||snapshot.windShift.dataUnavailable}?:current.windShiftAlarmSnoozedUntil,
        );session=updated;dao.updateSession(updated)
    }

    suspend fun tick(nowElapsed:Long):ConditionRuntimeSnapshot{
        val active=session?:return _state.value
        val config=active.conditionConfig();val before=_state.value
        val depth=depthRepository.state.value
        val depthReceived=depth.receivedElapsedRealtime?.takeIf{it>acceptSamplesReceivedAfter}
        val wind=windRepository.state.value;val speed=wind.speed(nowElapsed,config.windAllowApparentFallback)?.takeIf{it.first.receivedElapsedRealtime>acceptSamplesReceivedAfter};val direction=wind.direction(nowElapsed)?.takeIf{it.first.receivedElapsedRealtime>maxOf(acceptSamplesReceivedAfter,acceptDirectionReceivedAfter)}
        val nextDepth=depthEngine.update(config,depth.depthMeters.takeIf{depthReceived!=null},depthReceived,nowElapsed,active.paused)
        val nextWind=windEngine.update(config,speed?.first?.value,speed?.second,speed?.first?.receivedElapsedRealtime,nowElapsed,active.paused)
        val nextShift=shiftEngine.update(config,direction?.first?.value,direction?.second,direction?.first?.receivedElapsedRealtime,nowElapsed,active.paused)
        var persistedShift=nextShift
        var updated=active.copy(
            minObservedDepthMeters=nextDepth.filteredDepthMeters?.let{minOf(active.minObservedDepthMeters?:it,it)}?:active.minObservedDepthMeters,
            maxObservedDepthMeters=nextDepth.filteredDepthMeters?.let{maxOf(active.maxObservedDepthMeters?:it,it)}?:active.maxObservedDepthMeters,
            maxObservedWindKnots=nextWind.filteredSpeedKnots?.let{maxOf(active.maxObservedWindKnots?:it,it)}?:active.maxObservedWindKnots,
            maxObservedWindSource=if(nextWind.filteredSpeedKnots!=null&&(active.maxObservedWindKnots==null||nextWind.filteredSpeedKnots>=active.maxObservedWindKnots))nextWind.source?.name else active.maxObservedWindSource,
        )
        updated=updated.copy(windAlarmSnoozedUntil=ConditionAudibilityPolicy.windSnoozeAfterTransition(before.windSpeed,nextWind,updated.windAlarmSnoozedUntil))
        if(nextShift.baselineDirectionDegrees!=null&&active.windBaselineDirectionDegrees==null){val establishedAt=wallClock.currentTimeMillis();updated=updated.copy(windBaselineDirectionDegrees=nextShift.baselineDirectionDegrees,windBaselineEstablishedAt=establishedAt,windBaselineSource=nextShift.baselineSource?.name);persistedShift=nextShift.copy(baselineEstablishedAt=establishedAt);event("WIND_BASELINE_ESTABLISHED","baseline=${"%.0f".format(nextShift.baselineDirectionDegrees)};source=${nextShift.baselineSource?.name}")}
        updated=transitionEvents(updated,before,nextDepth,nextWind,persistedShift)
        if(updated!=active&&(nowElapsed-lastPersistElapsed>=30_000||updated.depthAlarmCount!=active.depthAlarmCount||updated.windAlarmCount!=active.windAlarmCount||updated.windBaselineDirectionDegrees!=active.windBaselineDirectionDegrees)){
            val latest=dao.session(active.id)?:active
            updated=latest.withConditionFieldsFrom(updated)
            dao.updateSession(updated);lastPersistElapsed=nowElapsed
        }
        session=updated
        return ConditionRuntimeSnapshot(updated.id,updated.paused,config,nextDepth,nextWind,persistedShift).also{_state.value=it}
    }

    /** Persist the last in-memory summaries before Pause/Lift can release them. */
    suspend fun flush(){
        val memory=session?:return
        val latest=dao.session(memory.id)?:memory
        val updated=latest.withConditionFieldsFrom(memory)
        if(updated!=latest)dao.updateSession(updated)
        session=updated
    }

    fun currentSession()=session
    fun audibleSources(nowWall:Long):Set<ConditionAlarmSource>{val active=session?:return emptySet();return ConditionAudibilityPolicy.audibleSources(_state.value,ConditionSnoozeState(active.depthAlarmSnoozedUntil,active.windAlarmSnoozedUntil,active.windShiftAlarmSnoozedUntil),nowWall)}

    private suspend fun transitionEvents(current:AnchorSessionEntity,before:ConditionRuntimeSnapshot,depth:DepthGuardSnapshot,wind:WindSpeedGuardSnapshot,shift:WindShiftGuardSnapshot):AnchorSessionEntity{
        var updated=current
        if(depth.status!=before.depth.status){
            if(before.depth.status==DepthGuardStatus.DATA_UNAVAILABLE&&depth.status!=DepthGuardStatus.DATA_UNAVAILABLE)event("DEPTH_DATA_RESTORED")
            val type=when(depth.status){DepthGuardStatus.SHALLOW_ALARM->"DEPTH_SHALLOW_ALARM";DepthGuardStatus.DEEP_ALARM->"DEPTH_DEEP_ALARM";DepthGuardStatus.DATA_UNAVAILABLE->"DEPTH_DATA_LOST";DepthGuardStatus.MONITORING->when(before.depth.status){DepthGuardStatus.SHALLOW_ALARM->"DEPTH_SHALLOW_CLEARED";DepthGuardStatus.DEEP_ALARM->"DEPTH_DEEP_CLEARED";else->null};else->null}
            type?.let{event(it,"depth=${depth.filteredDepthMeters?.let{"%.2f".format(it)}};threshold=${if(it.contains("SHALLOW"))current.shallowDepthAlarmMeters else current.deepDepthAlarmMeters}")}
            if(depth.alarmActive&&!before.depth.alarmActive)updated=updated.copy(depthAlarmCount=updated.depthAlarmCount+1)
        }
        if(wind.status!=before.windSpeed.status){
            if(before.windSpeed.status==WindSpeedGuardStatus.DATA_UNAVAILABLE&&wind.status!=WindSpeedGuardStatus.DATA_UNAVAILABLE)event("WIND_DATA_RESTORED")
            if(before.windSpeed.status==WindSpeedGuardStatus.ALARM&&wind.status!=WindSpeedGuardStatus.ALARM)event("WIND_ALARM_CLEARED")
            val type=when(wind.status){WindSpeedGuardStatus.WARNING->if(before.windSpeed.status==WindSpeedGuardStatus.ALARM)null else "WIND_WARNING";WindSpeedGuardStatus.ALARM->"WIND_ALARM";WindSpeedGuardStatus.DATA_UNAVAILABLE->"WIND_DATA_LOST";WindSpeedGuardStatus.MONITORING->if(before.windSpeed.status==WindSpeedGuardStatus.WARNING)"WIND_WARNING_CLEARED" else null;else->null}
            type?.let{event(it,"speed=${wind.filteredSpeedKnots?.let{"%.1f".format(it)}};source=${wind.source?.name};threshold=${if(it=="WIND_ALARM")current.windAlarmKnots else current.windWarningKnots}")}
            if(wind.alarmActive&&!before.windSpeed.alarmActive)updated=updated.copy(windAlarmCount=updated.windAlarmCount+1)
        }
        if(shift.status!=before.windShift.status){
            if(before.windShift.status==WindShiftGuardStatus.DATA_UNAVAILABLE&&shift.status!=WindShiftGuardStatus.DATA_UNAVAILABLE)event("WIND_DATA_RESTORED","source=TRUE_DIRECTION")
            val type=when(shift.status){WindShiftGuardStatus.ALARM->"WIND_SHIFT_ALARM";WindShiftGuardStatus.MONITORING->if(before.windShift.status==WindShiftGuardStatus.ALARM)"WIND_SHIFT_CLEARED" else null;WindShiftGuardStatus.DATA_UNAVAILABLE->"WIND_DATA_LOST";else->null}
            type?.let{event(it,"baseline=${shift.baselineDirectionDegrees?.let{"%.0f".format(it)}};current=${shift.currentDirectionDegrees?.let{"%.0f".format(it)}};shift=${shift.shiftDegrees?.let{"%.0f".format(it)}};threshold=${current.windShiftThresholdDegrees}")}
            if(shift.alarmActive&&!before.windShift.alarmActive)updated=updated.copy(windAlarmCount=updated.windAlarmCount+1)
        }
        return updated
    }
    private suspend fun event(type:String,detail:String=""){session?.let{dao.insertEvent(AlarmEventEntity(sessionId=it.id,timestamp=wallClock.currentTimeMillis(),type=type,detail=detail))}}
    private suspend fun setResources(config:ConditionGuardConfig,demo:Boolean){
        if(!config.depthGuardEnabled&&!config.windGuardEnabled&&!config.windShiftEnabled){releaseResources();return}
        val value=settings.settings.first();resources.set(RuntimeOwner.CONDITION_MONITOR,RuntimeRequirement(needsNmeaTransport=!demo,needsWakeLock=true,needsWifiLock=!demo&&value.keepWifiAwake));if(!demo)nmeaRuntime.ensureConnected(value.profile)
    }
    private fun releaseResources(){resources.release(RuntimeOwner.CONDITION_MONITOR);nmeaRuntime.releaseIfUnowned()}
    private fun release(){session=null;depthEngine.reset();windEngine.reset();shiftEngine.reset();acceptSamplesReceivedAfter=0L;acceptDirectionReceivedAfter=0L;releaseResources();_state.value=ConditionRuntimeSnapshot()}
    private fun AnchorSessionEntity.conditionConfig()=ConditionGuardConfig(depthGuardEnabled,shallowDepthAlarmMeters,deepDepthAlarmMeters,windGuardEnabled,windWarningKnots,windAlarmKnots,windShiftEnabled,windShiftThresholdDegrees,windAllowApparentFallback).validated()
    private fun AnchorSessionEntity.withConditionFieldsFrom(value:AnchorSessionEntity)=copy(
        depthGuardEnabled=value.depthGuardEnabled,
        shallowDepthAlarmMeters=value.shallowDepthAlarmMeters,
        deepDepthAlarmMeters=value.deepDepthAlarmMeters,
        windGuardEnabled=value.windGuardEnabled,
        windWarningKnots=value.windWarningKnots,
        windAlarmKnots=value.windAlarmKnots,
        windShiftEnabled=value.windShiftEnabled,
        windShiftThresholdDegrees=value.windShiftThresholdDegrees,
        windAllowApparentFallback=value.windAllowApparentFallback,
        windBaselineDirectionDegrees=value.windBaselineDirectionDegrees,
        windBaselineEstablishedAt=value.windBaselineEstablishedAt,
        windBaselineSource=value.windBaselineSource,
        depthAlarmSnoozedUntil=value.depthAlarmSnoozedUntil,
        windAlarmSnoozedUntil=value.windAlarmSnoozedUntil,
        windShiftAlarmSnoozedUntil=value.windShiftAlarmSnoozedUntil,
        minObservedDepthMeters=value.minObservedDepthMeters,
        maxObservedDepthMeters=value.maxObservedDepthMeters,
        maxObservedWindKnots=value.maxObservedWindKnots,
        maxObservedWindSource=value.maxObservedWindSource,
        depthAlarmCount=value.depthAlarmCount,
        windAlarmCount=value.windAlarmCount,
    )
}
