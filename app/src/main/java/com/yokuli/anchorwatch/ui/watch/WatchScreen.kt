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
import com.yokuli.anchorwatch.map.LinzHydroConfiguration
import com.yokuli.anchorwatch.map.LinzHydroTileProvider
import com.yokuli.anchorwatch.map.LinzHydroDiagnostics
import com.yokuli.anchorwatch.map.MapOverlayZ
import com.yokuli.anchorwatch.map.SonarTileProvider
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat

private data class SonarMapInspection(
    val grid:com.yokuli.anchorwatch.domain.sonar.SonarInspection,
    val nearestSample:com.yokuli.anchorwatch.data.database.DepthSampleEntity?,
)

@Composable
internal fun WatchPage(state: MainUiState, vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(false) };var showAdjust by remember { mutableStateOf(false) };var confirmLift by remember { mutableStateOf(false) };var showLayers by remember{mutableStateOf(false)};var showLinzDisclaimer by remember{mutableStateOf(false)}
    val fix = state.fix; val active = state.active
    LaunchedEffect(state.rangeEditorRequested,active?.id){if(state.rangeEditorRequested){showAdjust=active!=null;vm.consumeRangeEditorRequest()}}
    val boatIcon = remember { boatMarkerIcon() }; val anchorIcon = remember { anchorMarkerIcon() }
    val trail = remember(state.points) { fadingTrailChunks(state.points) }
    val linzTileProviders=remember{BuildConfig.LINZ_HYDRO_TILE_TEMPLATES.split('|').filter(LinzHydroConfiguration::isUsable).map(::LinzHydroTileProvider)}
    val sonarGrid=state.sonarGrid
    val sonarTileProvider=remember(sonarGrid){SonarTileProvider(sonarGrid)}
    var sonarInspection by remember{mutableStateOf<SonarMapInspection?>(null)}
    LaunchedEffect(state.settings.sonarLayerEnabled,sonarGrid){
        if(!state.settings.sonarLayerEnabled||sonarGrid.cells.isEmpty())sonarInspection=null
    }
    val initialPreciseFix=fix?.takeUnless{it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_NETWORK}
    val camera = rememberCameraPositionState { position = if(initialPreciseFix!=null)CameraPosition.fromLatLngZoom(LatLng(initialPreciseFix.latitude,initialPreciseFix.longitude), MapCameraPolicy.DEFAULT_FOLLOW_ZOOM)else CameraPosition.fromLatLngZoom(LatLng(0.0,0.0),2f) }
    var mapLoaded by remember { mutableStateOf(false) }
    var hasCenteredOnFix by remember { mutableStateOf(false) }
    var followedSource by remember { mutableStateOf<GpsDataSource?>(null) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(mapLoaded, fix?.latitude, fix?.longitude, fix?.valid, state.follow, state.settings.gpsDataSource, recenterRequest) {
        if (mapLoaded && fix?.valid == true && fix.positionProvider!=com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_NETWORK && state.follow) {
            val target = LatLng(fix.latitude, fix.longitude)
            val update = when (MapCameraPolicy.nextMove(hasCenteredOnFix, followedSource, state.settings.gpsDataSource)) {
                FollowCameraMove.CENTER_WITH_DEFAULT_ZOOM -> CameraUpdateFactory.newLatLngZoom(target, MapCameraPolicy.DEFAULT_FOLLOW_ZOOM)
                FollowCameraMove.CENTER_PRESERVING_ZOOM -> CameraUpdateFactory.newLatLng(target)
            }
            camera.animate(update)
            hasCenteredOnFix = true
            followedSource = state.settings.gpsDataSource
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (BuildConfig.MAPS_CONFIGURED) {
                    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(mapType = if(state.settings.mapType==2)MapType.SATELLITE else MapType.NORMAL), uiSettings = MapUiSettings(compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false), onMapLoaded = { mapLoaded = true },onMapClick={point->
                        val inspection=if(state.settings.sonarLayerEnabled)sonarGrid.inspect(point.latitude,point.longitude)else null
                        val nearest=inspection?.let{state.sonarSamples.filter{sample->sample.usable}.minByOrNull{sample->AnchorGeometry.distanceMeters(point.latitude,point.longitude,sample.latitude,sample.longitude)}}?.takeIf{sample->AnchorGeometry.distanceMeters(point.latitude,point.longitude,sample.latitude,sample.longitude)<=15.0}
                        sonarInspection=inspection?.let{SonarMapInspection(it,nearest)}
                    }) {
                        if(LinzHydroConfiguration.isOverlayVisible(BuildConfig.LINZ_HYDRO_CONFIGURED,state.settings.linzHydroEnabled))linzTileProviders.forEach{provider->TileOverlay(tileProvider=provider,fadeIn=true,transparency=LinzHydroConfiguration.transparency(state.settings.linzHydroOpacity),visible=true,zIndex=MapOverlayZ.LINZ_CHART)}
                        if(state.settings.sonarLayerEnabled&&sonarGrid.cells.isNotEmpty())TileOverlay(tileProvider=sonarTileProvider,fadeIn=false,transparency=(1.0-state.settings.sonarLayerOpacity.coerceIn(.20,1.0)).toFloat(),visible=true,zIndex=MapOverlayZ.SONAR)
                        fix?.let { position -> Marker(state=remember(position.latitude, position.longitude){MarkerState(LatLng(position.latitude,position.longitude))},title=tr("Boat","船位"),icon=boatIcon,rotation=(displayHeading(position,active,state.points)?:0.0).toFloat(),flat=true,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.BOAT) }
                        active?.let { session ->
                            if(session.centerStatus==AnchorCenterStatus.RESOLVED.name){val anchor=LatLng(session.anchorLatitude,session.anchorLongitude)
                             Marker(state=remember(session.anchorLatitude,session.anchorLongitude){MarkerState(anchor)},title=tr("Anchor","锚点"),icon=anchorIcon,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.ANCHOR)
                             Circle(center=anchor,radius=session.alarmRadiusMeters,strokeColor=Color(0xFFFF5252),fillColor=Color(0x16FF5252),strokeWidth=3f,zIndex=MapOverlayZ.ALARM_GEOMETRY)}
                            else{
                             val reference=LatLng(session.learningReferenceLatitude?:session.anchorLatitude,session.learningReferenceLongitude?:session.anchorLongitude)
                             Circle(center=reference,radius=session.alarmRadiusMeters,strokeColor=Color(0xFFFFA726),fillColor=Color(0x16FFA726),strokeWidth=4f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             val estimatedLat=session.provisionalAnchorLatitude;val estimatedLon=session.provisionalAnchorLongitude
                             if(estimatedLat!=null&&estimatedLon!=null){val estimated=LatLng(estimatedLat,estimatedLon)
                              if(session.candidateDecision==CandidateDecision.AVAILABLE.name){Marker(state=remember(estimatedLat,estimatedLon){MarkerState(estimated)},title=tr("Candidate anchor · approval required","候选锚点 · 等待确认"),icon=anchorIcon,alpha=.80f,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.ANCHOR);Circle(center=estimated,radius=session.alarmRadiusMeters,strokeColor=Color(0xFF26C6DA),fillColor=Color.Transparent,strokeWidth=3f,zIndex=MapOverlayZ.ALARM_GEOMETRY)}
                              else Circle(center=estimated,radius=session.provisionalRadiusMeters?:session.expectedSwingRadiusMeters.coerceAtLeast(10.0),strokeColor=Color(0xFF42A5F5),fillColor=Color(0x2242A5F5),strokeWidth=2f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             }
                            }
                            trail.forEach{chunk->Polyline(points=chunk.points,color=Color(0xFFFFD54F).copy(alpha=chunk.alpha),width=4f,zIndex=MapOverlayZ.TRAIL)}
                        }
                    }
            } else MapNotConfigured()
            CompactWatchStatus(state,Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton({hasCenteredOnFix=false;recenterRequest+=1;vm.follow(true)}) { Icon(Icons.Default.MyLocation, tr("Recenter on boat","回到船位")) }
                FilledTonalButton({showLayers=true}){Icon(Icons.Default.Layers,null);Spacer(Modifier.width(6.dp));Text(tr("Layers","图层"))}
            }
            if(LinzHydroConfiguration.isOverlayVisible(BuildConfig.LINZ_HYDRO_CONFIGURED,state.settings.linzHydroEnabled))Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp,vertical=8.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.88f),shape=MaterialTheme.shapes.small){Text(LinzHydroConfiguration.ATTRIBUTION,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}
            sonarInspection?.let{inspection->
                val sample=inspection.nearestSample;val survey=sample?.let{s->state.sonarSurveys.firstOrNull{it.id==s.surveyId}}
                Surface(Modifier.align(Alignment.TopCenter).padding(start=16.dp,top=72.dp,end=16.dp).clickable{sonarInspection=null},color=MaterialTheme.colorScheme.surface.copy(alpha=.96f),shape=MaterialTheme.shapes.medium,shadowElevation=4.dp){Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(inspection.grid.measured)tr("Measured depth","实测水深")else tr("Estimated from nearby sonar samples","由附近声呐样本插值得到"),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Icon(Icons.Default.Close,tr("Close","关闭"),Modifier.size(16.dp))};Text("${"%.2f".format(inspection.grid.depthMeters)} ${tr("m","米")} · ±${"%.2f".format(inspection.grid.uncertaintyMeters)} ${tr("m","米")} · ${inspection.grid.sampleCount} ${tr("samples","个样本")}",style=MaterialTheme.typography.bodySmall);sample?.let{Text(tr("Reference ${it.depthReference} · surveyed ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(it.timestamp))}","基准 ${it.depthReference} · 测量于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(it.timestamp))}"),style=MaterialTheme.typography.bodySmall)};survey?.let{Text(if(it.tideMode==com.yokuli.anchorwatch.domain.sonar.TideMode.MANUAL.name)tr("Manual tide correction ${"%.2f".format(it.manualTideOffsetMeters)} m","手动潮汐修正 ${"%.2f".format(it.manualTideOffsetMeters)} 米")else tr("Tide correction Off · not chart datum","潮汐修正关闭 · 不是海图基准水深"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            }
        }
        if(active?.candidateDecision==CandidateDecision.AVAILABLE.name)EstimatedCenterBanner(state,vm,active)
        WatchPanel(state,{showSetup=true},{showAdjust=true},vm::setPhoneHeadingEvidence,vm::pauseWatch,vm::resumeWatch,{confirmLift=true}){active?.let(vm::openAnchorInGoogleMaps)}
    }
    if (showSetup) {
        AnchorSetupSheet(state,{showSetup=false}){lat,lon,input->vm.arm(lat,lon,input);showSetup=false}
    }
    if(showAdjust&&active!=null)AnchorSettingsDialog(fix,active,{showAdjust=false}){input->vm.updateAnchorSettings(input);showAdjust=false}
    if (confirmLift) AlertDialog({ confirmLift = false }, confirmButton = { Button({ vm.liftAnchor(); confirmLift = false },colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Lift anchor","起锚")) } }, dismissButton = { TextButton({ confirmLift = false }) { Text(tr("Cancel","取消")) } }, title = { Text(tr("End this anchoring session?","结束本次锚泊？")) }, text = { Text(tr("Lift anchor permanently closes this session. Its track remains in History, but it cannot be resumed.","起锚会永久结束本次锚泊。轨迹仍保留在历史记录中，但不能再恢复。")) })
    if(showLayers)MapLayersSheet(state,{showLayers=false},{vm.setMapType(it)},{enabled->if(enabled&&!state.settings.linzHydroDisclaimerAccepted)showLinzDisclaimer=true else vm.updateSettings(state.settings.copy(linzHydroEnabled=enabled))},{opacity->vm.updateSettings(state.settings.copy(linzHydroOpacity=opacity))},{enabled->vm.updateSettings(state.settings.copy(sonarLayerEnabled=enabled))},{opacity->vm.updateSettings(state.settings.copy(sonarLayerOpacity=opacity))})
    if(showLinzDisclaimer)AlertDialog(onDismissRequest={showLinzDisclaimer=false},title={Text(tr("LINZ hydrographic chart overlay","LINZ 水文海图叠加层"))},text={Text(tr("This chart image layer is a navigation aid only. It may be unavailable or outdated and does not replace official charts, Notices to Mariners, depth instruments or a proper passage plan.","该海图影像层仅供辅助参考，可能不可用或已过期，不能替代官方海图、航海通告、测深仪或正规的航行计划。"))},confirmButton={Button({vm.updateSettings(state.settings.copy(linzHydroEnabled=true,linzHydroDisclaimerAccepted=true));showLinzDisclaimer=false}){Text(tr("I understand · Enable","我已了解 · 开启"))}},dismissButton={TextButton({showLinzDisclaimer=false}){Text(tr("Cancel","取消"))}})
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
private fun MapLayersSheet(state:MainUiState,dismiss:()->Unit,setMapType:(Int)->Unit,setLinz:(Boolean)->Unit,setOpacity:(Double)->Unit,setSonar:(Boolean)->Unit,setSonarOpacity:(Double)->Unit){
 val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
 val linzDiagnostics by LinzHydroDiagnostics.state.collectAsState()
 ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Text(tr("Map layers","地图图层"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
  Text(tr("Base map","底图"),style=MaterialTheme.typography.labelLarge);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){SegmentedButton(state.settings.mapType==1,{setMapType(1)},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Default","默认"))};SegmentedButton(state.settings.mapType==2,{setMapType(2)},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Satellite","卫星"))}}
  HorizontalDivider();SettingSwitch(tr("LINZ hydrographic charts","LINZ 水文海图"),if(BuildConfig.LINZ_HYDRO_CONFIGURED)tr("Image-tile overlay above either base map","可叠加在任一底图上的海图影像")else tr("Not configured in this build","当前编译版本未配置"),state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED,BuildConfig.LINZ_HYDRO_CONFIGURED,setLinz)
  if(!BuildConfig.LINZ_HYDRO_CONFIGURED){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(tr("Why the chart is unavailable","海图为什么不可用"),fontWeight=FontWeight.SemiBold);Text(tr("This APK was built without a LINZ data-access key (or a complete XYZ tile template). Add LINZ_API_KEY at build time; Yokuli then loads the official North Island, South Island and offshore chart sets.","此 APK 编译时没有配置 LINZ 数据访问密钥（或完整 XYZ 瓦片模板）。请在编译阶段加入 LINZ_API_KEY；Yokuli 随后会加载官方北岛、南岛及离岛海图集合。"),style=MaterialTheme.typography.bodySmall);TextButton({uriHandler.openUri("https://www.linz.govt.nz/guidance/data-service/linz-data-service-guide/web-services/creating-api-key")}){Icon(Icons.Default.OpenInNew,null);Spacer(Modifier.width(6.dp));Text(tr("Create a free LINZ data-access key","创建免费的 LINZ 数据访问密钥"))}}}}
  if(state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED){Text(tr("Chart opacity ${"%.0f".format(state.settings.linzHydroOpacity*100)}%","海图不透明度 ${"%.0f".format(state.settings.linzHydroOpacity*100)}%"),style=MaterialTheme.typography.labelLarge);Slider(state.settings.linzHydroOpacity.toFloat(),{setOpacity(it.toDouble())},valueRange=.30f..1f)}
  if(state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED){Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(tr("LINZ diagnostics","LINZ 诊断"),fontWeight=FontWeight.SemiBold);Text(tr("${linzDiagnostics.successes}/${linzDiagnostics.requests} tiles loaded · ${linzDiagnostics.failures} failed · HTTP ${linzDiagnostics.lastHttpCode?:"—"}","已加载 ${linzDiagnostics.successes}/${linzDiagnostics.requests} 个瓦片 · ${linzDiagnostics.failures} 个失败 · HTTP ${linzDiagnostics.lastHttpCode?:"—"}"),style=MaterialTheme.typography.bodySmall);Text(linzDiagnostics.message,style=MaterialTheme.typography.bodySmall,color=if(linzDiagnostics.failures>0&&linzDiagnostics.successes==0L)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}}}
  HorizontalDivider();SettingSwitch(tr("Personal sonar map","个人声呐海图"),tr("5 m measured grid; tap a cell to inspect depth and uncertainty","5 米实测网格；点击单元格查看水深与不确定度"),state.settings.sonarLayerEnabled,true,setSonar)
  if(state.settings.sonarLayerEnabled){Text(tr("Sonar opacity ${"%.0f".format(state.settings.sonarLayerOpacity*100)}%","声呐图层不透明度 ${"%.0f".format(state.settings.sonarLayerOpacity*100)}%"),style=MaterialTheme.typography.labelLarge);Slider(state.settings.sonarLayerOpacity.toFloat(),{setSonarOpacity(it.toDouble())},valueRange=.20f..1f);Text(tr("${state.sonarSamples.count{it.usable}} usable soundings in the selected survey. Interpolation is limited to 15 m and is labelled when inspected.","当前调查有 ${state.sonarSamples.count{it.usable}} 个可用测深点。插值限制在 15 米内，点击检查时会明确标注。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  Text(tr("Overlay availability depends on the configured LINZ service and network. Yokuli overlays and alarms remain independent.","叠加层是否可用取决于编译时配置的 LINZ 服务和网络；Yokuli 的轨迹与报警功能不受影响。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("Personal sonar mapping is an observation aid, not a certified navigation chart. Soundings can contain position, tide, transducer and bottom-detection errors.","个人声呐测绘仅供观测辅助，不是认证航海图。测深可能包含定位、潮汐、探头和海底识别误差。"),Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}
 }}
}
