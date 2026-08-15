package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.yokuli.anchorwatch.domain.anchor.WindAnchorEvidence
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
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
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm:MainViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionsChanged() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { AnchorApp(vm) } }
    }
}

private data class Destination(val label: String, val icon: ImageVector)
private val LocalAppLanguage = compositionLocalOf { AppLanguage.SYSTEM }
@Composable private fun tr(english:String, chinese:String)=localized(LocalAppLanguage.current,english,chinese)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnchorApp(vm: MainViewModel) {
    val state by vm.ui.collectAsState()
    CompositionLocalProvider(LocalAppLanguage provides state.settings.appLanguage) {
        val destinations = listOf(
            Destination(tr("Watch", "锚警"), Icons.Default.Map), Destination(tr("Connect", "连接"), Icons.Default.Link),
            Destination("NMEA", Icons.Default.DataObject), Destination(tr("History", "历史"), Icons.AutoMirrored.Filled.List),
            Destination(tr("Settings", "设置"), Icons.Default.Settings),
        )
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text(tr("Anchor by Yokuli", "Yokuli锚警系统"), fontWeight = FontWeight.SemiBold) }) },
            bottomBar = { NavigationBar { destinations.forEachIndexed { index, item -> NavigationBarItem(state.page == index, { vm.page(index) }, { Icon(item.icon, item.label) }, label = { Text(item.label) }) } } }
        ) { padding ->
            Box(Modifier.padding(padding)) { when (state.page) { 0 -> WatchPage(state, vm); 1 -> ConnectionPage(state, vm); 2 -> NmeaDataPage(state, vm); 3 -> HistoryPage(state, vm); else -> SettingsPage(state, vm) } }
        }
        AnchorDragAlarmDialog(state,vm)
    }
}

@Composable private fun AnchorDragAlarmDialog(state:MainUiState,vm:MainViewModel){
    val active=state.active
    val alarm=state.alarmSnapshot
    val snoozed=(active?.alarmSnoozedUntil?:0L)>System.currentTimeMillis()
    val visible=active?.paused==false&&!snoozed&&alarm.state==AlarmState.ALARM&&alarm.type==AlarmType.ANCHOR_RADIUS_EXCEEDED
    var confirmLift by remember(active?.id){mutableStateOf(false)}
    LaunchedEffect(visible){if(!visible)confirmLift=false}
    if(!visible)return
    if(confirmLift){
        AlertDialog(onDismissRequest={},confirmButton={Button({vm.liftAnchor();confirmLift=false},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Lift anchor and end session","起锚并结束会话"))}},dismissButton={TextButton({confirmLift=false}){Text(tr("Back to alarm actions","返回警报操作"))}},title={Text(tr("End this anchoring session?","结束本次锚泊？"))},text={Text(tr("Lift anchor permanently closes the session and silences this alarm. Use Pause if the anchor is still down.","起锚会永久结束本次会话并停止警报；如果锚仍在水中，请使用暂停监控。"))})
    }else{
        AlertDialog(
            onDismissRequest={},
            confirmButton={Button(vm::acknowledge,modifier=Modifier.testTag("alarm_snooze_action")){Icon(Icons.Default.Snooze,null);Spacer(Modifier.width(6.dp));Text(tr("Snooze ${state.settings.alarmSnoozeMinutes} min","${state.settings.alarmSnoozeMinutes} 分钟后提醒"))}},
            dismissButton={Column(horizontalAlignment=Alignment.End){TextButton({vm.acknowledge();vm.requestRangeEditor()}){Text(tr("Snooze & adjust range","稍后提醒并调整范围"))};Row{TextButton(vm::pauseWatch){Text(tr("Pause watch","暂停监控"))};TextButton({confirmLift=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(tr("Lift anchor","起锚"))}}}},
            title={Text(tr("ANCHOR DRAG ALARM","走锚警报"),modifier=Modifier.testTag("in_app_anchor_alarm"),color=MaterialTheme.colorScheme.error)},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("The boat is ${alarm.distanceMeters?.toInt()?:"--"} m from the anchor centre; the limit is ${active.alarmRadiusMeters.toInt()} m.","船距锚点中心 ${alarm.distanceMeters?.toInt()?:"--"} 米，报警范围为 ${active.alarmRadiusMeters.toInt()} 米。"),style=MaterialTheme.typography.titleMedium);Text(tr("Check the vessel and surroundings now. Choose an action below; monitoring continues unless you pause or lift anchor.","请立即检查船况和周边环境，并选择下方操作；除非暂停监控或起锚，否则锚警会继续运行。"))}},
        )
    }
}

@Composable
private fun WatchPage(state: MainUiState, vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(false) };var showAdjust by remember { mutableStateOf(false) };var confirmLift by remember { mutableStateOf(false) }
    val fix = state.fix; val active = state.active
    LaunchedEffect(state.rangeEditorRequested,active?.id){if(state.rangeEditorRequested){showAdjust=active!=null;vm.consumeRangeEditorRequest()}}
    val boatIcon = remember { boatMarkerIcon() }; val anchorIcon = remember { anchorMarkerIcon() }
    val trail = remember(state.points) { fadingTrailChunks(state.points) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(fix?.latitude ?: -36.8485, fix?.longitude ?: 174.7633), MapCameraPolicy.DEFAULT_FOLLOW_ZOOM) }
    var mapLoaded by remember { mutableStateOf(false) }
    var hasCenteredOnFix by remember { mutableStateOf(false) }
    var followedSource by remember { mutableStateOf<GpsDataSource?>(null) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(mapLoaded, fix?.latitude, fix?.longitude, fix?.valid, state.follow, state.settings.gpsDataSource, recenterRequest) {
        if (mapLoaded && fix?.valid == true && state.follow) {
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
        LiveStatusStrip(state)
        Box(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (BuildConfig.MAPS_CONFIGURED) {
                    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(mapType = if(state.settings.mapType==2)MapType.SATELLITE else MapType.NORMAL), uiSettings = MapUiSettings(compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false), onMapLoaded = { mapLoaded = true }) {
                        fix?.let { position -> Marker(state=remember(position.latitude, position.longitude){MarkerState(LatLng(position.latitude,position.longitude))},title=tr("Boat","船位"),icon=boatIcon,rotation=(displayHeading(position,active,state.points)?:0.0).toFloat(),flat=true,anchor=Offset(.5f,.5f),zIndex=3f) }
                        active?.let { session ->
                            if(session.centerStatus==AnchorCenterStatus.RESOLVED.name){val anchor=LatLng(session.anchorLatitude,session.anchorLongitude)
                             Marker(state=remember(session.anchorLatitude,session.anchorLongitude){MarkerState(anchor)},title=tr("Anchor","锚点"),icon=anchorIcon,anchor=Offset(.5f,.5f),zIndex=2f)
                             Circle(center=anchor,radius=session.alarmRadiusMeters,strokeColor=Color(0xFFFF5252),fillColor=Color(0x16FF5252),strokeWidth=3f)}
                            else{
                             val reference=LatLng(session.learningReferenceLatitude?:session.anchorLatitude,session.learningReferenceLongitude?:session.anchorLongitude)
                             Circle(center=reference,radius=session.alarmRadiusMeters,strokeColor=Color(0xFFFFA726),fillColor=Color(0x16FFA726),strokeWidth=4f)
                             val estimatedLat=session.provisionalAnchorLatitude;val estimatedLon=session.provisionalAnchorLongitude
                             if(estimatedLat!=null&&estimatedLon!=null){val estimated=LatLng(estimatedLat,estimatedLon);Marker(state=remember(estimatedLat,estimatedLon){MarkerState(estimated)},title=tr("Estimated anchor · ${session.centerConfidence.lowercase()} confidence","估算锚点 · ${session.centerConfidence.lowercase()} 置信度"),icon=anchorIcon,alpha=.75f,anchor=Offset(.5f,.5f),zIndex=2f);Circle(center=estimated,radius=session.provisionalRadiusMeters?:15.0,strokeColor=Color(0xFF26C6DA),fillColor=Color(0x2226C6DA),strokeWidth=3f)}
                            }
                            trail.forEachIndexed{index,points->val age=(index+1f)/trail.size.coerceAtLeast(1);val alpha=.12f+.86f*age*age;Polyline(points=points,color=Color(0xFFFFD54F).copy(alpha=alpha),width=4f,zIndex=1f)}
                        }
                    }
            } else MapNotConfigured()
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton({hasCenteredOnFix=false;recenterRequest+=1;vm.follow(true)}) { Icon(Icons.Default.MyLocation, tr("Recenter on boat","回到船位")) }
                FilledTonalButton({vm.setMapType(if(state.settings.mapType==2)1 else 2)}){Icon(Icons.Default.Layers,null);Spacer(Modifier.width(6.dp));Text(if(state.settings.mapType==2)tr("Default","默认") else tr("Satellite","卫星"))}
            }
        }
        WatchPanel(state,{showSetup=true},{showAdjust=true},vm::pauseWatch,vm::resumeWatch,{confirmLift=true}){active?.let(vm::openAnchorInGoogleMaps)}
    }
    if (showSetup) {
        AnchorSettingsDialog(fix,null,{showSetup=false}){input->vm.arm(fix!!.latitude,fix.longitude,input);showSetup=false}
    }
    if(showAdjust&&active!=null)AnchorSettingsDialog(fix,active,{showAdjust=false}){input->vm.updateAnchorSettings(input);showAdjust=false}
    if (confirmLift) AlertDialog({ confirmLift = false }, confirmButton = { Button({ vm.liftAnchor(); confirmLift = false },colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(tr("Lift anchor","起锚")) } }, dismissButton = { TextButton({ confirmLift = false }) { Text(tr("Cancel","取消")) } }, title = { Text(tr("End this anchoring session?","结束本次锚泊？")) }, text = { Text(tr("Lift anchor permanently closes this session. Its track remains in History, but it cannot be resumed.","起锚会永久结束本次锚泊。轨迹仍保留在历史记录中，但不能再恢复。")) })
}

