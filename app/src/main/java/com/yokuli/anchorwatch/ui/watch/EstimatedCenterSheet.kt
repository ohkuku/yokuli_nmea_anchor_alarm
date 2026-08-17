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

@Composable
internal fun EstimatedCenterBanner(state:MainUiState,vm:MainViewModel,session:AnchorSessionEntity){
 val candidateLat=session.provisionalAnchorLatitude?:return
 val candidateLon=session.provisionalAnchorLongitude?:return
 val shift=AnchorGeometry.distanceMeters(session.anchorLatitude,session.anchorLongitude,candidateLat,candidateLon)
 val unsafe=state.alarmSnapshot.state in setOf(AlarmState.WARNING,AlarmState.ALARM,AlarmState.ACKNOWLEDGED)
 var details by remember(session.candidateId){mutableStateOf(false)}
 Surface(color=MaterialTheme.colorScheme.secondaryContainer,tonalElevation=4.dp){
  Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){
     Text(tr("Estimated centre ready","估算中心已就绪"),fontWeight=FontWeight.SemiBold)
     Text(tr("Shift ${shift.toInt()} m · uncertainty ±${session.provisionalRadiusMeters?.toInt()?:"--"} m","移动 ${shift.toInt()} 米 · 不确定度 ±${session.provisionalRadiusMeters?.toInt()?:"--"} 米"),style=MaterialTheme.typography.bodySmall)
    }
    TextButton({details=!details}){Text(if(details)tr("Less","收起") else tr("Details","详情"))}
   }
   if(details)Text(tr("RMS ${session.candidateRmsErrorMeters?.let{"%.1f m".format(it)}?:"—"} · coverage ${session.candidateAngularCoverageDegrees?.toInt()?:0}° · ${session.candidateAngularSectorCount} sectors · ${session.candidateSwingReversalCount} reversals","RMS ${session.candidateRmsErrorMeters?.let{"%.1f 米".format(it)}?:"—"} · 覆盖 ${session.candidateAngularCoverageDegrees?.toInt()?:0}° · ${session.candidateAngularSectorCount} 扇区 · ${session.candidateSwingReversalCount} 次反转"),style=MaterialTheme.typography.bodySmall)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
    Button({vm.acceptEstimatedCenter(session)},enabled=!unsafe,modifier=Modifier.weight(1f)){Text(tr("Use estimated","采用估算"))}
    OutlinedButton({vm.keepCurrentCenter(session)},enabled=!unsafe,modifier=Modifier.weight(1f)){Text(tr("Keep current","保留当前"))}
    TextButton({vm.continueEstimatingCenter(session)},Modifier.weight(1f)){Text(tr("Keep learning","继续估算"))}
   }
   if(unsafe)Text(tr("Use/Keep is locked during warning or alarm; you may continue estimating.","警告或报警期间不可采用/保留中心；仍可继续估算。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  }
 }
}
