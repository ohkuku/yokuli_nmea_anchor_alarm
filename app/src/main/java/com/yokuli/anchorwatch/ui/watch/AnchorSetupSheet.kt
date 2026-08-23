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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchor.AnchorDepthSource
import com.yokuli.anchorwatch.domain.anchor.AnchorSetupDepthPolicy
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
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat

@Composable @OptIn(ExperimentalMaterial3Api::class)
internal fun AnchorSetupSheet(state:MainUiState,dismiss:()->Unit,reference:AnchorageSetupReference?=null,start:(Double,Double,AnchorWatchInput)->Unit){
 val now=android.os.SystemClock.elapsedRealtime();val proxyActive=GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
 val nmeaReady=NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,now,state.settings.gpsLossSeconds*1000L)
 val systemReady=state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&now-it.receivedElapsedRealtime<state.settings.gpsLossSeconds*1000L&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0}==true&&!proxyActive
 var source by remember{mutableStateOf(if(state.settings.demoMode)GpsDataSource.DEMO else state.settings.gpsDataSource)}
 var estimate by remember{mutableStateOf(false)};var knownMethod by remember{mutableStateOf(AnchorCenterSource.CURRENT_POSITION)}
 var coordinates by remember{mutableStateOf("")};var picked by remember{mutableStateOf<LatLng?>(null)};var showMapPicker by remember{mutableStateOf(false)}
 var rangeMode by remember{mutableStateOf(AnchorRangeMode.BASIC)};var preset by remember{mutableStateOf(AnchorSafetyPreset.BALANCED)}
 val nmeaDepthAvailable=AnchorSetupDepthPolicy.nmeaAvailable(state.connection,state.liveDepth.depthMeters,state.liveDepth.receivedElapsedRealtime,now)
 var depthSource by remember(reference){mutableStateOf(if(reference?.waterDepthMeters!=null)AnchorDepthSource.MANUAL else if(nmeaDepthAvailable)AnchorDepthSource.NMEA else AnchorDepthSource.MANUAL)}
 var depth by remember(reference){mutableStateOf(reference?.waterDepthMeters?.let{"%.1f".format(it)}?:"")};var rode by remember(reference){mutableStateOf(reference?.rodeMeters?.let{"%.0f".format(it)}?:"40")};var bow by remember{mutableStateOf("%.1f".format(state.settings.bowRollerHeightMeters))};var boat by remember{mutableStateOf("%.1f".format(state.settings.boatLengthMeters))};var radius by remember(reference){mutableStateOf("%.0f".format(reference?.alarmRadiusMeters?:state.settings.preferredAlarmRadiusMeters))};var usePhoneHeading by remember{mutableStateOf(false)}
 var conditionsExpanded by remember{mutableStateOf(false)}
 var depthGuard by remember{mutableStateOf(state.settings.defaultDepthGuardEnabled)};var shallowDepth by remember{mutableStateOf("%.1f".format(state.settings.defaultShallowDepthMeters))};var deepGuard by remember{mutableStateOf(state.settings.defaultDeepDepthEnabled)};var deepDepth by remember{mutableStateOf("%.1f".format(state.settings.defaultDeepDepthMeters))}
 var windGuard by remember{mutableStateOf(state.settings.defaultWindGuardEnabled)};var windWarning by remember{mutableStateOf("%.0f".format(state.settings.defaultWindWarningKnots))};var windAlarm by remember{mutableStateOf("%.0f".format(state.settings.defaultWindAlarmKnots))};var windShift by remember{mutableStateOf(state.settings.defaultWindShiftEnabled)};var windShiftDegrees by remember{mutableStateOf("%.0f".format(state.settings.defaultWindShiftDegrees))}
 var validationRequested by remember{mutableStateOf(false)}
 fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
 val sourceFix=when(source){GpsDataSource.NMEA->state.nmeaFix;GpsDataSource.SYSTEM->state.systemFix;GpsDataSource.DEMO->state.systemFix}
 val sourceReady=when(source){GpsDataSource.NMEA->nmeaReady;GpsDataSource.SYSTEM->systemReady;GpsDataSource.DEMO->systemReady}
 val parsed=CoordinateParser.parse(coordinates).getOrNull();val selectedCoordinate=when{estimate||knownMethod==AnchorCenterSource.CURRENT_POSITION->sourceFix?.let{LatLng(it.latitude,it.longitude)};knownMethod==AnchorCenterSource.MANUAL_COORDINATES->parsed?.let{LatLng(it.latitude,it.longitude)};else->picked}
 val depthValue=AnchorSetupDepthPolicy.selectedDepth(depthSource,depth.toDoubleOrNull(),state.connection,state.liveDepth.depthMeters,state.liveDepth.receivedElapsedRealtime,now);val rodeValue=rode.toDoubleOrNull();val bowValue=bow.toDoubleOrNull();val boatValue=boat.toDoubleOrNull();val enteredRadius=radius.toDoubleOrNull()
 val effectiveRangeMode=if(estimate)AnchorRangeMode.BASIC else rangeMode
 val geometryNeeded=estimate||effectiveRangeMode==AnchorRangeMode.ADVANCED;val geometryValid=!geometryNeeded||(depthValue!=null&&depthValue>=0&&rodeValue!=null&&rodeValue>0&&bowValue!=null&&bowValue>0&&rodeValue>depthValue+bowValue)
 val placement=if(estimate)AnchorPlacementMode.BACKDOWN else AnchorPlacementMode.CENTER_DROP
 val suggestion=if(effectiveRangeMode==AnchorRangeMode.ADVANCED&&geometryValid&&depthValue!=null&&rodeValue!=null&&bowValue!=null&&boatValue!=null&&boatValue>0)AnchorRangeCalculator.advanced(depthValue,rodeValue,boatValue,placement,preset,bowValue)else null
 val finalRadius=if(effectiveRangeMode==AnchorRangeMode.ADVANCED)suggestion?.radiusMeters else enteredRadius
 val notificationsReady=android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(androidx.compose.ui.platform.LocalContext.current,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
 val shallowValue=shallowDepth.toDoubleOrNull();val deepValue=deepDepth.toDoubleOrNull();val windWarningValue=windWarning.toDoubleOrNull();val windAlarmValue=windAlarm.toDoubleOrNull();val shiftValue=windShiftDegrees.toDoubleOrNull()
 val conditionNmeaReady=source==GpsDataSource.DEMO||com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability.hasInstrumentTraffic(state.connection)
 val depthReady=!depthGuard||(conditionNmeaReady&&shallowValue!=null&&shallowValue>0&&(!deepGuard||(deepValue!=null&&deepValue>=shallowValue+1))&&(source==GpsDataSource.DEMO||state.liveDepth.isFresh(now)))
 val liveSpeed=state.liveWind.speed(now,state.settings.allowApparentWindFallback);val liveDirection=state.liveWind.direction(now)
 val windReady=!windGuard||(conditionNmeaReady&&windWarningValue!=null&&windAlarmValue!=null&&windAlarmValue>=windWarningValue+3.0&&(source==GpsDataSource.DEMO||liveSpeed!=null))
 val shiftReady=!windShift||(conditionNmeaReady&&shiftValue!=null&&shiftValue in 15.0..180.0&&(source==GpsDataSource.DEMO||liveDirection!=null))
 val valid=sourceReady&&notificationsReady&&selectedCoordinate!=null&&geometryValid&&finalRadius!=null&&finalRadius>0&&(effectiveRangeMode!=AnchorRangeMode.ADVANCED||suggestion!=null)&&depthReady&&windReady&&shiftReady
 val coordinateError=validationRequested&&selectedCoordinate==null;val radiusError=validationRequested&&(finalRadius==null||finalRadius<=0)
 val depthError=validationRequested&&geometryNeeded&&(depthValue==null||depthValue<0);val rodeError=validationRequested&&geometryNeeded&&(rodeValue==null||rodeValue<=0||depthValue!=null&&bowValue!=null&&rodeValue<=depthValue+bowValue);val bowError=validationRequested&&geometryNeeded&&(bowValue==null||bowValue<=0);val boatError=validationRequested&&effectiveRangeMode==AnchorRangeMode.ADVANCED&&(boatValue==null||boatValue<=0)
 ModalBottomSheet(onDismissRequest=dismiss,dragHandle={BottomSheetDefaults.DragHandle()}){Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Text(tr("Set anchor watch","设置锚警"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
  if(reference!=null)Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Depth, rode and radius were prefilled from saved references. Re-check current conditions. No historical coordinate is used as this session's anchor.","水深、锚链和范围已按收藏参考预填。请重新核对当前环境；本次锚点不会使用任何历史坐标。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)}
  Text(tr("Position source","定位数据源"),style=MaterialTheme.typography.titleMedium)
  if(state.settings.demoMode)GpsSourceRow(tr("Demo GPS · locked","演示 GPS · 已锁定"),tr("Each session first captures the current real System GNSS position.","每次会话都会先获取当前真实系统 GNSS 位置。"),true,false,"setup_source_demo"){}
  else{GpsSourceRow(tr("Phone GPS","手机 GPS"),if(proxyActive)tr("Unavailable while Android GPS Proxy is active.","Android GPS 代理开启时不可使用。")else if(systemReady)tr("GNSS ready · ±${state.systemFix?.horizontalAccuracyMeters?.toInt()?:"--"} m","GNSS 就绪 · ±${state.systemFix?.horizontalAccuracyMeters?.toInt()?:"--"} 米")else tr("Waiting for precise GNSS; network location is not accepted.","正在等待精确 GNSS；不接受网络粗略定位。"),source==GpsDataSource.SYSTEM,!proxyActive,"setup_source_system"){source=GpsDataSource.SYSTEM};HorizontalDivider();GpsSourceRow("NMEA GPS",if(nmeaReady)tr("Connected · HDOP ${state.nmeaFix?.hdop?.let{"%.1f".format(it)}?:"—"}","已连接 · HDOP ${state.nmeaFix?.hdop?.let{"%.1f".format(it)}?:"—"}")else tr("Connect NMEA and wait for a fresh valid fix first.","请先连接 NMEA 并等待新鲜有效定位。"),source==GpsDataSource.NMEA,nmeaReady,"setup_source_nmea"){source=GpsDataSource.NMEA}}
  Text(tr("Anchor position","锚点位置"),style=MaterialTheme.typography.titleMedium)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!estimate,{estimate=false},label={Text(tr("I know it","我知道锚点"))},modifier=Modifier.weight(1f));FilterChip(estimate,{estimate=true},label={Text(tr("Estimate it","自动估算"))},modifier=Modifier.weight(1f))}
  if(!estimate){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){GpsSourceRow(tr("Use current position","使用当前位置"),tr("The selected source position becomes authoritative.","所选数据源当前位置将作为权威锚点。"),knownMethod==AnchorCenterSource.CURRENT_POSITION,true,"known_current"){knownMethod=AnchorCenterSource.CURRENT_POSITION};GpsSourceRow(tr("Enter coordinates","输入坐标"),tr("Decimal degrees, comma or space separated.","十进制度，可用逗号或空格分隔。"),knownMethod==AnchorCenterSource.MANUAL_COORDINATES,true,"known_manual"){knownMethod=AnchorCenterSource.MANUAL_COORDINATES};GpsSourceRow(tr("Pick on map","地图选点"),tr("Open a full-screen map and drag the anchor marker.","打开全屏地图并拖动锚点图标。"),knownMethod==AnchorCenterSource.MAP_PICK,true,"known_map"){knownMethod=AnchorCenterSource.MAP_PICK}}
   if(knownMethod==AnchorCenterSource.MANUAL_COORDINATES){OutlinedTextField(coordinates,{coordinates=it},label={Text(tr("Latitude, longitude *","纬度，经度 *"))},placeholder={Text("-36.812345, 174.712345")},isError=(coordinates.isNotBlank()&&parsed==null)||coordinateError,supportingText={if((coordinates.isNotBlank()&&parsed==null)||coordinateError)Text(if(coordinates.isBlank())tr("Required.","必填。")else tr("Latitude [-90,90], longitude [-180,180].","纬度范围 [-90,90]，经度范围 [-180,180]。"))},modifier=Modifier.fillMaxWidth().testTag("anchor_coordinates"));if(parsed!=null&&sourceFix!=null){val distance=AnchorGeometry.distanceMeters(sourceFix.latitude,sourceFix.longitude,parsed.latitude,parsed.longitude);Text(tr("Current boat distance to this point: ${distance.toInt()} m","当前船位距此点：${distance.toInt()} 米"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(parsed!=null&&BuildConfig.MAPS_CONFIGURED){val preview=LatLng(parsed.latitude,parsed.longitude);val previewCamera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(preview,16f)};LaunchedEffect(preview){previewCamera.move(CameraUpdateFactory.newLatLng(preview))};GoogleMap(Modifier.fillMaxWidth().height(180.dp),cameraPositionState=previewCamera,uiSettings=MapUiSettings(compassEnabled=false,mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false)){Marker(state=remember(preview){MarkerState(preview)},title=tr("Entered anchor","输入的锚点"))}}}
   if(knownMethod==AnchorCenterSource.MAP_PICK&&BuildConfig.MAPS_CONFIGURED){OutlinedButton({showMapPicker=true},Modifier.fillMaxWidth().heightIn(min=52.dp)){Icon(Icons.Default.EditLocationAlt,null);Spacer(Modifier.width(8.dp));Text(if(picked==null)tr("Open full-screen map picker","打开全屏地图选点")else tr("Edit anchor on map","在地图上编辑锚点"))};Text(picked?.let{tr("Selected ${"%.6f, %.6f".format(it.latitude,it.longitude)}","已选择 ${"%.6f, %.6f".format(it.latitude,it.longitude)}")}?:tr("No point selected yet. The full-screen picker prevents the setup sheet from stealing map drags.","尚未选点。全屏选点不会被设置面板的滑动手势抢占。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  }else{Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Start does not assume the boat is over the anchor. The current position only centres the temporary alarm boundary; GPS swing geometry estimates a candidate and waits for your approval.","开始时不会假设船就在锚点上。当前位置只用于临时报警边界；GPS 摆动几何会推算候选锚点，并等待你确认。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)};SettingSwitch(tr("Use phone heading","使用手机船首向"),tr("Optional. Fix the phone in place with its top pointing to the bow; movement automatically suspends this evidence.","可选。请固定手机并让顶部指向船首；拿起或移动时会自动停止采信。"),usePhoneHeading){usePhoneHeading=it}}
  Text(tr("Alarm range","报警范围"),style=MaterialTheme.typography.titleMedium);if(!estimate)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(rangeMode==AnchorRangeMode.BASIC,{rangeMode=AnchorRangeMode.BASIC},label={Text(tr("Basic","基础"))});FilterChip(rangeMode==AnchorRangeMode.ADVANCED,{rangeMode=AnchorRangeMode.ADVANCED},label={Text(tr("Advanced","高级"))})}else Text(tr("Automatic estimation always uses the radius you set below; geometry only constrains the possible centre.","自动估算始终使用下方手动设置的报警半径；几何参数只约束可能的锚点范围。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(effectiveRangeMode==AnchorRangeMode.BASIC)OutlinedTextField(radius,{radius=numeric(it)},label={Text(tr("Alarm radius *","报警半径 *"))},isError=radiusError,supportingText={if(radiusError)Text(tr("Enter a radius greater than zero.","请输入大于零的报警半径。"))},suffix={Text(tr("m","米"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("anchor_alarm_radius"))
  if(geometryNeeded){Text(if(estimate)tr("Estimation geometry · required","估算几何参数 · 必填")else tr("Range geometry · required","范围几何参数 · 必填"),style=MaterialTheme.typography.labelLarge);AnchorDepthInputRow(depthSource,nmeaDepthAvailable,state.liveDepth.depthMeters,depth,depthError,{depthSource=it},{depth=numeric(it)});OutlinedTextField(rode,{rode=numeric(it)},label={Text(tr("Rode / chain paid out *","放出的锚缆 / 锚链 *"))},isError=rodeError,supportingText={if(rodeError)Text(tr("Rode must exceed water depth plus bow height.","锚缆长度必须大于水深与船艏高度之和。"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth().testTag("anchor_rode"));OutlinedTextField(bow,{bow=numeric(it)},label={Text(tr("Bow roller height above water *","船艏滚轮离水面高度 *"))},isError=bowError,supportingText={if(bowError)Text(tr("Enter a value greater than zero.","请输入大于零的数值。"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth().testTag("anchor_bow_height"));if(effectiveRangeMode==AnchorRangeMode.ADVANCED){OutlinedTextField(boat,{boat=numeric(it)},label={Text(tr("Boat length *","船长 *"))},isError=boatError,supportingText={if(boatError)Text(tr("Enter a value greater than zero.","请输入大于零的数值。"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth().testTag("anchor_boat_length"));Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){AnchorSafetyPreset.entries.forEach{value->FilterChip(preset==value,{preset=value},label={Text(when(value){AnchorSafetyPreset.STRICT->tr("Strict","严格");AnchorSafetyPreset.BALANCED->tr("Balanced","均衡");AnchorSafetyPreset.TOLERANT->tr("Tolerant","宽容")})})}};suggestion?.let{Text(tr("Suggested radius ${it.radiusMeters.toInt()} m","建议半径 ${it.radiusMeters.toInt()} 米"),fontWeight=FontWeight.SemiBold)}}}
  HorizontalDivider();Row(Modifier.fillMaxWidth().clickable{conditionsExpanded=!conditionsExpanded},verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Condition alerts","环境警戒"),style=MaterialTheme.typography.titleMedium);Text(tr("Optional · depth, wind speed and wind shift","可选 · 水深、风速和风向变化"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(if(conditionsExpanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)}
  if(conditionsExpanded){
   Text(tr("Depth alerts use live onboard NMEA sounder data and the configured offset. Wind alerts use onboard NMEA wind; TRUE is preferred and APPARENT is labelled when used. Verify instrument references before relying on either.","水深警报使用船载 NMEA 测深数据和已设置的 offset；风警报使用船载 NMEA 风数据，优先 TRUE，回退 APPARENT 时会明确标注。依赖这些警报前请核对仪表基准。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   SettingSwitch(tr("Depth guard","水深警戒"),state.liveDepth.depthMeters?.let{tr("Live ${"%.1f".format(it)} m · ${state.liveDepth.sentenceType} · Fresh","实时 ${"%.1f".format(it)} 米 · ${state.liveDepth.sentenceType} · 新鲜") }?:tr("Waiting for fresh NMEA depth","等待新鲜 NMEA 水深"),depthGuard,enabled=source==GpsDataSource.DEMO||state.liveDepth.isFresh(now)||depthGuard){depthGuard=it}
   if(depthGuard){OutlinedTextField(shallowDepth,{shallowDepth=numeric(it)},label={Text(tr("Shallow alarm *","浅水警报 *"))},isError=validationRequested&&!depthReady,suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());SettingSwitch(tr("Deep alarm","深水警报"),tr("Optional high-depth boundary","可选的深水边界"),deepGuard){deepGuard=it};if(deepGuard)OutlinedTextField(deepDepth,{deepDepth=numeric(it)},label={Text(tr("Deep alarm *","深水警报 *"))},isError=validationRequested&&!depthReady,suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth())}
   SettingSwitch(tr("Wind guard","风速警戒"),liveSpeed?.let{tr("Live ${"%.1f".format(it.first.value)} kn · ${it.second}","实时 ${"%.1f".format(it.first.value)} 节 · ${it.second}")}?:tr("Waiting for supported NMEA wind","等待支持的 NMEA 风速"),windGuard,enabled=source==GpsDataSource.DEMO||liveSpeed!=null||windGuard){windGuard=it}
   if(windGuard){OutlinedTextField(windWarning,{windWarning=numeric(it)},label={Text(tr("Wind warning *","风速提醒 *"))},isError=validationRequested&&!windReady,suffix={Text("kn")},modifier=Modifier.fillMaxWidth());OutlinedTextField(windAlarm,{windAlarm=numeric(it)},label={Text(tr("Wind alarm *","风速警报 *"))},isError=validationRequested&&!windReady,suffix={Text("kn")},modifier=Modifier.fillMaxWidth())}
   SettingSwitch(tr("Wind shift","风向突变"),liveDirection?.let{tr("True direction ${it.first.value.toInt()}° · ${it.second}","真风向 ${it.first.value.toInt()}° · ${it.second}")}?:tr("Requires MWD or coherent MWV-T + HDT","需要 MWD 或同步的 MWV-T + HDT"),windShift,enabled=source==GpsDataSource.DEMO||liveDirection!=null||windShift){windShift=it}
   if(windShift)OutlinedTextField(windShiftDegrees,{windShiftDegrees=numeric(it)},label={Text(tr("Shift alarm *","风向变化警报 *"))},isError=validationRequested&&!shiftReady,suffix={Text("°")},modifier=Modifier.fillMaxWidth())
  }
  HorizontalDivider();Text(tr("Safety check","安全检查"),style=MaterialTheme.typography.titleMedium);PreflightRow(tr("Selected position source","所选定位源"),sourceReady,if(sourceReady)tr("Ready","就绪")else tr("Not ready","未就绪"));PreflightRow(tr("Notifications","通知"),notificationsReady,if(notificationsReady)tr("Ready","就绪")else tr("Permission required","需要权限"));PreflightRow(tr("Anchor coordinate","锚点坐标"),selectedCoordinate!=null,selectedCoordinate?.let{"%.5f, %.5f".format(it.latitude,it.longitude)}?:tr("Missing","缺失"))
  if(depthGuard)PreflightRow(tr("Depth guard","水深警戒"),depthReady,if(depthReady)state.liveDepth.depthMeters?.let{tr("Ready · ${"%.1f".format(it)} m","就绪 · ${"%.1f".format(it)} 米") }?:tr("Demo sensor ready","演示传感器就绪") else tr("Needs fresh NMEA depth","需要新鲜 NMEA 水深"))
  if(windGuard)PreflightRow(tr("Wind guard","风速警戒"),windReady,if(windReady)liveSpeed?.let{tr("Ready · ${it.second} ${"%.1f".format(it.first.value)} kn","就绪 · ${it.second} ${"%.1f".format(it.first.value)} 节") }?:tr("Demo sensor ready","演示传感器就绪") else tr("Needs usable NMEA wind speed","需要可用的 NMEA 风速"))
  if(windShift)PreflightRow(tr("Wind shift","风向突变"),shiftReady,if(shiftReady)tr("Ready to learn a true-wind baseline","已可学习真风向基线")else tr("True wind direction unavailable","真风向不可用"))
  if(validationRequested&&!valid)Text(tr("Complete the required fields marked * and resolve the failed safety checks above.","请完成所有带 * 的必填项，并处理上方未通过的安全检查。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("anchor_setup_validation_error"))
  Button({validationRequested=true;if(!valid)return@Button;val coordinate=selectedCoordinate?:return@Button;val confirmedRadius=finalRadius?:return@Button;val conditionConfig=ConditionGuardConfig(depthGuard,shallowValue,deepValue.takeIf{deepGuard},windGuard,windWarningValue,windAlarmValue,windShift,shiftValue,state.settings.allowApparentWindFallback).validated();start(coordinate.latitude,coordinate.longitude,AnchorWatchInput(placement,effectiveRangeMode,preset,depthValue,if(geometryNeeded)rodeValue?:0.0 else 0.0,if(geometryNeeded)bowValue?:0.0 else 0.0,if(effectiveRangeMode==AnchorRangeMode.ADVANCED)boatValue else null,confirmedRadius,source,if(estimate)AnchorCenterSource.UNKNOWN else knownMethod,usePhoneHeading,depthSource,conditionConfig))},modifier=Modifier.fillMaxWidth().padding(bottom=24.dp).testTag("start_anchor_watch")){Text(tr("Start anchor watch","启动锚警"))}
 }}
 if(showMapPicker&&BuildConfig.MAPS_CONFIGURED){val initial=picked?:sourceFix?.let{LatLng(it.latitude,it.longitude)}?:LatLng(-36.8485,174.7633);FullScreenAnchorMapPicker(initial,{showMapPicker=false}){picked=it;showMapPicker=false}}
}

@Composable
private fun AnchorDepthInputRow(source:AnchorDepthSource,nmeaAvailable:Boolean,nmeaDepthMeters:Double?,manualValue:String,showError:Boolean,onSource:(AnchorDepthSource)->Unit,onManualValue:(String)->Unit){
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
  Text(tr("Depth *","水深 *"),style=MaterialTheme.typography.labelLarge,modifier=Modifier.widthIn(min=38.dp))
  FilterChip(source==AnchorDepthSource.NMEA,{onSource(AnchorDepthSource.NMEA)},enabled=nmeaAvailable,label={Text("NMEA")},modifier=Modifier.testTag("anchor_depth_nmea"))
  FilterChip(source==AnchorDepthSource.MANUAL,{onSource(AnchorDepthSource.MANUAL)},label={Text(tr("Manual","手动"))},modifier=Modifier.testTag("anchor_depth_manual"))
  OutlinedTextField(
   value=if(source==AnchorDepthSource.NMEA)nmeaDepthMeters?.takeIf{nmeaAvailable}?.let{"%.1f".format(it)}?:"—" else manualValue,
   onValueChange={if(source==AnchorDepthSource.MANUAL)onManualValue(it)},
   readOnly=source==AnchorDepthSource.NMEA,
   isError=showError||(source==AnchorDepthSource.NMEA&&!nmeaAvailable),
   supportingText={if(showError)Text(tr("Choose fresh NMEA depth or enter a valid manual depth.","请选择新鲜的 NMEA 水深，或输入有效的手动水深。"))},
   singleLine=true,
   suffix={Text(tr("m","米"))},
   modifier=Modifier.weight(1f).testTag("anchor_depth_value"),
  )
 }
}

@Composable
private fun FullScreenAnchorMapPicker(initial:LatLng,dismiss:()->Unit,confirm:(LatLng)->Unit){
 val marker=remember{MarkerState(initial)};val camera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(initial,17f)}
 Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){Box(Modifier.fillMaxSize()){
   GoogleMap(Modifier.fillMaxSize(),cameraPositionState=camera,uiSettings=MapUiSettings(compassEnabled=false,mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false),onMapClick={marker.position=it},onMapLongClick={marker.position=it}){Marker(state=marker,title=tr("Drag anchor","拖动锚点"),draggable=true)}
   Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),shape=MaterialTheme.shapes.medium,tonalElevation=4.dp){Text(tr("Drag the anchor marker or tap the map","拖动锚点图标，或点击地图选点"),Modifier.padding(horizontal=14.dp,vertical=10.dp),fontWeight=FontWeight.Medium)}
   Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(dismiss,Modifier.weight(1f).heightIn(min=52.dp)){Text(tr("Cancel","取消"))};Button({confirm(marker.position)},Modifier.weight(1f).heightIn(min=52.dp)){Text(tr("Use this point","使用此位置"))}}
  }}
 }
}
