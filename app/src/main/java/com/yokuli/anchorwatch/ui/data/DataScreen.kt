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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.sonar.SonarRecorderStatus
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.VesselObservation
import com.yokuli.anchorwatch.domain.vessel.VesselMetricId
import com.yokuli.anchorwatch.domain.vessel.VesselSourceCandidate
import com.yokuli.anchorwatch.domain.vessel.toLegacySource
import com.yokuli.anchorwatch.domain.vessel.persistentKey
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
import com.yokuli.anchorwatch.domain.sonar.TideMode
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartDecision
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyContinuityPolicy
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyContinuityState
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.MetricLabelRegistry
import com.yokuli.anchorwatch.data.tide.TideStationCatalog
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.map.SonarTileDiagnostics
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DataPage(state:MainUiState,vm:MainViewModel){
    val pager=rememberPagerState(initialPage=state.dataSection,pageCount={4});val scope=rememberCoroutineScope()
    LaunchedEffect(state.dataSection){if(pager.currentPage!=state.dataSection)pager.scrollToPage(state.dataSection)}
    LaunchedEffect(pager){snapshotFlow{pager.currentPage}.collect(vm::rememberDataSection)}
    Column(Modifier.fillMaxSize().testTag("data_page")){
        PrimaryTabRow(selectedTabIndex=pager.currentPage){
            listOf(tr("Sources","来源"),tr("Input","输入"),tr("Share","共享"),tr("Sonar","声呐")).forEachIndexed{index,label->
                Tab(selected=pager.currentPage==index,onClick={scope.launch{pager.animateScrollToPage(index)}},modifier=Modifier.testTag("data_tab_${listOf("vessel","input","output","sonar")[index]}"),text={Text(label,maxLines=1)})
            }
        }
        ClickOnlyWorkspacePager(pager,Modifier.weight(1f)){section->when(section){0->VesselDataSourcesPage(state,vm);1->NmeaWorkspacePage(state,vm);2->DataOutputSettingsPage(state,vm);else->SonarSurveyPage(state,vm)}}
    }
}

@Composable private fun NmeaWorkspacePage(state:MainUiState,vm:MainViewModel){
    var raw by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!raw,{raw=false},label={Text(tr("Connection","连接"))},modifier=Modifier.weight(1f))
            FilterChip(raw,{raw=true},label={Text(tr("Raw & health","原始数据与健康"))},modifier=Modifier.weight(1f))
        }
        Box(Modifier.weight(1f)){if(raw)NmeaDataPage(state,vm)else ConnectionPage(state,vm)}
    }
}

@Composable @OptIn(ExperimentalMaterial3Api::class) private fun VesselDataSourcesPage(state:MainUiState,vm:MainViewModel){
    val data=state.vesselData
    var detailMetric by remember{mutableStateOf<VesselMetricId?>(null)}
    var showGpsProxy by remember{mutableStateOf(false)}
    var showDeveloperTools by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader(tr("Position & vessel sources","定位与船舶数据源"),tr("This is the only place to choose the App GPS source, Demo GPS and Android GPS proxy. Anchor setup only reports the choice made here.","这里是选择 App GPS 来源、演示 GPS 和 Android GPS 代理的唯一入口。下锚设置只会显示这里已经作出的选择。"))}
        item{Column(Modifier.fillMaxWidth().testTag("data_gps_controls"),verticalArrangement=Arrangement.spacedBy(12.dp)){
            GpsDataSourceCard(state,vm)
            OutlinedButton(
                onClick={showGpsProxy=!showGpsProxy},
                modifier=Modifier.fillMaxWidth().testTag("data_gps_proxy_toggle"),
            ){
                Icon(if(showGpsProxy)Icons.Default.ExpandLess else Icons.Default.GpsFixed,null)
                Spacer(Modifier.width(6.dp))
                Text(if(showGpsProxy)tr("Hide NMEA → Android GPS proxy","收起 NMEA → Android GPS 代理")else tr("NMEA → Android GPS proxy","NMEA → Android GPS 代理"))
            }
            if(showGpsProxy)GpsProxyCard(state,vm)
            OutlinedButton(
                onClick={showDeveloperTools=!showDeveloperTools},
                modifier=Modifier.fillMaxWidth().testTag("data_developer_tools_toggle"),
            ){
                Icon(if(showDeveloperTools)Icons.Default.ExpandLess else Icons.Default.Code,null)
                Spacer(Modifier.width(6.dp))
                Text(if(showDeveloperTools)tr("Hide Developer & Demo tools","收起开发者与演示工具")else tr("Developer & Demo tools","开发者与演示工具"))
            }
            if(showDeveloperTools)DeveloperSettingsCard(state,vm)
        }}
        item{HorizontalDivider();Text(tr("Instrument routing","仪表数据路由"),style=MaterialTheme.typography.titleMedium);Text(tr("These preferences only decide which proven sensor feeds App instruments and calculations. They do not change the GPS source selected above or publish Boat input back onto NMEA.","这里的偏好只决定哪个可信传感器供 App 仪表和计算使用，不会改变上方选择的 GPS 来源，也不会把船载输入重新发布回 NMEA。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{Card{Column{
            SourceRoutingSummaryRow(tr("Instrument position routing","仪表船位路由"),state.vesselSettings.positionPreference,data.position){detailMetric=VesselMetricId.POSITION}
            HorizontalDivider(Modifier.padding(horizontal=14.dp))
            SourceRoutingSummaryRow(tr("Heading source","船首向来源"),state.vesselSettings.headingPreference,data.headingTrueDegrees){detailMetric=VesselMetricId.HEADING_TRUE}
        }}}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(tr("Live vessel data","实时船舶数据"),style=MaterialTheme.typography.titleMedium)
            VesselSourceRow(metricName(InstrumentTileId.POSITION),data.position.value?.let{"%.5f, %.5f".format(it.latitude,it.longitude)}?:"—",data.position){detailMetric=VesselMetricId.POSITION}
            VesselSourceRow(metricName(InstrumentTileId.HEADING),data.headingTrueDegrees.value?.let{"%03.0f°T".format(it)}?:data.headingMagneticDegrees.value?.let{"%03.0f°M".format(it)}?:"—",data.headingTrueDegrees.takeIf{it.value!=null}?:data.headingMagneticDegrees){detailMetric=VesselMetricId.HEADING_TRUE}
            VesselSourceRow(metricName(InstrumentTileId.SOG),data.sogKnots.value?.let{"%.1f kn".format(it)}?:"—",data.sogKnots){detailMetric=VesselMetricId.SOG}
            VesselSourceRow(metricName(InstrumentTileId.BOAT_SPEED),data.speedThroughWaterKnots.value?.let{"%.1f kn".format(it)}?:"—",data.speedThroughWaterKnots){detailMetric=VesselMetricId.SPEED_THROUGH_WATER}
            VesselSourceRow("${metricName(InstrumentTileId.APPARENT_WIND_ANGLE)} / ${MetricLabelRegistry.get(InstrumentTileId.APPARENT_WIND_SPEED).acronym}",listOfNotNull(data.apparentWind.angleDegrees.value?.let{windDataAngle(it)},data.apparentWind.speedKnots.value?.let{"%.1f kn".format(it)}).joinToString(" · ").ifBlank{"—"},data.apparentWind.angleDegrees.takeIf{it.value!=null}?:data.apparentWind.speedKnots){detailMetric=VesselMetricId.APPARENT_WIND_ANGLE}
            VesselSourceRow("${metricName(InstrumentTileId.TRUE_WIND_ANGLE)} / ${MetricLabelRegistry.get(InstrumentTileId.TRUE_WIND_SPEED).acronym}",listOfNotNull(data.trueWind.angleDegrees.value?.let{windDataAngle(it)},data.trueWind.speedKnots.value?.let{"%.1f kn".format(it)},data.trueWind.directionDegrees.value?.let{"%03.0f°T".format(it)}).joinToString(" · ").ifBlank{"—"},data.trueWind.directionDegrees.takeIf{it.value!=null}?:data.trueWind.speedKnots){detailMetric=VesselMetricId.TRUE_WIND_SPEED}
            VesselSourceRow(metricName(InstrumentTileId.HEEL),data.attitude.value?.heelDegrees?.let{"%+.1f°".format(it)}?:"—",data.attitude){detailMetric=VesselMetricId.HEEL}
            VesselSourceRow(metricName(InstrumentTileId.PRESSURE),data.pressureHpa.value?.let{"%.1f hPa".format(it)}?:"—",data.pressureHpa){detailMetric=VesselMetricId.PRESSURE}
            VesselSourceRow(metricName(InstrumentTileId.DEPTH),data.depthMeters.value?.let{"%.1f m".format(it)}?:"—",data.depthMeters){detailMetric=VesselMetricId.DEPTH}
            Text(tr("Held values keep their original receive time; a blank NMEA field never erases the last observation.","保留值始终携带原始接收时间；NMEA 空字段不会清除上一条观测。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}}
        if(data.candidates.isNotEmpty())item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(tr("All sources & conflicts","全部来源与冲突"),style=MaterialTheme.typography.titleMedium)
            Text(tr("Every live candidate is retained. Selection never merges conflicting sensors.","所有实时候选都会保留；来源冲突时不会把多个传感器平均合并。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            data.candidates.toSortedMap(compareBy{it.name}).forEach{(metric,candidates)->
                val metricLabel=MetricLabelRegistry.get(metric)
                Row(Modifier.fillMaxWidth().clickable{detailMetric=metric}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${MetricLabelRegistry.localizedName(metric,LocalAppLanguage.current)} (${metricLabel.acronym})",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelMedium);Text(tr("${candidates.size} sources","${candidates.size} 个来源"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(data.conflicts[metric]?.active==true)Icon(Icons.Default.Warning,tr("Source conflict","来源冲突"),tint=MaterialTheme.colorScheme.error);Icon(Icons.Default.ChevronRight,null)}
            }
        }}}
    }
    detailMetric?.let{metric->ModalBottomSheet(onDismissRequest={detailMetric=null}){VesselSourceDetailSheet(metric,state,vm)}}
}

