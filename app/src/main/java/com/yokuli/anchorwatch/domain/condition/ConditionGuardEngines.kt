package com.yokuli.anchorwatch.domain.condition

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private data class TimedNumber(val value:Double,val elapsed:Long)
private fun median(values:List<Double>):Double{
    val sorted=values.sorted();val middle=sorted.size/2
    return if(sorted.size%2==1)sorted[middle]else(sorted[middle-1]+sorted[middle])/2.0
}

class DepthGuardEngine{
    private val samples=ArrayDeque<TimedNumber>()
    private var shallowSince:Long?=null;private var shallowClearSince:Long?=null
    private var deepSince:Long?=null;private var deepClearSince:Long?=null
    private var lastFresh:Long?=null;private var activeAlarm:DepthGuardStatus?=null

    fun reset(){samples.clear();shallowSince=null;shallowClearSince=null;deepSince=null;deepClearSince=null;lastFresh=null;activeAlarm=null}
    fun update(config:ConditionGuardConfig,depthMeters:Double?,receivedElapsed:Long?,now:Long,paused:Boolean=false):DepthGuardSnapshot{
        if(paused)return DepthGuardSnapshot(DepthGuardStatus.PAUSED)
        if(!config.depthGuardEnabled){reset();return DepthGuardSnapshot()}
        if(depthMeters!=null&&receivedElapsed!=null&&depthMeters.isFinite()&&depthMeters in .1..1000.0&&now-receivedElapsed in 0..3_000){
            if(lastFresh==null||receivedElapsed>lastFresh!!){samples.addLast(TimedNumber(depthMeters,receivedElapsed));lastFresh=receivedElapsed}
        }
        while(samples.isNotEmpty()&&(now-samples.first().elapsed>5_000||samples.size>10))samples.removeFirst()
        val last=lastFresh
        if(last==null)return DepthGuardSnapshot(DepthGuardStatus.WAITING_FOR_DATA)
        if(now-last>10_000){samples.clear();shallowSince=null;deepSince=null;shallowClearSince=null;deepClearSince=null;activeAlarm=null;return DepthGuardSnapshot(DepthGuardStatus.DATA_UNAVAILABLE,dataUnavailable=true)}
        // Do not advance persistence or clear an alarm from a stale value. During
        // the short grace period before DATA_UNAVAILABLE, an existing alarm stays
        // active but the old depth is no longer presented as a live measurement.
        if(now-last>3_000){
            val active=activeAlarm
            return if(active==DepthGuardStatus.SHALLOW_ALARM||active==DepthGuardStatus.DEEP_ALARM)DepthGuardSnapshot(active,alarmActive=true)
            else DepthGuardSnapshot(DepthGuardStatus.WAITING_FOR_DATA)
        }
        if(samples.size<3)return DepthGuardSnapshot(DepthGuardStatus.WAITING_FOR_DATA)
        val filtered=median(samples.map{it.value});val shallow=requireNotNull(config.shallowDepthAlarmMeters)
        when(activeAlarm){
            DepthGuardStatus.SHALLOW_ALARM->{
                val clear=shallow+maxOf(.3,shallow*.10)
                shallowClearSince=if(filtered>clear)shallowClearSince?:now else null
                if(shallowClearSince?.let{now-it>=10_000}==true){activeAlarm=null;shallowClearSince=null}
            }
            DepthGuardStatus.DEEP_ALARM->{
                val deep=config.deepDepthAlarmMeters?:Double.POSITIVE_INFINITY;val clear=deep-maxOf(.5,deep*.05)
                deepClearSince=if(filtered<clear)deepClearSince?:now else null
                if(deepClearSince?.let{now-it>=10_000}==true){activeAlarm=null;deepClearSince=null}
            }
            else->{
                shallowSince=if(filtered<=shallow)shallowSince?:now else null
                val deep=config.deepDepthAlarmMeters
                deepSince=if(deep!=null&&filtered>=deep)deepSince?:now else null
                if(shallowSince?.let{now-it>=5_000}==true)activeAlarm=DepthGuardStatus.SHALLOW_ALARM
                else if(deepSince?.let{now-it>=5_000}==true)activeAlarm=DepthGuardStatus.DEEP_ALARM
            }
        }
        val status=activeAlarm?:DepthGuardStatus.MONITORING
        return DepthGuardSnapshot(status,filtered,status==DepthGuardStatus.SHALLOW_ALARM||status==DepthGuardStatus.DEEP_ALARM)
    }
}

