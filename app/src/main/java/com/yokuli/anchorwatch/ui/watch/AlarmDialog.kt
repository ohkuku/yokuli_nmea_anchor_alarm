package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.os.PowerManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchor.AnchorRangeCalculator
import com.yokuli.anchorwatch.domain.anchor.CoordinateParser
import com.yokuli.anchorwatch.domain.anchor.WindAnchorEvidence
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorPositionMode
import com.yokuli.anchorwatch.domain.model.CandidateDecision
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.DemoScenario
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.condition.DepthGuardStatus
import com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus
import com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus
import com.yokuli.anchorwatch.domain.condition.ConditionAlarmSource
import com.yokuli.anchorwatch.domain.condition.SafetyAlert
import com.yokuli.anchorwatch.domain.condition.SafetyAlertAggregator
import com.yokuli.anchorwatch.domain.safety.SafetyRecoveryDestination
import com.yokuli.anchorwatch.domain.safety.SafetyRecoveryPolicy
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat

internal data class AlarmPresentation(val title:String,val primaryValue:String,val detail:String)

@Composable private fun alarmPresentation(primary:String?,alarm:com.yokuli.anchorwatch.domain.model.AlarmSnapshot,active:AnchorSessionEntity,state:MainUiState):AlarmPresentation=when(primary){
    "SHALLOW"->AlarmPresentation(tr("SHALLOW WATER ALARM","浅水警报"),state.conditions.depth.filteredDepthMeters?.let{"%.1f m".format(it)}?:"—",tr("Below the ${active.shallowDepthAlarmMeters} m shallow threshold.","低于 ${active.shallowDepthAlarmMeters} 米浅水阈值。"))
    "DEEP"->AlarmPresentation(tr("DEEP WATER ALARM","深水警报"),state.conditions.depth.filteredDepthMeters?.let{"%.1f m".format(it)}?:"—",tr("Above the ${active.deepDepthAlarmMeters} m deep-water threshold.","高于 ${active.deepDepthAlarmMeters} 米深水阈值。"))
    "WIND"->AlarmPresentation(tr("HIGH WIND ALARM","大风警报"),state.conditions.windSpeed.filteredSpeedKnots?.let{"%.1f kn ${state.conditions.windSpeed.source}".format(it)}?:"—",tr("The alarm threshold is ${active.windAlarmKnots} kn.","警报阈值为 ${active.windAlarmKnots} 节。"))
    "SHIFT"->AlarmPresentation(tr("WIND SHIFT ALARM","风向突变警报"),state.conditions.windShift.shiftDegrees?.let{"${it.toInt()}°"}?:"—",tr("Fixed baseline ${state.conditions.windShift.baselineDirectionDegrees?.toInt()?:"—"}° → current ${state.conditions.windShift.currentDirectionDegrees?.toInt()?:"—"}°.","固定基线 ${state.conditions.windShift.baselineDirectionDegrees?.toInt()?:"—"}° → 当前 ${state.conditions.windShift.currentDirectionDegrees?.toInt()?:"—"}°。"))
    "DEPTH_DATA"->AlarmPresentation(tr("DEPTH DATA LOST","水深数据丢失"),"—",tr("The depth guard is still enabled, but it cannot evaluate safety without fresh DPT/DBT data. Reconnect NMEA or explicitly disable this guard.","水深警戒仍处于开启状态，但缺少新鲜 DPT/DBT 数据时无法判断安全。请恢复 NMEA，或明确关闭该警戒。"))
    "WIND_DATA"->AlarmPresentation(tr("WIND DATA LOST","风数据丢失"),"—",tr("A wind guard is still enabled, but its required live NMEA data is unavailable. Reconnect NMEA or explicitly disable the unavailable guard.","风警戒仍处于开启状态，但所需实时 NMEA 数据不可用。请恢复 NMEA，或明确关闭不可用的警戒。"))
    else->when(alarm.type){
        AlarmType.ANCHOR_RADIUS_EXCEEDED->AlarmPresentation(tr("ANCHOR DRAG ALARM","走锚警报"),"${alarm.distanceMeters?.toInt()?:"—"} m",tr("The alarm radius is ${active.alarmRadiusMeters.toInt()} m.","报警范围为 ${active.alarmRadiusMeters.toInt()} 米。"))
        AlarmType.GPS_DATA_LOST->AlarmPresentation(tr("GPS DATA LOST","GPS 数据丢失"),"—",tr("No trusted position arrived within the safety timeout. There is no silent failover. Snooze, then reconnect; or Pause, verify another live source for this same session, and Resume.","安全超时内没有收到可信定位，应用不会静默切源。你可以稍后提醒并重连；也可以暂停后为同一会话验证另一实时来源，再继续监控。"))
        AlarmType.GPS_QUALITY_BAD->AlarmPresentation(tr("GPS QUALITY DEGRADED","GPS 质量下降"),"—",tr("Suspicious fixes are kept out of both the alarm and centre estimator.","可疑定位不会进入报警或锚点估算。"))
        AlarmType.NMEA_CONNECTION_LOST->AlarmPresentation(tr("NMEA CONNECTION LOST","NMEA 连接丢失"),"—",tr("The locked NMEA source needs recovery. Automatic retry depends on the saved server profile; no silent GPS-source switch is performed.","锁定的 NMEA 数据源需要恢复；是否自动重试取决于已保存的服务器配置，应用不会静默切换 GPS 来源。"))
        else->AlarmPresentation(tr("ANCHOR WATCH ALARM","锚警系统警报"),"—",tr("A safety alarm is active.","安全警报正在生效。"))
    }
}

