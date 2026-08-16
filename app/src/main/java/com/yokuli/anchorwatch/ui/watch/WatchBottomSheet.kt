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

@Composable
internal fun WatchPanel(state: MainUiState, arm: () -> Unit, adjust:()->Unit,pause:()->Unit,resume:()->Unit,lift:()->Unit,openAnchorMap:()->Unit) {
    val fix = state.fix; val active = state.active; val now=android.os.SystemClock.elapsedRealtime()
    var healthExpanded by remember{mutableStateOf(false)};val context=androidx.compose.ui.platform.LocalContext.current;val battery=context.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);val notificationsReady=android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    val freshFix = fix?.valid == true && when(state.settings.gpsDataSource){GpsDataSource.NMEA->state.connection == NmeaConnectionState.CONNECTED && state.diagnostics.lastFixElapsed?.let { now-it < state.settings.gpsLossSeconds * 1000L } == true;GpsDataSource.SYSTEM->now-fix.receivedElapsedRealtime < state.settings.gpsLossSeconds * 1000L;GpsDataSource.DEMO->state.demoGps.signalAvailable&&now-fix.receivedElapsedRealtime < state.settings.gpsLossSeconds * 1000L}
    val centerReady=active?.centerStatus==AnchorCenterStatus.RESOLVED.name
    val learningDistance=if(fix!=null&&active!=null&&!centerReady)AnchorGeometry.distanceMeters(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,fix.latitude,fix.longitude)else null
    val distance = if (fix != null && active != null&&centerReady) AnchorGeometry.distanceMeters(active.anchorLatitude, active.anchorLongitude, fix.latitude, fix.longitude) else null
    val nmeaReady=NmeaSourceSelectionPolicy.availability(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,now,state.settings.gpsLossSeconds*1000L)==NmeaSourceAvailability.AVAILABLE
    val systemReady=state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&now-it.receivedElapsedRealtime<state.settings.gpsLossSeconds*1000L}==true&&!GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
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
        if(active!=null&&!centerReady)Text(if(active.candidateDecision==CandidateDecision.AVAILABLE.name)tr("A candidate is ready. The orange working boundary will not move until you approve it.","候选锚点已就绪；在你确认前，橙色工作边界不会移动。")else tr("Orange is the active temporary alarm boundary. Blue is the possible anchor region and shrinks only as accepted evidence accumulates.","橙色是当前生效的临时报警边界；蓝色是可能锚位范围，只会随可信证据积累而缩小。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(active==null)Button(arm,Modifier.fillMaxWidth(),enabled=if(state.settings.demoMode)systemReady else systemReady||nmeaReady){Text(tr("Set anchor","设置锚点"))}
        else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(if(active.paused)resume else pause,Modifier.weight(1f)){Icon(if(active.paused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(6.dp));Text(if(active.paused)tr("Resume","继续") else tr("Pause","暂停"))};OutlinedButton(adjust,Modifier.weight(1f)){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(6.dp));Text(tr("Adjust range","调整范围"))}};if(centerReady)OutlinedButton(openAnchorMap,Modifier.fillMaxWidth().testTag("open_anchor_google_maps")){Icon(Icons.Default.Place,null);Spacer(Modifier.width(6.dp));Text(tr("Open anchor in Google Maps","在 Google 地图中打开锚点"))};TextButton(lift,Modifier.align(Alignment.End),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Anchor,null);Spacer(Modifier.width(6.dp));Text(tr("Lift anchor","起锚"))}}
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric(tr("SOG","航速"), fix?.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric(tr("Heading","艏向"), fix?.let{displayHeading(it,active,state.points)}?.let { "${it.toInt()}°" } ?: "—"); Metric("HDOP", fix?.hdop?.let { "%.1f".format(it) } ?: "—"); Metric(tr("Depth","水深"), fix?.depthMeters?.let { "%.1f m".format(it) } ?: "—") }
        TextButton({healthExpanded=!healthExpanded},Modifier.align(Alignment.End)){Icon(if(healthExpanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null);Text(tr("Watch health","监控健康状态"))}
        if(healthExpanded){Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){PreflightRow("GPS",state.positionHealth==com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_OK,state.positionHealth.name);PreflightRow(tr("Last fix","最后定位"),fix!=null,fix?.let{"${((now-it.receivedElapsedRealtime).coerceAtLeast(0)/1000)} s · ${it.positionProvider.name} · ±${it.horizontalAccuracyMeters?.toInt()?:"—"} m"}?:"—");PreflightRow(tr("Battery","电池"),battery>15,"$battery%");PreflightRow(tr("Notifications","通知"),notificationsReady,if(notificationsReady)tr("Ready","就绪")else tr("Permission missing","缺少权限"));PreflightRow(tr("Alarm sound","警报声音"),true,if(state.settings.alarmSound==AlarmSound.CUSTOM)tr("Custom","自定义")else tr("Built-in anchor alarm","内置锚警"));if((active?.positionSource?:state.settings.gpsDataSource.name)==GpsDataSource.NMEA.name)PreflightRow("NMEA",state.connection==NmeaConnectionState.CONNECTED,connectionStateLabel(state.connection))}}}
    } }
}
@Composable internal fun Metric(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyLarge) } }

@Composable internal fun AnchorSettingsDialog(fix:com.yokuli.anchorwatch.domain.model.NavigationFix?,session:AnchorSessionEntity?,dismiss:()->Unit,save:(AnchorWatchInput)->Unit){
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

@Composable internal fun AdjustAnchorRangeDialog(session:AnchorSessionEntity,dismiss:()->Unit,save:(AnchorWatchInput)->Unit){
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