class WindSpeedGuardEngine{
    private val samples=ArrayDeque<TimedNumber>();private var source:WindSpeedSource?=null;private var lastFresh:Long?=null
    private var warningSince:Long?=null;private var alarmSince:Long?=null;private var warningClearSince:Long?=null;private var alarmClearSince:Long?=null
    private var warning=false;private var alarm=false
    fun reset(){samples.clear();source=null;lastFresh=null;warningSince=null;alarmSince=null;warningClearSince=null;alarmClearSince=null;warning=false;alarm=false}
    fun update(config:ConditionGuardConfig,speedKnots:Double?,newSource:WindSpeedSource?,receivedElapsed:Long?,now:Long,paused:Boolean=false):WindSpeedGuardSnapshot{
        if(paused)return WindSpeedGuardSnapshot(WindSpeedGuardStatus.PAUSED)
        if(!config.windGuardEnabled){reset();return WindSpeedGuardSnapshot()}
        if(newSource!=null&&newSource!=source){samples.clear();warningSince=null;alarmSince=null;warningClearSince=null;alarmClearSince=null;warning=false;alarm=false;source=newSource;lastFresh=null}
        if(speedKnots!=null&&receivedElapsed!=null&&newSource!=null&&speedKnots.isFinite()&&speedKnots in 0.0..200.0&&now-receivedElapsed in 0..5_000){
            if(lastFresh==null||receivedElapsed>lastFresh!!){samples.addLast(TimedNumber(speedKnots,receivedElapsed));lastFresh=receivedElapsed;source=newSource}
        }
        while(samples.isNotEmpty()&&(now-samples.first().elapsed>5_000||samples.size>10))samples.removeFirst()
        val last=lastFresh
        if(last==null)return WindSpeedGuardSnapshot(WindSpeedGuardStatus.WAITING_FOR_DATA,source=source)
        if(now-last>10_000){samples.clear();warning=false;alarm=false;warningSince=null;alarmSince=null;warningClearSince=null;alarmClearSince=null;return WindSpeedGuardSnapshot(WindSpeedGuardStatus.DATA_UNAVAILABLE,source=source,dataUnavailable=true)}
        // Never use a stale wind value to enter or clear a threshold. Preserve an
        // already-active warning/alarm only until the explicit data-loss timeout.
        if(now-last>5_000){
            val status=when{alarm->WindSpeedGuardStatus.ALARM;warning->WindSpeedGuardStatus.WARNING;else->WindSpeedGuardStatus.WAITING_FOR_DATA}
            return WindSpeedGuardSnapshot(status,source=source,alarmActive=alarm,warningActive=warning)
        }
        if(samples.size<3)return WindSpeedGuardSnapshot(WindSpeedGuardStatus.WAITING_FOR_DATA,source=source)
        val filtered=median(samples.map{it.value});val warningThreshold=requireNotNull(config.windWarningKnots);val alarmThreshold=requireNotNull(config.windAlarmKnots)
        if(alarm){alarmClearSince=if(filtered<=alarmThreshold-3.0)alarmClearSince?:now else null;if(alarmClearSince?.let{now-it>=15_000}==true){alarm=false;alarmClearSince=null}}
        if(warning){warningClearSince=if(filtered<=warningThreshold-2.0)warningClearSince?:now else null;if(warningClearSince?.let{now-it>=20_000}==true){warning=false;warningClearSince=null}}
        if(!warning){warningSince=if(filtered>=warningThreshold)warningSince?:now else null;if(warningSince?.let{now-it>=10_000}==true)warning=true}
        if(!alarm){alarmSince=if(filtered>=alarmThreshold)alarmSince?:now else null;if(alarmSince?.let{now-it>=5_000}==true)alarm=true}
        val status=when{alarm->WindSpeedGuardStatus.ALARM;warning->WindSpeedGuardStatus.WARNING;else->WindSpeedGuardStatus.MONITORING}
        return WindSpeedGuardSnapshot(status,filtered,source,alarm,warning)
    }
}