@Composable internal fun AnchorDragAlarmDialog(state:MainUiState,vm:MainViewModel){
    val active=state.active
    val alarm=state.alarmSnapshot
    // A test must never open the blocking real-alarm dialog: it would cover the
    // confirmation controls the user is trying to verify. AnchorApp shows a global,
    // non-modal banner instead.
    if(alarm.type==AlarmType.ALARM_TEST)return
    val now=System.currentTimeMillis();val anchorAudible=alarm.state==AlarmState.ALARM&&alarm.type!=null&&(active?.alarmSnoozedUntil?:0L)<=now
    val depthAudible=(state.conditions.depth.alarmActive||state.conditions.depth.dataUnavailable)&&(active?.depthAlarmSnoozedUntil?:0L)<=now
    val windAudible=(state.conditions.windSpeed.alarmActive||state.conditions.windSpeed.dataUnavailable)&&(active?.windAlarmSnoozedUntil?:0L)<=now
    val shiftAudible=(state.conditions.windShift.alarmActive||state.conditions.windShift.dataUnavailable)&&(active?.windShiftAlarmSnoozedUntil?:0L)<=now
    val activeAlerts=buildList{
        if(anchorAudible)add(SafetyAlert(ConditionAlarmSource.ANCHOR,SafetyAlert.Severity.ALARM,if(alarm.type==AlarmType.ANCHOR_RADIUS_EXCEEDED)"anchor" else "critical source lost",alarm.type?.name?:""))
        if(depthAudible)add(SafetyAlert(ConditionAlarmSource.DEPTH,SafetyAlert.Severity.ALARM,when{state.conditions.depth.dataUnavailable->"depth data lost";state.conditions.depth.status==DepthGuardStatus.SHALLOW_ALARM->"shallow";else->"deep"},state.conditions.depth.status.name))
        if(windAudible)add(SafetyAlert(ConditionAlarmSource.WIND_SPEED,SafetyAlert.Severity.ALARM,if(state.conditions.windSpeed.dataUnavailable)"wind data lost" else "wind speed alarm",state.conditions.windSpeed.status.name))
        if(shiftAudible)add(SafetyAlert(ConditionAlarmSource.WIND_SHIFT,SafetyAlert.Severity.ALARM,if(state.conditions.windShift.dataUnavailable)"wind direction data lost" else "wind shift",state.conditions.windShift.status.name))
    }
    val primaryAlert=SafetyAlertAggregator.sorted(activeAlerts).firstOrNull()
    val primary=when(primaryAlert?.source){ConditionAlarmSource.DEPTH->if(state.conditions.depth.dataUnavailable)"DEPTH_DATA" else if(state.conditions.depth.status==DepthGuardStatus.SHALLOW_ALARM)"SHALLOW" else "DEEP";ConditionAlarmSource.ANCHOR->"ANCHOR";ConditionAlarmSource.WIND_SPEED->if(state.conditions.windSpeed.dataUnavailable)"WIND_DATA" else "WIND";ConditionAlarmSource.WIND_SHIFT->if(state.conditions.windShift.dataUnavailable)"WIND_DATA" else "SHIFT";else->null}
    val visible=active?.paused==false&&primary!=null
    val radiusAlarm=primary=="ANCHOR"&&alarm.type==AlarmType.ANCHOR_RADIUS_EXCEEDED
    val positionSource=runCatching{GpsDataSource.valueOf(active?.positionSource.orEmpty())}.getOrDefault(state.settings.gpsDataSource)
    val recoveryDestination=SafetyRecoveryPolicy.destination(positionSource,alarm.type,primary=="DEPTH_DATA"||primary=="WIND_DATA")
    val presentation=active?.let{alarmPresentation(primary,alarm,it,state)}
    var confirmLift by remember(active?.id){mutableStateOf(false)}
    LaunchedEffect(visible){if(!visible)confirmLift=false}
    if(!visible)return
    if(confirmLift){
        AlertDialog(onDismissRequest={},confirmButton={Button({vm.liftAnchor();confirmLift=false},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Lift anchor and end session","起锚并结束会话"))}},dismissButton={TextButton({confirmLift=false}){Text(tr("Back to alarm actions","返回警报操作"))}},title={Text(tr("End this anchoring session?","结束本次锚泊？"))},text={Text(tr("Lift anchor permanently closes the session and silences this alarm. Use Pause if the anchor is still down.","起锚会永久结束本次会话并停止警报；如果锚仍在水中，请使用暂停监控。"))})
    }else{
        Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)){
            Surface(Modifier.fillMaxWidth().padding(horizontal=8.dp),shape=MaterialTheme.shapes.extraLarge,tonalElevation=8.dp){Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(presentation?.title?:tr("ANCHOR WATCH ALARM","锚警系统警报"),modifier=Modifier.testTag("in_app_anchor_alarm"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                presentation?.primaryValue?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}
                Text(presentation?.detail.orEmpty(),style=MaterialTheme.typography.titleMedium)
                if(activeAlerts.size>1)Text(tr("+ ${activeAlerts.size-1} other active alert${if(activeAlerts.size>2)"s" else ""}","另有 ${activeAlerts.size-1} 项警报仍在生效"),fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.error)
                Text(tr("Check the vessel and surroundings now. Monitoring continues unless you pause or lift anchor.","请立即检查船况和周边环境。除非暂停监控或起锚，否则锚警会继续运行。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(vm::acknowledge,Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("alarm_snooze_action")){Icon(Icons.Default.Snooze,null);Spacer(Modifier.width(8.dp));Text(tr("Snooze ${state.settings.alarmSnoozeMinutes} min","${state.settings.alarmSnoozeMinutes} 分钟后提醒"))}
                if(recoveryDestination==SafetyRecoveryDestination.NMEA)OutlinedButton({vm.acknowledge();vm.openDataSection(1)},Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("alarm_open_nmea_recovery")){Icon(Icons.Default.SettingsEthernet,null);Spacer(Modifier.width(8.dp));Text(tr("Snooze & open NMEA recovery","稍后提醒并处理 NMEA"))}
                if(recoveryDestination==SafetyRecoveryDestination.SYSTEM_GPS)OutlinedButton({vm.pauseWatch();vm.page(3)},Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("alarm_open_system_gps_recovery")){Icon(Icons.Default.GpsFixed,null);Spacer(Modifier.width(8.dp));Text(tr("Pause & open Phone GPS recovery","暂停并处理手机 GPS"))}
                if(radiusAlarm)OutlinedButton({vm.acknowledge();vm.requestRangeEditor()},Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(8.dp));Text(tr("Snooze & adjust range","稍后提醒并调整范围"))}
                OutlinedButton(vm::pauseWatch,Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(8.dp));Text(tr("Pause watch","暂停监控"))}
                TextButton({confirmLift=true},Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Anchor,null);Spacer(Modifier.width(8.dp));Text(tr("Lift anchor and end session","起锚并结束会话"))}
            }}
        }
    }
}