@Composable private fun LiveStatusStrip(state: MainUiState) { val watch=state.active;val source=state.settings.gpsDataSource;val gpsOk=state.fix?.valid==true&&(source!=GpsDataSource.DEMO||state.demoGps.signalAvailable);Surface(tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusItem("NMEA", state.connection == NmeaConnectionState.CONNECTED, if(state.connection==NmeaConnectionState.CONNECTED)tr("LIVE","在线") else tr("OFF","关闭")); StatusItem("GPS · ${when(source){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->tr("SYS","系统");GpsDataSource.DEMO->tr("DEMO","演示")}}",gpsOk,if(gpsOk)tr("VALID","有效") else if(source==GpsDataSource.DEMO&&!state.demoGps.signalAvailable)tr("DROPOUT","中断") else tr("NO FIX","无定位")); StatusItem(tr("PROXY","代理"), state.mockGps.state == MockGpsState.ACTIVE, if(state.mockGps.state==MockGpsState.ACTIVE)tr("ACTIVE","已开启") else tr("OFF","关闭")); StatusItem(tr("WATCH","锚警"), watch!=null&&!watch.paused, when{watch==null->tr("OFF","关闭");watch.paused->tr("PAUSED","已暂停");watch.centerStatus==AnchorCenterStatus.LEARNING.name->tr("LEARNING","学习中");else->tr("ARMED","已布防")}) } } }
@Composable private fun RowScope.StatusItem(label: String, ok: Boolean, value: String) { Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Box(Modifier.size(9.dp).background(if (ok) Color(0xFF4CAF50) else Color(0xFFEF5350), MaterialTheme.shapes.small)); Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1) } } }

@Composable private fun MapNotConfigured() { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Card { Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.Map, null, Modifier.size(40.dp)); Text(tr("Google Maps is not configured","Google 地图尚未配置"), style = MaterialTheme.typography.titleMedium); Text(tr("Add a Maps SDK key at build time, then rebuild the app.","请在编译阶段加入 Maps SDK 密钥后重新构建应用。")) } } } }

@Composable
private fun WatchPanel(state: MainUiState, arm: () -> Unit, adjust:()->Unit,pause:()->Unit,resume:()->Unit,lift:()->Unit,openAnchorMap:()->Unit) {
    val fix = state.fix; val active = state.active; val now=android.os.SystemClock.elapsedRealtime()
    val freshFix = fix?.valid == true && when(state.settings.gpsDataSource){GpsDataSource.NMEA->state.connection == NmeaConnectionState.CONNECTED && state.diagnostics.lastFixElapsed?.let { now-it < state.settings.gpsLossSeconds * 1000L } == true;GpsDataSource.SYSTEM->now-fix.receivedElapsedRealtime < state.settings.gpsLossSeconds * 1000L;GpsDataSource.DEMO->state.demoGps.signalAvailable&&now-fix.receivedElapsedRealtime < state.settings.gpsLossSeconds * 1000L}
    val centerReady=active?.centerStatus==AnchorCenterStatus.RESOLVED.name
    val learningDistance=if(fix!=null&&active!=null&&!centerReady)AnchorGeometry.distanceMeters(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,fix.latitude,fix.longitude)else null
    val distance = if (fix != null && active != null&&centerReady) AnchorGeometry.distanceMeters(active.anchorLatitude, active.anchorLongitude, fix.latitude, fix.longitude) else null
    Surface(tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) {
            Text(when{active==null->tr("ANCHOR WATCH OFF","锚警已关闭");active.paused->tr("ANCHOR SESSION PAUSED","锚泊监控已暂停");!centerReady->tr("ANCHOR WATCH · LEARNING CENTRE","锚警 · 正在学习中心");else->tr("ANCHOR WATCH ACTIVE","锚警监控中")}, style = MaterialTheme.typography.labelLarge)
            Text(when{
                active==null->if(freshFix)tr("Ready to set anchor${if(state.settings.gpsDataSource==GpsDataSource.DEMO)" · Demo starts from System GPS" else ""}","已可下锚${if(state.settings.gpsDataSource==GpsDataSource.DEMO)" · 演示从系统 GPS 起点开始" else ""}") else tr("Waiting for live ${when(state.settings.gpsDataSource){GpsDataSource.NMEA->"NMEA";GpsDataSource.SYSTEM->"system";GpsDataSource.DEMO->"system origin for Demo"}} GPS","正在等待${when(state.settings.gpsDataSource){GpsDataSource.NMEA->" NMEA";GpsDataSource.SYSTEM->"系统";GpsDataSource.DEMO->"演示起点的系统"}} GPS 实时定位")
                active.paused->tr("Centre, track and ${active.alarmRadiusMeters.toInt()} m range preserved","中心、轨迹和 ${active.alarmRadiusMeters.toInt()} 米范围已保留")
                !centerReady->tr("${learningDistance?.toInt()?:"--"} m / ${active.alarmRadiusMeters.toInt()} m temporary boundary • ${state.points.size} fixes","临时边界 ${learningDistance?.toInt()?:"--"} / ${active.alarmRadiusMeters.toInt()} 米 · ${state.points.size} 个定位点")
                else->tr("${distance?.toInt() ?: "--"} m / ${active.alarmRadiusMeters.toInt()} m","${distance?.toInt() ?: "--"} / ${active.alarmRadiusMeters.toInt()} 米")
            }, style = if(active==null||active.paused||!centerReady)MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        } }
        if(active!=null&&!centerReady)Text(tr("Orange: active temporary alarm boundary  •  Cyan: estimated anchor uncertainty. They merge when confidence is high.","橙色：立即生效的临时报警边界  ·  青色：估算锚点的不确定范围。置信度足够后，两者会合并。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(active==null)Button(arm,Modifier.fillMaxWidth(),enabled=freshFix){Text(tr("Set anchor","设置锚点"))}
        else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(if(active.paused)resume else pause,Modifier.weight(1f)){Icon(if(active.paused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(6.dp));Text(if(active.paused)tr("Resume","继续") else tr("Pause","暂停"))};OutlinedButton(adjust,Modifier.weight(1f)){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(6.dp));Text(tr("Adjust range","调整范围"))}};if(centerReady)OutlinedButton(openAnchorMap,Modifier.fillMaxWidth().testTag("open_anchor_google_maps")){Icon(Icons.Default.Place,null);Spacer(Modifier.width(6.dp));Text(tr("Open anchor in Google Maps","在 Google 地图中打开锚点"))};TextButton(lift,Modifier.align(Alignment.End),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Anchor,null);Spacer(Modifier.width(6.dp));Text(tr("Lift anchor","起锚"))}}
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric(tr("SOG","航速"), fix?.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric(tr("Heading","艏向"), fix?.let{displayHeading(it,active,state.points)}?.let { "${it.toInt()}°" } ?: "—"); Metric("HDOP", fix?.hdop?.let { "%.1f".format(it) } ?: "—"); Metric(tr("Depth","水深"), fix?.depthMeters?.let { "%.1f m".format(it) } ?: "—") }
    } }
}
@Composable private fun Metric(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyLarge) } }

