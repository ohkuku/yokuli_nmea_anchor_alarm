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
import com.yokuli.anchorwatch.ui.theme.SafetyColors
import java.text.DateFormat

@Composable internal fun CompactWatchStatus(state:MainUiState,modifier:Modifier=Modifier){
    val active=state.active;val fix=state.fix;val health=state.positionHealth;val color=when(health){com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_OK->SafetyColors.Safe;com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_DEGRADED->SafetyColors.Warning;com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_LOST->SafetyColors.Alarm}
    val distance=if(active?.centerStatus==com.yokuli.anchorwatch.domain.model.AnchorCenterStatus.RESOLVED.name&&fix!=null)AnchorGeometry.distanceMeters(active.anchorLatitude,active.anchorLongitude,fix.latitude,fix.longitude)else null
    val status=when{active?.paused==true->tr("PAUSED","已暂停");state.alarmSnapshot.state==AlarmState.ALARM->tr("ALARM","报警");active!=null->tr("SAFE","安全");else->tr("STANDBY","待命")}
    Surface(modifier,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface.copy(alpha=.92f),tonalElevation=3.dp){Column(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.size(8.dp).background(color,MaterialTheme.shapes.small));Text(if(active!=null)"${distance?.toInt()?:"—"} m / ${active.alarmRadiusMeters.toInt()} m" else tr("Anchor watch off","锚警已关闭"),fontWeight=FontWeight.SemiBold);Text(status,style=MaterialTheme.typography.labelLarge,color=if(state.alarmSnapshot.state==AlarmState.ALARM)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)};Text(tr("Depth ${state.depthUi.liveDepthMeters?.let{"%.1f m".format(it)}?:"—"}","水深 ${state.depthUi.liveDepthMeters?.let{"%.1f 米".format(it)}?:"—"}"),style=MaterialTheme.typography.bodySmall)}}
}

@Composable internal fun MapNotConfigured() { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Card { Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.Map, null, Modifier.size(40.dp)); Text(tr("Google Maps is not configured","Google 地图尚未配置"), style = MaterialTheme.typography.titleMedium); Text(tr("Add a Maps SDK key at build time, then rebuild the app.","请在编译阶段加入 Maps SDK 密钥后重新构建应用。")) } } } }

internal fun displayHeading(fix:com.yokuli.anchorwatch.domain.model.NavigationFix,session:AnchorSessionEntity?,points:List<com.yokuli.anchorwatch.data.database.TrackPointEntity>):Double?{
    fix.headingTrueDegrees?.let{return it}
    val windHeading=WindAnchorEvidence.summarize(points.takeLast(300).map{point->WindAnchorEvidence.Sample(point.timestamp,point.latitude,point.longitude,point.sog,point.cog,point.heading.takeIf{point.headingMeasured},point.windDirectionTrue,point.trueWindAngle,point.apparentWindAngle,point.trueWindSpeedKnots,point.apparentWindSpeedKnots,point.headingSampleSequence,point.windSampleSequence)}).observations.lastOrNull{it.source!=WindAnchorEvidence.Source.PHYSICAL_HEADING&&it.source!=WindAnchorEvidence.Source.BACKDOWN_COG}?.headingToAnchorDegrees
    if(windHeading!=null)return windHeading
    return if(session?.placementMode==AnchorPlacementMode.BACKDOWN.name&&fix.cogTrueDegrees!=null&&(fix.sogKnots?:0.0)>=.8)(fix.cogTrueDegrees+180.0)%360.0 else fix.cogTrueDegrees?.takeIf{(fix.sogKnots?:0.0)>=.8}
}

internal data class FadingTrailChunk(val points:List<LatLng>,val alpha:Float)

/**
 * Fade by travelled distance, not by point-list position. The newest 600 m
 * stays strongly visible even when fixes arrive quickly; only older breadcrumb
 * history fades progressively.
 */
internal fun fadingTrailChunks(points:List<com.yokuli.anchorwatch.data.database.TrackPointEntity>):List<FadingTrailChunk>{
    val visible=TrailVisibilityPolicy.visiblePoints(points);if(visible.size<2)return emptyList()
    val distanceFromNewest=DoubleArray(visible.size)
    for(index in visible.lastIndex-1 downTo 0){val first=visible[index];val second=visible[index+1];distanceFromNewest[index]=distanceFromNewest[index+1]+AnchorGeometry.distanceMeters(first.latitude,first.longitude,second.latitude,second.longitude)}
    val maximumChunks=72;val step=kotlin.math.ceil((visible.size-1)/maximumChunks.toDouble()).toInt().coerceAtLeast(1)
    return (0 until visible.lastIndex step step).map{start->
        val from=(start-1).coerceAtLeast(0);val end=(start+step+1).coerceAtMost(visible.size);val distance=distanceFromNewest[(from+end-1)/2]
        val alpha=TrailVisibilityPolicy.alphaForDistanceFromNewest(distance)
        FadingTrailChunk(visible.subList(from,end).map{LatLng(it.latitude,it.longitude)},alpha)
    }
}

internal fun boatMarkerIcon():BitmapDescriptor{
    val bitmap=Bitmap.createBitmap(72,88,Bitmap.Config.ARGB_8888);val canvas=AndroidCanvas(bitmap);val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(0,188,212);style=Paint.Style.FILL};val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=5f;strokeJoin=Paint.Join.ROUND}
    val path=Path().apply{moveTo(36f,4f);lineTo(62f,68f);lineTo(36f,56f);lineTo(10f,68f);close()};canvas.drawPath(path,fill);canvas.drawPath(path,stroke);canvas.drawCircle(36f,46f,6f,stroke)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

internal fun anchorMarkerIcon():BitmapDescriptor{
    val bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888);val canvas=AndroidCanvas(bitmap);val background=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(255,183,77);style=Paint.Style.FILL};val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(25,31,43);style=Paint.Style.STROKE;strokeWidth=6f;strokeCap=Paint.Cap.ROUND};canvas.drawCircle(36f,36f,31f,background);canvas.drawCircle(36f,18f,6f,line);canvas.drawLine(36f,24f,36f,54f,line);canvas.drawLine(20f,35f,52f,35f,line);canvas.drawArc(19f,36f,53f,61f,0f,180f,false,line)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
