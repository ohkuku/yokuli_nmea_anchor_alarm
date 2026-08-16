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
import com.yokuli.anchorwatch.map.MapOverlayZ
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat

@Composable
internal fun WatchPage(state: MainUiState, vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(false) };var showAdjust by remember { mutableStateOf(false) };var confirmLift by remember { mutableStateOf(false) };var showLayers by remember{mutableStateOf(false)};var showLinzDisclaimer by remember{mutableStateOf(false)}
    val fix = state.fix; val active = state.active
    LaunchedEffect(state.rangeEditorRequested,active?.id){if(state.rangeEditorRequested){showAdjust=active!=null;vm.consumeRangeEditorRequest()}}
    val boatIcon = remember { boatMarkerIcon() }; val anchorIcon = remember { anchorMarkerIcon() }
    val trail = remember(state.points) { fadingTrailChunks(state.points) }
    val linzTileProvider=remember{LinzHydroTileProvider(BuildConfig.LINZ_HYDRO_TILE_TEMPLATE)}
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
                    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(mapType = if(state.settings.mapType==2)MapType.SATELLITE else MapType.NORMAL), uiSettings = MapUiSettings(compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false), onMapLoaded = { mapLoaded = true }) {
                        if(LinzHydroConfiguration.isOverlayVisible(BuildConfig.LINZ_HYDRO_CONFIGURED,state.settings.linzHydroEnabled)&&LinzHydroConfiguration.isUsable(BuildConfig.LINZ_HYDRO_TILE_TEMPLATE))TileOverlay(tileProvider=linzTileProvider,fadeIn=true,transparency=LinzHydroConfiguration.transparency(state.settings.linzHydroOpacity),visible=true,zIndex=MapOverlayZ.LINZ_CHART)
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
        }
        WatchPanel(state,{showSetup=true},{showAdjust=true},vm::pauseWatch,vm::resumeWatch,{confirmLift=true}){active?.let(vm::openAnchorInGoogleMaps)}
    }
    if (showSetup) {
        AnchorSetupSheet(state,{showSetup=false}){lat,lon,input->vm.arm(lat,lon,input);showSetup=false}
    }
    if(showAdjust&&active!=null)AnchorSettingsDialog(fix,active,{showAdjust=false}){input->vm.updateAnchorSettings(input);showAdjust=false}
    if (confirmLift) AlertDialog({ confirmLift = false }, confirmButton = { Button({ vm.liftAnchor(); confirmLift = false },colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Lift anchor","起锚")) } }, dismissButton = { TextButton({ confirmLift = false }) { Text(tr("Cancel","取消")) } }, title = { Text(tr("End this anchoring session?","结束本次锚泊？")) }, text = { Text(tr("Lift anchor permanently closes this session. Its track remains in History, but it cannot be resumed.","起锚会永久结束本次锚泊。轨迹仍保留在历史记录中，但不能再恢复。")) })
    if(active?.candidateDecision==CandidateDecision.AVAILABLE.name)EstimatedCenterSheet(state,vm,active)
    if(showLayers)MapLayersSheet(state,{showLayers=false},{vm.setMapType(it)},{enabled->if(enabled&&!state.settings.linzHydroDisclaimerAccepted)showLinzDisclaimer=true else vm.updateSettings(state.settings.copy(linzHydroEnabled=enabled))},{opacity->vm.updateSettings(state.settings.copy(linzHydroOpacity=opacity))})
    if(showLinzDisclaimer)AlertDialog(onDismissRequest={showLinzDisclaimer=false},title={Text(tr("LINZ hydrographic chart overlay","LINZ 水文海图叠加层"))},text={Text(tr("This chart image layer is a navigation aid only. It may be unavailable or outdated and does not replace official charts, Notices to Mariners, depth instruments or a proper passage plan.","该海图影像层仅供辅助参考，可能不可用或已过期，不能替代官方海图、航海通告、测深仪或正规的航行计划。"))},confirmButton={Button({vm.updateSettings(state.settings.copy(linzHydroEnabled=true,linzHydroDisclaimerAccepted=true));showLinzDisclaimer=false}){Text(tr("I understand · Enable","我已了解 · 开启"))}},dismissButton={TextButton({showLinzDisclaimer=false}){Text(tr("Cancel","取消"))}})
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
private fun MapLayersSheet(state:MainUiState,dismiss:()->Unit,setMapType:(Int)->Unit,setLinz:(Boolean)->Unit,setOpacity:(Double)->Unit){
 ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Text(tr("Map layers","地图图层"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
  Text(tr("Base map","底图"),style=MaterialTheme.typography.labelLarge);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){SegmentedButton(state.settings.mapType==1,{setMapType(1)},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Default","默认"))};SegmentedButton(state.settings.mapType==2,{setMapType(2)},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Satellite","卫星"))}}
  HorizontalDivider();SettingSwitch(tr("LINZ hydrographic charts","LINZ 水文海图"),if(BuildConfig.LINZ_HYDRO_CONFIGURED)tr("Image-tile overlay above either base map","可叠加在任一底图上的海图影像")else tr("Not configured in this build","当前编译版本未配置"),state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED,BuildConfig.LINZ_HYDRO_CONFIGURED,setLinz)
  if(state.settings.linzHydroEnabled&&BuildConfig.LINZ_HYDRO_CONFIGURED){Text(tr("Chart opacity ${"%.0f".format(state.settings.linzHydroOpacity*100)}%","海图不透明度 ${"%.0f".format(state.settings.linzHydroOpacity*100)}%"),style=MaterialTheme.typography.labelLarge);Slider(state.settings.linzHydroOpacity.toFloat(),{setOpacity(it.toDouble())},valueRange=.30f..1f)}
  Text(tr("Overlay availability depends on the configured LINZ service and network. Yokuli overlays and alarms remain independent.","叠加层是否可用取决于编译时配置的 LINZ 服务和网络；Yokuli 的轨迹与报警功能不受影响。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }}
}
