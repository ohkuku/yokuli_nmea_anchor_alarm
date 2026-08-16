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

@Composable @OptIn(ExperimentalMaterial3Api::class)
internal fun EstimatedCenterSheet(state:MainUiState,vm:MainViewModel,session:AnchorSessionEntity){
 val candidateLat=session.provisionalAnchorLatitude?:return;val candidateLon=session.provisionalAnchorLongitude?:return
 val shift=AnchorGeometry.distanceMeters(session.anchorLatitude,session.anchorLongitude,candidateLat,candidateLon);val alarmActive=state.alarmSnapshot.state==AlarmState.ALARM;var details by remember(session.candidateId){mutableStateOf(false)}
 ModalBottomSheet(onDismissRequest={}){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(tr("Good anchor estimate","已找到可靠锚点估算"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold);Text(tr("Candidate is ${shift.toInt()} m from the temporary alarm centre · uncertainty ±${session.provisionalRadiusMeters?.toInt()?:"--"} m.","候选锚点距临时报警中心 ${shift.toInt()} 米 · 不确定度 ±${session.provisionalRadiusMeters?.toInt()?:"--"} 米。"));Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Use this candidate to redraw the ${session.alarmRadiusMeters.toInt()} m alarm circle around the estimated anchor and leave learning mode? The radius itself will not change.","是否以该估算锚点重新绘制 ${session.alarmRadiusMeters.toInt()} 米报警圈并退出学习模式？报警半径本身不会改变。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)};TextButton({details=!details}){Text(if(details)tr("Hide estimation details","收起估算详情") else tr("Estimation details","估算详情"))};if(details)Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Text(tr("RMS ${session.candidateRmsErrorMeters?.let{"%.1f m".format(it)}?:"—"} · coverage ${session.candidateAngularCoverageDegrees?.toInt()?:0}° · ${session.candidateAngularSectorCount} sectors · ${session.candidateSwingReversalCount} reversals · ${session.candidateEffectiveDurationMillis/60_000} effective min · temporal ${if(session.candidateTemporalFitConsistent)"OK" else "pending"}","RMS ${session.candidateRmsErrorMeters?.let{"%.1f 米".format(it)}?:"—"} · 覆盖 ${session.candidateAngularCoverageDegrees?.toInt()?:0}° · ${session.candidateAngularSectorCount} 个扇区 · ${session.candidateSwingReversalCount} 次反转 · ${session.candidateEffectiveDurationMillis/60_000} 分钟有效数据 · 时间一致性 ${if(session.candidateTemporalFitConsistent)"通过" else "等待"}"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)};Button({vm.acceptEstimatedCenter(session)},enabled=!alarmActive,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(tr("Use centre & redraw alarm circle","使用中心并重绘报警圈"))};OutlinedButton({vm.rejectEstimatedCenter(session)},Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(tr("Keep learning","继续学习"))};if(alarmActive)Text(tr("Resolve or pause the active alarm before applying a new centre.","请先处理或暂停当前警报，再应用新中心。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(20.dp))}}
}
