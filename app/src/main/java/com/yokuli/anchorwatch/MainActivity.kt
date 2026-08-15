package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.AnchorRangeMode
import com.yokuli.anchorwatch.domain.model.AnchorSafetyPreset
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
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

@Composable
fun AnchorApp(vm: MainViewModel) {
    val state by vm.ui.collectAsState()
    val destinations = listOf(
        Destination("Watch", Icons.Default.Map), Destination("Connect", Icons.Default.Link),
        Destination("NMEA", Icons.Default.DataObject), Destination("History", Icons.AutoMirrored.Filled.List),
        Destination("Settings", Icons.Default.Settings),
    )
    Scaffold(bottomBar = { NavigationBar { destinations.forEachIndexed { index, item -> NavigationBarItem(state.page == index, { vm.page(index) }, { Icon(item.icon, item.label) }, label = { Text(item.label) }) } } }) { padding ->
        Box(Modifier.padding(padding)) { when (state.page) { 0 -> WatchPage(state, vm); 1 -> ConnectionPage(state, vm); 2 -> NmeaDataPage(state, vm); 3 -> HistoryPage(state, vm); else -> SettingsPage(state, vm) } }
    }
}

@Composable
private fun WatchPage(state: MainUiState, vm: MainViewModel) {
    var showSetup by remember { mutableStateOf(false) };var showAdjust by remember { mutableStateOf(false) };var confirmLift by remember { mutableStateOf(false) }
    val fix = state.fix; val active = state.active
    val boatIcon = remember { boatMarkerIcon() }; val anchorIcon = remember { anchorMarkerIcon() }
    val trail = remember(state.points) { fadingTrailChunks(state.points) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(fix?.latitude ?: -36.8485, fix?.longitude ?: 174.7633), 16f) }
    LaunchedEffect(fix, state.follow) { if (fix != null && state.follow) camera.animate(CameraUpdateFactory.newLatLng(LatLng(fix.latitude, fix.longitude))) }
    Column(Modifier.fillMaxSize()) {
        LiveStatusStrip(state)
        Box(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (BuildConfig.MAPS_CONFIGURED) {
                    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(mapType = if(state.settings.mapType==2)MapType.SATELLITE else MapType.NORMAL), uiSettings = MapUiSettings(compassEnabled = false, indoorLevelPickerEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false)) {
                        fix?.let { position -> Marker(state=remember(position.latitude, position.longitude){MarkerState(LatLng(position.latitude,position.longitude))},title="Boat",icon=boatIcon,rotation=(position.headingTrueDegrees?:position.cogTrueDegrees?:0.0).toFloat(),flat=true,anchor=Offset(.5f,.5f),zIndex=3f) }
                        active?.let { session ->
                            if(session.centerStatus==AnchorCenterStatus.RESOLVED.name){val anchor=LatLng(session.anchorLatitude,session.anchorLongitude)
                             Marker(state=remember(session.anchorLatitude,session.anchorLongitude){MarkerState(anchor)},title="Anchor",icon=anchorIcon,anchor=Offset(.5f,.5f),zIndex=2f)
                             Circle(center=anchor,radius=session.alarmRadiusMeters,strokeColor=Color(0xFFFF5252),fillColor=Color(0x16FF5252),strokeWidth=3f)}
                            trail.forEachIndexed{index,points->Polyline(points=points,color=Color(0xFFFFD54F).copy(alpha=.10f+.85f*(index+1)/trail.size.coerceAtLeast(1)),width=4f)}
                        }
                    }
            } else MapNotConfigured()
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton({ vm.follow(!state.follow) }) { Icon(Icons.Default.MyLocation, "Follow boat") }
                FilledTonalButton({vm.updateSettings(state.settings.copy(mapType=if(state.settings.mapType==2)1 else 2))}){Icon(Icons.Default.Layers,null);Spacer(Modifier.width(6.dp));Text(if(state.settings.mapType==2)"Default" else "Satellite")}
            }
        }
        WatchPanel(state,{showSetup=true},{showAdjust=true},vm::pauseWatch,vm::resumeWatch,{confirmLift=true})
    }
    if (showSetup) {
        AnchorSettingsDialog(fix,null,{showSetup=false}){input->vm.arm(fix!!.latitude,fix.longitude,input);showSetup=false}
    }
    if(showAdjust&&active!=null)AnchorSettingsDialog(fix,active,{showAdjust=false}){input->vm.updateAnchorSettings(input);showAdjust=false}
    if (confirmLift) AlertDialog({ confirmLift = false }, confirmButton = { Button({ vm.liftAnchor(); confirmLift = false },colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text("Lift anchor") } }, dismissButton = { TextButton({ confirmLift = false }) { Text("Cancel") } }, title = { Text("End this anchoring session?") }, text = { Text("Lift anchor permanently closes this session. Its track remains in History, but it cannot be resumed.") })
}

