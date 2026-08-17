package com.yokuli.anchorwatch.runtime

import android.content.Intent
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorConfig
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.service.AnchorForegroundService

sealed interface RuntimeCommand {
    data class ArmWatch(
        val config:AnchorConfig,
        val placement:AnchorPlacementMode,
        val rangeMode:AnchorRangeMode,
        val safetyPreset:AnchorSafetyPreset,
        val boatLength:Double?,
        val positionSource:GpsDataSource?,
        val centerSource:AnchorCenterSource,
        val usePhoneHeading:Boolean,
        val depthSource:AnchorDepthSource=AnchorDepthSource.MANUAL,
        val conditions:ConditionGuardConfig=ConditionGuardConfig(),
        val savedAnchorageId:Long?=null,
    ):RuntimeCommand
    data object SnoozeAlarm:RuntimeCommand
    data object PauseWatch:RuntimeCommand
    data object ResumeWatch:RuntimeCommand
    data object LiftAnchor:RuntimeCommand
    data class UpdateRadius(val radiusMeters:Double):RuntimeCommand
    data object PauseWatchAndDisconnect:RuntimeCommand
    data class Candidate(val action:CandidateAction,val sessionId:Long,val candidateId:Long):RuntimeCommand
    data class UpdatePhoneHeading(val enabled:Boolean):RuntimeCommand
    data class UpdateConditionGuards(val config:ConditionGuardConfig):RuntimeCommand
    data object ResetWindBaseline:RuntimeCommand
    data object StartProxy:RuntimeCommand
    data object StopProxy:RuntimeCommand
    data object TestAlarm:RuntimeCommand
    data object StopAlarmTest:RuntimeCommand
    data class SetSharing(val enabled:Boolean,val port:Int):RuntimeCommand
    data class StartSonar(val name:String,val tideMode:TideMode,val manualTideOffsetMeters:Double,val tideStationId:String?):RuntimeCommand
    data object StopSonar:RuntimeCommand
    data object RestoreOnly:RuntimeCommand
    data class Unknown(val action:String?):RuntimeCommand
}

enum class CandidateAction { ACCEPT, KEEP_CURRENT, CONTINUE_ESTIMATING }