class WindShiftGuardEngine{
    private val samples=ArrayDeque<Pair<TimedNumber,TrueWindDirectionSource>>()
    private var baseline:Double?=null;private var baselineAt:Long?=null;private var baselineSource:TrueWindDirectionSource?=null;private var baselineR:Double?=null;private var lastFresh:Long?=null
    private var alarmSince:Long?=null;private var clearSince:Long?=null;private var alarm=false
    fun restore(value:Double?,establishedAt:Long?,source:TrueWindDirectionSource?){reset();baseline=value;baselineAt=establishedAt;baselineSource=source}
    fun reset(){samples.clear();baseline=null;baselineAt=null;baselineSource=null;baselineR=null;lastFresh=null;alarmSince=null;clearSince=null;alarm=false}
    fun update(config:ConditionGuardConfig,directionDegrees:Double?,source:TrueWindDirectionSource?,receivedElapsed:Long?,now:Long,paused:Boolean=false):WindShiftGuardSnapshot{
        if(paused)return WindShiftGuardSnapshot(WindShiftGuardStatus.PAUSED,baseline,baselineAt,baselineSource)
        if(!config.windShiftEnabled){reset();return WindShiftGuardSnapshot()}
        if(directionDegrees!=null&&source!=null&&receivedElapsed!=null&&directionDegrees.isFinite()&&now-receivedElapsed in 0..5_000){
            if(lastFresh==null||receivedElapsed>lastFresh!!){samples.addLast(TimedNumber(normalize(directionDegrees),receivedElapsed) to source);lastFresh=receivedElapsed}
        }
        while(samples.isNotEmpty()&&now-samples.first().first.elapsed>180_000)samples.removeFirst()
        val last=lastFresh
        if(last==null)return WindShiftGuardSnapshot(WindShiftGuardStatus.WAITING_FOR_DIRECTION,baseline,baselineAt,baselineSource)
        if(now-last>10_000){samples.clear();alarm=false;alarmSince=null;clearSince=null;return WindShiftGuardSnapshot(WindShiftGuardStatus.DATA_UNAVAILABLE,baseline,baselineAt,baselineSource,dataUnavailable=true)}
        if(baseline==null){
            val training=samples.filter{now-it.first.elapsed<=180_000}
            if(training.size>=20&&(training.last().first.elapsed-training.first().first.elapsed)>=120_000){
                val mean=circularMean(training.map{it.first.value});baselineR=mean.second
                if(mean.second>=.85){baseline=mean.first;baselineAt=now;baselineSource=training.groupingBy{it.second}.eachCount().maxByOrNull{it.value}?.key;samples.clear();lastFresh=null}
            }
            return WindShiftGuardSnapshot(WindShiftGuardStatus.LEARNING_BASELINE,baseline,baselineAt,baselineSource,baselineConcentration=baselineR)
        }
        val currentSamples=samples.filter{now-it.first.elapsed<=60_000}
        if(currentSamples.size<8)return WindShiftGuardSnapshot(WindShiftGuardStatus.MONITORING,baseline,baselineAt,baselineSource)
        val current=circularMean(currentSamples.map{it.first.value}).first;val shift=angularDistance(requireNotNull(baseline),current);val threshold=requireNotNull(config.windShiftThresholdDegrees)
        if(alarm){clearSince=if(shift<threshold-15.0)clearSince?:now else null;if(clearSince?.let{now-it>=60_000}==true){alarm=false;clearSince=null}}
        else{alarmSince=if(shift>=threshold)alarmSince?:now else null;if(alarmSince?.let{now-it>=30_000}==true)alarm=true}
        return WindShiftGuardSnapshot(if(alarm)WindShiftGuardStatus.ALARM else WindShiftGuardStatus.MONITORING,baseline,baselineAt,baselineSource,current,shift,baselineR,alarm)
    }
    private fun circularMean(values:List<Double>):Pair<Double,Double>{val x=values.sumOf{cos(Math.toRadians(it))}/values.size;val y=values.sumOf{sin(Math.toRadians(it))}/values.size;return normalize(Math.toDegrees(atan2(y,x))) to hypot(x,y)}
    private fun normalize(value:Double)=(value%360.0+360.0)%360.0
    private fun angularDistance(a:Double,b:Double)=abs((a-b+540.0)%360.0-180.0)
}