@Composable private fun LiveStatusStrip(state: MainUiState) { val watch=state.active;Surface(tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusItem("NMEA", state.connection == NmeaConnectionState.CONNECTED, if(state.connection==NmeaConnectionState.CONNECTED)"LIVE" else "OFF"); StatusItem(if(state.settings.gpsDataSource==GpsDataSource.NMEA)"GPS · NMEA" else "GPS · SYS", state.fix?.valid == true, if (state.fix?.valid == true) "VALID" else "NO FIX"); StatusItem("PROXY", state.mockGps.state == MockGpsState.ACTIVE, if(state.mockGps.state==MockGpsState.ACTIVE)"ACTIVE" else "OFF"); StatusItem("WATCH", watch!=null&&!watch.paused, when{watch==null->"OFF";watch.paused->"PAUSED";watch.centerStatus==AnchorCenterStatus.LEARNING.name->"LEARNING";else->"ARMED"}) } } }
@Composable private fun RowScope.StatusItem(label: String, ok: Boolean, value: String) { Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Box(Modifier.size(9.dp).background(if (ok) Color(0xFF4CAF50) else Color(0xFFEF5350), MaterialTheme.shapes.small)); Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1) } } }

@Composable private fun MapNotConfigured() { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Card { Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.Map, null, Modifier.size(40.dp)); Text("Google Maps is not configured", style = MaterialTheme.typography.titleMedium); Text("Add a Maps SDK key at build time, then rebuild the app.") } } } }

@Composable
private fun WatchPanel(state: MainUiState, arm: () -> Unit, adjust:()->Unit,pause:()->Unit,resume:()->Unit,lift:()->Unit) {
    val fix = state.fix; val active = state.active; val now=android.os.SystemClock.elapsedRealtime()
    val freshFix = fix?.valid == true && if(state.settings.gpsDataSource==GpsDataSource.NMEA){state.connection == NmeaConnectionState.CONNECTED && state.diagnostics.lastFixElapsed?.let { now-it < state.settings.gpsLossSeconds * 1000L } == true}else now-fix.receivedElapsedRealtime < state.settings.gpsLossSeconds * 1000L
    val centerReady=active?.centerStatus==AnchorCenterStatus.RESOLVED.name
    val distance = if (fix != null && active != null&&centerReady) AnchorGeometry.distanceMeters(active.anchorLatitude, active.anchorLongitude, fix.latitude, fix.longitude) else null
    Surface(tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(when{active==null->"ANCHOR WATCH OFF";active.paused->"ANCHOR SESSION PAUSED";!centerReady->"ANCHOR WATCH · LEARNING CENTRE";else->"ANCHOR WATCH ACTIVE"}, style = MaterialTheme.typography.labelLarge); Text(when{active==null->if(freshFix)"Ready to set anchor" else "Waiting for live ${if(state.settings.gpsDataSource==GpsDataSource.NMEA)"NMEA" else "system"} GPS";active.paused->"Centre, track and ${active.alarmRadiusMeters.toInt()} m range preserved";!centerReady->"${state.points.size} fixes • back down steadily";else->"${distance?.toInt() ?: "--"} m / ${active.alarmRadiusMeters.toInt()} m"}, style = if(active==null||active.paused||!centerReady)MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) } }
        if(active==null)Button(arm,Modifier.fillMaxWidth(),enabled=freshFix){Text("Set anchor")}
        else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(if(active.paused)resume else pause,Modifier.weight(1f)){Icon(if(active.paused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(6.dp));Text(if(active.paused)"Resume" else "Pause")};OutlinedButton(adjust,Modifier.weight(1f)){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(6.dp));Text("Adjust range")}};TextButton(lift,Modifier.align(Alignment.End),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Anchor,null);Spacer(Modifier.width(6.dp));Text("Lift anchor")}}
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("SOG", fix?.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric("Heading", fix?.headingTrueDegrees?.let { "${it.toInt()}°" } ?: "—"); Metric("HDOP", fix?.hdop?.let { "%.1f".format(it) } ?: "—"); Metric("Depth", fix?.depthMeters?.let { "%.1f m".format(it) } ?: "—") }
    } }
}
@Composable private fun Metric(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyLarge) } }