@Composable private fun VesselSourceDetailSheet(metric:VesselMetricId,state:MainUiState,vm:MainViewModel){
    val data=state.vesselData;val observation=observationForMetric(metric,data);val candidates=data.candidates[metric].orEmpty();val conflict=data.conflicts[metric]
    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{val label=MetricLabelRegistry.get(metric);Column{Text("${MetricLabelRegistry.localizedName(metric,LocalAppLanguage.current)} (${label.acronym})",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(tr("Canonical value and every retained source","当前采用值与全部保留来源"),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(tr("Current selection","当前采用"),style=MaterialTheme.typography.titleMedium)
            DetailLine(tr("Value","数值"),formatVesselValue(metric,observation.value))
            DetailLine(tr("Selected source","选定来源"),observation.sourceIdentity?.displayName?:vesselSourceLabel(observation))
            DetailLine(tr("Reference","参考系"),vesselReferenceLabel(observation.reference))
            val detailNow=android.os.SystemClock.elapsedRealtime()
            DetailLine(tr("Measurement age","测量年龄"),observation.receivedElapsedRealtime?.let{"%.1f s".format((detailNow-it).coerceAtLeast(0L)/1_000.0)}?:"—")
            DetailLine(tr("Source heartbeat age","来源心跳年龄"),observation.sourceHeartbeatElapsedRealtime?.let{"%.1f s".format((detailNow-it).coerceAtLeast(0L)/1_000.0)}?:"—")
            DetailLine(tr("Quality","质量"),vesselQualityLabel(observation.quality))
            DetailLine(tr("State","状态"),vesselFreshnessLabel(observation.freshness))
            DetailLine(tr("Provenance","来源链"),vesselProvenanceLabel(observation))
            DetailLine(tr("Selection reason","选择原因"),observation.selectionReason?:"—")
            if(conflict?.active==true)Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.small){Text(tr("Source conflict: eligible sensors disagree. The App selects one and never averages them.","来源冲突：合格传感器互相矛盾。应用只选择一个，绝不会将它们平均。"),Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodySmall)}
        }}}
        if(metric==VesselMetricId.POSITION)item{SourceRoutingCard(tr("Instrument position routing","仪表船位路由"),state.vesselSettings.positionPreference,data.position){vm.updateVesselDataSettings(state.vesselSettings.copy(positionPreference=it))}}
        if(metric in setOf(VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC))item{SourceRoutingCard(tr("Vessel heading strategy","船艏向来源策略"),state.vesselSettings.headingPreference,data.headingTrueDegrees){vm.updateVesselDataSettings(state.vesselSettings.copy(headingPreference=it))}}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text(tr("Available sources","可用来源"),style=MaterialTheme.typography.titleMedium)
            if(candidates.isEmpty())Text(tr("No source has reported this metric yet.","尚未有来源报告此数据。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
            candidates.forEach{candidate->
                    val selected=candidate.source.id==observation.sourceIdentity?.id
                Surface(color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(candidate.source.displayName,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);if(selected)AssistChip({},label={Text(tr("Selected","已采用"))})}
                    Text("${formatVesselValue(metric,candidate.value)} · ${candidateValidityLabel(candidate.validity)} · ${"%.1f s".format((android.os.SystemClock.elapsedRealtime()-candidate.receivedElapsedRealtime).coerceAtLeast(0L)/1_000.0)}",style=MaterialTheme.typography.bodySmall)
                    Text(listOfNotNull(candidate.source.fullSentenceId,candidate.source.transducerName,candidate.source.transportProfileId?.let{tr("profile $it","配置 $it")},candidate.source.connectionGeneration?.let{tr("generation $it","连接代次 $it")}).joinToString(" · ").ifBlank{candidate.source.id},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val boatSource=candidate.sourceClass==com.yokuli.anchorwatch.domain.vessel.VesselSourceClass.BOAT_NMEA
                    val storedPin=when(metric){VesselMetricId.POSITION->state.vesselSettings.pinnedPositionSourceId;VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC->state.vesselSettings.boatHeadingSourceId;else->null}
                    val pinned=storedPin?.let{com.yokuli.anchorwatch.domain.vessel.VesselSourcePinPolicy.matches(candidate.source,it)}==true
                    if(boatSource&&metric in setOf(VesselMetricId.POSITION,VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC)){TextButton({val key=candidate.source.persistentKey;vm.updateVesselDataSettings(when(metric){VesselMetricId.POSITION->state.vesselSettings.copy(pinnedPositionSourceId=if(pinned)null else key);else->state.vesselSettings.copy(boatHeadingSourceId=if(pinned)null else key)})}){Text(if(pinned)tr("Unpin source","取消固定来源")else tr("Pin this boat source","固定这个船载来源"))}}
                }}
            }
            val hasPin=when(metric){VesselMetricId.POSITION->state.vesselSettings.pinnedPositionSourceId!=null;VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC->state.vesselSettings.boatHeadingSourceId!=null;else->false}
            if(hasPin)SettingSwitch(tr("Allow fallback if pinned source fails","固定来源失效时允许回退"),tr("Off is strict: this metric becomes unavailable instead of silently changing its physical sensor.","关闭时为严格模式：此数据会变为不可用，而不是静默切换物理传感器。"),state.vesselSettings.allowPinnedFallback){vm.updateVesselDataSettings(state.vesselSettings.copy(allowPinnedFallback=it))}
        }}}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(tr("Used by","使用此数据的功能"),style=MaterialTheme.typography.titleMedium);usedBy(metric).forEach{Text("• $it",style=MaterialTheme.typography.bodyMedium)};Text(tr("Consumers apply their own safety gates; display, anchor evidence and NMEA publication are not the same decision.","各功能会应用自己的安全门槛；屏幕显示、锚点证据和 NMEA 发布不是同一个决定。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
    }
}

@Composable private fun DetailLine(label:String,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){Text(label,Modifier.weight(.42f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,Modifier.weight(.58f),style=MaterialTheme.typography.bodyMedium)}}
@Composable private fun candidateValidityLabel(value:com.yokuli.anchorwatch.domain.vessel.CandidateValidity)=when(value){com.yokuli.anchorwatch.domain.vessel.CandidateValidity.ELIGIBLE->tr("Available","可用");com.yokuli.anchorwatch.domain.vessel.CandidateValidity.LOW_QUALITY->tr("Low quality","质量较低");com.yokuli.anchorwatch.domain.vessel.CandidateValidity.STALE->tr("Stale","已过期");com.yokuli.anchorwatch.domain.vessel.CandidateValidity.INVALID->tr("Invalid","无效");com.yokuli.anchorwatch.domain.vessel.CandidateValidity.DISABLED->tr("Disabled","已禁用")}
@Composable private fun vesselReferenceLabel(value:com.yokuli.anchorwatch.domain.vessel.VesselReference?)=when(value){com.yokuli.anchorwatch.domain.vessel.VesselReference.TrueNorth->tr("True north","真北");com.yokuli.anchorwatch.domain.vessel.VesselReference.MagneticNorth->tr("Magnetic north","磁北");com.yokuli.anchorwatch.domain.vessel.VesselReference.WaterReferenced->tr("Water referenced","对水参考");com.yokuli.anchorwatch.domain.vessel.VesselReference.GroundReferenced->tr("Ground referenced","对地参考");com.yokuli.anchorwatch.domain.vessel.VesselReference.VesselRelative->tr("Relative to vessel bow","相对船艏");is com.yokuli.anchorwatch.domain.vessel.VesselReference.Depth->value.reference.name.replace('_',' ');null->"—"}
@Composable private fun vesselProvenanceLabel(value:VesselObservation<*>)=when(val detail=value.provenanceDetail){is com.yokuli.anchorwatch.domain.vessel.VesselProvenance.Nmea->tr("NMEA · ${detail.source.displayName}","NMEA · ${detail.source.displayName}");is com.yokuli.anchorwatch.domain.vessel.VesselProvenance.PhoneSensor->tr("Phone sensor · ${detail.sensor} · calibration ${detail.calibrationVersion?:"—"}","手机传感器 · ${detail.sensor} · 校准 ${detail.calibrationVersion?:"—"}");is com.yokuli.anchorwatch.domain.vessel.VesselProvenance.Derived->tr("App derived · ${detail.algorithm} · ${detail.inputs.joinToString{it.displayName}}","应用推算 · ${detail.algorithm} · ${detail.inputs.joinToString{it.displayName}}");null->value.provenance?:"—"}