private fun displayHeading(fix:com.yokuli.anchorwatch.domain.model.NavigationFix,session:AnchorSessionEntity?,points:List<com.yokuli.anchorwatch.data.database.TrackPointEntity>):Double?{
    fix.headingTrueDegrees?.let{return it}
    val windHeading=WindAnchorEvidence.summarize(points.takeLast(300).map{point->WindAnchorEvidence.Sample(point.timestamp,point.latitude,point.longitude,point.sog,point.cog,point.heading.takeIf{point.headingMeasured},point.windDirectionTrue,point.trueWindAngle,point.apparentWindAngle,point.trueWindSpeedKnots,point.apparentWindSpeedKnots,point.headingSampleSequence,point.windSampleSequence)}).observations.lastOrNull{it.source!=WindAnchorEvidence.Source.PHYSICAL_HEADING&&it.source!=WindAnchorEvidence.Source.BACKDOWN_COG}?.headingToAnchorDegrees
    if(windHeading!=null)return windHeading
    return if(session?.placementMode==AnchorPlacementMode.BACKDOWN.name&&fix.cogTrueDegrees!=null&&(fix.sogKnots?:0.0)>=.8)(fix.cogTrueDegrees+180.0)%360.0 else fix.cogTrueDegrees?.takeIf{(fix.sogKnots?:0.0)>=.8}
}

private fun fadingTrailChunks(points:List<com.yokuli.anchorwatch.data.database.TrackPointEntity>):List<List<LatLng>>{
    val visible=TrailVisibilityPolicy.visiblePoints(points);if(visible.size<2)return emptyList();val chunks=48;val step=kotlin.math.ceil((visible.size-1)/chunks.toDouble()).toInt().coerceAtLeast(1)
    return (0 until visible.lastIndex step step).map{start->val from=(start-1).coerceAtLeast(0);val end=(start+step+1).coerceAtMost(visible.size);visible.subList(from,end).map{LatLng(it.latitude,it.longitude)}}
}

private fun boatMarkerIcon():BitmapDescriptor{
    val bitmap=Bitmap.createBitmap(72,88,Bitmap.Config.ARGB_8888);val canvas=AndroidCanvas(bitmap);val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(0,188,212);style=Paint.Style.FILL};val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=5f;strokeJoin=Paint.Join.ROUND}
    val path=Path().apply{moveTo(36f,4f);lineTo(62f,68f);lineTo(36f,56f);lineTo(10f,68f);close()};canvas.drawPath(path,fill);canvas.drawPath(path,stroke);canvas.drawCircle(36f,46f,6f,stroke)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun anchorMarkerIcon():BitmapDescriptor{
    val bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888);val canvas=AndroidCanvas(bitmap);val background=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(255,183,77);style=Paint.Style.FILL};val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(25,31,43);style=Paint.Style.STROKE;strokeWidth=6f;strokeCap=Paint.Cap.ROUND};canvas.drawCircle(36f,36f,31f,background);canvas.drawCircle(36f,18f,6f,line);canvas.drawLine(36f,24f,36f,54f,line);canvas.drawLine(20f,35f,52f,35f,line);canvas.drawArc(19f,36f,53f,61f,0f,180f,false,line)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

@Composable
private fun ConnectionPage(state: MainUiState, vm: MainViewModel) {
    var profile by remember(state.settings.profile) { mutableStateOf(state.settings.profile) }
    var showWatchDisconnect by remember { mutableStateOf(false) }
    val context=androidx.compose.ui.platform.LocalContext.current
    var systemPermissionReady by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) }
    val systemPermissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->systemPermissionReady=granted;vm.onPermissionsChanged()}
    val connectionRunning = state.connection != NmeaConnectionState.DISCONNECTED
    val testing=state.connectionAttempt.state==ConnectionAttemptState.TESTING
    val controlsEnabled=!connectionRunning&&!testing
    val activeWatchUsesNmea=state.active?.paused==false&&state.settings.gpsDataSource==GpsDataSource.NMEA
    val validationError=vm.validateProfile(profile)
    fun edit(next:ConnectionProfile){profile=next;vm.clearConnectionAttempt()}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader(tr("NMEA connection","NMEA 连接"), tr("Configure and verify live traffic. A successful connection becomes the selected GPS source.","配置并验证实时数据。连接成功后将自动选为 GPS 数据源。")) }
        if(activeWatchUsesNmea&&state.connection!=NmeaConnectionState.CONNECTED)item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Anchor watch needs NMEA","锚警需要 NMEA 数据"),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer);Text(tr("The watch remains armed, but NMEA is ${state.connection.name.replace('_',' ').lowercase()}. Reconnect it, switch safely to System GPS, or pause this anchor watch.","锚警仍保持布防，但 NMEA 当前为 ${connectionStateLabel(state.connection)}。请重新连接、安全切换到系统 GPS，或暂停本次锚警。"),color=MaterialTheme.colorScheme.onErrorContainer);OutlinedButton({showWatchDisconnect=true}){Text(tr("Resolve active watch","处理当前锚警"))}}} }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(connectionRunning) AssistChip({}, { Text(tr("Configuration locked while connected","连接期间配置已锁定")) }, leadingIcon={Icon(Icons.Default.Lock,null,Modifier.size(18.dp))}, enabled=false)
            OutlinedTextField(profile.name, { edit(profile.copy(name = it)) }, label = { Text(tr("Profile name","配置名称")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled)
            Text(tr("Protocol","协议"), style = MaterialTheme.typography.labelLarge); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(profile.protocol == Protocol.TCP, { edit(profile.copy(protocol = Protocol.TCP)) }, label = { Text(tr("TCP client","TCP 客户端")) },enabled=controlsEnabled); FilterChip(profile.protocol == Protocol.UDP, { edit(profile.copy(protocol = Protocol.UDP)) }, label = { Text(tr("UDP listener","UDP 监听")) },enabled=controlsEnabled) }
            if (profile.protocol == Protocol.TCP) OutlinedTextField(profile.host, { edit(profile.copy(host = it)) }, label = { Text(tr("Host or IP address","主机名或 IP 地址")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled,isError=validationError!=null,supportingText={if(validationError!=null)Text(localizeKnownMessage(validationError))})
            OutlinedTextField(profile.port.toString(), { v -> edit(profile.copy(port=v.filter(Char::isDigit).toIntOrNull()?:0)) }, label = { Text(if (profile.protocol == Protocol.TCP) tr("Server port","服务器端口") else tr("Listen port","监听端口")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),isError=profile.port !in 1..65535)
            SettingSwitch(tr("Require checksum","要求校验和"), tr("Reject sentences without a checksum","拒绝没有校验和的语句"), profile.requireChecksum,enabled=controlsEnabled) { edit(profile.copy(requireChecksum = it)) }; SettingSwitch(tr("Auto reconnect","自动重连"), tr("Reconnect after network loss","网络中断后自动重新连接"), profile.autoReconnect,enabled=controlsEnabled) { edit(profile.copy(autoReconnect = it)) }
            if(connectionRunning) Button({if(activeWatchUsesNmea)showWatchDisconnect=true else vm.disconnect()},Modifier.fillMaxWidth(),enabled=!testing){Icon(Icons.Default.LinkOff,null);Spacer(Modifier.width(6.dp));Text(tr("Disconnect","断开连接"))}
            else Button({vm.saveAndConnect(profile)},Modifier.fillMaxWidth(),enabled=!testing&&validationError==null){if(testing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Default.Link,null);Spacer(Modifier.width(6.dp));Text(if(testing)tr("Testing NMEA…","正在测试 NMEA…") else tr("Test, save & connect","测试、保存并连接"))}
            if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(localizeKnownMessage(state.connectionAttempt.message),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(testing)Text(localizeKnownMessage(state.connectionAttempt.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(testing&&!connectionRunning)Text(tr("The app must receive at least one valid NMEA sentence before it will connect.","应用必须收到至少一条有效 NMEA 语句后才会正式连接。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { ConnectionResultCard(state, vm) }
    }
    if(showWatchDisconnect)ActiveWatchDisconnectDialog(systemPermissionReady,GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state),{systemPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)},{showWatchDisconnect=false;vm.switchActiveWatchToSystemAndDisconnect()},{showWatchDisconnect=false;vm.stopActiveWatchAndDisconnect()}){showWatchDisconnect=false}
}

@Composable private fun ActiveWatchDisconnectDialog(systemPermissionReady:Boolean,proxyActive:Boolean,grantPermission:()->Unit,switchToSystem:()->Unit,pauseWatch:()->Unit,dismiss:()->Unit){
 AlertDialog(onDismissRequest=dismiss,title={Text(tr("Anchor watch is using NMEA","锚警正在使用 NMEA"))},text={Text(tr("Disconnecting its GPS without another live source would leave the anchor alarm blind. Choose how this watch should continue.","在没有其他实时定位源时断开 NMEA，会使锚警失去位置数据。请选择本次锚警如何继续。"))},confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Button(if(systemPermissionReady)switchToSystem else grantPermission,Modifier.fillMaxWidth(),enabled=!proxyActive){Icon(Icons.Default.GpsFixed,null);Spacer(Modifier.width(6.dp));Text(if(proxyActive)tr("Disable GPS proxy first","请先关闭 GPS 代理") else if(systemPermissionReady)tr("Switch to System GPS","切换到系统 GPS") else tr("Grant System GPS permission","授予系统 GPS 权限"))}
  OutlinedButton(pauseWatch,Modifier.fillMaxWidth()){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Pause watch & disconnect","暂停锚警并断开"))}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
 }})
}

@Composable private fun ConnectionResultCard(state: MainUiState, vm: MainViewModel) { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr("Live status","实时状态"), style = MaterialTheme.typography.titleMedium); Text(connectionStateLabel(state.connection), color = if(state.connection==NmeaConnectionState.CONNECTED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
    HorizontalDivider(); Text(tr("${state.diagnostics.validSentences} valid sentences • ${state.diagnostics.invalidSentences} invalid","${state.diagnostics.validSentences} 条有效语句 · ${state.diagnostics.invalidSentences} 条无效语句")); state.nmeaFix?.let { Text(tr("Latest position  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}","最新位置  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}")) } ?: Text(tr("No parsed GPS position yet","暂时没有解析出的 GPS 位置")); TextButton({ vm.page(2) }) { Text(tr("Open live NMEA data →","查看实时 NMEA 数据 →")) }
} } }

@Composable
private fun NmeaDataPage(state: MainUiState, vm: MainViewModel) {
    var paused by remember { mutableStateOf(false) }; var displayed by remember { mutableStateOf(state.diagnostics.raw) }; val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.diagnostics.raw, paused) { if (!paused) displayed = state.diagnostics.raw }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader(tr("Live NMEA data","实时 NMEA 数据"), tr("Parsed values and the latest 200 raw sentences.","查看解析值和最近 200 条原始语句。"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompactStat(tr("VALID","有效"), state.diagnostics.validSentences.toString(), Modifier.weight(1f)); CompactStat(tr("INVALID","无效"), state.diagnostics.invalidSentences.toString(), Modifier.weight(1f)); CompactStat(tr("CHECKSUM","校验错误"), state.diagnostics.checksumErrors.toString(), Modifier.weight(1f)) }
            state.nmeaFix?.let { fix -> Card { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Metric("LAT", "%.6f".format(fix.latitude)); Metric("LON", "%.6f".format(fix.longitude)); Metric("SOG", fix.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric("HDG", fix.headingTrueDegrees?.let { "${it.toInt()}°" } ?: "—") } } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(tr("Raw sentences","原始语句"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton({ paused = !paused }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Text(if (paused) tr("Resume","继续") else tr("Pause","暂停")) }; TextButton({ vm.clearDiagnostics(); displayed = emptyList() }) { Icon(Icons.Default.DeleteSweep, null); Text(tr("Clear","清空")) }; TextButton({ clipboard.setText(AnnotatedString(displayed.joinToString("\n"))) }, enabled = displayed.isNotEmpty()) { Icon(Icons.Default.ContentCopy, null); Text(tr("Copy","复制")) } }
        }
        SelectionContainer { LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 8.dp)) { if (displayed.isEmpty()) item { Text(if (state.connection == NmeaConnectionState.CONNECTED) tr("Connected. Waiting for NMEA sentences…","已连接，正在等待 NMEA 语句…") else tr("Connect to a data source to view raw NMEA.","连接数据源后即可查看原始 NMEA 数据。"), color = Color.Gray, fontFamily = FontFamily.Monospace) }; items(displayed.asReversed()) { Text(it, color = Color(0xFFB9F6CA), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } } }
    }
}
@Composable private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } } }

@Composable private fun HistoryPage(state: MainUiState, vm: MainViewModel) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { PageHeader(tr("Anchor history","锚泊历史"), tr("Locally stored monitoring sessions.","保存在本机的锚警监控记录。")) }; if (state.sessions.isEmpty()) item { Text(tr("No anchor sessions recorded.","还没有锚泊记录。"), color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(state.sessions) { s -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(DateFormat.getDateTimeInstance().format(s.startedAt), fontWeight = FontWeight.Medium); Text("${if(s.centerStatus==AnchorCenterStatus.LEARNING.name)tr("Centre learning","中心学习中") else "${"%.5f".format(s.anchorLatitude)}, ${"%.5f".format(s.anchorLongitude)}"} • ${when{!s.active->tr("LIFTED","已起锚");s.paused->tr("PAUSED","已暂停");else->tr("ACTIVE","监控中")}}", style = MaterialTheme.typography.bodySmall) }; TextButton({ vm.exportCsv(s) }) { Text("CSV") } } } } } }

@Composable
private fun SettingsPage(state: MainUiState, vm: MainViewModel) { LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("settings_list"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { PageHeader(tr("Settings","设置"), tr("GPS source and background behaviour. Map layers are changed directly on the map.","管理 GPS 数据源与后台监控；地图图层直接在地图页切换。")) }
    item { LanguageCard(state,vm) }
    item { DeveloperSettingsCard(state,vm) }
    item { GpsDataSourceCard(state,vm) }
    if(state.settings.gpsDataSource==GpsDataSource.NMEA)item { GpsProxyCard(state,vm) }
    item { AlarmBehaviourCard(state,vm) }
    item { BackgroundReliabilityCard(state,vm) }
} }

