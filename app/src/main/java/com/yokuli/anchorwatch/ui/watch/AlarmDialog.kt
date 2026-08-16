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
import androidx.compose.ui.platform.LocalClipboardManager
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

@Composable internal fun AnchorDragAlarmDialog(state:MainUiState,vm:MainViewModel){
    val active=state.active
    val alarm=state.alarmSnapshot
    if(alarm.state==AlarmState.ALARM&&alarm.type==AlarmType.ALARM_TEST){AlertDialog(onDismissRequest=vm::stopAlarmTest,confirmButton={Button(vm::stopAlarmTest){Text(tr("Stop test","停止测试"))}},title={Text(tr("Alarm test","警报测试"),color=MaterialTheme.colorScheme.error)},text={Text(tr("If you can hear the alarm, feel vibration and see this dialog, the foreground alarm path is working. Stop closes every part of this test immediately.","如果你能听到警报、感到振动并看到此弹窗，前台报警链路工作正常。点击停止会立即关闭本次测试的全部声音、振动和界面。"))});return}
    val snoozed=(active?.alarmSnoozedUntil?:0L)>System.currentTimeMillis()
    val visible=active?.paused==false&&!snoozed&&alarm.state==AlarmState.ALARM&&alarm.type!=null
    val radiusAlarm=alarm.type==AlarmType.ANCHOR_RADIUS_EXCEEDED
    val alarmTitle=when(alarm.type){AlarmType.ANCHOR_RADIUS_EXCEEDED->tr("ANCHOR DRAG ALARM","走锚警报");AlarmType.GPS_DATA_LOST->tr("GPS DATA LOST","GPS 数据丢失");AlarmType.GPS_QUALITY_BAD->tr("GPS QUALITY DEGRADED","GPS 质量下降");AlarmType.NMEA_CONNECTION_LOST->tr("NMEA CONNECTION LOST","NMEA 连接丢失");else->tr("ANCHOR WATCH ALARM","锚警系统警报")}
    var confirmLift by remember(active?.id){mutableStateOf(false)}
    LaunchedEffect(visible){if(!visible)confirmLift=false}
    if(!visible)return
    if(confirmLift){
        AlertDialog(onDismissRequest={},confirmButton={Button({vm.liftAnchor();confirmLift=false},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Lift anchor and end session","起锚并结束会话"))}},dismissButton={TextButton({confirmLift=false}){Text(tr("Back to alarm actions","返回警报操作"))}},title={Text(tr("End this anchoring session?","结束本次锚泊？"))},text={Text(tr("Lift anchor permanently closes the session and silences this alarm. Use Pause if the anchor is still down.","起锚会永久结束本次会话并停止警报；如果锚仍在水中，请使用暂停监控。"))})
    }else{
        Dialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false)){
            Surface(Modifier.fillMaxWidth().padding(horizontal=8.dp),shape=MaterialTheme.shapes.extraLarge,tonalElevation=8.dp){Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(alarmTitle,modifier=Modifier.testTag("in_app_anchor_alarm"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                if(radiusAlarm)Text(tr("The boat is ${alarm.distanceMeters?.toInt()?:"--"} m from the anchor centre; the limit is ${active.alarmRadiusMeters.toInt()} m.","船距锚点中心 ${alarm.distanceMeters?.toInt()?:"--"} 米，报警范围为 ${active.alarmRadiusMeters.toInt()} 米。"),style=MaterialTheme.typography.titleMedium)else Text(if(alarm.type==AlarmType.GPS_DATA_LOST)tr("No trusted position has arrived within the safety timeout. There is no silent failover. Restore the locked source, or pause/lift before starting with another source.","安全超时内没有收到可信定位，应用不会静默切源。请恢复当前锁定来源；如需换源，请暂停或起锚后重新开始。")else tr("Position quality has remained degraded. Suspicious fixes are kept out of the alarm and estimator.","定位质量持续下降；可疑定位不会进入报警与锚点估算。"),style=MaterialTheme.typography.titleMedium)
                Text(tr("Check the vessel and surroundings now. Monitoring continues unless you pause or lift anchor.","请立即检查船况和周边环境。除非暂停监控或起锚，否则锚警会继续运行。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(vm::acknowledge,Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("alarm_snooze_action")){Icon(Icons.Default.Snooze,null);Spacer(Modifier.width(8.dp));Text(tr("Snooze ${state.settings.alarmSnoozeMinutes} min","${state.settings.alarmSnoozeMinutes} 分钟后提醒"))}
                if(radiusAlarm)OutlinedButton({vm.acknowledge();vm.requestRangeEditor()},Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(8.dp));Text(tr("Snooze & adjust range","稍后提醒并调整范围"))}
                OutlinedButton(vm::pauseWatch,Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(8.dp));Text(tr("Pause watch","暂停监控"))}
                TextButton({confirmLift=true},Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Anchor,null);Spacer(Modifier.width(8.dp));Text(tr("Lift anchor and end session","起锚并结束会话"))}
            }}
        }
    }
}