private fun observationForMetric(metric:VesselMetricId,data:com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot):VesselObservation<*> = when(metric){
    VesselMetricId.POSITION->data.position;VesselMetricId.SOG->data.sogKnots;VesselMetricId.COG->data.cogTrueDegrees;VesselMetricId.HEADING_TRUE->data.headingTrueDegrees;VesselMetricId.HEADING_MAGNETIC->data.headingMagneticDegrees;VesselMetricId.DEVICE_HEADING_TRUE->data.deviceHeadingTrueDegrees;VesselMetricId.DEVICE_HEADING_MAGNETIC->data.deviceHeadingMagneticDegrees;VesselMetricId.SPEED_THROUGH_WATER->data.speedThroughWaterKnots;VesselMetricId.DEPTH->data.depthMeters;VesselMetricId.APPARENT_WIND_ANGLE->data.apparentWind.angleDegrees;VesselMetricId.APPARENT_WIND_SPEED->data.apparentWind.speedKnots;VesselMetricId.TRUE_WIND_ANGLE->data.trueWind.angleDegrees;VesselMetricId.TRUE_WIND_SPEED->data.trueWind.speedKnots;VesselMetricId.TRUE_WIND_DIRECTION->data.trueWind.directionDegrees;VesselMetricId.RATE_OF_TURN->data.rateOfTurnDegreesPerMinute;VesselMetricId.PRESSURE->data.pressureHpa;VesselMetricId.WATER_TEMPERATURE->data.waterTemperatureCelsius;VesselMetricId.AIR_TEMPERATURE->data.airTemperatureCelsius;VesselMetricId.CURRENT_SET->data.currentSetTrueDegrees;VesselMetricId.CURRENT_DRIFT->data.currentDriftKnots;VesselMetricId.XTE->data.crossTrackErrorNauticalMiles;VesselMetricId.WAYPOINT_BEARING->data.waypointBearingTrueDegrees;VesselMetricId.WAYPOINT_DISTANCE->data.waypointDistanceNauticalMiles;VesselMetricId.DESTINATION_WAYPOINT->data.destinationWaypoint;VesselMetricId.TOTAL_LOG->data.totalLogNauticalMiles;VesselMetricId.TRIP_LOG->data.tripLogNauticalMiles;VesselMetricId.HEEL->mapObservation(data.attitude){it.heelDegrees};VesselMetricId.PITCH->mapObservation(data.attitude){it.pitchDegrees};else->data.candidates[metric]?.firstOrNull()?.let{candidate->VesselObservation(candidate.value,source=candidate.sourceClass.toLegacySource(),observedAtUtcMillis=candidate.observedAtUtcMillis,receivedElapsedRealtime=candidate.receivedElapsedRealtime,quality=candidate.quality,freshness=if(candidate.validity==com.yokuli.anchorwatch.domain.vessel.CandidateValidity.ELIGIBLE)com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.FRESH else com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.STALE,provenance=candidate.source.displayName,sourceIdentity=candidate.source,sourceClass=candidate.sourceClass,reference=candidate.reference,provenanceDetail=candidate.provenance)}?:VesselObservation<Any>()
}
private fun <T,R> mapObservation(value:VesselObservation<T>,transform:(T)->R)=VesselObservation(value=value.value?.let(transform),source=value.source,observedAtUtcMillis=value.observedAtUtcMillis,receivedElapsedRealtime=value.receivedElapsedRealtime,quality=value.quality,freshness=value.freshness,provenance=value.provenance,sourceIdentity=value.sourceIdentity,sourceClass=value.sourceClass,reference=value.reference,provenanceDetail=value.provenanceDetail,conflict=value.conflict,sourceHeartbeatElapsedRealtime=value.sourceHeartbeatElapsedRealtime,selectionReason=value.selectionReason)
private fun formatVesselValue(metric:VesselMetricId,value:Any?):String=when(value){null->"—";is com.yokuli.anchorwatch.domain.vessel.VesselPosition->"%.6f, %.6f".format(value.latitude,value.longitude);is Number->when(metric){VesselMetricId.POSITION->value.toString();VesselMetricId.HEADING_TRUE,VesselMetricId.COG,VesselMetricId.TRUE_WIND_DIRECTION,VesselMetricId.CURRENT_SET,VesselMetricId.WAYPOINT_BEARING->"%03.1f°T".format(value.toDouble());VesselMetricId.HEADING_MAGNETIC,VesselMetricId.DEVICE_HEADING_MAGNETIC->"%03.1f°M".format(value.toDouble());VesselMetricId.APPARENT_WIND_ANGLE,VesselMetricId.TRUE_WIND_ANGLE->windDataAngle(value.toDouble());VesselMetricId.SOG,VesselMetricId.SPEED_THROUGH_WATER,VesselMetricId.APPARENT_WIND_SPEED,VesselMetricId.TRUE_WIND_SPEED,VesselMetricId.CURRENT_DRIFT->"%.2f kn".format(value.toDouble());VesselMetricId.DEPTH,VesselMetricId.UKC->"%.2f m".format(value.toDouble());VesselMetricId.PRESSURE->"%.1f hPa".format(value.toDouble());else->"%.2f".format(value.toDouble())};else->value.toString()}
@Composable private fun usedBy(metric:VesselMetricId)=when(metric){VesselMetricId.POSITION->listOf(tr("Anchor Watch safety","锚警安全"),tr("Sail MFD and Trip recorder","航行仪表与航程记录"));VesselMetricId.HEADING_TRUE,VesselMetricId.HEADING_MAGNETIC->listOf(tr("Sail MFD and map boat marker","航行仪表与地图船位图标"),tr("Trip recorder","航程记录"),tr("Automatic Anchor evidence with stricter gates","使用更严格门槛的自动锚点证据"));VesselMetricId.DEPTH->listOf(tr("Sail MFD and Trip recorder","航行仪表与航程记录"),tr("Anchor depth guard uses only its separately qualified NMEA channel","锚泊水深警戒只使用其独立审核的 NMEA 通道"));VesselMetricId.TRUE_WIND_SPEED,VesselMetricId.TRUE_WIND_ANGLE,VesselMetricId.TRUE_WIND_DIRECTION->listOf(tr("Sail MFD and Trip reports","航行仪表与航程报告"));else->listOf(tr("Sail MFD","航行仪表"),tr("Trip recorder and reports","航程记录与报告"))}

@Composable private fun SourceRoutingSummaryRow(title:String,value:VesselSourcePreference,current:VesselObservation<*>,open:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text("${sourcePreferenceLabel(value)} → ${vesselSourceLabel(current)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}
        Icon(Icons.Default.ChevronRight,null)
    }
}

@Composable private fun sourcePreferenceLabel(value:VesselSourcePreference)=when(value){VesselSourcePreference.AUTO->tr("Auto","自动");VesselSourcePreference.BOAT->tr("Boat","船载");VesselSourcePreference.PHONE->tr("Phone","手机");VesselSourcePreference.DERIVED->tr("Derived","推算")}

@Composable private fun SourceRoutingCard(title:String,value:VesselSourcePreference,current:VesselObservation<*>,onChange:(VesselSourcePreference)->Unit){
    Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(title,fontWeight=FontWeight.SemiBold)
        val choices=listOf(VesselSourcePreference.AUTO,VesselSourcePreference.BOAT,VesselSourcePreference.PHONE)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){choices.forEachIndexed{index,preference->SegmentedButton(value==preference,{onChange(preference)},shape=SegmentedButtonDefaults.itemShape(index,choices.size)){Text(when(preference){VesselSourcePreference.AUTO->tr("Auto","自动");VesselSourcePreference.BOAT->tr("Boat","船载");VesselSourcePreference.PHONE->tr("Phone","手机");VesselSourcePreference.DERIVED->tr("Derived","推算")})}}}
        Text("${if(value==VesselSourcePreference.AUTO)tr("AUTO → ","自动 → ")else ""}${vesselSourceLabel(current)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
    }}
}