private fun fadingTrailChunks(points:List<com.yokuli.anchorwatch.data.database.TrackPointEntity>):List<List<LatLng>>{
    val visible=points.takeLast(300);if(visible.size<2)return emptyList();val chunks=12;val step=kotlin.math.ceil((visible.size-1)/chunks.toDouble()).toInt().coerceAtLeast(1)
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
        item { PageHeader("NMEA connection", "Configure the boat data source and verify live traffic.") }
        if(activeWatchUsesNmea&&state.connection!=NmeaConnectionState.CONNECTED)item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Anchor watch needs NMEA",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer);Text("The watch remains armed, but NMEA is ${state.connection.name.replace('_',' ').lowercase()}. Reconnect it, switch safely to System GPS, or pause this anchor watch.",color=MaterialTheme.colorScheme.onErrorContainer);OutlinedButton({showWatchDisconnect=true}){Text("Resolve active watch")}}} }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(connectionRunning) AssistChip({}, { Text("Configuration locked while connected") }, leadingIcon={Icon(Icons.Default.Lock,null,Modifier.size(18.dp))}, enabled=false)
            OutlinedTextField(profile.name, { edit(profile.copy(name = it)) }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled)
            Text("Protocol", style = MaterialTheme.typography.labelLarge); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(profile.protocol == Protocol.TCP, { edit(profile.copy(protocol = Protocol.TCP)) }, label = { Text("TCP client") },enabled=controlsEnabled); FilterChip(profile.protocol == Protocol.UDP, { edit(profile.copy(protocol = Protocol.UDP)) }, label = { Text("UDP listener") },enabled=controlsEnabled) }
            if (profile.protocol == Protocol.TCP) OutlinedTextField(profile.host, { edit(profile.copy(host = it)) }, label = { Text("Host or IP address") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled,isError=validationError!=null,supportingText={if(validationError!=null)Text(validationError)})
            OutlinedTextField(profile.port.toString(), { v -> edit(profile.copy(port=v.filter(Char::isDigit).toIntOrNull()?:0)) }, label = { Text(if (profile.protocol == Protocol.TCP) "Server port" else "Listen port") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),isError=profile.port !in 1..65535)
            SettingSwitch("Require checksum", "Reject sentences without a checksum", profile.requireChecksum,enabled=controlsEnabled) { edit(profile.copy(requireChecksum = it)) }; SettingSwitch("Auto reconnect", "Reconnect after network loss", profile.autoReconnect,enabled=controlsEnabled) { edit(profile.copy(autoReconnect = it)) }
            if(connectionRunning) Button({if(activeWatchUsesNmea)showWatchDisconnect=true else vm.disconnect()},Modifier.fillMaxWidth(),enabled=!testing){Icon(Icons.Default.LinkOff,null);Spacer(Modifier.width(6.dp));Text("Disconnect")}
            else Button({vm.saveAndConnect(profile)},Modifier.fillMaxWidth(),enabled=!testing&&validationError==null){if(testing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Default.Link,null);Spacer(Modifier.width(6.dp));Text(if(testing)"Testing NMEA…" else "Test, save & connect")}
            if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(state.connectionAttempt.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(testing)Text(state.connectionAttempt.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(testing&&!connectionRunning)Text("The app must receive at least one valid NMEA sentence before it will connect.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { ConnectionResultCard(state, vm) }
    }
    if(showWatchDisconnect)ActiveWatchDisconnectDialog(systemPermissionReady,GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state),{systemPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)},{showWatchDisconnect=false;vm.switchActiveWatchToSystemAndDisconnect()},{showWatchDisconnect=false;vm.stopActiveWatchAndDisconnect()}){showWatchDisconnect=false}
}