@Composable private fun LanguageCard(state:MainUiState,vm:MainViewModel){
 val chinese=state.settings.appLanguage==AppLanguage.SIMPLIFIED_CHINESE||(state.settings.appLanguage==AppLanguage.SYSTEM&&state.settings.appLanguage.usesChinese())
 Card{Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  Text(tr("Language","语言"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
  FilterChip(chinese,{vm.updateSettings(state.settings.copy(appLanguage=AppLanguage.SIMPLIFIED_CHINESE))},label={Text("🇨🇳")},modifier=Modifier.testTag("language_zh"))
  FilterChip(!chinese,{vm.updateSettings(state.settings.copy(appLanguage=AppLanguage.ENGLISH))},label={Text("🇬🇧")},modifier=Modifier.testTag("language_en"))
 }}
}

@Composable private fun GpsDataSourceCard(state:MainUiState,vm:MainViewModel){
 val switching=state.connectionAttempt.state==ConnectionAttemptState.TESTING
 val proxyActive=GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
 val nmeaAvailability=NmeaSourceSelectionPolicy.availability(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1000L)
 val nmeaReady=nmeaAvailability==NmeaSourceAvailability.AVAILABLE
 val selectedFixReady=state.fix?.valid==true&&(state.settings.gpsDataSource!=GpsDataSource.DEMO||state.demoGps.signalAvailable)
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){vm.onPermissionsChanged()}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 Text(tr("GPS data source","GPS 数据源"),style=MaterialTheme.typography.titleMedium)
  if(state.settings.demoMode){
   Text(tr("Demo mode locks this App to Demo GPS. System and NMEA choices return after Demo mode is disabled.","演示模式会锁定本应用使用演示 GPS；关闭演示模式后才会重新显示系统与 NMEA 选项。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   GpsSourceRow(tr("Demo GPS · locked","演示 GPS · 已锁定"),tr("Every Set anchor first captures a fresh real System GPS coordinate, then the selected trajectory moves outward continuously from it.","每次设置锚点都会先获取新的真实系统 GPS 坐标，再从该点连续向外执行所选轨迹。"),true,false,"gps_source_demo"){}
  }else{
   Text(tr("This source drives the boat marker and anchor alarm. An active watch switches only after the target source supplies a fresh fix.","此数据源用于船位标记与锚警计算。监控进行中时，只有目标数据源提供新鲜定位后才会切换。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   GpsSourceRow(tr("System GPS","系统 GPS"),if(proxyActive)tr("Unavailable while the global NMEA GPS proxy owns Android location.","全局 NMEA GPS 代理接管 Android 定位时不可使用。") else tr("Use the phone or tablet location provider.","使用手机或平板自带的定位服务。"),state.settings.gpsDataSource==GpsDataSource.SYSTEM,!switching&&!proxyActive,"gps_source_system"){vm.switchGpsDataSource(GpsDataSource.SYSTEM)}
   HorizontalDivider()
   GpsSourceRow("NMEA GPS",when(nmeaAvailability){NmeaSourceAvailability.AVAILABLE->tr("Connected with a fresh valid position.","连接正常，且已有新鲜有效的定位。");NmeaSourceAvailability.NOT_CONNECTED->tr("Connect the NMEA server before selecting this source.","请先连接 NMEA 服务器，之后才能选择此数据源。");NmeaSourceAvailability.NO_VALID_FIX->tr("Connected, but waiting for a valid NMEA position.","服务器已连接，但仍在等待有效的 NMEA 定位。");NmeaSourceAvailability.STALE_FIX->tr("The last NMEA position is stale; wait for a fresh fix.","最后一个 NMEA 定位已过期，请等待新定位。")},state.settings.gpsDataSource==GpsDataSource.NMEA,!switching&&nmeaReady,"gps_source_nmea"){vm.switchGpsDataSource(GpsDataSource.NMEA)}
  }
  if(proxyActive)Text(tr("Disable global GPS proxy before selecting System GPS. Mock mode replaces fused location for every app, including this one.","选择系统 GPS 前请先关闭全局 GPS 代理。模拟位置会替换所有应用（包括本应用）的融合定位。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(state.connectionAttempt.state!=ConnectionAttemptState.IDLE)Text(localizeKnownMessage(state.connectionAttempt.message),color=if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
  PreflightRow(tr("Selected source fix","所选数据源定位"),selectedFixReady,if(selectedFixReady)tr("VALID","有效") else if(state.settings.gpsDataSource==GpsDataSource.DEMO&&!state.demoGps.signalAvailable)tr("DEMO DROPOUT","演示信号中断") else tr("NO FIX","无定位"))
  if(!permissionReady)OutlinedButton({permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)}){Text(tr("Grant system GPS permission","授予系统 GPS 权限"))}
 }}
}

@Composable private fun DeveloperSettingsCard(state:MainUiState,vm:MainViewModel){
 val enabled=state.settings.demoMode;val sessionOpen=state.active!=null
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(tr("Developer settings","开发者设置"),style=MaterialTheme.typography.titleMedium)
  SettingSwitch(tr("Demo mode","演示模式"),if(sessionOpen)tr("Lift the current anchor before changing Demo mode.","必须先起锚结束当前会话，才能切换演示模式。") else tr("Locks the App to Demo GPS; Android global location is never changed.","本应用会锁定使用演示 GPS，不会修改 Android 全局位置。"),enabled,!sessionOpen){vm.setDemoMode(it)}
  if(enabled){
   HorizontalDivider();Text(tr("Demo trajectory","演示轨迹"),style=MaterialTheme.typography.labelLarge)
   listOf(DemoScenario.SAFE_SWING to tr("Safe swing","安全摆动"),DemoScenario.ANCHOR_DRAG to tr("Anchor drag","走锚"),DemoScenario.WIND_SHIFT to tr("Wind shift","风向改变"),DemoScenario.GPS_DROPOUT to tr("GPS dropout","GPS 中断")).chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{(scenario,label)->FilterChip(state.settings.demoScenario==scenario,{vm.updateDemoConfiguration(scenario=scenario)},enabled=!sessionOpen,label={Text(label)},modifier=Modifier.weight(1f))}}}
   Text(when(state.settings.demoScenario){DemoScenario.SAFE_SWING->tr("Stays inside a normal swing radius. Back down first records the drop point and a gradual pull-back.","保持在正常摆动范围内；倒车下锚会先记录落锚点，再逐渐后退。");DemoScenario.ANCHOR_DRAG->tr("Settles first, then drifts continuously until it crosses the alarm boundary.","先完成稳定摆动，再持续走锚直至越过报警边界。");DemoScenario.WIND_SHIFT->tr("Turns through a smooth wind shift without jumping position.","平滑模拟风向改变，坐标不会瞬移。");DemoScenario.GPS_DROPOUT->tr("Follows a normal swing, then produces a seeded temporary GPS outage and recovery.","先正常摆动，再按本次会话产生临时 GPS 中断与恢复。")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("Simulation speed","模拟速度"),style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,2,5).forEach{speed->FilterChip(state.settings.demoSpeedMultiplier==speed,{vm.updateDemoConfiguration(speed=speed)},enabled=!sessionOpen,label={Text("${speed}×")})}}
   Text(if(sessionOpen)tr("Demo settings are locked for this session. Lift anchor to change or leave Demo mode.","本次会话已锁定演示设置；起锚后才能修改或退出演示模式。") else tr("At every Set anchor, the App captures a fresh System GPS origin. The randomized trajectory then moves continuously outward from that exact point.","每次设置锚点时，应用都会重新获取系统 GPS 起点，再从该点连续、随机地向外执行轨迹。"),style=MaterialTheme.typography.bodySmall,color=if(sessionOpen)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }}
}

@Composable private fun GpsSourceRow(title:String,subtitle:String,selected:Boolean,enabled:Boolean,testTag:String,click:()->Unit){Row(Modifier.fillMaxWidth().testTag(testTag).clickable(enabled=enabled,onClick=click),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,null,enabled=enabled);Column(Modifier.weight(1f)){Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable private fun GpsProxyCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val active=state.mockGps.state==MockGpsState.ACTIVE;val fixReady=state.connection==NmeaConnectionState.CONNECTED&&state.nmeaFix?.valid==true&&permissionReady
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("NMEA → Android GPS",style=MaterialTheme.typography.titleMedium)
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(if(active)Icons.Default.GpsFixed else Icons.Default.GpsOff,null,tint=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Column{Text(mockGpsStateLabel(state.mockGps.state),fontWeight=FontWeight.Medium);Text(localizeKnownMessage(state.proxyFeedback?:state.mockGps.message),style=MaterialTheme.typography.bodySmall,color=if(state.mockGps.state==MockGpsState.NOT_CONFIGURED||state.mockGps.state==MockGpsState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}}
  HorizontalDivider();PreflightRow(tr("NMEA connection","NMEA 连接"),state.connection==NmeaConnectionState.CONNECTED,connectionStateLabel(state.connection));PreflightRow(tr("NMEA position","NMEA 位置"),state.nmeaFix?.valid==true,if(state.nmeaFix?.valid==true)tr("VALID","有效") else tr("NO FIX","无定位"));PreflightRow(tr("Fine location permission","精确位置权限"),permissionReady,if(permissionReady)tr("OK","正常") else tr("REQUIRED","必需"))
  SettingSwitch(tr("Enhanced compatibility","增强兼容性"),tr("Also publish to LocationManager.GPS_PROVIDER","同时发布到 LocationManager.GPS_PROVIDER"),state.settings.enhancedMock){vm.updateSettings(state.settings.copy(enhancedMock=it))}
  Text(tr("Update rate","更新频率"),style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,2,5).forEach{hz->FilterChip(state.settings.mockHz==hz,{vm.updateSettings(state.settings.copy(mockHz=hz))},label={Text("$hz Hz")})}}
  if(!fixReady&&!active)Text(tr("Connect to a live NMEA source with a valid position before enabling the global proxy.","开启全局代理前，请先连接能够提供有效位置的实时 NMEA 数据源。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({if(active)vm.stopGpsProxy() else vm.startGpsProxy()}){Text(if(active)tr("Disable global GPS proxy","关闭全局 GPS 代理") else tr("Enable global GPS proxy","开启全局 GPS 代理"))};OutlinedButton(vm::openDeveloperOptions){Text(tr("Open Developer options","打开开发者选项"))}}
  Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(tr("One-time Android setup","Android 一次性设置"),fontWeight=FontWeight.SemiBold);Text(tr("1. Settings → About phone → tap Build number 7 times.\n2. Settings → System (or Additional settings) → Developer options.\n3. Select mock location app → Anchor by Yokuli.\n4. Return here and tap Enable global GPS proxy.","1. 设置 → 关于手机 → 连续点击版本号 7 次。\n2. 设置 → 系统（或更多设置）→ 开发者选项。\n3. 选择模拟位置信息应用 → Anchor by Yokuli。\n4. 返回这里，点击开启全局 GPS 代理。"),style=MaterialTheme.typography.bodySmall)}}
  Text(tr("The proxy is optional while NMEA GPS is selected. Disable it before selecting System GPS. If NMEA is stale for ${state.settings.gpsLossSeconds}s, Android location is restored.","选择 NMEA GPS 时，全局代理是可选功能。切换到系统 GPS 前请先关闭代理；如果 NMEA 超过 ${state.settings.gpsLossSeconds} 秒没有更新，Android 定位会恢复为正常来源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }}
}

@Composable private fun AlarmBehaviourCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current
 val customName=remember(state.settings.customAlarmSoundUri){alarmSoundDisplayName(context,state.settings.customAlarmSoundUri)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri!=null){runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};vm.updateSettings(state.settings.copy(alarmSound=AlarmSound.CUSTOM,customAlarmSoundUri=uri.toString()))}
 }
 val labels=mapOf(
  AlarmSound.SYSTEM_ALARM to tr("System alarm","系统警报音"),
  AlarmSound.SYSTEM_RINGTONE to tr("System ringtone","系统铃声"),
  AlarmSound.SYSTEM_NOTIFICATION to tr("System notification","系统通知音"),
 )
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(tr("Anchor alarm","锚警报警"),style=MaterialTheme.typography.titleMedium)
  Text(tr("The App plays the selected sound as a looping alarm. If a custom file becomes unavailable, it safely falls back to the system alarm.","应用会循环播放所选声音作为警报；如果自定义文件失效，会安全回退到系统警报音。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("Alarm sound","警报声音"),style=MaterialTheme.typography.labelLarge)
  labels.forEach{(sound,label)->Row(Modifier.fillMaxWidth().testTag("alarm_sound_${sound.name}").clickable{vm.updateSettings(state.settings.copy(alarmSound=sound))},verticalAlignment=Alignment.CenterVertically){RadioButton(state.settings.alarmSound==sound,null);Text(label)}}
  Row(Modifier.fillMaxWidth().testTag("alarm_sound_CUSTOM").clickable{if(state.settings.customAlarmSoundUri==null)picker.launch(arrayOf("audio/*"))else vm.updateSettings(state.settings.copy(alarmSound=AlarmSound.CUSTOM))},verticalAlignment=Alignment.CenterVertically){RadioButton(state.settings.alarmSound==AlarmSound.CUSTOM,null);Column(Modifier.weight(1f)){Text(tr("Custom audio file","自定义音频文件"));if(customName!=null)Text(customName,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton({picker.launch(arrayOf("audio/*"))}){Text(if(customName==null)tr("Choose","选择") else tr("Change","更换"))}}
  HorizontalDivider()
  Text(tr("Snooze stops sound and vibration now, while monitoring continues. If the danger remains, the alarm sounds again after this interval.","稍后提醒会立即停止声音和振动，但监控继续；如果危险仍然存在，超过所选时间后会再次响铃。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("Remind again after","再次提醒间隔"),style=MaterialTheme.typography.labelLarge)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(5,10,15).forEach{minutes->FilterChip(state.settings.alarmSnoozeMinutes==minutes,{vm.updateSettings(state.settings.copy(alarmSnoozeMinutes=minutes))},label={Text(tr("$minutes min","$minutes 分钟"))})}}
  OutlinedButton(vm::openAlarmNotificationSettings){Icon(Icons.Default.NotificationsActive,null);Spacer(Modifier.width(6.dp));Text(tr("Notification settings","通知设置"))}
 }}
}

private fun alarmSoundDisplayName(context:Context,uriText:String?):String?{
 if(uriText==null)return null
 return runCatching{context.contentResolver.query(Uri.parse(uriText),arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0)else null}}.getOrNull()?:Uri.parse(uriText).lastPathSegment
}

@Composable private fun PreflightRow(label:String,ok:Boolean,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Cancel,null,Modifier.size(18.dp),tint=if(ok)Color(0xFF4CAF50) else MaterialTheme.colorScheme.error);Spacer(Modifier.width(8.dp));Text(label,Modifier.weight(1f));Text(value,style=MaterialTheme.typography.labelMedium)}}

@Composable private fun BackgroundReliabilityCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val locationGranted=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val notificationsGranted=android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;val unrestricted=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("Background reliability","后台可靠性"),style=MaterialTheme.typography.titleMedium);PreflightRow(tr("System GPS / proxy permission","系统 GPS / 代理权限"),locationGranted,if(locationGranted)tr("OK","正常") else tr("REQUIRED WHEN USED","使用时必需"));PreflightRow(tr("Alarm notifications","锚警通知"),notificationsGranted,if(notificationsGranted)tr("OK","正常") else tr("REQUIRED","必需"));PreflightRow(tr("Battery optimization","电池优化"),unrestricted,if(unrestricted)tr("UNRESTRICTED","不受限制") else tr("SYSTEM MAY RESTRICT","系统可能限制"));SettingSwitch(tr("Keep Wi-Fi awake","保持 Wi-Fi 唤醒"),tr("Hold CPU and Wi-Fi awake while monitoring or proxying","监控或代理期间保持 CPU 与 Wi-Fi 唤醒"),state.settings.keepWifiAwake){vm.updateSettings(state.settings.copy(keepWifiAwake=it))};OutlinedButton(vm::openBatteryOptimization){Text(tr("Open battery optimization settings","打开电池优化设置"))};Text(tr("For overnight monitoring, keep the phone on reliable power and verify alarm volume before sleeping.","夜间监控时，请保持设备可靠供电，并在休息前确认报警音量。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}

@Composable private fun connectionStateLabel(state:NmeaConnectionState):String=when(state){
 NmeaConnectionState.DISCONNECTED->tr("DISCONNECTED","未连接")
 NmeaConnectionState.CONNECTING->tr("CONNECTING","正在连接")
 NmeaConnectionState.CONNECTED->tr("CONNECTED","已连接")
 NmeaConnectionState.CONNECTED_NO_DATA->tr("CONNECTED · NO DATA","已连接 · 无数据")
 NmeaConnectionState.CONNECTED_NO_FIX->tr("CONNECTED · NO FIX","已连接 · 无定位")
 NmeaConnectionState.STALE->tr("STALE","数据过期")
 NmeaConnectionState.RECONNECTING->tr("RECONNECTING","正在重连")
 NmeaConnectionState.ERROR->tr("ERROR","错误")
}

@Composable private fun mockGpsStateLabel(state:MockGpsState):String=when(state){
 MockGpsState.INACTIVE->tr("INACTIVE","未开启")
 MockGpsState.STARTING->tr("STARTING","正在检查")
 MockGpsState.ACTIVE->tr("ACTIVE","已开启")
 MockGpsState.NOT_CONFIGURED->tr("NOT CONFIGURED","未配置")
 MockGpsState.STALE->tr("STALE","数据过期")
 MockGpsState.FAILED->tr("FAILED","失败")
}

@Composable private fun localizeKnownMessage(message:String):String{
 if(!LocalAppLanguage.current.usesChinese())return message
 return when(message){
  "Port must be between 1 and 65535."->"端口必须在 1 到 65535 之间。"
  "Host or IP address is required."->"必须填写主机名或 IP 地址。"
  "Enter a host name or IP address, not a URL."->"请输入主机名或 IP 地址，不要填写网址。"
  "Host name is too long."->"主机名过长。"
  "Testing the endpoint and waiting for valid NMEA data…"->"正在测试端点并等待有效 NMEA 数据…"
  "The NMEA endpoint test failed."->"NMEA 端点测试失败。"
  "The endpoint responded, but no valid NMEA sentence arrived within 4 seconds."->"端点已有响应，但 4 秒内没有收到有效 NMEA 语句。"
  "The endpoint test passed, but the live NMEA connection did not deliver a fresh position."->"端点测试通过，但实时 NMEA 连接没有提供新的位置。"
  "NMEA connected, but the active anchor watch could not complete a safe GPS handover."->"NMEA 已连接，但当前锚警无法完成安全的 GPS 切换。"
  "Anchor watch is using NMEA. Choose System GPS or pause the watch before disconnecting."->"锚警正在使用 NMEA。断开前请切换到系统 GPS 或暂停锚警。"
  "Precise location permission is required before an active watch can switch to System GPS."->"当前锚警切换到系统 GPS 前需要精确位置权限。"
  "Disable the global NMEA GPS proxy before switching an anchor watch to System GPS."->"锚警切换到系统 GPS 前请先关闭全局 NMEA GPS 代理。"
  "Acquiring a fresh System GPS fix before disconnecting NMEA…"->"正在获取新的系统 GPS 定位，确认后再断开 NMEA…"
  "NMEA stayed connected because a fresh System GPS fix was not available."->"由于没有新的系统 GPS 定位，NMEA 仍保持连接。"
  "Enable Developer demo mode before selecting Demo GPS."->"选择演示 GPS 前请先开启开发者演示模式。"
  "Connect the NMEA source and wait for a fresh valid position before selecting NMEA GPS."->"请先连接 NMEA 数据源并等待新鲜有效的定位，然后才能选择 NMEA GPS。"
  "Disable the global NMEA GPS proxy first. While Android mock mode is active, System GPS is not an independent source."->"请先关闭全局 NMEA GPS 代理。Android 模拟位置开启时，系统 GPS 不是独立数据源。"
  "Disable the global NMEA GPS proxy before selecting Demo. Demo uses the real System GPS as its starting point."->"选择演示 GPS 前请关闭全局 NMEA GPS 代理，因为演示模式需要真实系统 GPS 作为起点。"
  "Pause or lift the active anchor session before switching a live watch into Demo GPS."->"实时锚警切换到演示 GPS 前，请暂停监控或起锚。"
  "Precise location permission is required before switching to System GPS."->"切换到系统 GPS 前需要精确位置权限。"
  "Precise location permission is required to leave Demo and return this active watch to System GPS."->"离开演示模式并让当前锚警返回系统 GPS 前，需要精确位置权限。"
  "Returning the active anchor watch to a fresh System GPS position…"->"正在将当前锚警切换到新的系统 GPS 位置…"
  "Demo stayed enabled because a fresh System GPS position was not available."->"由于没有新的系统 GPS 位置，演示模式仍保持开启。"
  "Demo mode owns the App GPS source. Lift the current anchor and disable Demo mode before choosing System or NMEA."->"演示模式已接管本应用 GPS。请先起锚并关闭演示模式，再选择系统或 NMEA。"
  "Demo GPS is selected only by enabling Demo mode."->"演示 GPS 只能通过开启演示模式来选择。"
  "Lift the current anchor session before changing Demo mode."->"切换演示模式前必须先起锚结束当前会话。"
  "Lift the current anchor session before changing the Demo trajectory."->"修改演示轨迹前必须先起锚结束当前会话。"
  "Disable the global NMEA GPS proxy before enabling Demo mode. Demo needs an independent System GPS origin."->"开启演示模式前请关闭全局 NMEA GPS 代理；演示需要独立的系统 GPS 起点。"
  "Select NMEA GPS before enabling the global proxy."->"开启全局代理前请先选择 NMEA GPS。"
  "Connect to the NMEA source first."->"请先连接 NMEA 数据源。"
  "The NMEA connection has not supplied a valid position yet."->"NMEA 连接尚未提供有效位置。"
  "Checking Android mock-location access…"->"正在检查 Android 模拟位置权限…"
  "Android GPS is using the normal system source."->"Android GPS 正在使用正常的系统数据源。"
  "NMEA is feeding Fused Location and GPS_PROVIDER."->"NMEA 正在向融合定位和 GPS_PROVIDER 提供位置。"
  "NMEA is feeding Fused Location. Direct GPS compatibility is unavailable."->"NMEA 正在向融合定位提供位置；直接 GPS 兼容模式不可用。"
  "GPS proxy was not enabled. Turn on Developer Options and select Anchor by Yokuli as the location override app."->"GPS 代理未开启。请启用开发者选项，并将 Anchor by Yokuli 设为模拟位置应用。"
  "Android GPS restored to the normal system source."->"Android GPS 已恢复到正常系统数据源。"
  else->message
 }
}

@Composable private fun PageHeader(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled:Boolean=true, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change,enabled=enabled) } }

@Composable private fun AnchorSettingsDialog(fix:com.yokuli.anchorwatch.domain.model.NavigationFix?,session:AnchorSessionEntity?,dismiss:()->Unit,save:(AnchorWatchInput)->Unit){
    if(session!=null){AdjustAnchorRangeDialog(session,dismiss,save);return}
    val editing=false
    var placement by remember(session?.id){mutableStateOf(session?.placementMode?.let{runCatching{AnchorPlacementMode.valueOf(it)}.getOrNull()}?:AnchorPlacementMode.CENTER_DROP)}
    var rangeMode by remember(session?.id){mutableStateOf(session?.rangeMode?.let{runCatching{AnchorRangeMode.valueOf(it)}.getOrNull()}?:AnchorRangeMode.BASIC)}
    var preset by remember(session?.id){mutableStateOf(session?.safetyPreset?.let{runCatching{AnchorSafetyPreset.valueOf(it)}.getOrNull()}?:AnchorSafetyPreset.BALANCED)}
    var depth by remember(session?.id){mutableStateOf((session?.waterDepthMeters?:fix?.depthMeters)?.let{"%.1f".format(it)}?:"")}
    var rode by remember(session?.id){mutableStateOf(session?.rodeLengthMeters?.takeIf{it>0}?.let{"%.1f".format(it)}?:"40")}
    var bowHeight by remember(session?.id){mutableStateOf(session?.bowRollerHeightMeters?.takeIf{it>0}?.let{"%.1f".format(it)}?:"1.5")}
    var boat by remember(session?.id){mutableStateOf(session?.boatLengthMeters?.let{"%.1f".format(it)}?:"10")}
    var alarm by remember(session?.id){mutableStateOf(session?.alarmRadiusMeters?.let{"%.1f".format(it)}?:"50")}
    var radiusEdited by remember{mutableStateOf(editing)}
    LaunchedEffect(placement){if(!radiusEdited)alarm=if(placement==AnchorPlacementMode.BACKDOWN)"70" else "50"}
    fun decimal(value:String)=value.filter{it.isDigit()||it=='.'}
    val depthValue=depth.toDoubleOrNull();val rodeValue=rode.toDoubleOrNull();val bowHeightValue=bowHeight.toDoubleOrNull();val boatValue=boat.toDoubleOrNull();val directRadius=alarm.toDoubleOrNull()
    val geometryRequired=placement==AnchorPlacementMode.BACKDOWN||rangeMode==AnchorRangeMode.ADVANCED
    val geometryValid=!geometryRequired||(depthValue!=null&&depthValue>=0&&rodeValue!=null&&rodeValue>0&&bowHeightValue!=null&&bowHeightValue>0&&rodeValue>depthValue+bowHeightValue)
    val suggestion=if(geometryValid&&depthValue!=null&&rodeValue!=null&&bowHeightValue!=null&&boatValue!=null)AnchorRangeCalculator.advanced(depthValue,rodeValue,boatValue,placement,preset,bowHeightValue)else null
    val radius=if(rangeMode==AnchorRangeMode.BASIC)directRadius else suggestion?.radiusMeters
    val fixReady=editing||fix?.valid==true
    val valid=fixReady&&radius!=null&&radius>0&&geometryValid&&(rangeMode!=AnchorRangeMode.ADVANCED||suggestion!=null)
    AlertDialog(dismiss,confirmButton={Button({val useGeometry=placement==AnchorPlacementMode.BACKDOWN||rangeMode==AnchorRangeMode.ADVANCED;save(AnchorWatchInput(placement,rangeMode,preset,depthValue,if(useGeometry)rodeValue?:0.0 else 0.0,if(useGeometry)bowHeightValue?:0.0 else 0.0,if(rangeMode==AnchorRangeMode.ADVANCED)boatValue else null,radius!!))},enabled=valid){Text(if(editing)tr("Update watch","更新锚警") else tr("Start watch","开始锚警"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},title={Text(if(editing)tr("Adjust anchor range","调整锚警范围") else tr("Start anchor session","开始锚泊会话"))},text={Column(Modifier.heightIn(max=600.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(!editing){Text(tr("Anchor placement","下锚方式"),style=MaterialTheme.typography.labelLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(placement==AnchorPlacementMode.CENTER_DROP,{placement=AnchorPlacementMode.CENTER_DROP},label={Text(tr("Centre drop","中心下锚"))},modifier=Modifier.weight(1f))
            FilterChip(placement==AnchorPlacementMode.BACKDOWN,{placement=AnchorPlacementMode.BACKDOWN},label={Text(tr("Back down","倒车下锚"))},modifier=Modifier.weight(1f))
        }}
        Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(if(placement==AnchorPlacementMode.CENTER_DROP)tr("Centre fixed immediately","立即确定中心") else if(session?.centerStatus==AnchorCenterStatus.RESOLVED.name)tr("Back-down centre resolved","倒车中心已确定") else tr("Centre learning starts immediately","立即开始学习中心"),style=MaterialTheme.typography.labelLarge)
            if(placement==AnchorPlacementMode.CENTER_DROP)Text(tr("Press Start while the GPS antenna is over the anchor. Radius monitoring begins immediately.","当 GPS 天线位于锚点上方时点击开始，范围监控会立即生效。"),style=MaterialTheme.typography.bodySmall)
            else Text(if(editing)tr("This session keeps its accumulated centre samples through pause and resume.","暂停和继续不会清除本次会话已积累的中心样本。") else tr("Press Start at the drop point, then back down steadily. An orange temporary alarm boundary works immediately while a cyan anchor estimate tightens. They merge into the final anchor circle at high confidence.","在落锚点点击开始，然后稳定倒车。橙色临时报警边界立即生效，青色估算锚点范围会逐渐收敛；置信度足够后，两者合并为最终锚警圈。"),style=MaterialTheme.typography.bodySmall)
        }}
        Text(tr("Range setup","范围设置"),style=MaterialTheme.typography.labelLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(rangeMode==AnchorRangeMode.BASIC,{rangeMode=AnchorRangeMode.BASIC},label={Text(tr("Basic","基础"))},modifier=Modifier.weight(1f));FilterChip(rangeMode==AnchorRangeMode.ADVANCED,{rangeMode=AnchorRangeMode.ADVANCED},label={Text(tr("Advanced","高级"))},modifier=Modifier.weight(1f))}
        if(rangeMode==AnchorRangeMode.BASIC){
            OutlinedTextField(alarm,{alarm=decimal(it);radiusEdited=true},label={Text(tr("Alarm radius","报警半径"))},suffix={Text(tr("m","米"))},supportingText={Text(if(placement==AnchorPlacementMode.BACKDOWN)tr("A wider initial range is recommended while the centre is being learned.","学习中心期间建议使用更大的初始范围。") else tr("Maximum distance from the anchor centre","距锚点中心的最大距离"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            if(placement==AnchorPlacementMode.CENTER_DROP)OutlinedTextField(depth,{depth=decimal(it)},label={Text(tr("Water depth (optional)","水深（可选）"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            else{
                Text(tr("Centre-learning geometry · required","中心推测参数 · 必填"),style=MaterialTheme.typography.labelLarge)
                OutlinedTextField(depth,{depth=decimal(it)},label={Text(if(fix?.depthMeters!=null)tr("Water depth · NMEA prefilled","水深 · 已用 NMEA 预填") else tr("Water depth","水深"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(rode,{rode=decimal(it)},label={Text(tr("Rode / chain paid out","放出的锚缆 / 锚链"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(bowHeight,{bowHeight=decimal(it)},label={Text(tr("Bow roller height above water","船艏滚轮离水面高度"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                Text(tr("These values define the initial possible-anchor region. The alarm radius above remains your manual Basic setting.","这些参数只定义初始锚位可行域；上方报警半径仍是基础模式下的手动设置。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }else{
            Text(tr("Geometry and calculated alarm range","几何参数与自动报警范围"),style=MaterialTheme.typography.labelLarge)
            OutlinedTextField(depth,{depth=decimal(it)},label={Text(if(fix?.depthMeters!=null)tr("Water depth · NMEA prefilled","水深 · 已用 NMEA 预填") else tr("Water depth","水深"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(rode,{rode=decimal(it)},label={Text(tr("Rode / chain paid out","放出的锚缆 / 锚链"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(bowHeight,{bowHeight=decimal(it)},label={Text(tr("Bow roller height above water","船艏滚轮离水面高度"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(boat,{boat=decimal(it)},label={Text(tr("Boat length","船长"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            Text(tr("Safety profile","安全模式"),style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                AnchorSafetyPreset.entries.forEach{value->
                    val label=when(value){
                        AnchorSafetyPreset.STRICT->tr("Strict","严格")
                        AnchorSafetyPreset.BALANCED->tr("Balanced","均衡")
                        AnchorSafetyPreset.TOLERANT->tr("Tolerant","宽容")
                    }
                    FilterChip(preset==value,{preset=value},label={Text(label)},modifier=Modifier.weight(1f))
                }
            }
            if(suggestion!=null)Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(tr("Suggested radius: ${suggestion.radiusMeters.toInt()} m","建议半径：${suggestion.radiusMeters.toInt()} 米"),fontWeight=FontWeight.SemiBold);Text(tr("${suggestion.horizontalRodeMeters.toInt()} m horizontal rode + ${boatValue?.toInt()} m boat + ${suggestion.gpsMarginMeters.toInt()} m GPS${if(suggestion.learningMarginMeters>0)" + ${suggestion.learningMarginMeters.toInt()} m back-down learning" else ""}","${suggestion.horizontalRodeMeters.toInt()} 米水平锚缆 + ${boatValue?.toInt()} 米船长 + ${suggestion.gpsMarginMeters.toInt()} 米 GPS 余量${if(suggestion.learningMarginMeters>0)" + ${suggestion.learningMarginMeters.toInt()} 米倒车学习余量" else ""}"),style=MaterialTheme.typography.bodySmall)}}
            else Text(tr("Rode must be longer than water depth plus the entered bow-roller height.","锚缆长度必须大于水深加所填船艏高度。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
        if(editing)Text(tr("Changing the radius re-arms the boundary calculation immediately. It does not erase this session, centre or track.","修改半径会立即重新计算报警边界，不会清除本次会话、锚点中心或轨迹。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!valid)Text(tr("Complete the required geometry, keep rode longer than depth plus bow height, and enter a valid range. A live GPS fix is required for a new session.","请完整填写必需几何参数，确保锚链长于水深加艏高，并设置有效范围；新会话还需要实时 GPS。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }})
}

@Composable private fun AdjustAnchorRangeDialog(session:AnchorSessionEntity,dismiss:()->Unit,save:(AnchorWatchInput)->Unit){
    var alarm by remember(session.id){mutableStateOf("%.1f".format(session.alarmRadiusMeters))}
    fun decimal(value:String)=value.filter{it.isDigit()||it=='.'}
    val radius=alarm.toDoubleOrNull()
    val placement=runCatching{AnchorPlacementMode.valueOf(session.placementMode)}.getOrDefault(AnchorPlacementMode.CENTER_DROP)
    val rangeMode=runCatching{AnchorRangeMode.valueOf(session.rangeMode)}.getOrDefault(AnchorRangeMode.BASIC)
    val preset=runCatching{AnchorSafetyPreset.valueOf(session.safetyPreset)}.getOrDefault(AnchorSafetyPreset.BALANCED)
    AlertDialog(
        onDismissRequest=dismiss,
        confirmButton={Button({save(AnchorWatchInput(placement,rangeMode,preset,session.waterDepthMeters,session.rodeLengthMeters,session.bowRollerHeightMeters,session.boatLengthMeters,radius!!))},enabled=radius!=null&&radius>0){Text(tr("Update range","更新范围"))}},
        dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},
        title={Text(tr("Adjust alarm radius","调整报警半径"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(alarm,{alarm=decimal(it)},label={Text(tr("Alarm radius","报警半径"))},suffix={Text(tr("m","米"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("adjust_alarm_radius"))
            Text(tr("Only this session's alarm boundary changes. Water depth, rode, bow height, placement mode and centre-learning evidence remain exactly as they were when the anchor was set.","这里只修改本次会话的报警边界。水深、锚链、船艏高度、下锚方式和中心学习数据全部保持下锚时的原值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
    )
}