@Composable private fun VesselSourceRow(label:String,display:String,value:VesselObservation<*>,open:()->Unit){
    val age=value.receivedElapsedRealtime?.let{(android.os.SystemClock.elapsedRealtime()-it).coerceAtLeast(0L)/100.0/10.0}
    val exceptions=buildList{if(value.quality!=com.yokuli.anchorwatch.domain.vessel.VesselDataQuality.GOOD)add(vesselQualityLabel(value.quality));if(value.freshness!=com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.FRESH)add(vesselFreshnessLabel(value.freshness))}
    Column(Modifier.fillMaxWidth().clickable(onClick=open).padding(vertical=2.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(display,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Icon(Icons.Default.ChevronRight,null,Modifier.size(18.dp))};Text(listOfNotNull(vesselSourceLabel(value),age?.let{"%.1fs".format(it)},exceptions.takeIf{it.isNotEmpty()}?.joinToString(" · ")).joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=if(value.value!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error);HorizontalDivider(Modifier.padding(top=8.dp))}
}

@Composable private fun metricName(id:InstrumentTileId)=MetricLabelRegistry.get(id).let{label->"${MetricLabelRegistry.localizedName(id,LocalAppLanguage.current)} (${label.acronym})"}

@Composable private fun vesselQualityLabel(value:com.yokuli.anchorwatch.domain.vessel.VesselDataQuality)=when(value){com.yokuli.anchorwatch.domain.vessel.VesselDataQuality.GOOD->tr("Good","良好");com.yokuli.anchorwatch.domain.vessel.VesselDataQuality.DEGRADED->tr("Degraded","降级");else->tr("Unknown","未知")}
@Composable private fun vesselFreshnessLabel(value:com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness)=when(value){com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.FRESH->tr("Live","实时");com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.HELD->tr("Held","保留");com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness.STALE->tr("Stale","过期");else->tr("Unavailable","不可用")}

@Composable private fun vesselSourceLabel(value:VesselObservation<*>):String{val source=when(value.source){com.yokuli.anchorwatch.domain.vessel.VesselDataSource.BOAT_NMEA->tr("Boat NMEA","船载 NMEA");com.yokuli.anchorwatch.domain.vessel.VesselDataSource.PHONE_GNSS->tr("Phone GNSS","手机 GNSS");com.yokuli.anchorwatch.domain.vessel.VesselDataSource.PHONE_IMU->tr("Phone IMU","手机 IMU");com.yokuli.anchorwatch.domain.vessel.VesselDataSource.PHONE_MAGNETOMETER->tr("Phone compass","手机罗盘");com.yokuli.anchorwatch.domain.vessel.VesselDataSource.PHONE_BAROMETER->tr("Phone barometer","手机气压计");com.yokuli.anchorwatch.domain.vessel.VesselDataSource.DERIVED->tr("Derived","推算");com.yokuli.anchorwatch.domain.vessel.VesselDataSource.DEMO->tr("Demo","演示");else->tr("No source","无来源")};return listOfNotNull(source,value.provenance?.takeIf{it.isNotBlank()}).joinToString(" · ")}
private fun windDataAngle(value:Double)="%.0f°%s".format(kotlin.math.abs(value),if(value<0)"P" else "S")

@Composable
private fun SonarSurveyPage(state:MainUiState,vm:MainViewModel){
    var showStart by remember{mutableStateOf(false)};var showDisclaimer by remember{mutableStateOf(false)};var rename by remember{mutableStateOf<com.yokuli.anchorwatch.data.database.SonarSurveyEntity?>(null)};var delete by remember{mutableStateOf<com.yokuli.anchorwatch.data.database.SonarSurveyEntity?>(null)}
    val freshNmeaPosition=state.sonarRecorder.hasFreshNmeaPosition(android.os.SystemClock.elapsedRealtime())
    val demoWatchRunning=state.active?.paused==false&&state.active.positionSource==GpsDataSource.DEMO.name
    val continuity=SonarSurveyContinuityPolicy.evaluate(state.activeSonarSurvey!=null,state.settings.demoMode,demoWatchRunning,state.connection,freshNmeaPosition)
    val demoSurveyWaiting=continuity==SonarSurveyContinuityState.DEMO_WAITING
    val realSurveyInterrupted=continuity==SonarSurveyContinuityState.REAL_INTERRUPTED
    val startDecision=SonarSurveyStartPolicy.evaluate(state.settings.demoMode,demoWatchRunning,state.connection,state.sonarRecorder.realDepthHoldState(),freshNmeaPosition)
    val canStart=startDecision==SonarSurveyStartDecision.ALLOWED
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader(tr("Personal sonar mapping","个人声呐测绘"),tr("Pair DPT/DBT depth only with GPS from the same NMEA server, then build a robust 5 m local grid.","DPT/DBT 水深只与同一 NMEA 服务器的 GPS 配对，并生成稳健的 5 米本地网格。"))}
        item{Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("Observation aid only — not a certified chart or a substitute for safe navigation, tide planning or depth instruments.","仅供观测辅助——不是认证海图，也不能替代安全航行、潮汐计划或测深仪判断。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}}
        if(state.activeSonarSurvey==null)item{Card{Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Waves,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(if(state.settings.demoMode)tr("Live demo sonar","实时演示声呐")else tr("Live NMEA depth","实时 NMEA 水深"),fontWeight=FontWeight.SemiBold);Text(localizeKnownMessage(state.sonarRecorder.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);SonarDepthProvenance(state.sonarRecorder)};Text(state.sonarRecorder.lastMeasuredDepthMeters?.let{"%.2f m".format(it)}?:"—",style=MaterialTheme.typography.titleMedium)}}}
        state.activeSonarSurvey?.let{survey->item{Card(colors=CardDefaults.cardColors(containerColor=if(demoSurveyWaiting||realSurveyInterrupted||state.sonarRecorder.depthHoldState==com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.WARNING)MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(demoSurveyWaiting||realSurveyInterrupted)Icons.Default.PauseCircle else Icons.Default.Sensors,null);Spacer(Modifier.width(8.dp));Text(when{demoSurveyWaiting->tr("Survey waiting for Demo watch","调查正在等待演示锚警");realSurveyInterrupted->tr("Survey waiting for NMEA","调查正在等待 NMEA");else->tr("Recording","正在记录")},style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));AssistChip({},label={Text("${survey.sampleCount}")})};Text(survey.name,fontWeight=FontWeight.SemiBold);when{demoSurveyWaiting->Text(tr("The survey is preserved but cannot create soundings while its Demo anchor session is paused. Resume that same anchor session, or stop and save this survey.","调查已保留，但演示锚泊会话暂停时不会生成测深点。请继续同一个锚泊会话，或停止并保存本次调查。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onTertiaryContainer);realSurveyInterrupted->{Text(tr("The survey is preserved, but no new sounding is being written without a live position from its original NMEA stream. Reconnect that server or stop and save the survey.","调查已保留，但原 NMEA 数据流没有实时船位时不会写入新的测深点。请重连该服务器，或停止并保存调查。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onTertiaryContainer);OutlinedButton(vm::reconnectNmea,Modifier.fillMaxWidth(),enabled=state.connection !in setOf(NmeaConnectionState.CONNECTING,NmeaConnectionState.RECONNECTING)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(6.dp));Text(tr("Reconnect NMEA","重连 NMEA"))}};else->Text(sonarHoldMessage(state.sonarRecorder),style=MaterialTheme.typography.bodySmall)};SonarDepthProvenance(state.sonarRecorder);state.sonarRecorder.lastDepthMeters?.let{depth->Text(tr("Grid depth ${"%.2f".format(depth)} m${if(state.sonarRecorder.lastDepthIsChartDatum)" · chart datum" else ""}","网格水深 ${"%.2f".format(depth)} 米${if(state.sonarRecorder.lastDepthIsChartDatum)" · 海图基准" else ""}"))};Text(tr("${state.sonarGrid.cells.size} grid cells · incremental updates","${state.sonarGrid.cells.size} 个网格 · 增量更新"),style=MaterialTheme.typography.bodySmall);OutlinedButton(vm::stopSonarSurvey,Modifier.fillMaxWidth()){Icon(Icons.Default.StopCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Stop and save survey","停止并保存调查"))}}}}}
        if(state.activeSonarSurvey==null)item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Button({if(state.settings.sonarDisclaimerAccepted)showStart=true else showDisclaimer=true},Modifier.fillMaxWidth(),enabled=canStart){Icon(Icons.Default.PlayCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Start sonar survey","开始声呐调查"))};when{startDecision==SonarSurveyStartDecision.DEMO_WATCH_REQUIRED->Text(tr("Start or resume a Demo anchor watch first. Its continuous Demo GPS track is the only valid position source for a Demo sonar survey.","请先启动或继续一个演示锚泊监控；它的连续演示 GPS 轨迹是演示声呐调查唯一有效的定位源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);state.settings.demoMode->Text(tr("Demo survey uses continuous simulated sonar tied to the running Demo anchor track. Lift anchor stops and saves the survey. The map is drawn only while the Personal sonar layer is enabled.","演示调查会生成与当前运行中的演示锚泊轨迹连续对应的模拟声呐；起锚会停止并保存调查。只有开启“个人声呐”图层时才会绘制在地图上。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);startDecision==SonarSurveyStartDecision.NMEA_NOT_CONNECTED->Text(tr("Connect the NMEA server before starting a real sonar survey.","开始真实声呐调查前必须先连接 NMEA 服务器。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);startDecision==SonarSurveyStartDecision.DEPTH_NOT_SEEN->Text(tr("Waiting for the first valid DPT/DBT depth from this NMEA connection.","正在等待本次 NMEA 连接的首个有效 DPT/DBT 水深。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);startDecision==SonarSurveyStartDecision.DEPTH_HOLD_EXPIRED->Text(tr("The last real depth has expired. Wait for a new DPT/DBT sentence.","上一次真实水深已经失效，请等待新的 DPT/DBT 句子。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);startDecision==SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH->Text(tr("Depth is available, but the same NMEA server has not supplied a fresh valid GPS position.","水深数据可用，但同一 NMEA 服务器尚未提供新鲜有效的 GPS 船位。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}}}
        item{Text(tr("Saved surveys","已保存调查"),style=MaterialTheme.typography.titleMedium)}
        if(state.sonarSurveys.any{it.tideMode!=TideMode.OFF.name})item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Chart-datum corrected history","海图基准修正历史"),fontWeight=FontWeight.SemiBold);Text(tr("Combines only usable samples with a non-null manual or LINZ-predicted tide normalization.","只合并已通过质量检查且存在手动或 LINZ 预测潮汐归一化值的样本。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)};if(state.selectedSonarSurveyId==CORRECTED_SONAR_HISTORY_ID)AssistChip({},label={Text(tr("ON MAP","地图中"))})else TextButton({vm.selectCorrectedSonarHistory();vm.page(0)}){Icon(Icons.Default.Map,null);Text(tr("Map","地图"))}}}}
        if(state.sonarSurveys.isEmpty())item{Text(tr("No sonar surveys yet.","还没有声呐调查。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.sonarSurveys,key={it.id}){survey->Card{Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(survey.name,fontWeight=FontWeight.SemiBold);Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(survey.startedAt))} · ${survey.sampleCount} ${tr("samples","个样本")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};when{survey.active->AssistChip({},label={Text(tr("ACTIVE","进行中"))});state.selectedSonarSurveyId==survey.id->AssistChip({},label={Text(tr("ON MAP","地图中"))})}};Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){TextButton({vm.selectSonarSurvey(survey.id);vm.page(0)}){Icon(Icons.Default.Map,null);Text(tr("Map","地图"))};IconButton({vm.exportSonarCsv(survey)}){Icon(Icons.Default.Share,"CSV")};IconButton({vm.rebuildSonarSurvey(survey.id)},enabled=!survey.active){Icon(Icons.Default.Refresh,tr("Rebuild","重建"))};IconButton({rename=survey}){Icon(Icons.Default.Edit,tr("Rename","重命名"))};IconButton({delete=survey},enabled=!survey.active){Icon(Icons.Default.Delete,tr("Delete","删除"))}}}}}
    }
    if(showStart)SonarStartDialog(state,{showStart=false}){name,tideMode,manualTide,stationId->vm.startSonarSurvey(name,tideMode,manualTide,stationId);showStart=false}
    if(showDisclaimer)SonarSafetyDisclaimerDialog({showDisclaimer=false}){vm.updateSettings(state.settings.copy(sonarDisclaimerAccepted=true));showDisclaimer=false;showStart=true}
    rename?.let{survey->var value by remember(survey.id){mutableStateOf(survey.name)};AlertDialog(onDismissRequest={rename=null},title={Text(tr("Rename survey","重命名调查"))},text={OutlinedTextField(value,{value=it},singleLine=true,label={Text(tr("Name","名称"))})},confirmButton={Button({vm.renameSonarSurvey(survey.id,value);rename=null},enabled=value.isNotBlank()){Text(tr("Save","保存"))}},dismissButton={TextButton({rename=null}){Text(tr("Cancel","取消"))}})}
    delete?.let{survey->AlertDialog(onDismissRequest={delete=null},title={Text(tr("Delete sonar survey?","删除声呐调查？"))},text={Text(tr("All raw and normalized soundings in this survey will be permanently deleted.","该调查中的全部原始和归一化测深数据都会被永久删除。"))},confirmButton={Button({vm.deleteSonarSurvey(survey.id);delete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({delete=null}){Text(tr("Cancel","取消"))}})}
}

@Composable
private fun SonarDepthProvenance(status:SonarRecorderStatus){
    if(status.lastRawDepthMeters==null)return
    fun depth(value:Double?)=value?.let{"%.2f m".format(it)}?:"—"
    fun offset(value:Double?)=value?.let{"${if(it>=0)"+" else ""}${"%.2f".format(it)} m"}?:"—"
    Text(
        tr(
            "Raw ${depth(status.lastRawDepthMeters)} · NMEA offset ${offset(status.lastNmeaOffsetMeters)}\nUser offset ${offset(status.lastUserOffsetMeters)} · Final ${depth(status.lastMeasuredDepthMeters)}",
            "原始值 ${depth(status.lastRawDepthMeters)} · NMEA offset ${offset(status.lastNmeaOffsetMeters)}\n用户 offset ${offset(status.lastUserOffsetMeters)} · 最终值 ${depth(status.lastMeasuredDepthMeters)}",
        ),
        style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun sonarHoldMessage(status:SonarRecorderStatus):String{
    val seconds=status.depthAgeMillis/1_000
    return when(status.depthHoldState){
        com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.NO_DEPTH->tr("Waiting for the first valid DPT/DBT depth","正在等待首个有效 DPT/DBT 水深")
        com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.LIVE->tr("Live real depth","实时真实水深")
        com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.HELD->tr("Held · last real update ${seconds}s ago","保留值 · 上次真实更新在 ${seconds} 秒前")
        com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.WARNING->tr("Held warning · ${seconds}s · ${status.depthTravelledMeters.toInt()}m since last real depth","保留值警告 · ${seconds} 秒 · 距上次真实水深已移动 ${status.depthTravelledMeters.toInt()} 米")
        com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.EXPIRED_TIME->tr("Expired · no real depth for 5 minutes","已失效 · 5 分钟未收到真实水深")
        com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.EXPIRED_DISTANCE->tr("Expired · 500m travelled without real depth","已失效 · 未收到真实水深已移动 500 米")
    }
}

@Composable
private fun SonarStartDialog(state:MainUiState,dismiss:()->Unit,start:(String,TideMode,Double,String?)->Unit){
    var name by remember{mutableStateOf("")}
    var tideMode by remember{mutableStateOf(TideMode.OFF)};var manualText by remember{mutableStateOf("0.0")}
    val position=if(state.settings.demoMode)state.fix else state.nmeaFix
    val recommended=position?.let{TideStationCatalog.nearest(it.latitude,it.longitude)}
    var selectedStationId by remember(recommended?.first?.id){mutableStateOf(recommended?.first?.id)}
    var stationMenu by remember{mutableStateOf(false)}
    val station=selectedStationId?.let(TideStationCatalog::byId)?.let{port->port to (position?.let{AnchorGeometry.distanceMeters(it.latitude,it.longitude,port.latitude,port.longitude)}?:Double.NaN)}
    val manual=manualText.toDoubleOrNull()
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Start sonar survey","开始声呐调查"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(name,{name=it},label={Text(tr("Survey name (optional)","调查名称（可选）"))},singleLine=true)
        Text(tr("Sounder depth + ${signed(state.settings.sounderOffsetMeters)} m offset will be stored.","将记录测深仪水深 + ${signed(state.settings.sounderOffsetMeters)} 米 offset。"),fontWeight=FontWeight.SemiBold)
        Text(if(state.settings.demoMode)tr("Demo sonar follows a smooth simulated seabed along the Demo GPS track.","演示声呐会沿演示 GPS 轨迹生成连续、平滑变化的模拟海床。")else tr("After the first real DPT/DBT, accepted GPS from the same NMEA stream keeps drawing with the held depth. Age and distance are recorded; the survey stops automatically if that evidence expires.","收到首个真实 DPT/DBT 后，同一 NMEA 数据流的可信 GPS 会使用保留水深继续绘制。每个点都会记录水深年龄和距离；证据失效时调查会自动停止。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider();Text(tr("Tide correction","潮汐修正"),style=MaterialTheme.typography.labelLarge)
        listOf(TideMode.OFF to tr("Off","关闭"),TideMode.MANUAL to tr("Manual","手动"),TideMode.AUTO_PREDICTED to tr("Automatic · LINZ predicted","自动 · LINZ 预测")).forEach{(mode,label)->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{tideMode=mode},verticalAlignment=Alignment.CenterVertically){RadioButton(tideMode==mode,{tideMode=mode});Text(label)}}
        if(tideMode==TideMode.MANUAL)OutlinedTextField(manualText,{manualText=it.filter{character->character.isDigit()||character=='.'||character=='-'}},label={Text(tr("Tide height above chart datum","高于海图基准的潮高"))},suffix={Text("m")},isError=manual==null,singleLine=true)
        if(tideMode==TideMode.AUTO_PREDICTED){
            Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){
                Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(tr("Tide station","潮汐站"),style=MaterialTheme.typography.labelLarge)
                    Box{
                        OutlinedButton({stationMenu=true},Modifier.fillMaxWidth()){
                            Text(station?.let{(port,distance)->
                                if(distance.isFinite())tr("${port.name} · ${(distance/1000).toInt()} km","${port.name} · ${(distance/1000).toInt()} 公里") else port.name
                            }?:tr("No position available","暂无船位"))
                        }
                        DropdownMenu(stationMenu,{stationMenu=false}){
                            TideStationCatalog.stations.forEach{port->
                                DropdownMenuItem(
                                    text={Text(port.name)},
                                    onClick={selectedStationId=port.id;stationMenu=false},
                                )
                            }
                        }
                    }
                    station?.first?.referenceStationId?.let{referenceId->
                        TideStationCatalog.byId(referenceId)?.let{reference->
                            Text(tr("Reference port: ${reference.name}","基准港：${reference.name}"),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(tr("The nearest station is recommended, but you can choose another. Selection and distance use the same NMEA GPS as the sounding. This is predicted, not observed sea level.","默认推荐最近站点，但你可以改选。站点与距离依据测深点所用的同一 NMEA GPS；这是预测潮位，不是实测海平面。"),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }},confirmButton={Button({start(name,tideMode,manual?:0.0,selectedStationId)},enabled=(tideMode!=TideMode.MANUAL||manual!=null)&&(tideMode!=TideMode.AUTO_PREDICTED||selectedStationId!=null)){Text(tr("Start recording","开始记录"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}

@Composable
internal fun ConnectionPage(state: MainUiState, vm: MainViewModel) {
    var profile by remember(state.settings.profile) { mutableStateOf(state.settings.profile) }
    var portText by remember(state.settings.profile) { mutableStateOf(state.settings.profile.port.takeIf{it>0}?.toString().orEmpty()) }
    var validationRequested by remember { mutableStateOf(false) }
    var showWatchDisconnect by remember { mutableStateOf(false) }
    var showDependencyDisconnect by remember { mutableStateOf(false) }
    // ERROR is a terminal attempt with no usable transport. Treating it as a
    // running connection hid the editable endpoint and made the next explicit
    // Connect impossible even though the failure card remained on screen.
    val connectionRunning = state.connection !in setOf(NmeaConnectionState.DISCONNECTED,NmeaConnectionState.ERROR)
    var showAdvancedSettings by remember{mutableStateOf(false)}
    var showTechnicalDetails by remember{mutableStateOf(false)}
    val testing=state.connectionAttempt.state==ConnectionAttemptState.TESTING
    val controlsEnabled=state.settingsReady&&!connectionRunning&&!testing
    val activeWatchUsesNmea=state.active?.paused==false&&state.active.positionSource==GpsDataSource.NMEA.name
    val activeWatchNmeaFault=activeWatchUsesNmea&&!NmeaSourceSelectionPolicy.isUsablePosition(
        state.connection,
        state.nmeaFix,
        state.nmeaConnectionStartedElapsed,
        android.os.SystemClock.elapsedRealtime(),
        state.settings.gpsLossSeconds*1_000L,
    )
    val nmeaDependencies=state.runtimeResources.nmeaOwners.mapNotNull{owner->when(owner){
        RuntimeOwner.ANCHOR_WATCH->null // handled by the dedicated safe-pause dialog
        RuntimeOwner.CONDITION_MONITOR->localized(state.settings.appLanguage,"Active depth / wind alerts","运行中的水深 / 风警戒")
        RuntimeOwner.SONAR_MAPPING->localized(state.settings.appLanguage,"Active sonar survey (will stop and save)","进行中的声呐调查（将停止并保存）")
        RuntimeOwner.TRIP_WATCH->localized(state.settings.appLanguage,"Trip Watch using NMEA (will pause, not end)","正在使用 NMEA 的航程监控（将暂停，不会结束）")
        RuntimeOwner.GPS_PROXY->localized(state.settings.appLanguage,"Global GPS proxy","全局 GPS 代理")
        RuntimeOwner.PHONE_NMEA_OUTPUT->localized(state.settings.appLanguage,"Phone-to-boat NMEA output","手机到船网的 NMEA 输出")
        RuntimeOwner.NMEA_SHARING->null // phone-hosted service never owns the boat RX transport
        else->null
    }}
    val retryClock by produceState(android.os.SystemClock.elapsedRealtime()){
        while(true){kotlinx.coroutines.delay(1_000);value=android.os.SystemClock.elapsedRealtime()}
    }
    val nowElapsed=retryClock
    val tripCanContinueWithPhone=RuntimeOwner.TRIP_WATCH in state.runtimeResources.nmeaOwners&&state.activeTrip?.paused==false&&state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&nowElapsed-it.receivedElapsedRealtime in 0L..3_000L}==true
    val draftProfile=profile.copy(port=portText.toIntOrNull()?:0)
    val validationError=vm.validateProfile(draftProfile)
    fun edit(next:ConnectionProfile){
        profile=next
        validationRequested=false
        vm.clearConnectionAttempt()
    }
    val formError=validationError.takeIf{validationRequested}
    val transportError=state.nmeaTransportDiagnostics.lastDisconnectReason
        ?.takeIf{state.connection==NmeaConnectionState.ERROR}
        ?.let{reason->"${state.nmeaTransportDiagnostics.lastFailureCategory?:tr("Transport error","传输错误")}: $reason"}
    val feedbackMessage=formError
        ?:state.connectionAttempt.message.takeIf{state.connectionAttempt.state!=ConnectionAttemptState.IDLE&&it.isNotBlank()}
        ?:transportError
    val feedbackIsError=formError!=null||state.connectionAttempt.state==ConnectionAttemptState.FAILED||
        (state.connectionAttempt.state==ConnectionAttemptState.IDLE&&transportError!=null)
    val feedbackIsWarning=state.connectionAttempt.state==ConnectionAttemptState.WARNING
    LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("nmea_runtime_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader(tr("Boat NMEA input","船载 NMEA 输入"), tr("Boat server → App receive (RX). Sending Phone/App data to the boat network and the Phone NMEA service each have separate controls on Share.","船载服务器 → App 的接收连接（RX）。向船网发送手机 / App 数据与本机 NMEA 服务都在“共享”页分别管理。")) }
        item { Card(Modifier.fillMaxWidth().testTag("nmea_input_connection_card")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Input connection (RX)","输入连接（RX）"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text(connectionStateLabel(state.connection),style=MaterialTheme.typography.bodySmall,color=when(state.connection){NmeaConnectionState.CONNECTED->MaterialTheme.colorScheme.primary;NmeaConnectionState.ERROR->MaterialTheme.colorScheme.error;else->MaterialTheme.colorScheme.onSurfaceVariant})};if(connectionRunning)AssistChip({},label={Text(tr("Settings locked","配置已锁定"))},leadingIcon={Icon(Icons.Default.Lock,null,Modifier.size(16.dp))},enabled=false)}
            if(!connectionRunning){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(profile.protocol==Protocol.TCP,{edit(profile.copy(protocol=Protocol.TCP))},label={Text(tr("TCP client","TCP 客户端"))},enabled=controlsEnabled);FilterChip(profile.protocol==Protocol.UDP,{edit(profile.copy(protocol=Protocol.UDP))},label={Text(tr("UDP listener","UDP 监听"))},enabled=controlsEnabled)}
                if(profile.protocol==Protocol.TCP)OutlinedTextField(profile.host,{edit(profile.copy(host=it))},label={Text(tr("Boat server address *","船载服务器地址 *"))},modifier=Modifier.fillMaxWidth().testTag("nmea_rx_host"),singleLine=true,enabled=controlsEnabled,isError=formError!=null)
                OutlinedTextField(portText,{value->portText=value.filter(Char::isDigit);validationRequested=false;vm.clearConnectionAttempt()},label={Text(if(profile.protocol==Protocol.TCP)tr("Receive port *","接收端口 *")else tr("Listen port *","监听端口 *"))},singleLine=true,enabled=controlsEnabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=formError!=null,modifier=Modifier.fillMaxWidth().testTag("nmea_rx_port"))
                Button({validationRequested=true;if(validationError==null)vm.saveAndConnect(draftProfile)},Modifier.fillMaxWidth().testTag("nmea_connect_input"),enabled=state.settingsReady&&!testing){if(testing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Default.Link,null);Spacer(Modifier.width(6.dp));Text(if(testing)tr("Connecting…","正在连接…")else tr("Connect input","连接输入"))}
            }else{
                Text("${profile.protocol} · ${profile.host}:${profile.port}",style=MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(vm::reconnectNmea,Modifier.weight(1f),enabled=!testing&&state.connection !in setOf(NmeaConnectionState.CONNECTING,NmeaConnectionState.RECONNECTING)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(6.dp));Text(tr("Reconnect now","立即重连"))};Button({when{activeWatchUsesNmea->showWatchDisconnect=true;nmeaDependencies.isNotEmpty()->showDependencyDisconnect=true;else->vm.disconnect()}},Modifier.weight(1f).testTag("nmea_stop_input"),enabled=!testing){Icon(Icons.Default.LinkOff,null);Spacer(Modifier.width(6.dp));Text(if(state.nmeaTransportDiagnostics.safetyOwnedRetry)tr("Stop recovery","停止恢复")else tr("Stop input","停止输入"))}}
            }
            if(state.nmeaTransportDiagnostics.desiredConnected){
                val retryIn=state.nmeaTransportDiagnostics.nextRetryElapsedRealtime?.let{((it-nowElapsed).coerceAtLeast(0L)+999L)/1_000L}
                Surface(
                    color=if(state.nmeaTransportDiagnostics.safetyOwnedRetry)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape=MaterialTheme.shapes.medium,
                    modifier=Modifier.fillMaxWidth().testTag("nmea_retry_status"),
                ){
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(if(state.nmeaTransportDiagnostics.safetyOwnedRetry)tr("Automatic safety recovery is running","安全自动恢复正在运行")else tr("Automatic reconnect is enabled","自动重连已启用"),fontWeight=FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                tr("attempt ${state.nmeaTransportDiagnostics.reconnectAttempt}","第 ${state.nmeaTransportDiagnostics.reconnectAttempt} 次尝试"),
                                retryIn?.let{tr("next retry in ${it}s","${it} 秒后再次尝试")},
                                state.nmeaTransportDiagnostics.lastFailureCategory,
                            ).joinToString(" · "),
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            feedbackMessage?.let{message->Surface(color=when{feedbackIsError->MaterialTheme.colorScheme.errorContainer;feedbackIsWarning->MaterialTheme.colorScheme.tertiaryContainer;else->MaterialTheme.colorScheme.surfaceVariant},shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().testTag("nmea_connection_attempt")){Row(Modifier.padding(10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top){if(testing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(if(feedbackIsError)Icons.Default.ErrorOutline else Icons.Default.Info,null,Modifier.size(18.dp));Text(localizeKnownMessage(message),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))}}}
            if(!state.settingsReady)Text(tr("Loading saved connection settings…","正在加载已保存的连接设置…"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({showAdvancedSettings=!showAdvancedSettings},Modifier.align(Alignment.End).testTag("nmea_input_advanced_toggle")){Icon(if(showAdvancedSettings)Icons.Default.ExpandLess else Icons.Default.Tune,null);Spacer(Modifier.width(4.dp));Text(if(showAdvancedSettings)tr("Hide advanced settings","收起高级设置")else tr("Advanced settings","高级设置"))}
            if(showAdvancedSettings){
                OutlinedTextField(profile.name,{edit(profile.copy(name=it))},label={Text(tr("Profile name","配置名称"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=controlsEnabled)
                OutlinedTextField(profile.noDataTimeoutSeconds.toString(),{value->edit(profile.copy(noDataTimeoutSeconds=value.filter(Char::isDigit).toIntOrNull()?:0))},label={Text(tr("No-data warning delay","无数据提醒延时"))},suffix={Text(tr("s","秒"))},supportingText={Text(tr("A quiet TCP stream stays connected; this only changes when the App reports No data.","TCP 数据安静时仍保持连接；这里只调整何时显示“无数据”。"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=controlsEnabled,isError=profile.noDataTimeoutSeconds !in 3..120,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                SettingSwitch(tr("Require checksum","要求校验和"),tr("Reject sentences without a checksum","拒绝没有校验和的语句"),profile.requireChecksum,enabled=controlsEnabled){edit(profile.copy(requireChecksum=it))}
                SettingSwitch(tr("Auto reconnect","自动重连"),tr("Use protected retries after a network loss","网络中断后执行受保护的重连"),profile.autoReconnect,enabled=controlsEnabled){edit(profile.copy(autoReconnect=it))}
                Text(tr("This page only controls Boat → App input. Both sharing directions remain separate on Share.","此页只控制船载服务器 → App 的输入；两种共享方向仍在“共享”页独立管理。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } }
		if(state.outputSettings.publicationEnabled)item{Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Phone/App NMEA output is active. Manage or stop it from Share before changing input configuration.","手机 / App NMEA 输出正在运行。更改输入配置前，请先到“共享”页面管理或停止发送。"),Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)}}
        if(activeWatchNmeaFault)item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Anchor watch needs a usable NMEA position","锚警需要可用的 NMEA 船位"),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer);Text(tr("The transport may be disconnected, stale, without a current fix, or reporting unacceptable quality. The session remains locked to NMEA with no silent failover. Reconnect, or pause safely before changing the source.","连接可能已断开、过期、没有当前定位，或正在报告不合格的定位质量。本次会话仍锁定 NMEA，不会静默切源。请重连，或先安全暂停再更换数据源。"),color=MaterialTheme.colorScheme.onErrorContainer);OutlinedButton({showWatchDisconnect=true}){Text(tr("Pause safely","安全暂停"))}}} }
        if(state.settings.nmeaSharingEnabled)item{Text(tr("A legacy Sharing request is waiting in the stopped Phone NMEA service. Review and explicitly start it on Share.","旧版“共享”请求正在已停止的“本机 NMEA 服务”中等待；请前往“共享”页检查并明确启动。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item{OutlinedButton({showTechnicalDetails=!showTechnicalDetails},Modifier.fillMaxWidth().testTag("nmea_input_diagnostics_toggle")){Icon(if(showTechnicalDetails)Icons.Default.ExpandLess else Icons.Default.MonitorHeart,null);Spacer(Modifier.width(6.dp));Text(if(showTechnicalDetails)tr("Hide technical details","收起技术详情")else tr("Technical details","技术详情"))}}
        if(showTechnicalDetails)item{ConnectionResultCard(state)}
    }
    if(showWatchDisconnect)ActiveWatchDisconnectDialog(pauseWatch={showWatchDisconnect=false;vm.stopActiveWatchAndDisconnect()},dismiss={showWatchDisconnect=false})
    if(showDependencyDisconnect)NmeaDependencyDisconnectDialog(
        dependencies=nmeaDependencies,
        tripCanContinueWithPhone=tripCanContinueWithPhone,
        continueTripWithPhone={showDependencyDisconnect=false;vm.continueTripWithPhoneAndDisconnect()},
        stopAndDisconnect={showDependencyDisconnect=false;vm.stopNmeaDependenciesAndDisconnect()},
        dismiss={showDependencyDisconnect=false},
    )
}

@Composable private fun ActiveWatchDisconnectDialog(pauseWatch:()->Unit,dismiss:()->Unit){
 AlertDialog(onDismissRequest=dismiss,title={Text(tr("Anchor watch is using NMEA","锚警正在使用 NMEA"))},text={Text(tr("Disconnecting a running position source would leave the watch unable to evaluate movement. Pause first; the centre, range and track stay intact. NMEA closes only if no sonar, proxy, sharing, output, Trip or condition feature still owns it. Then reconnect/configure a server or switch this same paused session to Phone GPS before resuming.","直接断开运行中的定位源会让锚警无法判断船位。请先暂停；中心、范围和轨迹都会保留。只有当声呐、代理、共享、输出、航程或环境警戒均不再占用时，NMEA 才会真正断开。之后可重连/配置服务器，或让同一个暂停会话改用手机 GPS，再继续监控。"))},confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedButton(pauseWatch,Modifier.fillMaxWidth()){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Pause safely & release watch source","安全暂停并释放锚警数据源"))}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
 }})
}

@Composable private fun NmeaDependencyDisconnectDialog(dependencies:List<String>,tripCanContinueWithPhone:Boolean,continueTripWithPhone:()->Unit,stopAndDisconnect:()->Unit,dismiss:()->Unit){
 AlertDialog(
  onDismissRequest=dismiss,
  title={Text(tr("NMEA is still in use","仍有功能正在使用 NMEA"))},
  text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(tr("Disconnect is not a cosmetic switch: the following running features own this stream.","“断开”不是装饰性开关：以下运行中功能仍占用这条数据流。"))
   dependencies.forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}
   Text(if(tripCanContinueWithPhone)tr("A fresh Phone GNSS fix is available. You may keep Trip Watch recording on Phone GPS; NMEA instruments will be recorded as a gap.","手机 GNSS 船位可用。你可以让航程监控改用手机 GPS 继续记录；NMEA 仪表将记为数据缺口。")else tr("Continuing will disable condition alerts, stop and save sonar, pause Trip Watch, stop GPS proxy/sharing/output, then close NMEA. Anchor sessions that use System GPS remain open.","继续后会关闭环境警戒、停止并保存声呐、暂停航程监控、关闭 GPS 代理/共享/输出，再断开 NMEA。使用系统 GPS 的锚泊会话仍会保留。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }},
  confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
   if(tripCanContinueWithPhone)OutlinedButton(continueTripWithPhone,Modifier.fillMaxWidth()){Text(tr("Continue Trip with Phone GPS & disconnect","改用手机 GPS 继续航程并断开"))}
   Button(stopAndDisconnect,Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Stop listed features & disconnect","停止上述功能并断开"))}
   TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
  }},
 )
}

@Composable
private fun ConnectionResultCard(state: MainUiState) {
    val now = android.os.SystemClock.elapsedRealtime()
    fun age(value: Long?): String = value
        ?.let { "%.1fs".format((now - it).coerceAtLeast(0L) / 1_000.0) }
        ?: "—"

    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Live status", "实时状态"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        connectionStateLabel(state.connection),
                        color = when(state.connection){
                            NmeaConnectionState.CONNECTED->MaterialTheme.colorScheme.primary
                            NmeaConnectionState.ERROR->MaterialTheme.colorScheme.error
                            else->MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            HorizontalDivider()
            DiagnosticsRow(
                tr("Input endpoint", "输入端点"),
                "${state.settings.profile.protocol} · ${state.settings.profile.host}:${state.settings.profile.port}",
            )
            DiagnosticsRow(
                tr("Last bytes / sentence / GPS", "最近字节 / 语句 / GPS"),
                "${age(state.nmeaTransportDiagnostics.lastByteReceivedElapsedRealtime)} / " +
                    "${age(state.nmeaTransportDiagnostics.lastSentenceReceivedElapsedRealtime)} / " +
                    age(state.diagnostics.lastFixElapsed),
            )
            DiagnosticsRow(
                tr("Socket generation / retry", "Socket 代次 / 重试"),
                "${state.nmeaTransportDiagnostics.connectionGeneration} / ${state.nmeaTransportDiagnostics.reconnectAttempt}",
            )
            DiagnosticsRow(
                tr("Recovery policy", "恢复策略"),
                buildString{
                    append(state.nmeaTransportDiagnostics.retryPolicyName)
                    state.nmeaTransportDiagnostics.nextRetryElapsedRealtime?.let{append(" · ");append(((it-now).coerceAtLeast(0L)+999L)/1_000L);append("s")}
                },
            )
            state.nmeaTransportDiagnostics.lastDisconnectReason?.let { reason ->
                DiagnosticsRow(
                    tr("Last transport failure", "最近传输故障"),
                    "${state.nmeaTransportDiagnostics.lastFailureCategory ?: tr("Transport error", "传输错误")}: $reason",
                )
            }
            DiagnosticsRow(tr("Heading / wind", "船艏向 / 风"),"${age(state.nmeaInstruments.headingTrue?.second?:state.nmeaInstruments.headingMagnetic?.second)} / ${age(listOfNotNull(state.liveWind.trueSpeed?.receivedElapsedRealtime,state.liveWind.apparentSpeed?.receivedElapsedRealtime,state.liveWind.trueDirection?.receivedElapsedRealtime).maxOrNull())}")
            DiagnosticsRow(tr("Depth / STW", "水深 / 对水航速"),"${age(state.liveDepth.receivedElapsedRealtime.takeUnless{state.liveDepth.isDemo})} / ${age(state.nmeaInstruments.speedThroughWaterKnots?.second)}")
            Text(
                tr(
                    "${state.diagnostics.validSentences} valid • ${state.diagnostics.invalidSentences} invalid • ${state.diagnostics.echoedAppTxSentences} exact App TX echoes",
                    "${state.diagnostics.validSentences} 条有效 · ${state.diagnostics.invalidSentences} 条无效 · ${state.diagnostics.echoedAppTxSentences} 条精确 App TX 回显",
                ),
            )
            state.nmeaFix?.let {
                val latitude = "%.6f".format(it.latitude)
                val longitude = "%.6f".format(it.longitude)
                Text(tr("Latest position  $latitude, $longitude", "最新位置  $latitude, $longitude"))
            } ?: Text(tr("No parsed GPS position yet", "暂时没有解析出的 GPS 位置"))
            state.diagnostics.lastPositionRejectionReason?.let{reason->
                Text(tr("Latest position rejection: ${positionRejectionLabel(reason)}","最近船位拒绝：${positionRejectionLabel(reason)}"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            }
            Text(
                tr(
                    "Open Raw data above for the NMEA stream.",
                    "可在上方切换到“原始数据”查看 NMEA 数据流。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NmeaDataPage(state: MainUiState, vm: MainViewModel) {
    var paused by remember { mutableStateOf(false) };var healthExpanded by remember{mutableStateOf(false)};var displayed by remember { mutableStateOf(state.diagnostics.raw) }; val context=LocalContext.current;val tileDiagnostics by SonarTileDiagnostics.state.collectAsState()
    val ageClock by produceState(android.os.SystemClock.elapsedRealtime()){
        while(true){kotlinx.coroutines.delay(1_000);value=android.os.SystemClock.elapsedRealtime()}
    }
    LaunchedEffect(state.diagnostics.raw, paused) { if (!paused) displayed = state.diagnostics.raw }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader(tr("Live NMEA data","实时 NMEA 数据"), tr("Parsed values, readable health checks and the latest 200 raw sentences.","查看解析值、清晰的健康检查和最近 200 条原始语句。"))}
        item{Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompactStat(tr("VALID","有效"), state.diagnostics.validSentences.toString(), Modifier.weight(1f)); CompactStat(tr("INVALID","无效"), state.diagnostics.invalidSentences.toString(), Modifier.weight(1f)); CompactStat(tr("CHECKSUM","校验错误"), state.diagnostics.checksumErrors.toString(), Modifier.weight(1f)) }}
        item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("Latest NMEA values","最新 NMEA 数值"),fontWeight=FontWeight.SemiBold)
            val now=ageClock
            val instruments=state.nmeaInstruments
            val depth=state.liveDepth.takeUnless{it.isDemo}
            val trueWind=state.liveWind.trueSpeed
            val apparentWind=state.liveWind.apparentSpeed
            DiagnosticsRow(tr("Position","船位"),state.nmeaFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—")
            DiagnosticsRow(tr("SOG / COG","对地航速 / 对地航向"),"${heldNmeaValue(instruments.speedOverGroundKnots?.first,instruments.speedOverGroundKnots?.second,"kn",now)}  ·  ${heldNmeaValue(instruments.courseOverGroundTrue?.first,instruments.courseOverGroundTrue?.second,"°",now)}")
            DiagnosticsRow(tr("True heading / STW","真船首向 / 对水航速"),"${heldNmeaValue(instruments.headingTrue?.first,instruments.headingTrue?.second,"°",now)}  ·  ${heldNmeaValue(instruments.speedThroughWaterKnots?.first,instruments.speedThroughWaterKnots?.second,"kn",now)}")
            DiagnosticsRow(tr("True / apparent wind","真风 / 视风"),"${heldNmeaValue(trueWind?.value,trueWind?.receivedElapsedRealtime,"kn",now)}  ·  ${heldNmeaValue(apparentWind?.value,apparentWind?.receivedElapsedRealtime,"kn",now)}")
            DiagnosticsRow(tr("Depth","水深"),heldNmeaValue(depth?.depthMeters,depth?.receivedElapsedRealtime,"m",now,3_000L))
            DiagnosticsRow(tr("HDOP / provider","HDOP / 提供者"),"${heldNmeaValue(state.nmeaFix?.hdop,state.nmeaFix?.hdopReceivedElapsedRealtime,"",now)}  ·  ${state.nmeaFix?.positionProvider?.name?.let{diagnosticState(it)}?:"—"}")
            state.diagnostics.lastPositionRejectionReason?.let{reason->Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.small){Text(tr("Latest position rejection: ${positionRejectionLabel(reason)}","最近船位拒绝：${positionRejectionLabel(reason)}"),Modifier.fillMaxWidth().padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodySmall)}}
            Text(tr("A blank field in a valid sentence means unchanged. That same physical source refreshes its last value; an explicit invalid status clears it, and values are never borrowed across instruments or reconnects.","有效语句中的空字段表示数值未变化。同一物理来源会刷新上一值；明确的无效状态会清除该值，且不同仪器或重连代次之间绝不会互相借值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}}
        item{RuntimeHealthCard(state,tileDiagnostics,healthExpanded){healthExpanded=!healthExpanded}}
        item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Raw sentences","原始语句"),style=MaterialTheme.typography.titleMedium);Text(if(paused)tr("Display paused; incoming data is not discarded.","显示已暂停；新到数据不会被丢弃。")else tr("Live display · byte-identical App TX echoes are labelled and excluded","实时显示 · 字节完全相同的 App TX 回显会被标记并排除"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton({paused=!paused}){Icon(if(paused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(4.dp));Text(if(paused)tr("Resume","继续")else tr("Pause","暂停"))}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({vm.clearDiagnostics();displayed=emptyList()},Modifier.weight(1f)){Icon(Icons.Default.DeleteSweep,null);Spacer(Modifier.width(4.dp));Text(tr("Clear","清空"))};OutlinedButton({context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("NMEA",displayed.joinToString("\n")))},Modifier.weight(1f),enabled=displayed.isNotEmpty()){Icon(Icons.Default.ContentCopy,null);Spacer(Modifier.width(4.dp));Text(tr("Copy","复制"))}}
        }}
        item{SelectionContainer{Surface(Modifier.fillMaxWidth(),color=Color.Black,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(displayed.isEmpty())Text(if(state.connection==NmeaConnectionState.CONNECTED)tr("Connected. Waiting for NMEA sentences…","已连接，正在等待 NMEA 语句…")else tr("Connect to a data source to view raw NMEA.","连接数据源后即可查看原始 NMEA 数据。"),color=Color.Gray,fontFamily=FontFamily.Monospace)
            else displayed.asReversed().forEach{sentence->Text(sentence,color=if(sentence.startsWith("[Echoed App TX]"))Color(0xFFFFCC80) else Color(0xFFB9F6CA),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
        }}}}
    }
}

@Composable
private fun heldNmeaValue(value:Double?,received:Long?,unit:String,now:Long,freshMillis:Long=5_000L):String{
    if(value==null)return "—"
    val age=received?.let{(now-it).coerceAtLeast(0L)}
    val status=when{
        age==null->""
        age<=freshMillis->tr("live","实时")
        age<=60_000L->tr("held ${age/1_000}s","保留值 ${age/1_000} 秒")
        else->tr("stale ${age/1_000}s","已过期 ${age/1_000} 秒")
    }
    return "%.1f%s%s".format(value,if(unit.isBlank())"" else " $unit",if(status.isBlank())"" else " · $status")
}
@Composable private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } } }

@Composable private fun RuntimeHealthCard(state:MainUiState,tile:com.yokuli.anchorwatch.map.SonarTileDiagnosticsSnapshot,expanded:Boolean,toggle:()->Unit){
    val runtime=state.runtimeDiagnostics;val grid=state.sonarRecorder.gridDiagnostics
    val ownerLabels=mapOf(
        "ANCHOR_WATCH" to tr("Anchor watch","锚警监控"),
        "ANCHOR_TELEMETRY" to tr("Anchor report sensors","锚泊报告传感器"),
        "CONDITION_MONITOR" to tr("Depth / wind alerts","水深 / 风警戒"),
        "NMEA_SHARING" to tr("NMEA sharing","NMEA 共享"),
        "GPS_PROXY" to tr("GPS proxy","GPS 代理"),
        "SONAR_MAPPING" to tr("Sonar mapping","声呐测绘"),
        "PHONE_NMEA_OUTPUT" to tr("Phone GPS output","手机 GPS 输出"),
        "VESSEL_HUB_UI" to tr("Live vessel instruments","实时船舶仪表"),
        "TRIP_WATCH" to tr("Trip recording","航程记录"),
    )
    Card{
        Column(Modifier.fillMaxWidth()){
            Row(Modifier.fillMaxWidth().clickable(onClick=toggle).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Icon(Icons.Default.HealthAndSafety,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){Text(tr("Runtime & health","运行与健康"),fontWeight=FontWeight.SemiBold);Text(tr("${runtime.acceptedFixCount} accepted fixes · ${runtime.rejectedFixCount} rejected · ${runtime.activeOwners.size} active services","${runtime.acceptedFixCount} 个可信定位 · ${runtime.rejectedFixCount} 个拒绝定位 · ${runtime.activeOwners.size} 个活动服务"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,if(expanded)tr("Collapse","收起")else tr("Expand","展开"))
            }
            if(expanded){HorizontalDivider();Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                DiagnosticsSection(tr("Position acceptance","船位接收"))
                DiagnosticsRow(tr("Selected source","选定来源"),diagnosticState(state.acceptedPosition.selectedSource.name))
                DiagnosticsRow(tr("Current result","当前结果"),diagnosticState(state.acceptedPosition.disposition))
                DiagnosticsRow(tr("Trust level","可信等级"),state.acceptedPosition.trust?.name?.let{diagnosticState(it)}?:"—")
                DiagnosticsRow(tr("Raw position","原始船位"),state.acceptedPosition.rawFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—")
                DiagnosticsRow(tr("Accepted position","可信船位"),state.acceptedPosition.acceptedFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—")
                state.acceptedPosition.reason?.let{DiagnosticsRow(tr("Reason","原因"),localizeKnownMessage(it))}
                DiagnosticsRow(tr("Accepted / quarantined / rejected","可信 / 隔离 / 拒绝"),"${runtime.acceptedFixCount} / ${runtime.quarantinedFixCount} / ${runtime.rejectedFixCount}")
                DiagnosticsRow(tr("NMEA reconnects","NMEA 重连次数"),runtime.nmeaReconnectCount.toString())
                DiagnosticsRow(tr("Integrity time (last / max)","完整性检查耗时（最近 / 最大）"),"${runtime.positionIntegrityLastDurationMicros} / ${runtime.positionIntegrityMaxDurationMicros} µs")

                HorizontalDivider();DiagnosticsSection(tr("Anchor & sonar processing","锚点与声呐处理"))
                DiagnosticsRow(tr("Centre estimator runs","中心估算次数"),"${runtime.estimatorRuns}  ·  ${runtime.estimatorLastDurationMs}/${runtime.estimatorMaxDurationMs} ms")
                DiagnosticsRow(tr("Sonar samples written","已写入声呐样本"),runtime.sonarSamplesWritten.toString())
                DiagnosticsRow(tr("Grid updates (last / max)","网格更新（最近 / 最大）"),"${runtime.sonarGridUpdates}  ·  ${runtime.sonarGridLastDurationMs}/${runtime.sonarGridMaxDurationMs} ms")
                DiagnosticsRow(tr("Raw / stored / selected cells","原始样本 / 已存网格 / 当前网格"),"${grid.rawSamples} / ${grid.gridCells} / ${state.sonarGrid.cells.size}")
                DiagnosticsRow(tr("Grid mode","网格模式"),if(grid.rebuilding)tr("Rebuilding","正在重建")else tr("Incremental","增量更新"))
                DiagnosticsRow(tr("Tile cache / rendered","瓦片缓存 / 已渲染"),"${tile.cacheEntries} / ${tile.renderedTiles}  ·  ${tile.lastRenderDurationMillis}/${tile.maxRenderDurationMillis} ms")
                DiagnosticsRow(tr("Tide corrections","潮汐修正"),"${runtime.tideCorrections}  ·  ${runtime.tideLastDurationMs}/${runtime.tideMaxDurationMs} ms")

                HorizontalDivider();DiagnosticsSection(tr("LINZ vector depth","LINZ 矢量水深"))
                DiagnosticsRow(tr("Status / HTTP","状态 / HTTP"),"${diagnosticState(state.linzDepth.status.name)} / ${state.linzDepthDiagnostics.lastHttpCode?:"—"}")
                DiagnosticsRow(tr("Depth area","水深区域"),"${state.linzDepth.depthAreaMinMeters?:"—"}–${state.linzDepth.depthAreaMaxMeters?:"—"} m")
                DiagnosticsRow(tr("Nearest sounding","最近测深点"),"${state.linzDepth.nearestSoundingDepthMeters?:"—"} m  ·  ${state.linzDepth.nearestSoundingDistanceMeters?.toInt()?:"—"} m")
                DiagnosticsRow(tr("Nearest contour","最近等深线"),"${state.linzDepth.nearestContourDepthMeters?:"—"} m  ·  ${state.linzDepth.nearestContourDistanceMeters?.toInt()?:"—"} m")
                DiagnosticsRow(tr("Cache hits / misses","缓存命中 / 未命中"),"${state.linzDepthDiagnostics.cacheHits} / ${state.linzDepthDiagnostics.cacheMisses}")
                DiagnosticsRow(tr("Message","消息"),localizeKnownMessage(state.linzDepthDiagnostics.message))

                HorizontalDivider();DiagnosticsSection(tr("Background resources","后台资源"))
                DiagnosticsRow(tr("Active services","活动服务"),runtime.activeOwners.map{ownerLabels[it.name]?:it.name}.joinToString().ifBlank{"—"})
                DiagnosticsRow(tr("CPU wake lock","CPU 唤醒锁"),if(runtime.wakeLockHeld)tr("Held","已持有")else tr("Off","关闭"))
                DiagnosticsRow(tr("Wi-Fi lock","Wi-Fi 锁"),if(runtime.wifiLockHeld)tr("Held","已持有")else tr("Off","关闭"))
                DiagnosticsRow(tr("Phone motion / heading / pressure","手机运动 / 方位 / 气压"),"${if(runtime.phoneMotionActive)tr("ON","开")else tr("OFF","关")} / ${if(runtime.phoneHeadingActive)tr("ON","开")else tr("OFF","关")} / ${if(runtime.phonePressureActive)tr("ON","开")else tr("OFF","关")}")
                DiagnosticsRow(tr("Sharing clients / dropped","共享客户端 / 已断开"),"${runtime.sharingClients} / ${runtime.sharingSlowClientsDropped}")
            }}
        }
    }
}

@Composable private fun DiagnosticsSection(title:String){Text(title,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)}
@Composable private fun DiagnosticsRow(label:String,value:String){Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodyMedium)}}
@Composable private fun positionRejectionLabel(code:String):String{
    val detail=code.substringAfter(':',"")
    return when(code.substringBefore(':')){
        "CHECKSUM_REQUIRED"->tr("$detail has no checksum while ‘Require checksum’ is enabled","$detail 没有校验和，但当前开启了“要求校验和”")
        "CHECKSUM_MISMATCH"->tr("$detail checksum does not match","$detail 校验和不匹配")
        "MALFORMED_POSITION"->tr("$detail is unsupported or malformed","$detail 不受支持或格式不完整")
        "EXPLICIT_NO_FIX"->tr("$detail explicitly reports no valid fix","$detail 明确报告当前无有效定位")
        "NO_POSITION_UPDATE"->tr("$detail contains no complete coordinate update","$detail 没有携带完整的新坐标")
        "FIX_QUALITY_ZERO"->tr("$detail reports fix quality 0","$detail 报告定位质量为 0")
        "POOR_HDOP"->tr("Current HDOP $detail exceeds the safety limit 5.0","当前 HDOP $detail 超过安全门槛 5.0")
        "QUALITY_REJECTED"->tr("$detail position quality was rejected","$detail 船位质量未通过")
        "EXACT_APP_TX_ECHO"->tr("$detail is byte-identical to a recent App transmission","$detail 与应用刚发送的数据逐字相同，已按回显隔离")
        else->code
    }
}
@Composable private fun diagnosticState(value:String):String=when(value){
    "SYSTEM"->tr("System GPS","系统 GPS");"NMEA"->"NMEA GPS";"DEMO"->tr("Demo GPS","演示 GPS")
    "ACCEPTED"->tr("Accepted","可信");"QUARANTINED"->tr("Quarantined","隔离");"REJECTED"->tr("Rejected","拒绝");"PENDING"->tr("Pending","等待中")
    "TRUSTED"->tr("Trusted","可信");"DEGRADED"->tr("Degraded","质量下降");"UNTRUSTED"->tr("Untrusted","不可信")
    "IDLE"->tr("Idle","待命");"LOADING"->tr("Loading","正在加载");"AVAILABLE"->tr("Available","可用");"NO_DATA"->tr("No data","无数据");"OFFLINE"->tr("Offline","离线");"NOT_CONFIGURED"->tr("Not configured","未配置");"ERROR"->tr("Error","错误")
    "ANCHOR_WATCH"->tr("Anchor watch","锚警监控");"ANCHOR_TELEMETRY"->tr("Anchor report sensors","锚泊报告传感器");"CONDITION_MONITOR"->tr("Depth / wind alerts","水深 / 风警戒");"NMEA_SHARING"->tr("NMEA sharing","NMEA 共享");"GPS_PROXY"->tr("GPS proxy","GPS 代理");"SONAR_MAPPING"->tr("Sonar mapping","声呐测绘");"PHONE_NMEA_OUTPUT"->tr("Phone GPS output","手机 GPS 输出");"VESSEL_HUB_UI"->tr("Live vessel instruments","实时船舶仪表");"TRIP_WATCH"->tr("Trip recording","航程记录")
    "ANDROID_GNSS"->tr("Android GNSS","安卓 GNSS");"ANDROID_NETWORK"->tr("Android network","安卓网络定位");"NMEA_GNSS"->tr("NMEA GNSS","NMEA GNSS");"DEMO_SIMULATED"->tr("Demo simulation","演示模拟")
    else->value.replace('_',' ').lowercase().replaceFirstChar{it.titlecase()}
}
