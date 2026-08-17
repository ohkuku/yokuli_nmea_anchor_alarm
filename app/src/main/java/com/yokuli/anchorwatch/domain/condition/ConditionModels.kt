package com.yokuli.anchorwatch.domain.condition

enum class DepthGuardStatus { OFF, WAITING_FOR_DATA, MONITORING, SHALLOW_ALARM, DEEP_ALARM, DATA_UNAVAILABLE, PAUSED }
enum class WindSpeedGuardStatus { OFF, WAITING_FOR_DATA, MONITORING, WARNING, ALARM, DATA_UNAVAILABLE, PAUSED }
enum class WindShiftGuardStatus { OFF, WAITING_FOR_DIRECTION, LEARNING_BASELINE, MONITORING, ALARM, DATA_UNAVAILABLE, PAUSED }
enum class WindSpeedSource { TRUE, APPARENT }
enum class TrueWindDirectionSource { MWD, MWV_TRUE_PLUS_HDT }
enum class ConditionAlarmSource { ANCHOR, DEPTH, WIND_SPEED, WIND_SHIFT, ALARM_TEST }

data class ConditionGuardConfig(
    val depthGuardEnabled:Boolean=false,
    val shallowDepthAlarmMeters:Double?=null,
    val deepDepthAlarmMeters:Double?=null,
    val windGuardEnabled:Boolean=false,
    val windWarningKnots:Double?=null,
    val windAlarmKnots:Double?=null,
    val windShiftEnabled:Boolean=false,
    val windShiftThresholdDegrees:Double?=null,
    val windAllowApparentFallback:Boolean=true,
){
    fun validated():ConditionGuardConfig{
        val shallow=shallowDepthAlarmMeters?.takeIf{it.isFinite()&&it in .1..999.0}
        val deep=deepDepthAlarmMeters?.takeIf{it.isFinite()&&shallow!=null&&it>=shallow+1.0&&it<=1000.0}
        val warning=windWarningKnots?.takeIf{it.isFinite()&&it>=0.0}
        val alarm=windAlarmKnots?.takeIf{it.isFinite()&&warning!=null&&it>=warning+3.0&&it<=200.0}
        val shift=windShiftThresholdDegrees?.takeIf{it.isFinite()&&it in 15.0..180.0}
        return copy(
            depthGuardEnabled=depthGuardEnabled&&shallow!=null,
            shallowDepthAlarmMeters=shallow,
            deepDepthAlarmMeters=deep,
            windGuardEnabled=windGuardEnabled&&warning!=null&&alarm!=null,
            windWarningKnots=warning,
            windAlarmKnots=alarm,
            windShiftEnabled=windShiftEnabled&&shift!=null,
            windShiftThresholdDegrees=shift,
        )
    }
}

data class DepthGuardSnapshot(
    val status:DepthGuardStatus=DepthGuardStatus.OFF,
    val filteredDepthMeters:Double?=null,
    val alarmActive:Boolean=false,
    val dataUnavailable:Boolean=false,
)

data class WindSpeedGuardSnapshot(
    val status:WindSpeedGuardStatus=WindSpeedGuardStatus.OFF,
    val filteredSpeedKnots:Double?=null,
    val source:WindSpeedSource?=null,
    val alarmActive:Boolean=false,
    val warningActive:Boolean=false,
    val dataUnavailable:Boolean=false,
)

data class WindShiftGuardSnapshot(
    val status:WindShiftGuardStatus=WindShiftGuardStatus.OFF,
    val baselineDirectionDegrees:Double?=null,
    val baselineEstablishedAt:Long?=null,
    val baselineSource:TrueWindDirectionSource?=null,
    val currentDirectionDegrees:Double?=null,
    val shiftDegrees:Double?=null,
    val baselineConcentration:Double?=null,
    val alarmActive:Boolean=false,
    val dataUnavailable:Boolean=false,
)

data class ConditionRuntimeSnapshot(
    val activeSessionId:Long?=null,
    val paused:Boolean=false,
    val config:ConditionGuardConfig=ConditionGuardConfig(),
    val depth:DepthGuardSnapshot=DepthGuardSnapshot(),
    val windSpeed:WindSpeedGuardSnapshot=WindSpeedGuardSnapshot(),
    val windShift:WindShiftGuardSnapshot=WindShiftGuardSnapshot(),
)

data class SafetyAlert(
    val source:ConditionAlarmSource,
    val severity:Severity,
    val title:String,
    val detail:String,
){ enum class Severity { INFO, WARNING, ALARM } }

object SafetyAlertAggregator{
    private val priority=listOf(
        "SHALLOW", "ANCHOR", "CRITICAL_SOURCE_LOSS", "WIND_SPEED_ALARM",
        "DEEP", "WIND_SHIFT", "WIND_WARNING", "DATA_LOSS",
    )
    fun sorted(alerts:Collection<SafetyAlert>):List<SafetyAlert> = alerts.sortedWith(
        compareByDescending<SafetyAlert>{it.severity.ordinal}.thenBy{alert->
            val key=when(alert.source){
                ConditionAlarmSource.DEPTH->if(alert.title.contains("shallow",true))"SHALLOW" else "DEEP"
                ConditionAlarmSource.ANCHOR->if(alert.title.contains("lost",true))"CRITICAL_SOURCE_LOSS" else "ANCHOR"
                ConditionAlarmSource.WIND_SPEED->if(alert.severity==SafetyAlert.Severity.ALARM)"WIND_SPEED_ALARM" else "WIND_WARNING"
                ConditionAlarmSource.WIND_SHIFT->"WIND_SHIFT"
                ConditionAlarmSource.ALARM_TEST->"DATA_LOSS"
            }
            priority.indexOf(key).takeIf{it>=0}?:Int.MAX_VALUE
        }
    )
}