@Composable private fun ActiveWatchDisconnectDialog(systemPermissionReady:Boolean,proxyActive:Boolean,grantPermission:()->Unit,switchToSystem:()->Unit,pauseWatch:()->Unit,dismiss:()->Unit){
 AlertDialog(onDismissRequest=dismiss,title={Text("Anchor watch is using NMEA")},text={Text("Disconnecting its GPS without another live source would leave the anchor alarm blind. Choose how this watch should continue.")},confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Button(if(systemPermissionReady)switchToSystem else grantPermission,Modifier.fillMaxWidth(),enabled=!proxyActive){Icon(Icons.Default.GpsFixed,null);Spacer(Modifier.width(6.dp));Text(if(proxyActive)"Disable GPS proxy first" else if(systemPermissionReady)"Switch to System GPS" else "Grant System GPS permission")}
  OutlinedButton(pauseWatch,Modifier.fillMaxWidth()){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(6.dp));Text("Pause watch & disconnect")}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text("Cancel")}
 }})
}

@Composable private fun ConnectionResultCard(state: MainUiState, vm: MainViewModel) { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Live status", style = MaterialTheme.typography.titleMedium); Text(state.connection.name.replace('_', ' '), color = if(state.connection==NmeaConnectionState.CONNECTED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
    HorizontalDivider(); Text("${state.diagnostics.validSentences} valid sentences • ${state.diagnostics.invalidSentences} invalid"); state.nmeaFix?.let { Text("Latest position  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}") } ?: Text("No parsed GPS position yet"); TextButton({ vm.page(2) }) { Text("Open live NMEA data →") }
} } }

@Composable
private fun NmeaDataPage(state: MainUiState, vm: MainViewModel) {
    var paused by remember { mutableStateOf(false) }; var displayed by remember { mutableStateOf(state.diagnostics.raw) }; val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.diagnostics.raw, paused) { if (!paused) displayed = state.diagnostics.raw }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader("Live NMEA data", "Parsed values and the latest 200 raw sentences.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompactStat("VALID", state.diagnostics.validSentences.toString(), Modifier.weight(1f)); CompactStat("INVALID", state.diagnostics.invalidSentences.toString(), Modifier.weight(1f)); CompactStat("CHECKSUM", state.diagnostics.checksumErrors.toString(), Modifier.weight(1f)) }
            state.nmeaFix?.let { fix -> Card { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Metric("LAT", "%.6f".format(fix.latitude)); Metric("LON", "%.6f".format(fix.longitude)); Metric("SOG", fix.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric("HDG", fix.headingTrueDegrees?.let { "${it.toInt()}°" } ?: "—") } } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Raw sentences", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton({ paused = !paused }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Text(if (paused) "Resume" else "Pause") }; TextButton({ vm.clearDiagnostics(); displayed = emptyList() }) { Icon(Icons.Default.DeleteSweep, null); Text("Clear") }; TextButton({ clipboard.setText(AnnotatedString(displayed.joinToString("\n"))) }, enabled = displayed.isNotEmpty()) { Icon(Icons.Default.ContentCopy, null); Text("Copy") } }
        }
        SelectionContainer { LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 8.dp)) { if (displayed.isEmpty()) item { Text(if (state.connection == NmeaConnectionState.CONNECTED) "Connected. Waiting for NMEA sentences…" else "Connect to a data source to view raw NMEA.", color = Color.Gray, fontFamily = FontFamily.Monospace) }; items(displayed.asReversed()) { Text(it, color = Color(0xFFB9F6CA), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } } }
    }
}
@Composable private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } } }

@Composable private fun HistoryPage(state: MainUiState, vm: MainViewModel) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { PageHeader("Anchor history", "Locally stored monitoring sessions.") }; if (state.sessions.isEmpty()) item { Text("No anchor sessions recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(state.sessions) { s -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(DateFormat.getDateTimeInstance().format(s.startedAt), fontWeight = FontWeight.Medium); Text("${if(s.centerStatus==AnchorCenterStatus.LEARNING.name)"Centre learning" else "${"%.5f".format(s.anchorLatitude)}, ${"%.5f".format(s.anchorLongitude)}"} • ${when{!s.active->"LIFTED";s.paused->"PAUSED";else->"ACTIVE"}}", style = MaterialTheme.typography.bodySmall) }; TextButton({ vm.exportCsv(s) }) { Text("CSV") } } } } } }

@Composable
private fun SettingsPage(state: MainUiState, vm: MainViewModel) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { PageHeader("Settings", "GPS source and background behaviour. Map layers are changed directly on the map.") }
    item { GpsDataSourceCard(state,vm) }
    if(state.settings.gpsDataSource==GpsDataSource.NMEA)item { GpsProxyCard(state,vm) }
    item { BackgroundReliabilityCard(state,vm) }
} }

@Composable private fun GpsDataSourceCard(state:MainUiState,vm:MainViewModel){
 val switching=state.connectionAttempt.state==ConnectionAttemptState.TESTING
 val proxyActive=GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){vm.onPermissionsChanged()}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("GPS data source",style=MaterialTheme.typography.titleMedium)
  Text("This source drives the boat marker and anchor alarm. An active watch switches only after the target source supplies a fresh fix.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  GpsSourceRow("System GPS",if(proxyActive)"Unavailable while the global NMEA GPS proxy owns Android location." else "Use the phone or tablet location provider.",state.settings.gpsDataSource==GpsDataSource.SYSTEM,!switching&&!proxyActive){vm.switchGpsDataSource(GpsDataSource.SYSTEM)}
  HorizontalDivider()
  GpsSourceRow("NMEA GPS","Connect and verify the saved boat source before the handover.",state.settings.gpsDataSource==GpsDataSource.NMEA,!switching){vm.switchGpsDataSource(GpsDataSource.NMEA)}
  if(proxyActive)Text("Disable global GPS proxy before selecting System GPS. Mock mode replaces fused location for every app, including this one.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(state.connectionAttempt.state!=ConnectionAttemptState.IDLE)Text(state.connectionAttempt.message,color=if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
  PreflightRow("Selected source fix",state.fix?.valid==true,if(state.fix?.valid==true)"VALID" else "NO FIX")
  if(!permissionReady)OutlinedButton({permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)}){Text("Grant system GPS permission")}
 }}
}

@Composable private fun GpsSourceRow(title:String,subtitle:String,selected:Boolean,enabled:Boolean,click:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,click,enabled=enabled);Column(Modifier.weight(1f)){Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable private fun GpsProxyCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val active=state.mockGps.state==MockGpsState.ACTIVE;val fixReady=state.connection==NmeaConnectionState.CONNECTED&&state.nmeaFix?.valid==true&&permissionReady
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("NMEA → Android GPS",style=MaterialTheme.typography.titleMedium)
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(if(active)Icons.Default.GpsFixed else Icons.Default.GpsOff,null,tint=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Column{Text(state.mockGps.state.name.replace('_',' '),fontWeight=FontWeight.Medium);Text(state.mockGps.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  HorizontalDivider();PreflightRow("NMEA connection",state.connection==NmeaConnectionState.CONNECTED,state.connection.name);PreflightRow("NMEA position",state.nmeaFix?.valid==true,if(state.nmeaFix?.valid==true)"VALID" else "NO FIX");PreflightRow("Fine location permission",permissionReady,if(permissionReady)"OK" else "REQUIRED")
  SettingSwitch("Enhanced compatibility","Also publish to LocationManager.GPS_PROVIDER",state.settings.enhancedMock){vm.updateSettings(state.settings.copy(enhancedMock=it))}
  Text("Update rate",style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,2,5).forEach{hz->FilterChip(state.settings.mockHz==hz,{vm.updateSettings(state.settings.copy(mockHz=hz))},label={Text("$hz Hz")})}}
  if(!fixReady&&!active)Text("Connect to a live NMEA source with a valid position before enabling the global proxy.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({if(active)vm.stopGpsProxy() else vm.startGpsProxy()},enabled=active||fixReady){Text(if(active)"Disable global GPS proxy" else "Enable global GPS proxy")};OutlinedButton(vm::openDeveloperOptions){Text("Developer options")}}
  Text("The proxy is optional while NMEA GPS is selected. Disable it before selecting System GPS. If NMEA is stale for ${state.settings.gpsLossSeconds}s, Android location is restored.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }}
}

@Composable private fun PreflightRow(label:String,ok:Boolean,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Cancel,null,Modifier.size(18.dp),tint=if(ok)Color(0xFF4CAF50) else MaterialTheme.colorScheme.error);Spacer(Modifier.width(8.dp));Text(label,Modifier.weight(1f));Text(value,style=MaterialTheme.typography.labelMedium)}}

@Composable private fun BackgroundReliabilityCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val locationGranted=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val notificationsGranted=android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;val unrestricted=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Background reliability",style=MaterialTheme.typography.titleMedium);PreflightRow("System GPS / proxy permission",locationGranted,if(locationGranted)"OK" else "REQUIRED WHEN USED");PreflightRow("Alarm notifications",notificationsGranted,if(notificationsGranted)"OK" else "REQUIRED");PreflightRow("Battery optimization",unrestricted,if(unrestricted)"UNRESTRICTED" else "SYSTEM MAY RESTRICT");SettingSwitch("Keep Wi-Fi awake","Hold CPU and Wi-Fi awake while monitoring or proxying",state.settings.keepWifiAwake){vm.updateSettings(state.settings.copy(keepWifiAwake=it))};OutlinedButton(vm::openBatteryOptimization){Text("Open battery optimization settings")};Text("For overnight monitoring, keep the phone on reliable power and verify alarm volume before sleeping.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}

@Composable private fun PageHeader(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled:Boolean=true, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change,enabled=enabled) } }

