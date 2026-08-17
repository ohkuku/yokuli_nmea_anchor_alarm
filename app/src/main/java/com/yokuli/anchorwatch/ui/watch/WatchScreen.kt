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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yokuli.anchorwatch.map.LinzHydroConfiguration
import com.yokuli.anchorwatch.map.LinzHydroTileProvider
import com.yokuli.anchorwatch.map.LinzHydroDiagnostics
import com.yokuli.anchorwatch.map.MapOverlayZ
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import com.yokuli.anchorwatch.map.SonarTileProvider
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import com.yokuli.anchorwatch.ui.theme.SafetyColors
import java.text.DateFormat
import kotlinx.coroutines.delay

private data class SonarMapInspection(
    val grid:com.yokuli.anchorwatch.domain.sonar.SonarInspection,
)

@Composable @OptIn(ExperimentalMaterial3Api::class)
internal fun WatchPage(state: MainUiState, vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(false) };var showPreflight by remember { mutableStateOf(false) };var showAdjust by remember { mutableStateOf(false) };var confirmLift by remember { mutableStateOf(false) };var showLayers by remember{mutableStateOf(false)};var showLinzDisclaimer by remember{mutableStateOf(false)};var showSonarDisclaimer by remember{mutableStateOf(false)}
    val fix = state.fix; val active = state.active
    LaunchedEffect(state.rangeEditorRequested,active?.id){if(state.rangeEditorRequested){showAdjust=active!=null;vm.consumeRangeEditorRequest()}}
    val renderGoogleMap = BuildConfig.MAPS_CONFIGURED && MapRuntimePolicy.renderGoogleEngine
    val trail = remember(state.points) { fadingTrailChunks(state.points) }
    val context=androidx.compose.ui.platform.LocalContext.current
    val linzTileProviders=remember{BuildConfig.LINZ_HYDRO_TILE_TEMPLATES.split('|').filter(LinzHydroConfiguration::isUsable).mapIndexed{index,template->LinzHydroTileProvider(template,java.io.File(context.filesDir,"offline_maps/linz_recent/$index"))}}
    val offlineTileProvider=remember(state.offlineMap.installed,state.offlineMap.revision){vm.createOfflineMapProvider()}
    DisposableEffect(offlineTileProvider){onDispose{offlineTileProvider?.close()}}
    val offlineImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.importOfflineMap(uri)}
    val sonarGrid=state.sonarGrid
    val sonarTileProvider=remember{SonarTileProvider(SonarGrid.build(emptyList()))};val sonarTileOverlayState=rememberTileOverlayState()
    val sonarLayerAvailable=SonarSurveyStartPolicy.canEnableLayer(state.settings.demoMode,state.connection)
    val sonarLayerVisible=state.settings.sonarLayerEnabled&&sonarLayerAvailable
    val sonarOverlayMounted=renderGoogleMap&&sonarLayerVisible&&sonarGrid.cells.isNotEmpty()
    var sonarInspection by remember{mutableStateOf<SonarMapInspection?>(null)}
    LaunchedEffect(sonarGrid,state.sonarGridVersion,sonarOverlayMounted){val version=state.sonarGridVersion;val changed=sonarTileProvider.updateGrid(sonarGrid,state.sonarGridChangedCells.takeIf{it.isNotEmpty()});if(changed>0&&sonarOverlayMounted)runCatching{sonarTileOverlayState.clearTileCache()};vm.consumeSonarGridChanges(version)}
    LaunchedEffect(sonarLayerVisible,sonarGrid){
        if(!sonarLayerVisible||sonarGrid.cells.isEmpty())sonarInspection=null
    }
    val initialPreciseFix=fix?.takeUnless{it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_NETWORK}
    val camera = rememberCameraPositionState { position = if(initialPreciseFix!=null)CameraPosition.fromLatLngZoom(LatLng(initialPreciseFix.latitude,initialPreciseFix.longitude), MapCameraPolicy.DEFAULT_FOLLOW_ZOOM)else CameraPosition.fromLatLngZoom(LatLng(0.0,0.0),2f) }
    var mapLoaded by remember { mutableStateOf(false) }
    var hasCenteredOnFix by remember { mutableStateOf(false) }
    var followedSource by remember { mutableStateOf<GpsDataSource?>(null) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var mapLocked by rememberSaveable{mutableStateOf(true)}
    LaunchedEffect(recenterRequest,mapLocked){if(recenterRequest>0&&!mapLocked){delay(900);vm.follow(false)}}
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
    val bottomSheetState=rememberStandardBottomSheetState(initialValue=SheetValue.PartiallyExpanded,skipHiddenState=true)
    val scaffoldState=rememberBottomSheetScaffoldState(bottomSheetState=bottomSheetState)
    BottomSheetScaffold(
        modifier=Modifier.fillMaxSize(),
        scaffoldState=scaffoldState,
        sheetPeekHeight=104.dp,
        sheetShadowElevation=8.dp,
        sheetDragHandle={BottomSheetDefaults.DragHandle()},
        sheetContent={WatchPanel(state,{showPreflight=true},{showAdjust=true},vm::setPhoneHeadingEvidence,vm::pauseWatch,vm::resumeWatch,{confirmLift=true}){active?.let(vm::openAnchorInGoogleMaps)}},
    ) { _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (renderGoogleMap) {
                    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(mapType = if(state.settings.mapType==2)MapType.SATELLITE else MapType.NORMAL), uiSettings = MapUiSettings(compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false,scrollGesturesEnabled=!mapLocked,zoomGesturesEnabled=!mapLocked,rotationGesturesEnabled=!mapLocked,tiltGesturesEnabled=!mapLocked), onMapLoaded = { mapLoaded = true },onMapClick={point->
                        val inspection=if(sonarLayerVisible)sonarGrid.inspect(point.latitude,point.longitude)else null
                        sonarInspection=inspection?.let{SonarMapInspection(it)}
                    }) {
                        // BitmapDescriptorFactory is initialized by the GoogleMap instance.
                        // Creating descriptors before entering this content lambda races cold
                        // Maps startup and crashes with "IBitmapDescriptorFactory is not initialized".
                        val boatIcon=remember{boatMarkerIcon()};val anchorIcon=remember{anchorMarkerIcon()}
                        if(state.settings.offlineMapEnabled&&offlineTileProvider!=null)TileOverlay(tileProvider=offlineTileProvider,fadeIn=false,transparency=(1.0-state.settings.offlineMapOpacity.coerceIn(.30,1.0)).toFloat(),visible=true,zIndex=MapOverlayZ.OFFLINE_CHART)
                        if(LinzHydroConfiguration.isOverlayVisible(BuildConfig.LINZ_HYDRO_CONFIGURED,state.settings.linzHydroEnabled))linzTileProviders.forEach{provider->TileOverlay(tileProvider=provider,fadeIn=true,transparency=LinzHydroConfiguration.transparency(state.settings.linzHydroOpacity),visible=true,zIndex=MapOverlayZ.LINZ_CHART)}
                        if(sonarOverlayMounted)TileOverlay(tileProvider=sonarTileProvider,state=sonarTileOverlayState,fadeIn=false,transparency=(1.0-state.settings.sonarLayerOpacity.coerceIn(.20,1.0)).toFloat(),visible=true,zIndex=MapOverlayZ.SONAR)
                        fix?.let { position -> Marker(state=remember(position.latitude, position.longitude){MarkerState(LatLng(position.latitude,position.longitude))},title=tr("Boat","船位"),icon=boatIcon,rotation=(displayHeading(position,active,state.points)?:0.0).toFloat(),flat=true,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.BOAT) }
                        active?.let { session ->
                            if(session.centerStatus==AnchorCenterStatus.RESOLVED.name){val anchor=LatLng(session.anchorLatitude,session.anchorLongitude)
                             Marker(state=remember(session.anchorLatitude,session.anchorLongitude){MarkerState(anchor)},title=tr("Anchor","锚点"),icon=anchorIcon,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.ANCHOR)
                             Circle(center=anchor,radius=session.alarmRadiusMeters,strokeColor=SafetyColors.Alarm,fillColor=SafetyColors.Alarm.copy(alpha=.09f),strokeWidth=3f,zIndex=MapOverlayZ.ALARM_GEOMETRY)}
                            else{
                             val reference=LatLng(session.learningReferenceLatitude?:session.anchorLatitude,session.learningReferenceLongitude?:session.anchorLongitude)
                             Circle(center=reference,radius=session.alarmRadiusMeters,strokeColor=SafetyColors.Warning,fillColor=SafetyColors.Warning.copy(alpha=.09f),strokeWidth=4f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             val estimatedLat=session.provisionalAnchorLatitude;val estimatedLon=session.provisionalAnchorLongitude
                             if(estimatedLat!=null&&estimatedLon!=null){val estimated=LatLng(estimatedLat,estimatedLon)
                              if(session.candidateDecision==CandidateDecision.AVAILABLE.name){Marker(state=remember(estimatedLat,estimatedLon){MarkerState(estimated)},title=tr("Candidate anchor · approval required","候选锚点 · 等待确认"),icon=anchorIcon,alpha=.80f,anchor=Offset(.5f,.5f),zIndex=MapOverlayZ.ANCHOR);Circle(center=estimated,radius=session.alarmRadiusMeters,strokeColor=SafetyColors.Candidate,fillColor=Color.Transparent,strokeWidth=3f,zIndex=MapOverlayZ.ALARM_GEOMETRY)}
                              else Circle(center=estimated,radius=session.provisionalRadiusMeters?:session.expectedSwingRadiusMeters.coerceAtLeast(10.0),strokeColor=SafetyColors.Learning,fillColor=SafetyColors.Learning.copy(alpha=.13f),strokeWidth=2f,zIndex=MapOverlayZ.ALARM_GEOMETRY)
                             }
                            }
                            trail.forEach{chunk->Polyline(points=chunk.points,color=SafetyColors.Trail.copy(alpha=chunk.alpha),width=4f,zIndex=MapOverlayZ.TRAIL)}
                        }
                    }
            } else if(!BuildConfig.MAPS_CONFIGURED) MapNotConfigured()
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).testTag("map_test_surface"))
            CompactWatchStatus(state,Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick={mapLocked=!mapLocked;if(mapLocked){hasCenteredOnFix=false;recenterRequest+=1;vm.follow(true)}else vm.follow(false)},modifier=Modifier.testTag("map_lock_toggle")){Icon(if(mapLocked)Icons.Default.Lock else Icons.Default.LockOpen,if(mapLocked)tr("Map locked to boat","地图已锁定跟船")else tr("Free map pan and zoom","地图可自由缩放"))}
                FilledTonalIconButton(onClick={hasCenteredOnFix=false;recenterRequest+=1;vm.follow(true)},modifier=Modifier.testTag("map_recenter")) { Icon(Icons.Default.MyLocation, tr("Recenter on boat","回到船位")) }
                FilledTonalIconButton({showLayers=true},modifier=Modifier.testTag("map_layers")){Icon(Icons.Default.Layers,tr("Map layers","地图图层"))}
            }
            if(LinzHydroConfiguration.isOverlayVisible(BuildConfig.LINZ_HYDRO_CONFIGURED,state.settings.linzHydroEnabled))Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp).padding(bottom=112.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.88f),shape=MaterialTheme.shapes.small){Text(LinzHydroConfiguration.ATTRIBUTION,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}
            else if(state.settings.offlineMapEnabled&&state.offlineMap.installed)Surface(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp).padding(bottom=112.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.88f),shape=MaterialTheme.shapes.small){Text(state.settings.offlineMapAttribution?:tr("User-supplied offline MBTiles","用户导入的离线 MBTiles"),Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}
            sonarInspection?.let{inspection->
                val survey=state.selectedSonarSurveyId?.takeIf{it!=CORRECTED_SONAR_HISTORY_ID}?.let{id->state.sonarSurveys.firstOrNull{it.id==id}}
                Surface(Modifier.align(Alignment.TopCenter).padding(start=16.dp,top=72.dp,end=16.dp).clickable{sonarInspection=null},color=MaterialTheme.colorScheme.surface.copy(alpha=.96f),shape=MaterialTheme.shapes.medium,shadowElevation=4.dp){Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(inspection.grid.measured)tr("Measured depth","实测水深")else tr("Estimated from nearby sonar samples","由附近声呐样本插值得到"),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Icon(Icons.Default.Close,tr("Close","关闭"),Modifier.size(16.dp))};Text("${"%.2f".format(inspection.grid.depthMeters)} ${tr("m","米")} · ±${"%.2f".format(inspection.grid.uncertaintyMeters)} ${tr("m","米")} · ${inspection.grid.sampleCount} ${tr("samples","个样本")}",style=MaterialTheme.typography.bodySmall);survey?.let{Text("${it.name} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(java.util.Date(it.startedAt))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            }
            if(active?.candidateDecision==CandidateDecision.AVAILABLE.name)Box(Modifier.align(Alignment.BottomCenter).padding(bottom=112.dp,start=12.dp,end=12.dp)){EstimatedCenterBanner(state,vm,active)}
        }
    }
    if (showSetup) {
        AnchorSetupSheet(state,{showSetup=false}){lat,lon,input->vm.arm(lat,lon,input);showSetup=false}
    }
    if(showPreflight)WatchPreflightSheet(state,{showPreflight=false}){showPreflight=false;showSetup=true}
    if(showAdjust&&active!=null)AnchorSettingsDialog(fix,active,{showAdjust=false}){input->vm.updateAnchorSettings(input);showAdjust=false}
    if (confirmLift) AlertDialog({ confirmLift = false }, confirmButton = { Button({ vm.liftAnchor(); confirmLift = false },colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Lift anchor","起锚")) } }, dismissButton = { TextButton({ confirmLift = false }) { Text(tr("Cancel","取消")) } }, title = { Text(tr("End this anchoring session?","结束本次锚泊？")) }, text = { Text(tr("Lift anchor permanently closes this session. Its track remains in History, but it cannot be resumed.","起锚会永久结束本次锚泊。轨迹仍保留在历史记录中，但不能再恢复。")) })
    if(showLayers)MapLayersSheet(state,{showLayers=false},{vm.setMapType(it)},{enabled->if(enabled&&!state.settings.linzHydroDisclaimerAccepted)showLinzDisclaimer=true else vm.updateSettings(state.settings.copy(linzHydroEnabled=enabled))},{opacity->vm.updateSettings(state.settings.copy(linzHydroOpacity=opacity))},{enabled->if(enabled&&!state.settings.sonarDisclaimerAccepted)showSonarDisclaimer=true else vm.setSonarLayerEnabled(enabled)},{opacity->vm.updateSettings(state.settings.copy(sonarLayerOpacity=opacity))},vm::setOfflineMapEnabled,{offlineImport.launch(arrayOf("application/vnd.sqlite3","application/x-sqlite3","application/octet-stream","*/*"))},vm::removeOfflineMap)
    if(showLinzDisclaimer)AlertDialog(onDismissRequest={showLinzDisclaimer=false},title={Text(tr("LINZ hydrographic chart overlay","LINZ 水文海图叠加层"))},text={Text(tr("This chart image layer is a navigation aid only. It may be unavailable or outdated and does not replace official charts, Notices to Mariners, depth instruments or a proper passage plan.","该海图影像层仅供辅助参考，可能不可用或已过期，不能替代官方海图、航海通告、测深仪或正规的航行计划。"))},confirmButton={Button({vm.updateSettings(state.settings.copy(linzHydroEnabled=true,linzHydroDisclaimerAccepted=true));showLinzDisclaimer=false}){Text(tr("I understand · Enable","我已了解 · 开启"))}},dismissButton={TextButton({showLinzDisclaimer=false}){Text(tr("Cancel","取消"))}})
    if(showSonarDisclaimer)SonarSafetyDisclaimerDialog({showSonarDisclaimer=false}){vm.setSonarLayerEnabled(true,acceptDisclaimer=true);showSonarDisclaimer=false}
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
private fun WatchPreflightSheet(state:MainUiState,dismiss:()->Unit,continueSetup:()->Unit){
 val report=state.watchSafety
 ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(if(report.ready)Icons.Default.VerifiedUser else if(report.canContinue)Icons.Default.WarningAmber else Icons.Default.GppBad,null,tint=if(report.ready)SafetyColors.Safe else if(report.canContinue)SafetyColors.Warning else SafetyColors.Alarm);Column{Text(tr("Watch preflight","布防前安全检查"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold);Text(when{report.ready->tr("Ready to watch","已准备好监控");report.canContinue->tr("Can continue with stated risks","可以继续，但存在明确风险");else->tr("Fix blockers before arming","解决阻断项后才能布防")},color=if(report.ready)SafetyColors.Safe else MaterialTheme.colorScheme.onSurfaceVariant)}}
  LazyColumn(Modifier.fillMaxWidth().heightIn(max=430.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(report.checks,key={it.id}){check->SafetyCheckRow(check)}}
  if(report.canContinue&&!report.ready)Text(tr("Warnings do not silently disable the watch. Continuing means you accept the listed operational risks for this session.","警告不会悄悄关闭锚警；继续表示你接受本次会话列出的运行风险。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Button(continueSetup,Modifier.fillMaxWidth().testTag("preflight_continue"),enabled=report.canContinue){Text(if(report.ready)tr("Continue to anchor setup","继续设置锚点")else tr("Continue with warnings","带警告继续"))}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
 }}
}

@Composable private fun SafetyCheckRow(check:com.yokuli.anchorwatch.domain.safety.SafetyCheck){
 val color=when(check.status){com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.OK->SafetyColors.Safe;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING->SafetyColors.Warning;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.BLOCKER->SafetyColors.Alarm}
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(when(check.status){com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.OK->Icons.Default.CheckCircle;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING->Icons.Default.Warning;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.BLOCKER->Icons.Default.Cancel},null,Modifier.size(20.dp),tint=color);Column(Modifier.weight(1f)){Text(safetyTitle(check.id,check.title),fontWeight=FontWeight.Medium);Text(localizeSafetyText(check.detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);check.risk?.let{Text(localizeSafetyText(it),style=MaterialTheme.typography.bodySmall,color=color)}}}
}

@Composable internal fun safetyTitle(id:String,fallback:String)=when(id){"gps_fresh"->tr("GPS freshness","GPS 新鲜度");"gps_accuracy"->tr("GPS accuracy","GPS 精度");"nmea"->tr("NMEA source","NMEA 数据源");"notifications"->tr("Alarm notifications","报警通知");"alarm_sound"->tr("Alarm sound","报警声音");"background"->tr("Background monitor","后台监控");"battery_optimization"->tr("Battery optimization","电池优化");"battery"->tr("Battery","电量");"network"->tr("Wi-Fi / network","Wi-Fi / 网络");"storage"->tr("Storage","存储空间");"sonar"->tr("Sonar","声呐");else->fallback}

@Composable internal fun localizeSafetyText(value:String):String{
 if(!LocalAppLanguage.current.usesChinese())return value
 return when{
  value=="No valid fix"->"没有有效定位";value.startsWith("Stale by ")->value.replace("Stale by ","已过期 ").replace(" s"," 秒")
  value=="An anchor alarm cannot measure vessel movement without a position."->"没有船位时，锚警无法测量船舶移动。";value=="Arming from an old position can put the boundary in the wrong place."->"使用旧船位布防可能会把警戒范围放错位置。"
  value.startsWith("Fresh · ")->value.replace("Fresh · ","新鲜 · ").replace(" s"," 秒");value=="Unavailable"->"不可用";value=="Position quality is unknown."->"定位质量未知。"
  value=="Accuracy is wider than a useful anchor boundary."->"定位误差已经大于实用的锚警范围。";value=="Use a wider alarm radius or wait for better accuracy."->"请扩大报警半径，或等待更好的定位精度。";value=="Satellite geometry is weak; allow more margin."->"卫星几何条件较弱，请预留更大余量。"
  value=="Not reported"->"未报告";value=="The source did not report accuracy; choose a conservative range."->"数据源没有报告精度，请使用更保守的范围。";value=="Not required for selected GPS"->"所选 GPS 不需要网络";value.startsWith("Not required for ")->"当前 GPS 数据源不需要 NMEA"
  value=="Connected and delivering data"->"已连接并持续提供数据";value in setOf("CONNECTING","RECONNECTING")->if(value=="CONNECTING")"正在连接" else "正在重连";value in setOf("DISCONNECTED","CONNECTED_NO_DATA","STALE","ERROR")->when(value){"DISCONNECTED"->"未连接";"CONNECTED_NO_DATA"->"已连接但无数据";"STALE"->"数据过期";else->"错误"}
  value=="Wait for a stable live NMEA position before arming."->"请等待稳定的实时 NMEA 船位后再布防。";value=="The selected GPS source is unavailable."->"所选 GPS 数据源不可用。"
  value=="Allowed"->"已允许";value=="Permission denied"->"权限被拒绝";value=="Android may hide critical anchor alarm notifications."->"Android 可能会隐藏关键锚警通知。"
  value=="Android alarm volume is muted"->"Android 警报音量为静音";value=="The alarm cannot be heard."->"警报将无法被听见。";value=="Not confirmed on this device"->"尚未在本设备确认";value=="Run the alarm test and confirm that you heard it."->"请运行警报测试并确认你能听见。";value=="Last confirmed over 30 days ago"->"上次确认已超过 30 天";value=="Retest after Android, audio or vessel setup changes."->"Android、音频或船上安装发生变化后请重新测试。";value=="Audible test confirmed"->"已确认能听见警报"
  value=="Foreground service available"->"前台服务可用";value=="Service unavailable"->"服务不可用";value=="The watch cannot continue reliably when the screen is off."->"屏幕关闭后锚警将无法可靠持续运行。"
  value=="Unrestricted"->"不受限制";value=="Android may restrict background work"->"Android 可能限制后台运行";value=="Allow unrestricted battery use for an overnight watch."->"过夜监控前请允许不受限制的电池使用。"
  value=="Confirm reliable external power before an overnight watch."->"过夜监控前请确认有可靠的外部电源。";value=="Connect reliable power before starting the watch."->"开始监控前请连接可靠电源。";value=="Keep the device on reliable power."->"请让设备持续连接可靠电源。"
  value=="No active network"->"没有活动网络";value=="The NMEA endpoint cannot be kept reachable."->"无法持续访问 NMEA 端点。";value=="Wi-Fi connected"->"Wi-Fi 已连接";value=="Connected without Wi-Fi"->"已联网但不是 Wi-Fi";value=="Confirm the NMEA server is reachable over this transport."->"请确认可通过当前网络访问 NMEA 服务器。"
  value=="Less than 10 MB free"->"可用空间不足 10 MB";value.endsWith(" MB free")->value.replace(" MB free"," MB 可用");value=="Safety events and track points may not be saved."->"安全事件与轨迹点可能无法保存。";value=="Free storage before a long watch or sonar survey."->"长时间监控或声呐调查前请释放存储空间。"
  value=="Not required for anchor watch"->"锚警不要求声呐";value=="Live depth and matching position"->"实时水深与配对船位正常";value=="Survey active but data is stale"->"调查进行中，但数据已过期";value=="Anchor watch can continue; sonar mapping will wait for same-stream data."->"锚警可以继续；声呐测绘会等待同一数据流恢复。"
  else->value
 }
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
private fun MapLayersSheet(state:MainUiState,dismiss:()->Unit,setMapType:(Int)->Unit,setLinz:(Boolean)->Unit,setOpacity:(Double)->Unit,setSonar:(Boolean)->Unit,setSonarOpacity:(Double)->Unit,setOffline:(Boolean)->Unit,importOffline:()->Unit,removeOffline:()->Unit){
 val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
 val linzDiagnostics by LinzHydroDiagnostics.state.collectAsState()
 val sonarAvailable=SonarSurveyStartPolicy.canEnableLayer(state.settings.demoMode,state.connection)
 ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Text(tr("Map layers","地图图层"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
  Text(tr("Base map","底图"),style=MaterialTheme.typography.labelLarge);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){SegmentedButton(state.settings.mapType==1,{setMapType(1)},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Default","默认"))};SegmentedButton(state.settings.mapType==2,{setMapType(2)},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Satellite","卫星"))}}
  HorizontalDivider();Text(tr("Offline chart","离线海图"),style=MaterialTheme.typography.labelLarge)
  SettingSwitch(tr("User MBTiles","用户 MBTiles"),if(state.offlineMap.installed)"${state.offlineMap.name?:tr("Offline map","离线地图")} · ${state.offlineMap.tileCount} ${tr("tiles","瓦片")}" else tr("No offline map installed","尚未安装离线地图"),state.settings.offlineMapEnabled&&state.offlineMap.installed,state.offlineMap.installed,setOffline)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(importOffline){Icon(Icons.Default.FileOpen,null);Spacer(Modifier.width(6.dp));Text(if(state.offlineMap.installed)tr("Replace MBTiles","替换 MBTiles")else tr("Import MBTiles","导入 MBTiles"))};if(state.offlineMap.installed)TextButton(removeOffline){Icon(Icons.Default.DeleteOutline,null);Spacer(Modifier.width(4.dp));Text(tr("Remove","删除"))}}
  Text(tr("Yokuli never caches Google tiles. Import only raster MBTiles that you are licensed to store and use. GeoPackage is reserved for a later interoperable reader.","Yokuli 绝不会缓存 Google 瓦片。请只导入你有权存储和使用的栅格 MBTiles；GeoPackage 将由后续兼容读取器支持。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  HorizontalDivider();SettingSwitch(tr("LINZ hydrographic charts","LINZ 水文海图"),if(BuildConfig.LINZ_HYDRO_CONFIGURED)tr("Image-tile overlay above either base map","可叠加在任一底图上的海图影像")else tr("Not configured in this build","当前编译版本未配置"),state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED,BuildConfig.LINZ_HYDRO_CONFIGURED,setLinz)
  if(!BuildConfig.LINZ_HYDRO_CONFIGURED){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(tr("Why the chart is unavailable","海图为什么不可用"),fontWeight=FontWeight.SemiBold);Text(tr("This APK was built without a LINZ data-access key (or a complete XYZ tile template). Add LINZ_API_KEY at build time; Yokuli then loads the official North Island, South Island and offshore chart sets.","此 APK 编译时没有配置 LINZ 数据访问密钥（或完整 XYZ 瓦片模板）。请在编译阶段加入 LINZ_API_KEY；Yokuli 随后会加载官方北岛、南岛及离岛海图集合。"),style=MaterialTheme.typography.bodySmall);TextButton({uriHandler.openUri("https://www.linz.govt.nz/guidance/data-service/linz-data-service-guide/web-services/creating-api-key")}){Icon(Icons.AutoMirrored.Filled.OpenInNew,null);Spacer(Modifier.width(6.dp));Text(tr("Create a free LINZ data-access key","创建免费的 LINZ 数据访问密钥"))}}}}
  if(state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED){Text(tr("Chart opacity ${"%.0f".format(state.settings.linzHydroOpacity*100)}%","海图不透明度 ${"%.0f".format(state.settings.linzHydroOpacity*100)}%"),style=MaterialTheme.typography.labelLarge);Slider(state.settings.linzHydroOpacity.toFloat(),{setOpacity(it.toDouble())},valueRange=.30f..1f)}
  if(state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED){Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(tr("LINZ diagnostics","LINZ 诊断"),fontWeight=FontWeight.SemiBold);Text(tr("${linzDiagnostics.successes}/${linzDiagnostics.requests} tiles loaded · ${linzDiagnostics.failures} failed · HTTP ${linzDiagnostics.lastHttpCode?:"—"}","已加载 ${linzDiagnostics.successes}/${linzDiagnostics.requests} 个瓦片 · ${linzDiagnostics.failures} 个失败 · HTTP ${linzDiagnostics.lastHttpCode?:"—"}"),style=MaterialTheme.typography.bodySmall);Text(localizeKnownMessage(linzDiagnostics.message),style=MaterialTheme.typography.bodySmall,color=if(linzDiagnostics.failures>0&&linzDiagnostics.successes==0L)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}}}
  HorizontalDivider();SettingSwitch(tr("Personal sonar map","个人声呐海图"),if(sonarAvailable)tr("5 m measured grid; tap a cell to inspect depth and uncertainty","5 米实测网格；点击单元格查看水深与不确定度")else tr("Connect the NMEA server first; Demo mode is the only offline exception","请先连接 NMEA 服务器；仅演示模式允许离线开启"),state.settings.sonarLayerEnabled&&sonarAvailable,sonarAvailable,setSonar)
  if(!sonarAvailable)Text(tr("Unavailable while NMEA is disconnected. The map cannot combine sonar with the phone's unrelated GPS.","NMEA 未连接时不可用；声呐海图不能与手机上无关的 GPS 船位混用。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
  if(state.settings.sonarLayerEnabled&&sonarAvailable){val samples=state.sonarGrid.cells.values.sumOf{it.sampleCount};Text(tr("Sonar opacity ${"%.0f".format(state.settings.sonarLayerOpacity*100)}%","声呐图层不透明度 ${"%.0f".format(state.settings.sonarLayerOpacity*100)}%"),style=MaterialTheme.typography.labelLarge);Slider(state.settings.sonarLayerOpacity.toFloat(),{setSonarOpacity(it.toDouble())},valueRange=.20f..1f);Text(tr("${state.sonarGrid.cells.size} measured cells from $samples usable soundings. Interpolation is limited to 15 m and is labelled when inspected.","${state.sonarGrid.cells.size} 个实测网格，来自 $samples 个可用测深点。插值限制在 15 米内，点击检查时会明确标注。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  Text(tr("Overlay availability depends on the configured LINZ service and network. Yokuli overlays and alarms remain independent.","叠加层是否可用取决于编译时配置的 LINZ 服务和网络；Yokuli 的轨迹与报警功能不受影响。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("Personal sonar mapping is an observation aid, not a certified navigation chart. Soundings can contain position, tide, transducer and bottom-detection errors.","个人声呐测绘仅供观测辅助，不是认证航海图。测深可能包含定位、潮汐、探头和海底识别误差。"),Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}
 }}
}