object RuntimeCommandParser {
    fun parse(intent:Intent?):RuntimeCommand{
        if(intent==null)return RuntimeCommand.RestoreOnly
        return when(intent.action){
            AnchorForegroundService.ARM->{
                val config=AnchorConfig(intent.getDoubleExtra("lat",0.0),intent.getDoubleExtra("lon",0.0),intent.getDoubleExtra("rode",0.0),intent.getDoubleExtra("depth",Double.NaN).takeUnless(Double::isNaN),bowRollerHeightMeters=intent.getDoubleExtra("bowHeight",0.0),gpsAntennaOffsetMeters=intent.getDoubleExtra("antennaOffset",0.0),warningRadiusMeters=intent.getDoubleExtra("warning",40.0),alarmRadiusMeters=intent.getDoubleExtra("alarm",50.0))
                RuntimeCommand.ArmWatch(config,enum(intent,"placement",AnchorPlacementMode.CENTER_DROP),enum(intent,"rangeMode",AnchorRangeMode.BASIC),enum(intent,"safetyPreset",AnchorSafetyPreset.BALANCED),intent.getDoubleExtra("boatLength",Double.NaN).takeUnless(Double::isNaN),intent.getStringExtra("positionSource")?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()},enum(intent,"centerSource",AnchorCenterSource.CURRENT_POSITION),intent.getBooleanExtra("usePhoneHeading",false),enum(intent,"depthSource",AnchorDepthSource.MANUAL),ConditionGuardConfig(
                    depthGuardEnabled=intent.getBooleanExtra("depthGuard",false),
                    shallowDepthAlarmMeters=intent.getDoubleExtra("shallowDepth",Double.NaN).takeUnless(Double::isNaN),
                    deepDepthAlarmMeters=intent.getDoubleExtra("deepDepth",Double.NaN).takeUnless(Double::isNaN),
                    windGuardEnabled=intent.getBooleanExtra("windGuard",false),
                    windWarningKnots=intent.getDoubleExtra("windWarning",Double.NaN).takeUnless(Double::isNaN),
                    windAlarmKnots=intent.getDoubleExtra("windAlarm",Double.NaN).takeUnless(Double::isNaN),
                    windShiftEnabled=intent.getBooleanExtra("windShift",false),
                    windShiftThresholdDegrees=intent.getDoubleExtra("windShiftDegrees",Double.NaN).takeUnless(Double::isNaN),
                    windAllowApparentFallback=intent.getBooleanExtra("apparentFallback",true),
                ).validated(),intent.getLongExtra("savedAnchorageId",-1L).takeIf{it>0})
            }
            AnchorForegroundService.ACK,AnchorForegroundService.SNOOZE->RuntimeCommand.SnoozeAlarm
            AnchorForegroundService.STOP_WATCH,AnchorForegroundService.PAUSE_WATCH->RuntimeCommand.PauseWatch
            AnchorForegroundService.RESUME_WATCH->RuntimeCommand.ResumeWatch
            AnchorForegroundService.LIFT_ANCHOR->RuntimeCommand.LiftAnchor
            AnchorForegroundService.UPDATE_RADIUS->RuntimeCommand.UpdateRadius(intent.getDoubleExtra("alarm",Double.NaN))
            AnchorForegroundService.STOP_WATCH_AND_DISCONNECT->RuntimeCommand.PauseWatchAndDisconnect
            AnchorForegroundService.ACCEPT_ESTIMATED_CENTER->candidate(intent,CandidateAction.ACCEPT)
            AnchorForegroundService.KEEP_CURRENT_CENTER->candidate(intent,CandidateAction.KEEP_CURRENT)
            AnchorForegroundService.CONTINUE_ESTIMATING_CENTER->candidate(intent,CandidateAction.CONTINUE_ESTIMATING)
            AnchorForegroundService.UPDATE_PHONE_HEADING->RuntimeCommand.UpdatePhoneHeading(intent.getBooleanExtra("enabled",false))
            AnchorForegroundService.UPDATE_CONDITION_GUARDS->RuntimeCommand.UpdateConditionGuards(ConditionGuardConfig(
                depthGuardEnabled=intent.getBooleanExtra("depthGuard",false),shallowDepthAlarmMeters=intent.getDoubleExtra("shallowDepth",Double.NaN).takeUnless(Double::isNaN),deepDepthAlarmMeters=intent.getDoubleExtra("deepDepth",Double.NaN).takeUnless(Double::isNaN),windGuardEnabled=intent.getBooleanExtra("windGuard",false),windWarningKnots=intent.getDoubleExtra("windWarning",Double.NaN).takeUnless(Double::isNaN),windAlarmKnots=intent.getDoubleExtra("windAlarm",Double.NaN).takeUnless(Double::isNaN),windShiftEnabled=intent.getBooleanExtra("windShift",false),windShiftThresholdDegrees=intent.getDoubleExtra("windShiftDegrees",Double.NaN).takeUnless(Double::isNaN),windAllowApparentFallback=intent.getBooleanExtra("apparentFallback",true),
            ).validated())
            AnchorForegroundService.RESET_WIND_BASELINE->RuntimeCommand.ResetWindBaseline
            AnchorForegroundService.START_PROXY->RuntimeCommand.StartProxy
            AnchorForegroundService.STOP_PROXY->RuntimeCommand.StopProxy
            AnchorForegroundService.TEST_ALARM->RuntimeCommand.TestAlarm
            AnchorForegroundService.STOP_ALARM_TEST->RuntimeCommand.StopAlarmTest
            AnchorForegroundService.SET_NMEA_SHARING->RuntimeCommand.SetSharing(intent.getBooleanExtra("enabled",false),intent.getIntExtra("port",10111))
            AnchorForegroundService.START_SONAR_SURVEY->RuntimeCommand.StartSonar(intent.getStringExtra("name")?:"Sonar survey",enum(intent,"tideMode",TideMode.OFF),intent.getDoubleExtra("manualTideOffset",0.0),intent.getStringExtra("tideStationId"))
            AnchorForegroundService.STOP_SONAR_SURVEY->RuntimeCommand.StopSonar
            else->RuntimeCommand.Unknown(intent.action)
        }
    }
    private fun candidate(intent:Intent,action:CandidateAction)=RuntimeCommand.Candidate(action,intent.getLongExtra("sessionId",-1),intent.getLongExtra("candidateId",-1))
    private inline fun <reified T:Enum<T>> enum(intent:Intent,key:String,default:T)=runCatching{enumValueOf<T>(intent.getStringExtra(key)?:default.name)}.getOrDefault(default)
}