@Composable private fun AnchorSettingsDialog(fix:com.yokuli.anchorwatch.domain.model.NavigationFix?,session:AnchorSessionEntity?,dismiss:()->Unit,save:(AnchorWatchInput)->Unit){
    val editing=session!=null
    var placement by remember(session?.id){mutableStateOf(session?.placementMode?.let{runCatching{AnchorPlacementMode.valueOf(it)}.getOrNull()}?:AnchorPlacementMode.CENTER_DROP)}
    var rangeMode by remember(session?.id){mutableStateOf(session?.rangeMode?.let{runCatching{AnchorRangeMode.valueOf(it)}.getOrNull()}?:AnchorRangeMode.BASIC)}
    var preset by remember(session?.id){mutableStateOf(session?.safetyPreset?.let{runCatching{AnchorSafetyPreset.valueOf(it)}.getOrNull()}?:AnchorSafetyPreset.BALANCED)}
    var depth by remember(session?.id){mutableStateOf((session?.waterDepthMeters?:fix?.depthMeters)?.let{"%.1f".format(it)}?:"")}
    var rode by remember(session?.id){mutableStateOf(session?.rodeLengthMeters?.takeIf{it>0}?.let{"%.1f".format(it)}?:"40")}
    var boat by remember(session?.id){mutableStateOf(session?.boatLengthMeters?.let{"%.1f".format(it)}?:"10")}
    var alarm by remember(session?.id){mutableStateOf(session?.alarmRadiusMeters?.let{"%.1f".format(it)}?:"50")}
    var radiusEdited by remember{mutableStateOf(editing)}
    LaunchedEffect(placement){if(!radiusEdited)alarm=if(placement==AnchorPlacementMode.BACKDOWN)"70" else "50"}
    fun decimal(value:String)=value.filter{it.isDigit()||it=='.'}
    val depthValue=depth.toDoubleOrNull();val rodeValue=rode.toDoubleOrNull();val boatValue=boat.toDoubleOrNull();val directRadius=alarm.toDoubleOrNull()
    val suggestion=if(depthValue!=null&&rodeValue!=null&&boatValue!=null)AnchorRangeCalculator.advanced(depthValue,rodeValue,boatValue,placement,preset)else null
    val radius=if(rangeMode==AnchorRangeMode.BASIC)directRadius else suggestion?.radiusMeters
    val fixReady=editing||fix?.valid==true
    val valid=fixReady&&radius!=null&&radius>0&&(rangeMode==AnchorRangeMode.BASIC&&(depth.isBlank()||depthValue?.let{it>=0}==true)||rangeMode==AnchorRangeMode.ADVANCED&&suggestion!=null)
    AlertDialog(dismiss,confirmButton={Button({save(AnchorWatchInput(placement,rangeMode,preset,depthValue,if(rangeMode==AnchorRangeMode.ADVANCED)rodeValue?:0.0 else 0.0,if(rangeMode==AnchorRangeMode.ADVANCED)boatValue else null,radius!!))},enabled=valid){Text(if(editing)"Update watch" else "Start watch")}},dismissButton={TextButton(dismiss){Text("Cancel")}},title={Text(if(editing)"Adjust anchor range" else "Start anchor session")},text={Column(Modifier.heightIn(max=600.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(!editing){Text("Anchor placement",style=MaterialTheme.typography.labelLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(placement==AnchorPlacementMode.CENTER_DROP,{placement=AnchorPlacementMode.CENTER_DROP},label={Text("Centre drop")},modifier=Modifier.weight(1f))
            FilterChip(placement==AnchorPlacementMode.BACKDOWN,{placement=AnchorPlacementMode.BACKDOWN},label={Text("Back down")},modifier=Modifier.weight(1f))
        }}
        Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(if(placement==AnchorPlacementMode.CENTER_DROP)"Centre fixed immediately" else if(session?.centerStatus==AnchorCenterStatus.RESOLVED.name)"Back-down centre resolved" else "Centre learning starts immediately",style=MaterialTheme.typography.labelLarge)
            if(placement==AnchorPlacementMode.CENTER_DROP)Text("Press Start while the GPS antenna is over the anchor. Radius monitoring begins immediately.",style=MaterialTheme.typography.bodySmall)
            else Text(if(editing)"This session keeps its accumulated centre samples through pause and resume." else "Press Start at the drop point, then back down steadily. The session, GPS-loss watch and track start now; no centre is drawn until the initial cluster and movement reach high confidence.",style=MaterialTheme.typography.bodySmall)
        }}
        Text("Range setup",style=MaterialTheme.typography.labelLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(rangeMode==AnchorRangeMode.BASIC,{rangeMode=AnchorRangeMode.BASIC},label={Text("Basic")},modifier=Modifier.weight(1f));FilterChip(rangeMode==AnchorRangeMode.ADVANCED,{rangeMode=AnchorRangeMode.ADVANCED},label={Text("Advanced")},modifier=Modifier.weight(1f))}
        if(rangeMode==AnchorRangeMode.BASIC){
            OutlinedTextField(alarm,{alarm=decimal(it);radiusEdited=true},label={Text("Alarm radius")},suffix={Text("m")},supportingText={Text(if(placement==AnchorPlacementMode.BACKDOWN)"A wider initial range is recommended while the centre is being learned." else "Maximum distance from the anchor centre")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(depth,{depth=decimal(it)},label={Text("Water depth (optional)")},suffix={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
        }else{
            OutlinedTextField(depth,{depth=decimal(it)},label={Text("Low-tide water depth")},suffix={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(rode,{rode=decimal(it)},label={Text("Rode / chain paid out")},suffix={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(boat,{boat=decimal(it)},label={Text("Boat length")},suffix={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            Text("Safety profile",style=MaterialTheme.typography.labelLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){AnchorSafetyPreset.entries.forEach{value->FilterChip(preset==value,{preset=value},label={Text(value.name.lowercase().replaceFirstChar{it.uppercase()})},modifier=Modifier.weight(1f))}}
            if(suggestion!=null)Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp)){Text("Suggested radius: ${suggestion.radiusMeters.toInt()} m",fontWeight=FontWeight.SemiBold);Text("${suggestion.horizontalRodeMeters.toInt()} m horizontal rode + ${boatValue?.toInt()} m boat + ${suggestion.gpsMarginMeters.toInt()} m GPS${if(suggestion.learningMarginMeters>0)" + ${suggestion.learningMarginMeters.toInt()} m back-down learning" else ""}",style=MaterialTheme.typography.bodySmall)}}
            else Text("Rode must be at least the water depth plus approximately 1.5 m bow-roller height.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
        if(editing)Text("Changing the radius re-arms the boundary calculation immediately. It does not erase this session, centre or track.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!valid)Text("Enter a valid positive radius, or complete the advanced geometry fields. A live GPS fix is required to start a new session.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }})
}
