package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.domain.vessel.NmeaStreamReadiness
import com.yokuli.anchorwatch.data.backup.BackupRestorePolicy

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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.yokuli.anchorwatch.data.nmea.output.NmeaRawTxConsolePolicy
import com.yokuli.anchorwatch.data.nmea.output.NmeaOutputEndpointPolicy
import com.yokuli.anchorwatch.data.nmea.output.NmeaWriteBackpressureState
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchor.AnchorRangeCalculator
import com.yokuli.anchorwatch.domain.anchor.CoordinateParser
import com.yokuli.anchorwatch.domain.anchor.WindAnchorEvidence
import com.yokuli.anchorwatch.domain.condition.hasMeaningfulDiff
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
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.nativeName
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.ui.about.AboutScreen
import com.yokuli.anchorwatch.ui.about.FeedbackScreen
import com.yokuli.anchorwatch.ui.about.ExternalLinkLauncher
import com.yokuli.anchorwatch.ui.about.ExternalLinkResult
import com.yokuli.anchorwatch.brand.ProductBrand
import com.yokuli.anchorwatch.ui.theme.SafetyColors
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness
import com.yokuli.anchorwatch.location.vessel.PhoneVesselOutputReadinessPolicy
import com.yokuli.anchorwatch.location.vessel.PhoneHeadingAlignmentPolicy
import java.text.DateFormat

private enum class SettingsDestination{ROOT,ALARM,VESSEL,PHONE_SENSORS,DEPTH_SOUNDER,POSITIONING,MAP_DEPTH,BACKGROUND,DATA_BACKUP,STORAGE_SUPPORT,DEVELOPER,ABOUT,FEEDBACK}

@Composable internal fun SettingsScreen(state:MainUiState,vm:MainViewModel)=SettingsPageContent(state,vm)

@Composable internal fun SettingsPageContent(state:MainUiState,vm:MainViewModel){
 var destination by rememberSaveable{mutableStateOf(SettingsDestination.ROOT)};var feedbackReturn by rememberSaveable{mutableStateOf(SettingsDestination.ROOT)};var languageDialog by remember{mutableStateOf(false)};var pendingSupportUrl by remember{mutableStateOf<String?>(null)};val context=androidx.compose.ui.platform.LocalContext.current;val externalLauncher=remember(context){ExternalLinkLauncher(context)};BackHandler(destination!=SettingsDestination.ROOT){destination=if(destination==SettingsDestination.FEEDBACK)feedbackReturn else SettingsDestination.ROOT}
 when(destination){
  SettingsDestination.ROOT->SettingsRoot(state,{next->if(next==SettingsDestination.FEEDBACK)feedbackReturn=SettingsDestination.ROOT;destination=next},{languageDialog=true}){pendingSupportUrl=it}
  SettingsDestination.ABOUT->AboutScreen(onBack={destination=SettingsDestination.ROOT},onFeedback={feedbackReturn=SettingsDestination.ABOUT;destination=SettingsDestination.FEEDBACK})
  SettingsDestination.FEEDBACK->FeedbackScreen{destination=feedbackReturn}
  else->SettingsSubPage(destination,state,vm){destination=SettingsDestination.ROOT}
 }
 if(languageDialog)LanguagePickerDialog(state.settings.appLanguage,{languageDialog=false}){language->vm.updateSettings(state.settings.copy(appLanguage=language));languageDialog=false}
 pendingSupportUrl?.let{url->AlertDialog(onDismissRequest={pendingSupportUrl=null},title={Text(tr("Open Buy Me a Coffee?","打开 Buy Me a Coffee？"))},text={Text(tr("You’re leaving Boat Watch to visit Buy Me a Coffee in your browser. Support is optional and does not unlock app features.","即将离开 Boat Watch，并在浏览器中打开 Buy Me a Coffee。支持完全自愿，不会解锁任何 App 功能。"))},confirmButton={Button({pendingSupportUrl=null;if(externalLauncher.open(url)!=ExternalLinkResult.OPENED)android.widget.Toast.makeText(context,localized(state.settings.appLanguage,"No app could open this secure link.","没有应用可以打开此安全链接。"),android.widget.Toast.LENGTH_SHORT).show()},Modifier.testTag("settings_support_continue")){Text(tr("Continue","继续"))}},dismissButton={TextButton({pendingSupportUrl=null}){Text(tr("Cancel","取消"))}})}
}

@Composable private fun SettingsRoot(state:MainUiState,open:(SettingsDestination)->Unit,language:()->Unit,support:(String)->Unit){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp).testTag("settings_list"),contentPadding=PaddingValues(vertical=18.dp)){
  item{PageHeader(tr("Settings","设置"),tr("Choose a section; runtime NMEA and sonar controls stay in Data.","选择要配置的类别；NMEA 与声呐运行操作仍在“数据”页面。"));Spacer(Modifier.height(14.dp));ProductBrand.supportProviders.firstOrNull()?.let{provider->ElevatedCard(onClick={support(provider.url)},modifier=Modifier.fillMaxWidth().testTag("settings_support_card")){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Default.Coffee,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text(tr("Support Yokuli","支持 Yokuli"),fontWeight=FontWeight.SemiBold);Text(tr("Buy Me a Coffee · every app feature stays free","Buy Me a Coffee · App 全部功能始终免费"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.AutoMirrored.Filled.OpenInNew,null)}}};Spacer(Modifier.height(10.dp))}
  item{SettingsSection(tr("ALARM & WATCH","报警与监控"));SettingsRow(Icons.Default.NotificationsActive,tr("Alarm & notifications","报警与通知"),tr("Anchor alarm · ${state.settings.alarmSnoozeMinutes} min snooze","锚警 · ${state.settings.alarmSnoozeMinutes} 分钟后提醒"),"settings_alarm"){open(SettingsDestination.ALARM)}}
  item{SettingsSection(tr("VESSEL","船舶"));SettingsRow(Icons.Default.Sailing,tr("Vessel profile","船舶资料"),tr("${state.settings.boatLengthMeters} m · bow ${state.settings.bowRollerHeightMeters} m","${state.settings.boatLengthMeters} 米 · 船艏 ${state.settings.bowRollerHeightMeters} 米"),"settings_vessel"){open(SettingsDestination.VESSEL)};SettingsRow(Icons.Default.Sensors,tr("Phone vessel sensors","手机船舶传感器"),if(state.vesselMountCalibration.headingAligned)tr("Heading aligned · tap to realign at any time","船首向已对齐 · 可随时点此重新对齐")else tr("Point the phone toward the bow to align","将手机指向船艏即可对齐"),"settings_phone_sensors"){open(SettingsDestination.PHONE_SENSORS)};SettingsRow(Icons.Default.Waves,tr("Depth sounder","测深仪"),tr("Depth offset ${signed(state.settings.sounderOffsetMeters)} m","水深修正 ${signed(state.settings.sounderOffsetMeters)} 米"),"settings_depth_sounder"){open(SettingsDestination.DEPTH_SOUNDER)}}
  item{SettingsSection(tr("POSITION & MAP","定位与地图"));SettingsRow(Icons.Default.GpsFixed,tr("Positioning","定位"),tr("Default: ${settingsGpsSourceLabel(state.settings.gpsDataSource)}","默认：${settingsGpsSourceLabel(state.settings.gpsDataSource)}"),"settings_positioning"){open(SettingsDestination.POSITIONING)};SettingsRow(Icons.Default.Layers,tr("Map data","地图数据"),tr("Offline MBTiles · personal sonar","离线 MBTiles · 个人声呐"),"settings_map_depth"){open(SettingsDestination.MAP_DEPTH)}}
  item{SettingsSection(tr("DEVICE & DATA","设备与数据"));SettingsRow(Icons.Default.BatterySaver,tr("Background reliability","后台可靠性"),tr("Permissions, power and Wi-Fi","权限、电源与 Wi-Fi"),"settings_background"){open(SettingsDestination.BACKGROUND)};SettingsRow(Icons.Default.Backup,tr("Data & backup","数据与备份"),tr("Export or replace from a Boat Watch backup","导出或从 Boat Watch 备份替换恢复"),"settings_data_backup"){open(SettingsDestination.DATA_BACKUP)};SettingsRow(Icons.Default.Storage,tr("Storage & support","存储与支持"),tr("Health, incident log and diagnostics","健康、事件日志与诊断包"),"settings_storage_support"){open(SettingsDestination.STORAGE_SUPPORT)};SettingsRow(Icons.Default.Language,tr("Language","语言"),state.settings.appLanguage.nativeName,"settings_language",language)}
  item{SettingsSection(tr("ADVANCED","高级"));SettingsRow(Icons.Default.DeveloperMode,tr("Developer","开发者"),if(state.settings.demoMode)tr("Demo mode on","演示模式已开启")else tr("Demo mode off","演示模式已关闭"),"settings_developer"){open(SettingsDestination.DEVELOPER)}}
  item{SettingsSection(tr("ABOUT","关于"));SettingsRow(Icons.Default.Info,tr("About & support","关于与支持"),tr("Developed aboard SV Yokuli","开发于 SV Yokuli 船上"),"settings_about"){open(SettingsDestination.ABOUT)};if(ProductBrand.contactEmail.isNotBlank())SettingsRow(Icons.Default.Feedback,tr("Feedback & feature requests","反馈与功能建议"),tr("Email kuku directly","直接给 kuku 发邮件"),"settings_feedback"){open(SettingsDestination.FEEDBACK)}}
 }
}

@Composable private fun SettingsSubPage(destination:SettingsDestination,state:MainUiState,vm:MainViewModel,back:()->Unit){Column(Modifier.fillMaxSize()){Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Filled.ArrowBack,tr("Back","返回"))};Text(when(destination){SettingsDestination.ALARM->tr("Alarm & notifications","报警与通知");SettingsDestination.VESSEL->tr("Vessel profile","船舶资料");SettingsDestination.PHONE_SENSORS->tr("Phone vessel sensors","手机船舶传感器");SettingsDestination.DEPTH_SOUNDER->tr("Depth sounder","测深仪");SettingsDestination.POSITIONING->tr("Positioning","定位");SettingsDestination.MAP_DEPTH->tr("Map & depth","地图与水深");SettingsDestination.BACKGROUND->tr("Background reliability","后台可靠性");SettingsDestination.DATA_BACKUP->tr("Data & backup","数据与备份");SettingsDestination.STORAGE_SUPPORT->tr("Storage & support","存储与支持");SettingsDestination.DEVELOPER->tr("Developer","开发者");else->""},style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)};LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{when(destination){SettingsDestination.ALARM->AlarmSettingsPage(state,vm);SettingsDestination.VESSEL->VesselProfileCard(state,vm);SettingsDestination.PHONE_SENSORS->PhoneVesselSensorsCard(state,vm);SettingsDestination.DEPTH_SOUNDER->DepthSounderPage(state,vm);SettingsDestination.POSITIONING->Column(verticalArrangement=Arrangement.spacedBy(12.dp)){GpsDataSourceCard(state,vm);GpsProxyCard(state,vm)};SettingsDestination.MAP_DEPTH->MapDepthSettingsPage(state,vm);SettingsDestination.BACKGROUND->BackgroundReliabilityCard(state,vm);SettingsDestination.DATA_BACKUP->DataBackupPage(state,vm);SettingsDestination.STORAGE_SUPPORT->StorageSupportPage(state,vm);SettingsDestination.DEVELOPER->DeveloperSettingsCard(state,vm);else->{}}}}}}

@Composable private fun DataBackupPage(state:MainUiState,vm:MainViewModel){
 var privacyConfirm by remember{mutableStateOf(false)};var restoreUri by remember{mutableStateOf<Uri?>(null)}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)vm.exportBackup(uri)}
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)restoreUri=uri}
 val blockedReason=BackupRestorePolicy.blockingReason(
  anchorActive=state.active!=null,
  sonarActive=state.activeSonarSurvey!=null,
  proxyActive=GpsSourceSafety.requiresStopAction(state.settings.mockEnabled,state.mockGps.state),
  sharingActive=state.settings.nmeaSharingEnabled||state.nmeaSharing.state!=com.yokuli.anchorwatch.data.sharing.SharingServerState.STOPPED,
  tripActive=state.activeTrip!=null,
  phoneOutputActive=state.outputSettings.anyEnabled,
  nmeaConnected=state.connection!=NmeaConnectionState.DISCONNECTED,
 )
 val blocked=blockedReason!=null
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("BACKUP","备份"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(tr("Export Boat Watch backup","导出 Boat Watch 备份"),style=MaterialTheme.typography.titleMedium);Text(tr("Includes settings, anchor history, saved anchorages, Trip Watch sessions, tracks, events, waypoints, sonar surveys and raw soundings. Derived caches are rebuilt after restore.","包含设置、锚泊历史、收藏锚地、航程会话、轨迹、事件、航点、声呐调查和原始测深点。派生缓存会在恢复后重建。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);state.backup.lastBackupAt?.let{Text(tr("Last backup: ${DateFormat.getDateTimeInstance().format(java.util.Date(it))}","上次备份：${DateFormat.getDateTimeInstance().format(java.util.Date(it))}"),style=MaterialTheme.typography.bodySmall)};Button({privacyConfirm=true},Modifier.fillMaxWidth(),enabled=!state.backup.running){Icon(Icons.Default.FileUpload,null);Spacer(Modifier.width(6.dp));Text(tr("Export Boat Watch backup","导出 Boat Watch 备份"))}}}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("RESTORE · REPLACE","恢复 · 替换"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.error);Text(tr("Restore from Boat Watch backup","从 Boat Watch 备份恢复"),style=MaterialTheme.typography.titleMedium);Text(tr("The archive is fully checked before local data changes. Restore replaces local Boat Watch history; merge is intentionally unavailable.","备份会先完成全部校验，再改动本机数据。恢复会替换本机 Boat Watch 历史；本版本故意不提供合并。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);blockedReason?.let{Text(localizeKnownMessage(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)};OutlinedButton({restore.launch(arrayOf("application/zip","application/octet-stream","*/*"))},Modifier.fillMaxWidth(),enabled=!blocked&&!state.backup.running){Icon(Icons.Default.Restore,null);Spacer(Modifier.width(6.dp));Text(tr("Choose backup to restore","选择要恢复的备份"))}}}
  if(state.backup.running){LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.backup.progress,style=MaterialTheme.typography.bodySmall)}
  state.backup.result?.let{AssistChip(vm::clearBackupResult,{Text(localizeKnownMessage(it))},leadingIcon={Icon(Icons.Default.CheckCircle,null)})}
  state.backup.error?.let{Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp)){Text(localizeKnownMessage(it),color=MaterialTheme.colorScheme.onErrorContainer);TextButton(vm::clearBackupResult){Text(tr("Dismiss","关闭"))}}}}
 }
 if(privacyConfirm)AlertDialog(onDismissRequest={privacyConfirm=false},title={Text(tr("Precise location history","精确位置历史"))},text={Text(tr("This backup contains anchoring locations, saved anchorages, trips, tracks and sonar positions. Protect it like a vessel logbook. Boat Watch backup v4 is not encrypted.","此备份包含锚泊位置、收藏锚地、航程、轨迹和声呐坐标。请像保护航海日志一样保护该文件；Boat Watch 备份 v4 未加密。"))},confirmButton={Button({privacyConfirm=false;export.launch("Boat-Watch-${java.time.LocalDate.now()}.yokuli-backup")}){Text(tr("Choose export location","选择导出位置"))}},dismissButton={TextButton({privacyConfirm=false}){Text(tr("Cancel","取消"))}})
 restoreUri?.let{uri->AlertDialog(onDismissRequest={restoreUri=null},title={Text(tr("Replace all local Boat Watch data?","替换全部本机 Boat Watch 数据？"))},text={Text(tr("Validation happens first. If it passes, local anchor and sonar history will be replaced in one database transaction. This cannot be undone without another backup.","系统会先完整校验；通过后，本机锚泊与声呐历史将在一个数据库事务中被替换。除非另有备份，否则无法撤销。"))},confirmButton={Button({restoreUri=null;vm.restoreBackup(uri)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Validate & replace","校验并替换"))}},dismissButton={TextButton({restoreUri=null}){Text(tr("Cancel","取消"))}})}
}

@Composable private fun SettingsSection(title:String){Text(title,Modifier.padding(top=14.dp,bottom=5.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
@Composable private fun SettingsRow(icon:ImageVector,title:String,summary:String,tag:String,click:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=66.dp).testTag(tag).clickable(onClick=click).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(16.dp));Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);Text(summary,maxLines=1,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)};HorizontalDivider()}
internal fun signed(value:Double)=if(value>=0)"+${"%.1f".format(value)}" else "%.1f".format(value)
private fun depthMeters(value:Double?)=value?.let{"%.2f m".format(it)}?:"—"
private fun signedDepthMeters(value:Double?)=value?.let{"${if(it>=0)"+" else ""}${"%.2f".format(it)} m"}?:"—"

@Composable private fun AlarmSettingsPage(state:MainUiState,vm:MainViewModel){
 var radius by remember(state.settings.preferredAlarmRadiusMeters){mutableStateOf(state.settings.preferredAlarmRadiusMeters.toString())};val value=radius.toDoubleOrNull()
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){AlarmBehaviourCard(state,vm);Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Default range","默认范围"),style=MaterialTheme.typography.titleMedium);OutlinedTextField(radius,{radius=it.filter{c->c.isDigit()||c=='.'}},label={Text(tr("Preferred alarm radius","首选报警半径"))},suffix={Text(tr("m","米"))},isError=value==null||value<=0,modifier=Modifier.fillMaxWidth());Button({vm.updateSettings(state.settings.copy(preferredAlarmRadiusMeters=value!!))},enabled=value!=null&&value>0&&value!=state.settings.preferredAlarmRadiusMeters,modifier=Modifier.fillMaxWidth()){Text(tr("Save range","保存范围"))}}};ConditionDefaultsCard(state,vm)}
}

@Composable private fun ConditionDefaultsCard(state:MainUiState,vm:MainViewModel){
 var depthEnabled by remember(state.settings.defaultDepthGuardEnabled){mutableStateOf(state.settings.defaultDepthGuardEnabled)};var shallow by remember(state.settings.defaultShallowDepthMeters){mutableStateOf(state.settings.defaultShallowDepthMeters.toString())};var deepEnabled by remember(state.settings.defaultDeepDepthEnabled){mutableStateOf(state.settings.defaultDeepDepthEnabled)};var deep by remember(state.settings.defaultDeepDepthMeters){mutableStateOf(state.settings.defaultDeepDepthMeters.toString())};var windEnabled by remember(state.settings.defaultWindGuardEnabled){mutableStateOf(state.settings.defaultWindGuardEnabled)};var warning by remember(state.settings.defaultWindWarningKnots){mutableStateOf(state.settings.defaultWindWarningKnots.toString())};var alarm by remember(state.settings.defaultWindAlarmKnots){mutableStateOf(state.settings.defaultWindAlarmKnots.toString())};var shiftEnabled by remember(state.settings.defaultWindShiftEnabled){mutableStateOf(state.settings.defaultWindShiftEnabled)};var shift by remember(state.settings.defaultWindShiftDegrees){mutableStateOf(state.settings.defaultWindShiftDegrees.toString())}
 fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
 val shallowValue=shallow.toDoubleOrNull();val deepValue=deep.toDoubleOrNull();val warningValue=warning.toDoubleOrNull();val alarmValue=alarm.toDoubleOrNull();val shiftValue=shift.toDoubleOrNull();val valid=shallowValue!=null&&shallowValue>0&&deepValue!=null&&deepValue>=shallowValue+1&&warningValue!=null&&alarmValue!=null&&alarmValue>=warningValue+3.0&&shiftValue!=null&&shiftValue in 15.0..180.0
 val current=com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig(state.settings.defaultDepthGuardEnabled,state.settings.defaultShallowDepthMeters,state.settings.defaultDeepDepthMeters.takeIf{state.settings.defaultDeepDepthEnabled},state.settings.defaultWindGuardEnabled,state.settings.defaultWindWarningKnots,state.settings.defaultWindAlarmKnots,state.settings.defaultWindShiftEnabled,state.settings.defaultWindShiftDegrees,state.settings.allowApparentWindFallback)
 val proposed=com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig(depthEnabled,shallowValue,deepValue.takeIf{deepEnabled},windEnabled,warningValue,alarmValue,shiftEnabled,shiftValue,state.settings.allowApparentWindFallback)
 val hasDiff=valid&&current.hasMeaningfulDiff(proposed)
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text(tr("Condition alert defaults","环境警戒默认值"),style=MaterialTheme.typography.titleMedium);Text(tr("Copied only when a new watch is armed. Active-session thresholds never change silently.","仅在新会话布防时复制；当前会话阈值绝不会被设置页静默改变。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);SettingSwitch(tr("Depth guard by default","默认开启水深警戒"),"",depthEnabled){depthEnabled=it};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(shallow,{shallow=numeric(it)},label={Text(tr("Shallow","浅水"))},suffix={Text("m")},modifier=Modifier.weight(1f));OutlinedTextField(deep,{deep=numeric(it)},label={Text(tr("Deep","深水"))},suffix={Text("m")},modifier=Modifier.weight(1f),enabled=deepEnabled)};SettingSwitch(tr("Deep alarm by default","默认开启深水警报"),"",deepEnabled){deepEnabled=it};SettingSwitch(tr("Wind guard by default","默认开启风速警戒"),"",windEnabled){windEnabled=it};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(warning,{warning=numeric(it)},label={Text(tr("Warning","提醒"))},suffix={Text("kn")},modifier=Modifier.weight(1f));OutlinedTextField(alarm,{alarm=numeric(it)},label={Text(tr("Alarm","警报"))},suffix={Text("kn")},modifier=Modifier.weight(1f))};SettingSwitch(tr("Wind shift by default","默认开启风向突变"),"",shiftEnabled){shiftEnabled=it};OutlinedTextField(shift,{shift=numeric(it)},label={Text(tr("Shift threshold","变化阈值"))},suffix={Text("°")},modifier=Modifier.fillMaxWidth());SettingSwitch(tr("Allow apparent-wind fallback","允许视风速回退"),tr("True wind is preferred and the UI always labels the source.","优先使用真风速，界面始终标明实际来源。"),state.settings.allowApparentWindFallback){vm.updateSettings(state.settings.copy(allowApparentWindFallback=it))};Button({vm.updateSettings(state.settings.copy(defaultDepthGuardEnabled=depthEnabled,defaultShallowDepthMeters=shallowValue!!,defaultDeepDepthEnabled=deepEnabled,defaultDeepDepthMeters=deepValue!!,defaultWindGuardEnabled=windEnabled,defaultWindWarningKnots=warningValue!!,defaultWindAlarmKnots=alarmValue!!,defaultWindShiftEnabled=shiftEnabled,defaultWindShiftDegrees=shiftValue!!))},Modifier.fillMaxWidth(),enabled=hasDiff){Text(tr("Save condition defaults","保存环境警戒默认值"))}}}
}

@Composable private fun DepthSounderPage(state:MainUiState,vm:MainViewModel){
 var offset by remember(state.settings.sounderOffsetMeters){mutableStateOf(state.settings.sounderOffsetMeters.toString())};var saved by remember{mutableStateOf(false)};val value=offset.toDoubleOrNull();val valid=value!=null&&value in -20.0..20.0;val dirty=valid&&value!=state.settings.sounderOffsetMeters
 LaunchedEffect(dirty){if(dirty)saved=false}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(tr("Simple depth calibration","简化水深校准"),style=MaterialTheme.typography.titleMedium);Text(tr("Boat Watch displays and maps: raw instrument depth + NMEA offset + your fixed offset.","Boat Watch 显示并绘制：仪器原始水深 + NMEA 自带 offset + 你的固定 offset。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("LATEST DEPTH PROVENANCE","最近水深来源"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(tr("Raw instrument","仪器原始值"),depthMeters(state.sonarRecorder.lastRawDepthMeters));Metric(tr("NMEA offset","NMEA offset"),signedDepthMeters(state.sonarRecorder.lastNmeaOffsetMeters))}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(tr("User offset","用户 offset"),signedDepthMeters(state.sonarRecorder.lastUserOffsetMeters?:state.settings.sounderOffsetMeters));Metric(tr("Final depth","最终水深"),depthMeters(state.sonarRecorder.lastMeasuredDepthMeters))}
  OutlinedTextField(offset,{text->offset=text.filterIndexed{index,c->c.isDigit()||c=='.'||(c=='-'&&index==0)}},label={Text(tr("Depth offset","水深 offset"))},prefix={Text(if((value?:0.0)>=0)"+" else "")},suffix={Text(tr("m","米"))},supportingText={Text(tr("Example: instrument 6.0 m, offset +0.4 m → Boat Watch 6.4 m.","例如：仪器显示 6.0 米，offset 为 +0.4 米 → Boat Watch 显示 6.4 米。"))},isError=!valid,modifier=Modifier.fillMaxWidth(),enabled=state.activeSonarSurvey==null)
  Text(tr("GPS only places the sounding on the map. Its accuracy remains quality metadata and never changes the depth number.","GPS 只负责把测深点放到地图上；定位精度仅作为质量信息，不会修改水深数值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(state.activeSonarSurvey!=null)Text(tr("Stop and save the active sonar survey before changing its depth calibration.","请先停止并保存当前声呐调查，再修改本次测深校准。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
  Button({vm.updateSettings(state.settings.copy(sounderOffsetMeters=value!!));saved=true},Modifier.fillMaxWidth(),enabled=dirty&&state.activeSonarSurvey==null){Icon(Icons.Default.Save,null);Spacer(Modifier.width(6.dp));Text(if(saved)tr("Saved","已保存")else tr("Save offset","保存 offset"))}
 }}
}

@Composable private fun MapDepthSettingsPage(state:MainUiState,vm:MainViewModel){
 val offlineImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.importOfflineMap(uri)}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(tr("Chart files & storage","海图文件与存储"),style=MaterialTheme.typography.titleMedium)
   Text(tr("This page manages files only. Choose the base map, nautical source, overlays and current-position depth readouts from Map → Layers.","本页只管理文件。底图、航海图来源、叠加层和当前位置水深读数请在“地图 → 图层”中设置。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(tr("Imported nautical chart","已导入航海图"),style=MaterialTheme.typography.titleMedium)
   if(state.offlineMap.installed){
    Text("${state.offlineMap.name?:tr("User chart","用户海图")} · ${state.offlineMap.format?.uppercase()?:"Raster"} · z${state.offlineMap.minZoom?:"?"}–${state.offlineMap.maxZoom?:"?"} · ${humanBytes(state.offlineMap.sizeBytes)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    state.offlineMap.description?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
   }
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({offlineImport.launch(arrayOf("application/vnd.sqlite3","application/x-sqlite3","application/octet-stream","*/*"))}){Icon(Icons.Default.FileOpen,null);Spacer(Modifier.width(6.dp));Text(if(state.offlineMap.installed)tr("Replace MBTiles","替换 MBTiles")else tr("Import MBTiles","导入 MBTiles"))};if(state.offlineMap.installed)TextButton(vm::removeOfflineMap){Icon(Icons.Default.DeleteOutline,null);Spacer(Modifier.width(4.dp));Text(tr("Remove","删除"))}}
   Text(tr("Importing or replacing a file does not change the visible map. Select Imported MBTiles in Map → Layers when you want to use it; uncovered tiles always fall back to the default online nautical view. Google tiles are never cached.","导入或替换文件不会改变当前地图。需要使用时，请在“地图 → 图层”选择“已导入 MBTiles”；未覆盖瓦片始终回退到默认在线航海图。Google 瓦片绝不会被缓存。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
 }
}

@Composable private fun VesselProfileCard(state:MainUiState,vm:MainViewModel){
 var boat by remember(state.settings.boatLengthMeters){mutableStateOf(state.settings.boatLengthMeters.toString())}
 var draft by remember(state.vesselSettings.draftMeters){mutableStateOf(state.vesselSettings.draftMeters?.toString()?:"0")}
 var bow by remember(state.settings.bowRollerHeightMeters){mutableStateOf(state.settings.bowRollerHeightMeters.toString())}
 var antenna by remember(state.settings.nmeaGpsAntennaToBowMeters){mutableStateOf(state.settings.nmeaGpsAntennaToBowMeters.toString())}
 var savedFeedback by remember{mutableStateOf(false)}
 fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
 val valid=listOf(boat,draft,bow,antenna).all{it.toDoubleOrNull()!=null}
 val draftValue=draft.toDoubleOrNull()?.takeIf{it>0};val dirty=valid&&(boat.toDouble()!=state.settings.boatLengthMeters||draftValue!=state.vesselSettings.draftMeters||bow.toDouble()!=state.settings.bowRollerHeightMeters||antenna.toDouble()!=state.settings.nmeaGpsAntennaToBowMeters)
 LaunchedEffect(dirty){if(dirty)savedFeedback=false}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(tr("Vessel profile","船舶资料"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));when{!valid->AssistChip({},label={Text(tr("Invalid values","数值无效"))});dirty->AssistChip({},label={Text(tr("Unsaved changes","尚未保存"))});savedFeedback->AssistChip({},label={Text(tr("Saved","已保存"))})}};Text(tr("Defaults for new anchor setups. Draft is used only for Trip Watch UKC; enter 0 when it is unknown. System GPS does not assume a fixed antenna position.","用于新锚泊设置。吃水只用于航程监控的龙骨下余量；未知时填 0。系统 GPS 不假设手机有固定安装位置。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(boat,{boat=numeric(it)},label={Text(tr("Boat length","船长"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(draft,{draft=numeric(it)},label={Text(tr("Vessel draft for UKC","用于 UKC 的船舶吃水"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(bow,{bow=numeric(it)},label={Text(tr("Bow roller height","船艏滚轮高度"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(antenna,{antenna=numeric(it)},label={Text(tr("NMEA GPS antenna to bow roller","NMEA GPS 天线到船艏滚轮距离"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());Button({vm.updateSettings(state.settings.copy(boatLengthMeters=boat.toDouble(),bowRollerHeightMeters=bow.toDouble(),nmeaGpsAntennaToBowMeters=antenna.toDouble()));vm.updateVesselDataSettings(state.vesselSettings.copy(draftMeters=draftValue));savedFeedback=true},Modifier.fillMaxWidth(),enabled=dirty){Icon(Icons.Default.Save,null);Spacer(Modifier.width(6.dp));Text(tr("Save changes","保存修改"))}}}
}

@Composable private fun PhoneVesselSensorsCard(state:MainUiState,vm:MainViewModel){
 DisposableEffect(Unit){vm.setPhoneHeadingDisplayActive(true);onDispose{vm.setPhoneHeadingDisplayActive(false)}}
 val caps=state.phoneSensorCapabilities
 val alignmentReady=state.vesselMountCalibration.headingAligned
 val productionReady=PhoneVesselOutputReadinessPolicy.evaluate(state.vesselMountCalibration,state.phoneVesselMountState).ready
 val now=android.os.SystemClock.elapsedRealtime()
 val phoneFresh=state.phoneHeading.receivedElapsedRealtime?.let{now-it in 0L..2_000L}==true
 val phoneHeading=state.phoneHeading.liveTrueHeadingDegrees?:state.phoneHeading.liveMagneticHeadingDegrees
 val phoneReferenceTrue=state.phoneHeading.liveTrueHeadingDegrees!=null
 val nmeaTrue=state.nmeaInstruments.headingTrue?.takeIf{now-it.second in 0L..3_000L}?.first
 val nmeaMagnetic=state.nmeaInstruments.headingMagnetic?.takeIf{now-it.second in 0L..3_000L}?.first
 val nmeaMatch=if(phoneFresh)PhoneHeadingAlignmentPolicy.matchLiveReference(state.phoneHeading.liveTrueHeadingDegrees,state.phoneHeading.liveMagneticHeadingDegrees,nmeaTrue,nmeaMagnetic)else null
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(tr("Phone vessel sensors","手机船舶传感器"),style=MaterialTheme.typography.titleMedium)
  Text(tr("Phone capabilities are independent. GNSS and pressure need no vessel mount. Vessel heading can be realigned here whenever the phone orientation changes. Sailing attitude is configured only inside Trip Watch.","手机的各项能力彼此独立：GNSS 和气压不需要固定安装；手机方向改变后，可随时在这里重新对齐船首向；航行姿态仅在 Trip Watch 内设置。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("Rotation ${if(caps.attitudeAvailable)"available" else "unavailable"} · Gyro ${if(caps.gyroAvailable)"available" else "unavailable"} · Pressure ${if(caps.pressureAvailable)"available" else "unavailable"}","旋转 ${if(caps.attitudeAvailable)"可用" else "不可用"} · 陀螺仪 ${if(caps.gyroAvailable)"可用" else "不可用"} · 气压 ${if(caps.pressureAvailable)"可用" else "不可用"}"),style=MaterialTheme.typography.bodySmall)
  HorizontalDivider()
  PhoneSensorStep(1,alignmentReady,tr("Align phone direction to the vessel bow","对齐手机与船艏方向"),tr("Point the top edge of the phone toward the bow, then tap Align now. This replaces the previous alignment and may be repeated at any time.","将手机顶部指向船艏，然后点击“现在对齐”。它会替换上一次结果，并且可以随时重复操作。"))
  Button(vm::alignPhoneHeadingToBow,enabled=phoneFresh&&phoneHeading!=null,modifier=Modifier.fillMaxWidth().testTag("phone_sensor_confirm_heading")){Icon(Icons.Default.Navigation,null);Spacer(Modifier.width(6.dp));Text(if(alignmentReady)tr("Realign now","现在重新对齐")else tr("Align now","现在对齐"))}
  if(!phoneFresh||phoneHeading==null)Text(tr("Waiting for a live phone compass reading…","正在等待手机罗盘实时数据……"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(nmeaMatch!=null)OutlinedButton(vm::alignPhoneHeadingToNmea,Modifier.fillMaxWidth().testTag("phone_sensor_align_nmea")){Icon(Icons.Default.Sync,null);Spacer(Modifier.width(6.dp));Text(tr("Match live NMEA vessel heading","匹配实时 NMEA 船首向"))}
  if(nmeaMatch!=null)Text(tr("Use this when the phone is fixed at an angle: Boat Watch calculates the correction from simultaneous Phone and NMEA headings with the same north reference.","手机以一定角度固定时可使用此项：Boat Watch 会用采用相同北向基准的手机与 NMEA 实时艏向自动计算修正。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  HorizontalDivider()
  Surface(color=if(productionReady)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.testTag("phone_sensor_readiness")){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(productionReady)tr("HEADING READY · REALIGN ANY TIME","船首向已就绪 · 可随时重新对齐")else tr("HEADING ALIGNMENT REQUIRED","需要对齐船首向"),fontWeight=FontWeight.Bold);Text(if(productionReady)tr("Phone/App sharing may publish the aligned vessel heading. If the phone is rotated independently, return here and realign; GNSS and pressure are unaffected.","手机 / App 分享可发布对齐后的船首向。若手机被单独旋转，请回到这里重新对齐；GNSS 与气压不受影响。")else tr("Point the phone toward the bow and tap Align now before sharing vessel heading. No numeric offset is required.","请先把手机指向船艏并点击“现在对齐”，再分享船首向；不需要填写任何角度偏差。"),style=MaterialTheme.typography.bodySmall)}}
  Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(tr("Sailing attitude belongs to Trip Watch","航行姿态属于 Trip Watch"),fontWeight=FontWeight.SemiBold);Text(tr("When starting or running a trip, place the phone plane parallel to the vessel, select the bow-facing edge and confirm. Current heel is retained, not zeroed. Pause attitude manually before moving the phone, then place it back and confirm.","开始或进行航程时，将手机平面与船体平行、选择指向船艏的一边并确认。当前横倾会被保留而不是归零；移动手机前请主动暂停姿态，放回后再确认。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  Text(tr("Live verification","实时验证"),fontWeight=FontWeight.SemiBold)
  val alignedHeading=phoneHeading?.let{((it+state.vesselMountCalibration.headingAlignmentOffsetDegrees)%360.0+360.0)%360.0}
  val reference=if(phoneReferenceTrue)"T" else "M"
  Text("${tr("Device direction","手机方向")} ${phoneHeading?.let{"%03.0f°$reference".format(it)}?:"—"} · ${tr("Vessel heading","船首向")} ${alignedHeading?.takeIf{alignmentReady}?.let{"%03.0f°$reference".format(it)}?:"—"}",style=MaterialTheme.typography.bodyMedium)
  Text("${tr("Pressure","气压")} ${state.vesselData.pressureHpa.value?.let{"%.1f hPa".format(it)}?:"—"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  state.vesselCalibrationFeedback?.let{feedback->AssistChip(vm::clearVesselCalibrationFeedback,label={Text(feedback)})}
 }}
}

@Composable private fun PhoneSensorStep(number:Int,complete:Boolean,title:String,detail:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top){AssistChip({},label={Text(if(complete)"✓" else number.toString())},enabled=false);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Suppress("DEPRECATION")
@Composable internal fun DataOutputSettingsPage(state:MainUiState,vm:MainViewModel){
 var product by rememberSaveable{mutableStateOf<String?>(null)}
 when(product){
  "boat"->PhoneAppBoatOutputPage(state,vm){product=null}
  "local"->LocalNmeaServicePage(state,vm){product=null}
  else->NmeaProductsOverview(state,onBoat={product="boat"},onLocal={product="local"},onStopAll=vm::stopAllNmeaSharing)
 }
}

@Composable private fun NmeaProductsOverview(state:MainUiState,onBoat:()->Unit,onLocal:()->Unit,onStopAll:()->Unit){
 val anySharingActive=state.outputSettings.publicationEnabled||state.phonePositionOutputStatus.enabled||state.localNmeaServerSettings.serverRequested||state.nmeaSharing.state!=com.yokuli.anchorwatch.data.sharing.SharingServerState.STOPPED
 LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("nmea_products_overview"),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{PageHeader(tr("NMEA sharing","NMEA 数据共享"),tr("Choose the direction first. These are two independent products and may run at the same time.","先选择数据流向。这是两个完全独立的功能，也可以同时运行。"))}
  item{ElevatedCard(onClick=onBoat,modifier=Modifier.fillMaxWidth().testTag("nmea_product_boat_network")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.Upload,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text(tr("Send Phone/App data to the boat network","向船载网络发送手机 / App 数据"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text(tr("Phone → boat gateway","手机 → 船载网关"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)};Icon(Icons.Default.ChevronRight,null)}
	   Text(tr("Continuously writes every valid Phone/App-owned measurement into the NMEA network. It never repeats Boat input; receiving instruments choose their preferred source. Enter the gateway address and the App handles connection reuse automatically.","把每个有效的手机 / App 自有测量值持续写入 NMEA 船网；绝不重复船载输入，由接收端仪表选择来源。填写网关地址后，App 会自动处理连接复用。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   AssistChip({},label={Text(if(state.phonePositionOutputStatus.enabled)tr("Running","运行中")else tr("Stopped","已停止"))},leadingIcon={Icon(if(state.phonePositionOutputStatus.enabled)Icons.Default.CheckCircle else Icons.Default.StopCircle,null)})
  }}}
  item{ElevatedCard(onClick=onLocal,modifier=Modifier.fillMaxWidth().testTag("nmea_product_phone_server")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.WifiTethering,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text(tr("Host an NMEA service on this phone","在本机提供 NMEA 服务"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text(tr("This phone → connected client devices","本手机 → 已连接的客户端设备"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)};Icon(Icons.Default.ChevronRight,null)}
   Text(tr("Opens a listening TCP server for another phone, tablet or dashboard App. It does not connect to the boat gateway and does not depend on NMEA input.","在手机上开启 TCP 监听服务，供另一台手机、平板或仪表 App 连接。它不会连接船载网关，也不依赖 NMEA 输入。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   AssistChip({},label={Text(if(state.localNmeaServerRuntime.requested)tr("Running · ${state.nmeaSharing.clientCount} clients","运行中 · ${state.nmeaSharing.clientCount} 个客户端")else tr("Stopped","已停止"))},leadingIcon={Icon(if(state.localNmeaServerRuntime.requested)Icons.Default.CheckCircle else Icons.Default.StopCircle,null)})
  }}}
	  item{Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Shared source, separate ownership: both use the same Phone/App data rules, but each has its own configuration, Start/Stop, errors and diagnostics. Internal connection reuse is automatic.","数据规则共享、运行权完全分离：两者使用相同的手机 / App 数据边界，但各自拥有独立配置、启动 / 停止、错误和诊断；内部连接复用由 App 自动完成。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)}}
  if(anySharingActive)item{OutlinedButton(onStopAll,Modifier.fillMaxWidth().testTag("stop_all_nmea_sharing"),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text(tr("Emergency stop · both sharing products","紧急停止 · 两种 NMEA 分享全部关闭"))}}
 }
}

@Suppress("DEPRECATION")
@Composable private fun PhoneAppBoatOutputPage(state:MainUiState,vm:MainViewModel,back:()->Unit){
 val automaticOutput=NmeaOutputEndpointPolicy.automatic(state.outputSettings,state.settings.profile)
 val writable=state.settings.profile.protocol==Protocol.TCP&&state.connection in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)
 val outputReady=automaticOutput.transportConfigured&&when(automaticOutput.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->writable;NmeaOutputTransportMode.TCP_SERVER->false;NmeaOutputTransportMode.DEDICATED_TCP,NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->automaticOutput.outputHost.isNotBlank()&&automaticOutput.outputPort in 1..65535}
 val outputRequested=state.outputSettings.publicationEnabled
 val outputActive=state.phonePositionOutputStatus.enabled
 val outputTransitioning=outputRequested!=outputActive||state.phonePositionOutputStatus.connectionState==com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.STOPPING
 val outputLocked=outputRequested||outputActive||outputTransitioning
 val headingAlignmentReady=PhoneVesselOutputReadinessPolicy.evaluate(state.vesselMountCalibration,state.phoneVesselMountState).ready
 var routeMode by remember(automaticOutput.transportMode){mutableStateOf(automaticOutput.transportMode.takeIf{it in setOf(NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST)}?:NmeaOutputTransportMode.DEDICATED_TCP)}
 val savedOutputEndpoint=NmeaOutputEndpointPolicy.resolved(automaticOutput,state.settings.profile)
 var host by remember(savedOutputEndpoint.first,state.settings.profile.host){mutableStateOf(savedOutputEndpoint.first.ifBlank{state.settings.profile.host})}
 var port by remember(savedOutputEndpoint.second,state.settings.profile.port){mutableStateOf((savedOutputEndpoint.second.takeIf{it in 1..65535}?:state.settings.profile.port.takeIf{it in 1..65535}?:10110).toString())}
 var testResult by remember{mutableStateOf<String?>(null)}
 var confirmHeadingDiagnostic by remember{mutableStateOf(false)}
 var showPublisherDiagnostics by rememberSaveable{mutableStateOf(false)}
 var showAdvancedTransport by rememberSaveable{mutableStateOf(automaticOutput.transportMode in setOf(NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST))}
 var rawPaused by rememberSaveable{mutableStateOf(false)}
 var rawStreamFilter by rememberSaveable{mutableStateOf("")}
 var rawTypeFilter by rememberSaveable{mutableStateOf("")}
 var generatedClearMarker by rememberSaveable{mutableStateOf<String?>(null)}
 var writtenClearMarker by rememberSaveable{mutableStateOf<String?>(null)}
 var pausedGenerated by remember{mutableStateOf<List<String>>(emptyList())}
 var pausedWritten by remember{mutableStateOf<List<String>>(emptyList())}
 val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
 val txNow by produceState(android.os.SystemClock.elapsedRealtime()){
  while(true){kotlinx.coroutines.delay(1_000L);value=android.os.SystemClock.elapsedRealtime()}
 }
 fun txAge(value:Long?)=value?.let{"%.1fs".format((txNow-it).coerceAtLeast(0L)/1_000.0)}?:"—"
 val testWrittenMessage=tr("Test written to the socket · server receipt is not confirmed","测试语句已写入 Socket · 尚未确认服务器是否收到")
 val testFailedMessage=tr("Test write failed — check status below","测试发送失败，请查看下方状态")
 LazyColumn(Modifier.fillMaxSize().testTag("nmea_output_list").padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Filled.ArrowBack,tr("Back","返回"))};Column{Text(tr("Send Phone/App data","发送手机 / App 数据"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(tr("Phone/App → boat network","手机 / App → 船载网络"),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
  item{Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(tr("Share Phone/App vessel data","分享手机 / App 船舶数据"),style=MaterialTheme.typography.titleMedium)
   Text(tr("Every valid value calculated by this phone or Boat Watch is published continuously at a stable 1 Hz. Boat instruments decide which source to display; Boat data received by the App is never copied back. Returned converter echoes remain quarantined.","本手机或 Boat Watch 能计算出的每个有效值都会以稳定的 1 Hz 持续发布。由船载仪表决定显示哪个来源；App 接收到的船载数据绝不会被复制回去，转换器回显仍会被隔离。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   val phoneFrameReady=PhoneVesselOutputReadinessPolicy.evaluate(state.vesselMountCalibration,state.phoneVesselMountState).ready
   Surface(color=if(phoneFrameReady)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Text(if(phoneFrameReady)tr("Phone vessel heading is aligned and can be realigned at any time. GNSS and pressure are independent; Motion appears only during a confirmed Trip attitude segment.","手机船首向已对齐，并可随时重新对齐。GNSS 与气压独立工作；只有 Trip Watch 中确认了有效姿态段时才会发送运动数据。")else tr("Sharing is locked until Settings → Phone vessel sensors aligns the phone to the bow. No numeric offset or fixed attitude mount is required.","在“设置 → 手机船舶传感器”完成手机与船艏方向对齐前，分享保持锁定；不需要填写角度偏差，也不要求固定姿态安装。"),Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.bodySmall)}
   Text(tr("What will be shared","将分享的数据"),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
   Text(tr("Published whenever the local value is valid: Phone GNSS, aligned Phone heading, Phone barometer, Trip-confirmed Phone motion, and true wind computed by Boat Watch. Never republished: Boat depth, STW, direct Boat wind, Boat position or Boat heading.","本机值有效时即持续发布：手机 GNSS、已对齐的手机船首向、手机气压、Trip Watch 中确认有效的手机运动，以及 Boat Watch 推算的真风。绝不重新发布：船载水深、对水航速、船载直接风、船载船位或船载船首向。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("Output never auto-starts. Press Start below for each App run so a fragile single-client gateway is not occupied unexpectedly.","输出绝不会自动启动。每次 App 运行都必须在下方明确点击启动，避免意外占用脆弱的单客户端网关。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
  }}}
	  item{Card(Modifier.testTag("nmea_output_route")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
	   Text(tr("Boat Gateway","船载网关"),style=MaterialTheme.typography.titleMedium)
	   if(!automaticOutput.transportConfigured)Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Enter the boat gateway address that receives App/phone NMEA. Saving it does not start output.","请输入用于接收 App / 手机 NMEA 的船载网关地址；保存地址不会自动开始发送。"),Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.bodySmall)}
	   Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
	    Text(tr("Connection direction","连接方向"),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
	    Text("Server → App  (RX)  · ${state.settings.profile.protocol} · ${state.settings.profile.host}:${state.settings.profile.port}",style=MaterialTheme.typography.bodySmall)
	    Text("App → Boat network  (TX) · ${outputTransportLabel(automaticOutput.transportMode)} · ${savedOutputEndpoint.first}:${savedOutputEndpoint.second}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
	   }}
	   FilterChip(routeMode==NmeaOutputTransportMode.DEDICATED_TCP,{routeMode=NmeaOutputTransportMode.DEDICATED_TCP},label={Text(tr("TCP output","TCP 输出"))},enabled=!outputLocked,modifier=Modifier.fillMaxWidth())
	   Text(tr("Enter the gateway's receive address. If RX and TX resolve to the same TCP host and port, Boat Watch automatically uses its existing connection; otherwise it creates one independent write-only TX connection.","填写网关的接收地址。若 RX 与 TX 是同一个 TCP 主机和端口，Boat Watch 会自动使用已有连接；地址不同时才建立一条独立的只写 TX 连接。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
	   TextButton({showAdvancedTransport=!showAdvancedTransport},Modifier.align(Alignment.End)){Text(if(showAdvancedTransport)tr("Hide advanced transport","收起高级传输")else tr("Advanced transport","高级传输"))}
	   if(showAdvancedTransport){
	    Text(tr("UDP is connectionless, so a successful send cannot prove that a receiver consumed the data.","UDP 没有连接确认，因此发送成功不能证明接收端已经处理数据。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
	    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
	     FilterChip(routeMode==NmeaOutputTransportMode.UDP_UNICAST,{routeMode=NmeaOutputTransportMode.UDP_UNICAST},label={Text(tr("Advanced · UDP unicast","高级 · UDP 单播"))},enabled=!outputLocked,modifier=Modifier.fillMaxWidth())
	     FilterChip(routeMode==NmeaOutputTransportMode.UDP_BROADCAST,{routeMode=NmeaOutputTransportMode.UDP_BROADCAST;if(host.isBlank())host="255.255.255.255"},label={Text(tr("Advanced · UDP broadcast","高级 · UDP 广播"))},enabled=!outputLocked,modifier=Modifier.fillMaxWidth())
	    }
	   }
	   val parsedPort=port.toIntOrNull();val routeValid=host.trim().isNotBlank()&&parsedPort in 1..65535
	   val candidate=if(routeMode==NmeaOutputTransportMode.DEDICATED_TCP)NmeaOutputEndpointPolicy.tcpDestination(automaticOutput,state.settings.profile,host,parsedPort?:0)else automaticOutput.copy(transportMode=routeMode,outputHost=host.trim(),outputPort=parsedPort?:0)
	   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(host,{host=it},Modifier.weight(1f).testTag("nmea_output_tx_host"),singleLine=true,enabled=!outputLocked,label={Text(tr("TX host *","发送主机 *"))},isError=host.isBlank());OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5)},Modifier.width(110.dp).testTag("nmea_output_tx_port"),singleLine=true,enabled=!outputLocked,label={Text(tr("TX port *","发送端口 *"))},isError=parsedPort !in 1..65535,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))}
	   TextButton({host=state.settings.profile.host;port=state.settings.profile.port.takeIf{it in 1..65535}?.toString().orEmpty()},enabled=!outputLocked&&state.settings.profile.host.isNotBlank()&&state.settings.profile.port in 1..65535){Text(tr("Use input address","使用输入地址"))}
	   Button({vm.setNmeaOutputEndpoint(routeMode,host,parsedPort?:0)},Modifier.fillMaxWidth(),enabled=!outputLocked&&routeValid&&(!automaticOutput.transportConfigured||candidate.transportMode!=automaticOutput.transportMode||candidate.outputHost.trim()!=automaticOutput.outputHost.trim()||candidate.outputPort!=automaticOutput.outputPort)){Text(tr("Save output destination","保存输出地址"))}
	   Text(tr("Provenance firewall: no route forwards or reformats direct Boat input. Only Phone sensor evidence and explicit Boat Watch APP_DERIVED values may be published; transmitted echoes remain quarantined.","来源防火墙：任何线路都不会转发或改写船载直接输入。只有手机传感器证据和带有 Boat Watch APP_DERIVED 明确来源的推算值才能发布；发送回显仍会被隔离。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
	   OutlinedButton({testResult=null;vm.testNmeaDeviceOutput{success->testResult=if(success)testWrittenMessage else testFailedMessage}},Modifier.fillMaxWidth(),enabled=outputReady&&!outputLocked){Text(tr("Test NMEA output","测试 NMEA 输出"))}
   testResult?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=if(it.contains("failed")||it.contains("失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
  }}}
  item{Card(colors=CardDefaults.cardColors(containerColor=if(outputActive)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
	   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(outputActive)Icons.Default.Sensors else Icons.Default.StopCircle,null,tint=if(outputActive)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(if(outputActive)tr("Phone/App data is being sent","正在发送手机 / App 数据")else tr("Ready to send Phone/App data","可以发送手机 / App 数据"),fontWeight=FontWeight.SemiBold);Text(if(outputActive)tr("Sending to the saved boat gateway destination. Connection reuse is handled automatically.","正在向已保存的船载网关地址发送；连接复用由 App 自动处理。")else tr("Configure the boat destination above. Saving never starts transmission by itself.","请先配置上方船载网关地址；仅保存配置不会自动开始发送。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
   if(outputRequested||outputActive)Button(vm::stopNmeaOutput,Modifier.fillMaxWidth(),enabled=!outputTransitioning,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text(if(outputTransitioning)tr("Stopping…","正在停止…")else tr("Stop sending Phone/App data","停止发送手机 / App 数据"))}
   else Button(vm::startNmeaOutput,Modifier.fillMaxWidth(),enabled=outputReady&&headingAlignmentReady&&!outputTransitioning){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text(tr("Start sharing Phone/App data","开始分享手机 / App 数据"))}
   if(!outputReady)Text(tr("Save a valid output route before starting.","启动前请先保存有效的输出线路。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
   if(!headingAlignmentReady)Text(tr("Heading alignment required: point the phone toward the bow and tap Align now in Settings → Phone vessel sensors. Trip attitude placement is optional and independent.","需要对齐船首向：请在“设置 → 手机船舶传感器”中把手机指向船艏，并点击“现在对齐”。航程姿态放置是可选且独立的。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
   if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(localizeKnownMessage(state.connectionAttempt.message),Modifier.testTag("nmea_output_error"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
  }}}
  item{Card(Modifier.testTag("nmea_output_live_status")){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(tr("Live output status","实时输出状态"),style=MaterialTheme.typography.titleMedium)
	   val configuredEndpoint=NmeaOutputEndpointPolicy.resolved(automaticOutput,state.settings.profile)
	   val visibleMode=if(outputActive||outputRequested)state.phonePositionOutputStatus.mode else automaticOutput.transportMode
   val visibleHost=if(outputActive||outputRequested)state.phonePositionOutputStatus.endpointHost else configuredEndpoint.first
   val visiblePort=if(outputActive||outputRequested)state.phonePositionOutputStatus.endpointPort else configuredEndpoint.second
   Text("${outputTransportLabel(visibleMode)} · $visibleHost:$visiblePort",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
   if(!outputActive&&!outputRequested)Text(tr("Saved route · no live TX socket","已保存线路 · 当前没有 TX Socket"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text("● ${txConnectionLabel(state.phonePositionOutputStatus.connectionState)} · ${tr("last transport TX","最近传输发送")} ${txAge(state.phonePositionOutputStatus.lastWriteElapsed)}",style=MaterialTheme.typography.bodySmall,color=if(state.phonePositionOutputStatus.connectionState==com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.CONNECTED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("Session ${state.phonePositionOutputStatus.sessionId?:"—"} · generation ${state.phonePositionOutputStatus.publicationGeneration}","会话 ${state.phonePositionOutputStatus.sessionId?:"—"} · 发布代次 ${state.phonePositionOutputStatus.publicationGeneration}"),style=MaterialTheme.typography.labelSmall,fontFamily=FontFamily.Monospace)
   Text(tr("${state.phonePositionOutputStatus.writtenSentences} written / ${state.phonePositionOutputStatus.attemptedSentences} attempted / ${state.phonePositionOutputStatus.failedSentences} failed · ${state.phonePositionOutputStatus.bytesWritten} bytes","${state.phonePositionOutputStatus.writtenSentences} 条写入 / ${state.phonePositionOutputStatus.attemptedSentences} 条尝试 / ${state.phonePositionOutputStatus.failedSentences} 条失败 · ${state.phonePositionOutputStatus.bytesWritten} 字节"),style=MaterialTheme.typography.labelSmall)
   Text(tr("${state.phonePositionOutputStatus.droppedBatches} dropped batches · ${state.phonePositionOutputStatus.suppressedDecisions} suppressed decisions","${state.phonePositionOutputStatus.droppedBatches} 个批次丢弃 · ${state.phonePositionOutputStatus.suppressedDecisions} 次抑制决策"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   val pressure=state.phonePositionOutputStatus.backpressureState
   val activeWriteAge=state.phonePositionOutputStatus.activeWriteStartedElapsed?.let{(android.os.SystemClock.elapsedRealtime()-it).coerceAtLeast(0L)}
   Text(
    tr(
     "1 Hz coalesced heartbeat · last write ${state.phonePositionOutputStatus.lastWriteDurationMillis?.let{"${it} ms"}?:"—"} · maximum ${state.phonePositionOutputStatus.maximumWriteDurationMillis} ms",
     "1 Hz 合并心跳 · 上次写入 ${state.phonePositionOutputStatus.lastWriteDurationMillis?.let{"${it} ms"}?:"—"} · 最长 ${state.phonePositionOutputStatus.maximumWriteDurationMillis} ms",
    ),
    style=MaterialTheme.typography.labelSmall,
    color=if(pressure==NmeaWriteBackpressureState.NORMAL)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    modifier=Modifier.testTag("nmea_output_backpressure"),
   )
   if(pressure!=NmeaWriteBackpressureState.NORMAL)Text(
    when(pressure){
     NmeaWriteBackpressureState.NORMAL->""
     NmeaWriteBackpressureState.CONGESTED->tr("Gateway is congested (${activeWriteAge?:0} ms). Only each stream's newest value is retained.","网关正在拥塞（${activeWriteAge?:0} ms），每个数据流只保留最新值。")
     NmeaWriteBackpressureState.STALLED->tr("Write stalled. This transport generation is being stopped once; bounded RX reconnect owns recovery.","写入已卡死。当前传输代次只会被中止一次，随后由有界 RX 重连恢复。")
    },
    style=MaterialTheme.typography.bodySmall,
    color=MaterialTheme.colorScheme.error,
   )
   if(state.phonePositionOutputStatus.backpressureAbortCount>0)Text(tr("Stall aborts ${state.phonePositionOutputStatus.backpressureAbortCount}","卡死中止 ${state.phonePositionOutputStatus.backpressureAbortCount} 次"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(state.outputSettings.anyEnabled)Text("${localizeKnownMessage(state.phonePositionOutputStatus.message)}${state.phonePositionOutputStatus.sentenceTypes.takeIf{it.isNotEmpty()}?.joinToString(prefix=" · ").orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
   state.phonePositionOutputStatus.lastError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
   TextButton({showPublisherDiagnostics=!showPublisherDiagnostics},Modifier.fillMaxWidth()){Icon(if(showPublisherDiagnostics)Icons.Default.ExpandLess else Icons.Default.Troubleshoot,null);Spacer(Modifier.width(6.dp));Text(if(showPublisherDiagnostics)tr("Hide stream diagnostics","收起数据流诊断")else tr("Stream diagnostics","数据流诊断"))}
   if(showPublisherDiagnostics){
    state.phonePositionOutputStatus.streams.filterKeys{it in setOf("POSITION","HEADING","MOTION","PRESSURE","DERIVED_WIND")}.forEach{(stream,status)->
     Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
      Text("${publisherStreamLabel(stream)} · ${publisherReadinessLabel(status.readiness)}",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,color=if(status.readiness in setOf(NmeaStreamReadiness.READY,NmeaStreamReadiness.PUBLISHING))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
      Text(publisherOwnershipLabel(status.ownership),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      Text(tr("Generated ${status.generatedCount} · written ${status.writtenCount} · dropped ${status.droppedCount}","生成 ${status.generatedCount} · 写入 ${status.writtenCount} · 丢弃 ${status.droppedCount}"),style=MaterialTheme.typography.labelSmall)
      Text("${"%.1f".format(status.generatedRateHz)} Hz → ${"%.1f".format(status.socketWriteRateHz)} Hz · seq ${status.lastWrittenSequence}/${status.lastGeneratedSequence}${status.suppressionReason?.let{" · ${publisherSuppressionLabel(it)}"}.orEmpty()}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      Text(tr("Source ${status.sourceStableKey?:"—"} · epoch ${status.sourceEpoch}","来源 ${status.sourceStableKey?:"—"} · 切换代次 ${status.sourceEpoch}"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,fontFamily=FontFamily.Monospace)
     }}
    }
    state.phonePositionOutputStatus.packetPathDiagnostics.takeLast(6).asReversed().forEach{event->
     Text("${event.path} · ${event.stage} · ${event.stream}/${event.sentenceType} · pub ${event.generation}${event.inputTransportGeneration?.let{" · tcp $it"}.orEmpty()}${event.outcome?.let{" · $it"}.orEmpty()}${event.failureReason?.let{" · $it"}.orEmpty()}",style=MaterialTheme.typography.labelSmall,color=if(event.stage==com.yokuli.anchorwatch.data.nmea.output.NmeaPacketStage.DROPPED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,fontFamily=FontFamily.Monospace)
    }
    TextButton({confirmHeadingDiagnostic=true},Modifier.fillMaxWidth(),enabled=outputReady&&!outputLocked){Text(tr("Developer diagnostic · 5 × IIHDG 123.4°","开发诊断 · 发送 5 条 IIHDG 123.4°"))}
   }
   Text(tr("Output ignores Data → Vessel source selection. Boat-source conflicts remain visible for diagnosis, but only eligible Phone/App-owned evidence can enter TX.","输出不采用“数据 → 船舶”的来源选择。船载来源冲突仍可用于诊断，但只有符合条件的手机 / App 自有证据才能进入 TX。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}}
  item{Card(Modifier.testTag("nmea_raw_output")){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(tr("Raw NMEA output","NMEA 原始输出"),style=MaterialTheme.typography.titleMedium)
   Text(tr("Generated sentences are visible even if the gateway write fails. Socket-written history confirms only that Android wrote the bytes, not that the server consumed them.","即使网关写入失败，也能看到已生成语句。Socket 写入记录只证明安卓已写出字节，不能证明服务器已处理。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   val liveGenerated=state.phonePositionOutputStatus.recentGenerated;val liveWritten=state.phonePositionOutputStatus.recentTx
   val sourceGenerated=if(rawPaused)pausedGenerated else liveGenerated;val sourceWritten=if(rawPaused)pausedWritten else liveWritten
   val visibleGenerated=NmeaRawTxConsolePolicy.filter(NmeaRawTxConsolePolicy.afterClearMarker(sourceGenerated,generatedClearMarker),rawStreamFilter,rawTypeFilter)
   val visibleWritten=NmeaRawTxConsolePolicy.filter(NmeaRawTxConsolePolicy.afterClearMarker(sourceWritten,writtenClearMarker),rawStreamFilter,rawTypeFilter)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedTextField(rawStreamFilter,{rawStreamFilter=it.take(24)},Modifier.weight(1f).testTag("raw_tx_stream_filter"),singleLine=true,label={Text(tr("Stream filter","数据流筛选"))},placeholder={Text("HEADING")})
    OutlinedTextField(rawTypeFilter,{rawTypeFilter=it.take(8)},Modifier.weight(1f).testTag("raw_tx_type_filter"),singleLine=true,label={Text(tr("Type filter","语句筛选"))},placeholder={Text("HDT")})
   }
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
    OutlinedButton({if(!rawPaused){pausedGenerated=liveGenerated;pausedWritten=liveWritten};rawPaused=!rawPaused},Modifier.weight(1f).testTag("raw_tx_pause")){Icon(if(rawPaused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(4.dp));Text(if(rawPaused)tr("Resume","继续")else tr("Pause","暂停"))}
    OutlinedButton({generatedClearMarker=liveGenerated.lastOrNull();writtenClearMarker=liveWritten.lastOrNull();pausedGenerated=emptyList();pausedWritten=emptyList()},Modifier.weight(1f).testTag("raw_tx_clear")){Icon(Icons.Default.ClearAll,null);Spacer(Modifier.width(4.dp));Text(tr("Clear UI","清空界面"))}
   }
   if(rawPaused)Text(tr("Display paused · NMEA publication and socket writes continue.","显示已暂停 · NMEA 发布与 Socket 写入仍在继续。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
   Text(tr("Generated / queued","已生成 / 已排队"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   SelectionContainer{Surface(Modifier.fillMaxWidth(),color=Color.Black,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
    if(visibleGenerated.isEmpty())Text(tr("No generated sentence matches the current view.","当前视图没有匹配的已生成语句。"),color=Color.Gray,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
    else visibleGenerated.takeLast(40).asReversed().forEach{line->Text(line,color=Color(0xFFB9F6CA),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)}
   }}}
   TextButton({clipboard.setText(AnnotatedString(visibleGenerated.joinToString("\n")))},Modifier.align(Alignment.End),enabled=visibleGenerated.isNotEmpty()){Icon(Icons.Default.ContentCopy,null);Spacer(Modifier.width(4.dp));Text(tr("Copy generated","复制已生成"))}
   Text(tr("Successfully written to socket","已成功写入 Socket"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   SelectionContainer{Surface(Modifier.fillMaxWidth(),color=Color.Black,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
    if(visibleWritten.isEmpty())Text(tr("No socket-written sentence matches the current view.","当前视图没有匹配的 Socket 写入语句。"),color=Color.Gray,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
    else visibleWritten.takeLast(40).asReversed().forEach{line->Text(line,color=Color(0xFF80CBC4),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)}
   }}}
   TextButton({clipboard.setText(AnnotatedString(visibleWritten.joinToString("\n")))},Modifier.align(Alignment.End),enabled=visibleWritten.isNotEmpty()){Icon(Icons.Default.ContentCopy,null);Spacer(Modifier.width(4.dp));Text(tr("Copy socket writes","复制 Socket 写入"))}
   if(!outputActive&&(state.phonePositionOutputStatus.lastSessionGenerated.isNotEmpty()||state.phonePositionOutputStatus.lastSessionTx.isNotEmpty())){
    HorizontalDivider()
    Text(tr("Last stopped session (history, not live)","上次已停止会话（历史，并非实时）"),style=MaterialTheme.typography.labelLarge)
    Text(tr("Session ${state.phonePositionOutputStatus.lastSessionId?:"—"} · ${state.phonePositionOutputStatus.lastSessionGenerated.size} generated · ${state.phonePositionOutputStatus.lastSessionTx.size} socket-written","会话 ${state.phonePositionOutputStatus.lastSessionId?:"—"} · 生成 ${state.phonePositionOutputStatus.lastSessionGenerated.size} 条 · Socket 写入 ${state.phonePositionOutputStatus.lastSessionTx.size} 条"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }}}
  if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)item{Text(localizeKnownMessage(state.connectionAttempt.message),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
 }
 if(confirmHeadingDiagnostic)AlertDialog(onDismissRequest={confirmHeadingDiagnostic=false},title={Text(tr("Transmit diagnostic heading?","发送诊断船首向？"))},text={Text(tr("This sends five IIHDG sentences claiming 123.4°. Do not run it while an autopilot, heading-dependent display or another vessel heading source is in operational use.","这会发送 5 条声称船首向为 123.4° 的 IIHDG 语句。自动驾驶、依赖船首向的显示设备或其他船首向源正在工作时，绝对不要测试。"))},confirmButton={Button({confirmHeadingDiagnostic=false;testResult=null;vm.testKnownGoodHdgOutput{success->testResult=if(success)testWrittenMessage else testFailedMessage}}){Text(tr("Send 5 test sentences","发送 5 条测试语句"))}},dismissButton={TextButton({confirmHeadingDiagnostic=false}){Text(tr("Cancel","取消"))}})
}

@Suppress("DEPRECATION")
@Composable private fun LocalNmeaServicePage(state:MainUiState,vm:MainViewModel,back:()->Unit){
 val saved=state.localNmeaServerSettings
 val requested=saved.serverRequested
 val server=state.nmeaSharing
 val frameReady=PhoneVesselOutputReadinessPolicy.evaluate(state.vesselMountCalibration,state.phoneVesselMountState).ready
 var port by remember(saved.port){mutableStateOf(saved.port.toString())}
 var includePressure by remember(saved.includePressure){mutableStateOf(saved.includePressure)}
 var includeWind by remember(saved.includeDerivedWind){mutableStateOf(saved.includeDerivedWind)}
 val parsedPort=port.toIntOrNull()
 val changed=parsedPort!=saved.port||includePressure!=saved.includePressure||includeWind!=saved.includeDerivedWind
 val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
 LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("local_nmea_server_page"),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Filled.ArrowBack,tr("Back","返回"))};Column{Text(tr("Phone NMEA service","本机 NMEA 服务"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(tr("This phone sends to clients that connect here","本手机向连接到这里的客户端发送"),color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
  item{Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Default.WifiTethering,null,tint=MaterialTheme.colorScheme.primary);Text(tr("A server, not a boat-network output route","这是服务器，不是船网输出线路"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
   Text(tr("The phone listens on Wi-Fi or hotspot. A second phone, tablet, laptop or NMEA dashboard connects here as a TCP client. Boat NMEA input may be off; starting or stopping this service never touches it.","本机会在 Wi-Fi 或热点网络上监听。另一台手机、平板、电脑或 NMEA 仪表以 TCP 客户端连接到这里。船载 NMEA 输入可以关闭；本服务的启停绝不会影响它。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("It serves only Phone GNSS, aligned Phone vessel heading, Phone pressure, Trip-confirmed motion and explicit App-derived true wind. Boat input is never repeated.","只提供手机 GNSS、已对齐的手机船首向、手机气压、Trip Watch 中确认有效的运动和明确的 App 推算真风；绝不重复船载输入。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}}
  item{Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text(tr("Listener & feed","监听与数据流"),style=MaterialTheme.typography.titleMedium)
   OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5)},Modifier.fillMaxWidth().testTag("local_nmea_server_port"),enabled=!requested,singleLine=true,label={Text(tr("Listening port *","监听端口 *"))},isError=parsedPort !in 1024..65535,supportingText={Text(tr("Use 1024–65535. The default 10111 normally works on a private Wi-Fi or hotspot.","请输入 1024–65535。默认 10111 通常适合私人 Wi-Fi 或手机热点。"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
   SettingSwitch(tr("Include Phone pressure","包含手机气压"),tr("Publishes PHONE_BARO only while the phone sensor is valid","仅在手机气压传感器有效时发布 PHONE_BARO"),includePressure,enabled=!requested){includePressure=it}
   SettingSwitch(tr("Include App-derived true wind","包含 App 推算真风"),tr("Never forwards direct Boat wind sentences","绝不转发船载直接风语句"),includeWind,enabled=!requested){includeWind=it}
   Button({vm.saveLocalNmeaServerConfiguration(parsedPort?:0,includePressure,includeWind)},Modifier.fillMaxWidth(),enabled=!requested&&parsedPort in 1024..65535&&changed){Icon(Icons.Default.Save,null);Spacer(Modifier.width(6.dp));Text(tr("Save service configuration","保存服务配置"))}
   Text(tr("Saving does not open a listener. Start is always an explicit action for the current App process.","保存配置不会打开监听端口；每次 App 进程都必须明确点击启动。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}}
  item{Card(colors=CardDefaults.cardColors(containerColor=if(requested)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   val stateLabel=when(server.state){com.yokuli.anchorwatch.data.sharing.SharingServerState.STOPPED->tr("Stopped","已停止");com.yokuli.anchorwatch.data.sharing.SharingServerState.STARTING->tr("Starting listener…","正在启动监听…");com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING->tr("Listening","正在监听");com.yokuli.anchorwatch.data.sharing.SharingServerState.ERROR->tr("Listener error · retrying","监听错误 · 正在重试")}
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(if(server.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING)Icons.Default.CheckCircle else Icons.Default.WifiTetheringError,null,tint=if(server.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Column(Modifier.weight(1f)){Text(stateLabel,fontWeight=FontWeight.SemiBold);Text(tr("${server.clientCount} connected clients · generation ${state.localNmeaServerRuntime.generation}","${server.clientCount} 个客户端已连接 · 代次 ${state.localNmeaServerRuntime.generation}"),style=MaterialTheme.typography.bodySmall)}}
   if(requested){
    state.nmeaSharing.addresses.forEach{address->val endpoint="tcp://${if(':' in address)"[$address]" else address}:${saved.port}";Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(endpoint,Modifier.weight(1f),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall);IconButton({clipboard.setText(AnnotatedString(endpoint))}){Icon(Icons.Default.ContentCopy,tr("Copy","复制"))}}}
    Text(tr("The listener stays up with zero clients. A client disconnect affects only that client and never stops the service.","即使没有客户端，监听服务也会保持运行。客户端断开只影响该客户端，绝不会停止整个服务。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Button(vm::stopLocalNmeaServer,Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text(tr("Stop Phone NMEA service","停止本机 NMEA 服务"))}
   }else Button(vm::startLocalNmeaServer,Modifier.fillMaxWidth(),enabled=frameReady&&parsedPort in 1024..65535&&!changed){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text(tr("Start Phone NMEA service","启动本机 NMEA 服务"))}
   if(!frameReady)Text(tr("Align the phone to the bow in Settings → Phone vessel sensors before starting. You can repeat the alignment whenever needed. Trip attitude movement never stops this listener; only the optional Motion stream pauses.","启动前请在“设置 → 手机船舶传感器”把手机与船艏方向对齐；以后可按需随时重新对齐。航程姿态移动绝不会停止监听；只会暂停可选的运动数据流。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
   server.message.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=if(server.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
  }}}
  item{Card{Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(tr("Service data & diagnostics","服务数据与诊断"),style=MaterialTheme.typography.titleMedium)
   Text(tr("${state.localNmeaServerRuntime.generatedSentences} generated · ${state.localNmeaServerRuntime.queuedSentences} queued to clients · ${server.sentSentences} flushed","已生成 ${state.localNmeaServerRuntime.generatedSentences} 条 · 向客户端排队 ${state.localNmeaServerRuntime.queuedSentences} 条 · 已写出 ${server.sentSentences} 条"),style=MaterialTheme.typography.bodySmall)
   state.localNmeaServerRuntime.suppressedStreams.forEach{(stream,reason)->Text("${publisherStreamLabel(stream)} · ${publisherSuppressionLabel(reason)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)}
   Text(tr("Generated on phone","本机已生成"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   SelectionContainer{Surface(Modifier.fillMaxWidth(),color=Color.Black,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp)){if(state.localNmeaServerRuntime.recentGenerated.isEmpty())Text(tr("No local sentence generated yet.","尚未生成本机语句。"),color=Color.Gray,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)else state.localNmeaServerRuntime.recentGenerated.takeLast(30).asReversed().forEach{Text(it,color=Color(0xFFB9F6CA),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)}}}}
   Text(tr("Flushed to connected clients","已写入已连接客户端"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   SelectionContainer{Surface(Modifier.fillMaxWidth(),color=Color.Black,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp)){if(server.recentWritten.isEmpty())Text(tr("No client has received a sentence in this session.","本次会话尚未向客户端写出语句。"),color=Color.Gray,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)else server.recentWritten.takeLast(30).asReversed().forEach{Text(it,color=Color(0xFF80CBC4),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelSmall)}}}}
  }}}
  if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)item{Text(localizeKnownMessage(state.connectionAttempt.message),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
 }
}

@Composable private fun outputTransportLabel(value:NmeaOutputTransportMode)=when(value){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,NmeaOutputTransportMode.DEDICATED_TCP->tr("TCP output","TCP 输出");NmeaOutputTransportMode.TCP_SERVER->tr("TCP server","TCP 服务器");NmeaOutputTransportMode.UDP_UNICAST->tr("UDP unicast","UDP 单播");NmeaOutputTransportMode.UDP_BROADCAST->tr("UDP broadcast","UDP 广播")}

@Composable private fun txConnectionLabel(value:com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState)=when(value){
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.OFF->tr("Off","关闭")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.STOPPING->tr("Stopping","正在停止")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.DISCONNECTED->tr("Disconnected","未连接")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.CONNECTING->tr("Connecting","连接中")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.CONNECTED->tr("Connected","已连接")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.ERROR->tr("Connection error","连接错误")
}
@Composable private fun publisherStreamLabel(value:String)=when(value){"POSITION"->tr("Phone position","手机船位");"HEADING"->tr("Phone vessel heading","手机船首向");"MOTION"->tr("Phone vessel motion","手机船体运动");"PRESSURE"->tr("Phone pressure","手机气压");"DERIVED_WIND"->tr("App-derived true wind","App 推算真风");else->tr("Sensor status","传感器状态")}
@Composable private fun publisherReadinessLabel(value:NmeaStreamReadiness)=when(value){NmeaStreamReadiness.READY->tr("Ready","已就绪");NmeaStreamReadiness.WAITING_CALIBRATION->tr("Waiting calibration","等待校准");NmeaStreamReadiness.WAITING_POSITION->tr("Waiting position","等待定位");NmeaStreamReadiness.STANDBY->tr("Waiting for a valid local value","等待有效本机数据");NmeaStreamReadiness.PUBLISHING->tr("Publishing","正在发布")}
@Composable private fun publisherOwnershipLabel(value:com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState)=when(value){
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT->tr("Legacy standby state","旧版备用状态")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.TAKEOVER_PENDING->tr("Legacy takeover state","旧版接管状态")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.PHONE_ACTIVE->tr("Phone/App source active","手机 / App 来源已启用")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.SUPPRESSED->tr("Suppressed","已抑制")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.SOURCE_CONFLICT->tr("Duplicate/conflict","重复或冲突")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.ERROR->tr("Error","错误")
}
@Composable private fun publisherSuppressionLabel(value:String)=when(value){
 "USER_DISABLED"->tr("disabled by user","用户已关闭");"EXTERNAL_SOURCE_PRESENT","BOAT_SOURCE_PRESENT","TAKEOVER_DELAY","BOAT_SOURCE_LOSS_HOLD","BOAT_SOURCE_HANDOVER"->tr("legacy source-selection state (not used by this publisher)","旧版来源选择状态（当前发布器不再使用）");"PHONE_NOT_MOUNTED"->tr("Trip attitude paused by user","用户已暂停航程姿态");"HEADING_NOT_ALIGNED"->tr("heading alignment not confirmed","尚未确认艏向对齐");"MOUNT_SUSPECT"->tr("previous attitude frame invalid","上一个姿态参考已失效");"NO_DECLINATION_REFERENCE"->tr("no declination reference","缺少磁偏角参考");"PHONE_HEADING_STALE"->tr("phone heading stale","手机方位已过期");"PHONE_GPS_STALE"->tr("phone GPS stale","手机 GPS 已过期");"NO_APP_DERIVED_WIND","NO_DERIVED_WIND"->tr("App-derived wind unavailable","App 暂时无法推算真风");"OUTPUT_DISCONNECTED"->tr("output disconnected","输出未连接");"SOURCE_CONFLICT"->tr("multiple local candidates; deterministic source selected","多个本机候选；已按固定规则选择");else->value.lowercase().replace('_',' ')
}

@Composable private fun GpsDataSourceCard(state:MainUiState,vm:MainViewModel){
 val switching=state.connectionAttempt.state==ConnectionAttemptState.TESTING
 val sessionOpen=state.active!=null
 val sessionPaused=state.active?.paused==true
 val lockedSource=state.active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
 val proxyActive=GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
 val nmeaAvailability=NmeaSourceSelectionPolicy.availability(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1000L)
 val nmeaQualityReady=com.yokuli.anchorwatch.location.NmeaFixQualityPolicy.allowsContinuation(state.nmeaFix)
 val nmeaReady=NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1_000L)
 val nowElapsed=android.os.SystemClock.elapsedRealtime()
 val effectiveSource=lockedSource?:state.settings.gpsDataSource
 val selectedFixReady=when(effectiveSource){
  GpsDataSource.NMEA->NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,nowElapsed,state.settings.gpsLossSeconds*1_000L)
  GpsDataSource.SYSTEM->state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&nowElapsed-it.receivedElapsedRealtime in 0L until state.settings.gpsLossSeconds*1_000L}==true&&!proxyActive
  GpsDataSource.DEMO->if(state.active!=null)state.demoGps.signalAvailable&&state.fix?.valid==true else state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&nowElapsed-it.receivedElapsedRealtime in 0L until state.settings.gpsLossSeconds*1_000L}==true
 }
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){vm.onPermissionsChanged()}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 Text(tr("GPS data source","GPS 数据源"),style=MaterialTheme.typography.titleMedium)
  if(state.settings.demoMode){
   Text(tr("Demo mode locks this App to Demo GPS. System and NMEA choices return after Demo mode is disabled.","演示模式会锁定本应用使用演示 GPS；关闭演示模式后才会重新显示系统与 NMEA 选项。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   GpsSourceRow(tr("Demo GPS · locked","演示 GPS · 已锁定"),tr("Every Set anchor captures the real System-GNSS boat position; the simulated anchor centre is hidden and offset from it.","每次设置锚点都会获取真实系统 GNSS 船位；模拟锚中心会隐藏并与该船位保持偏移。"),true,false,"gps_source_demo"){}
  }else{
   Text(when{!sessionOpen->tr("Choose the default for the next anchor setup. Start validates the selected source again.","选择下一次锚警的默认来源；启动时还会再次校验。");sessionPaused->tr("Recovery mode: verify another live source for this same paused session. Centre, range and track are preserved; Resume remains a separate action.","恢复模式：为同一个暂停会话验证另一实时数据源。中心、范围和轨迹都会保留；切换后仍需单独点击继续。");else->tr("Running on ${lockedSource?.let{settingsGpsSourceLabel(it)}?:"—"}. Pause the watch before a manual source handover; the App never switches silently.","正在使用 ${lockedSource?.let{settingsGpsSourceLabel(it)}?:"—"}。手动切源前请先暂停；应用绝不会静默切换。")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   GpsSourceRow(tr("System GPS","系统 GPS"),if(proxyActive)tr("Unavailable while the global NMEA GPS proxy owns Android location.","全局 NMEA GPS 代理接管 Android 定位时不可使用。") else if(sessionPaused&&lockedSource!=GpsDataSource.SYSTEM)tr("Acquire a fresh precise phone/tablet GNSS fix, then bind it to this paused session.","获取新鲜精确的手机或平板 GNSS 定位，再绑定到当前暂停会话。") else tr("Use precise phone/tablet GNSS; coarse network location is diagnostics-only.","使用手机或平板精确 GNSS；网络粗略定位仅供诊断。"),lockedSource?.let{it==GpsDataSource.SYSTEM}?: (state.settings.gpsDataSource==GpsDataSource.SYSTEM),(!sessionOpen||sessionPaused)&&!switching&&!proxyActive,"gps_source_system"){vm.switchGpsDataSource(GpsDataSource.SYSTEM)}
   HorizontalDivider()
   GpsSourceRow("NMEA GPS",if(nmeaAvailability==NmeaSourceAvailability.AVAILABLE&&!nmeaQualityReady)tr("The current NMEA fix has unacceptable quality; wait for fix quality/HDOP to recover.","当前 NMEA 定位质量不合格；请等待定位质量或 HDOP 恢复。")else when(nmeaAvailability){NmeaSourceAvailability.AVAILABLE->if(sessionPaused&&lockedSource!=GpsDataSource.NMEA)tr("Fresh NMEA position ready for this paused session. Phone/App output remains independent.","已有新鲜 NMEA 定位，可绑定到当前暂停会话；手机 / App 输出保持独立。")else tr("Connected with a fresh valid position. Phone/App output does not change this input source.","连接正常且已有新鲜有效定位；手机 / App 输出不会改变此输入来源。");NmeaSourceAvailability.NOT_CONNECTED->tr("Connect the NMEA server before selecting this source.","请先连接 NMEA 服务器，之后才能选择此数据源。");NmeaSourceAvailability.NO_VALID_FIX->tr("Connected, but waiting for a valid NMEA position.","服务器已连接，但仍在等待有效的 NMEA 定位。");NmeaSourceAvailability.STALE_FIX->tr("The last NMEA position is stale; wait for a fresh fix.","最后一个 NMEA 定位已过期，请等待新定位。")},lockedSource?.let{it==GpsDataSource.NMEA}?: (state.settings.gpsDataSource==GpsDataSource.NMEA),(!sessionOpen||sessionPaused)&&!switching&&nmeaReady,"gps_source_nmea"){vm.switchGpsDataSource(GpsDataSource.NMEA)}
  }
  if(proxyActive)Text(tr("Disable global GPS proxy before selecting System GPS. Mock mode replaces fused location for every app, including this one.","选择系统 GPS 前请先关闭全局 GPS 代理。模拟位置会替换所有应用（包括本应用）的融合定位。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  if(state.connectionAttempt.state!=ConnectionAttemptState.IDLE)Text(localizeKnownMessage(state.connectionAttempt.message),color=if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
  PreflightRow(tr("Selected source fix","所选数据源定位"),selectedFixReady,if(selectedFixReady)tr("VALID","有效") else if(state.settings.gpsDataSource==GpsDataSource.DEMO&&!state.demoGps.signalAvailable)tr("DEMO DROPOUT","演示信号中断") else tr("NO FIX","无定位"))
  if(!permissionReady)OutlinedButton({permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)}){Text(tr("Grant system GPS permission","授予系统 GPS 权限"))}
 }}
}

@Composable private fun DeveloperSettingsCard(state:MainUiState,vm:MainViewModel){
 val enabled=state.settings.demoMode;val sessionOpen=state.active!=null||state.activeTrip!=null||state.activeSonarSurvey!=null
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(tr("Developer settings","开发者设置"),style=MaterialTheme.typography.titleMedium)
  SettingSwitch(tr("Demo mode","演示模式"),if(sessionOpen)tr("End the active Anchor/Trip session and stop any sonar survey before changing Demo mode.","必须先结束当前锚泊/航程会话并停止声呐调查，才能切换演示模式。") else tr("Locks the App to Demo GPS; Android global location is never changed.","本应用会锁定使用演示 GPS，不会修改 Android 全局位置。"),enabled,!sessionOpen){vm.setDemoMode(it)}
  if(enabled){
   HorizontalDivider();Text(tr("Demo trajectory","演示轨迹"),style=MaterialTheme.typography.labelLarge)
   listOf(DemoScenario.SAFE_SWING to tr("Safe swing","安全摆动"),DemoScenario.ANCHOR_DRAG to tr("Anchor drag","走锚"),DemoScenario.WIND_SHIFT to tr("Wind shift","风向改变"),DemoScenario.GPS_DROPOUT to tr("GPS dropout","GPS 中断"),DemoScenario.DEPTH_SHALLOW to tr("Shallow depth","浅水警报"),DemoScenario.DEPTH_DEEP to tr("Deep depth","深水警报"),DemoScenario.WIND_ALARM to tr("High wind","大风警报")).chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{(scenario,label)->FilterChip(state.settings.demoScenario==scenario,{vm.updateDemoConfiguration(scenario=scenario)},enabled=!sessionOpen,label={Text(label)},modifier=Modifier.weight(1f))};if(row.size==1)Spacer(Modifier.weight(1f))}}
   Text(when(state.settings.demoScenario){DemoScenario.SAFE_SWING->tr("Stays inside a normal swing radius. Back down first records the drop point and a gradual pull-back.","保持在正常摆动范围内；倒车下锚会先记录落锚点，再逐渐后退。");DemoScenario.ANCHOR_DRAG->tr("Settles first, then drifts continuously until it crosses the alarm boundary.","先完成稳定摆动，再持续走锚直至越过报警边界。");DemoScenario.WIND_SHIFT->tr("Turns through a smooth wind shift without jumping position.","平滑模拟风向改变，坐标不会瞬移。");DemoScenario.GPS_DROPOUT->tr("Follows a normal swing, then produces a seeded temporary GPS outage and recovery.","先正常摆动，再按本次会话产生临时 GPS 中断与恢复。");DemoScenario.DEPTH_SHALLOW->tr("Live demo depth moves from normal to sustained shallow water, then safely recovers.","演示水深从正常值持续进入浅水区，再安全恢复。");DemoScenario.DEPTH_DEEP->tr("Live demo depth moves from normal to sustained deep water, then safely recovers.","演示水深从正常值持续进入深水区，再安全恢复。");DemoScenario.WIND_ALARM->tr("Wind rises through warning and alarm thresholds, then returns to a safe level.","风速依次越过提醒和警报阈值，再回到安全水平。")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("Simulation speed","模拟速度"),style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,2,5).forEach{speed->FilterChip(state.settings.demoSpeedMultiplier==speed,{vm.updateDemoConfiguration(speed=speed)},enabled=!sessionOpen,label={Text("${speed}×")})}}
   Text(if(sessionOpen)tr("Demo settings are locked while an Anchor or Trip session is open.","锚泊或航程会话未结束时，演示设置保持锁定。") else tr("Set anchor starts the boat at fresh System GNSS. A hidden offset centre, gradual payout, sector dwell, correlated noise and slow direction changes drive the real estimator.","下锚时船从新鲜系统 GNSS 位置开始；隐藏偏移圆心、逐步放缆、扇区停留、相关噪声和缓慢换向共同驱动真实估算器。"),style=MaterialTheme.typography.bodySmall,color=if(sessionOpen)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }}
}

@Composable internal fun GpsSourceRow(title:String,subtitle:String,selected:Boolean,enabled:Boolean,testTag:String,click:()->Unit){Row(Modifier.fillMaxWidth().testTag(testTag).clickable(enabled=enabled,onClick=click),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,null,enabled=enabled);Column(Modifier.weight(1f)){Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable internal fun GpsProxyCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val stopRequired=GpsSourceSafety.requiresStopAction(state.settings.mockEnabled,state.mockGps.state);val nmeaFixReady=NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1_000L);val fixReady=nmeaFixReady&&permissionReady
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("NMEA → Android GPS",style=MaterialTheme.typography.titleMedium)
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(if(stopRequired)Icons.Default.GpsFixed else Icons.Default.GpsOff,null,tint=if(stopRequired)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Column{Text(mockGpsStateLabel(state.mockGps.state),fontWeight=FontWeight.Medium);Text(localizeKnownMessage(state.proxyFeedback?:state.mockGps.message),style=MaterialTheme.typography.bodySmall,color=if(state.mockGps.state==MockGpsState.NOT_CONFIGURED||state.mockGps.state==MockGpsState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}}
  HorizontalDivider();PreflightRow(tr("NMEA connection","NMEA 连接"),state.connection==NmeaConnectionState.CONNECTED,connectionStateLabel(state.connection));PreflightRow(tr("Fresh acceptable NMEA position","新鲜且质量合格的 NMEA 船位"),nmeaFixReady,if(nmeaFixReady)tr("READY","就绪") else tr("WAITING","等待中"));PreflightRow(tr("Fine location permission","精确位置权限"),permissionReady,if(permissionReady)tr("OK","正常") else tr("REQUIRED","必需"))
  SettingSwitch(tr("Enhanced compatibility","增强兼容性"),tr("Also publish to LocationManager.GPS_PROVIDER","同时发布到 LocationManager.GPS_PROVIDER"),state.settings.enhancedMock,enabled=!stopRequired){vm.updateSettings(state.settings.copy(enhancedMock=it))}
  Text(tr("Update rate","更新频率"),style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(1,2,5).forEach{hz->FilterChip(state.settings.mockHz==hz,{vm.updateSettings(state.settings.copy(mockHz=hz))},enabled=!stopRequired,label={Text("$hz Hz")})}}
  if(stopRequired)Text(tr("Disable the global proxy before changing compatibility or update rate. Stop remains available while startup is in progress.","修改兼容模式或更新频率前，请先关闭全局代理；即使仍在启动中，也始终可以停止。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(!fixReady&&!stopRequired)Text(tr("Connect to a live NMEA source with a valid position before enabling the global proxy.","开启全局代理前，请先连接能够提供有效位置的实时 NMEA 数据源。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({if(stopRequired)vm.stopGpsProxy() else vm.startGpsProxy()}){Text(if(stopRequired)tr("Disable global GPS proxy","关闭全局 GPS 代理") else tr("Enable global GPS proxy","开启全局 GPS 代理"))};OutlinedButton(vm::openDeveloperOptions){Text(tr("Open Developer options","打开开发者选项"))}}
  Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(tr("One-time Android setup","Android 一次性设置"),fontWeight=FontWeight.SemiBold);Text(tr("1. Settings → About phone → tap Build number 7 times.\n2. Settings → System (or Additional settings) → Developer options.\n3. Select mock location app → Boat Watch.\n4. Return here and tap Enable global GPS proxy.","1. 设置 → 关于手机 → 连续点击版本号 7 次。\n2. 设置 → 系统（或更多设置）→ 开发者选项。\n3. 选择模拟位置信息应用 → Boat Watch。\n4. 返回这里，点击开启全局 GPS 代理。"),style=MaterialTheme.typography.bodySmall)}}
  Text(tr("The proxy is optional while NMEA GPS is selected. Disable it before selecting System GPS. If NMEA is stale for ${state.settings.gpsLossSeconds}s, Android location is restored.","选择 NMEA GPS 时，全局代理是可选功能。切换到系统 GPS 前请先关闭代理；如果 NMEA 超过 ${state.settings.gpsLossSeconds} 秒没有更新，Android 定位会恢复为正常来源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }}
}

@Composable private fun AlarmBehaviourCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current
 val audio=context.getSystemService(android.media.AudioManager::class.java);val alarmVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_ALARM);val alarmMax=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);val testing=state.alarmSnapshot.type==AlarmType.ALARM_TEST&&state.alarmSnapshot.state==AlarmState.ALARM
 val customName=remember(state.settings.customAlarmSoundUri){alarmSoundDisplayName(context,state.settings.customAlarmSoundUri)}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri!=null){runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};vm.updateSettings(state.settings.copy(alarmSound=AlarmSound.CUSTOM,customAlarmSoundUri=uri.toString()))}
 }
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(tr("Anchor alarm","锚警报警"),style=MaterialTheme.typography.titleMedium)
  Text(tr("The App loops its built-in two-tone anchor alarm or your custom file. If custom audio becomes unavailable, it falls back to the built-in alarm.","应用会循环播放内置双音锚警或你的自定义文件；自定义音频失效时会回退到内置锚警。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("Alarm sound","警报声音"),style=MaterialTheme.typography.labelLarge)
  Row(Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("alarm_sound_SYSTEM_ALARM").clickable{vm.updateSettings(state.settings.copy(alarmSound=AlarmSound.SYSTEM_ALARM))},verticalAlignment=Alignment.CenterVertically){RadioButton(state.settings.alarmSound!=AlarmSound.CUSTOM,null);Column{Text(tr("Anchor alarm","锚警警报音"));Text(tr("Looping alarm-channel sound","循环播放警报声道声音"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  Row(Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("alarm_sound_CUSTOM").clickable{if(state.settings.customAlarmSoundUri==null)picker.launch(arrayOf("audio/*"))else vm.updateSettings(state.settings.copy(alarmSound=AlarmSound.CUSTOM))},verticalAlignment=Alignment.CenterVertically){RadioButton(state.settings.alarmSound==AlarmSound.CUSTOM,null);Column(Modifier.weight(1f)){Text(tr("Custom audio file","自定义音频文件"));if(customName!=null)Text(customName,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton({picker.launch(arrayOf("audio/*"))}){Text(if(customName==null)tr("Choose","选择") else tr("Change","更换"))}}
  HorizontalDivider()
  Text(tr("Snooze stops sound and vibration now, while monitoring continues. If the danger remains, the alarm sounds again after this interval.","稍后提醒会立即停止声音和振动，但监控继续；如果危险仍然存在，超过所选时间后会再次响铃。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("Remind again after","再次提醒间隔"),style=MaterialTheme.typography.labelLarge)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(5,10,15).forEach{minutes->FilterChip(state.settings.alarmSnoozeMinutes==minutes,{vm.updateSettings(state.settings.copy(alarmSnoozeMinutes=minutes))},label={Text(tr("$minutes min","$minutes 分钟"))})}}
  PreflightRow(tr("Android alarm volume","Android 警报音量"),alarmVolume>0,"$alarmVolume / $alarmMax")
  Button({if(testing)vm.stopAlarmTest() else vm.testAlarm()},Modifier.fillMaxWidth().testTag("test_alarm")){Icon(if(testing)Icons.Default.StopCircle else Icons.Default.Campaign,null);Spacer(Modifier.width(6.dp));Text(if(testing)tr("Stop alarm test","停止警报测试")else tr("Test alarm","测试警报"))}
  if(testing)OutlinedButton({vm.confirmAlarmAudible();vm.stopAlarmTest()},Modifier.fillMaxWidth().testTag("confirm_alarm_audible")){Icon(Icons.Default.Hearing,null);Spacer(Modifier.width(6.dp));Text(tr("I can hear the alarm","我能听到警报"))}
  state.settings.alarmAudibleConfirmedAt?.let{confirmed->Text(tr("Audible test confirmed ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(confirmed))}","已确认可听见：${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(confirmed))}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  if(alarmVolume==0)Text(tr("Android's Alarm volume is muted. Playback can start but cannot be heard until this system volume is raised.","Android 的“警报”音量已静音；播放器可以启动，但必须先调高系统警报音量才能听见。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
  OutlinedButton(vm::openAlarmNotificationSettings){Icon(Icons.Default.NotificationsActive,null);Spacer(Modifier.width(6.dp));Text(tr("Notification settings","通知设置"))}
 }}
}

private fun alarmSoundDisplayName(context:Context,uriText:String?):String?{
 if(uriText==null)return null
 return runCatching{context.contentResolver.query(Uri.parse(uriText),arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0)else null}}.getOrNull()?:Uri.parse(uriText).lastPathSegment
}

@Composable private fun StorageSupportPage(state:MainUiState,vm:MainViewModel){
 var confirmClearLog by remember{mutableStateOf(false)}
 var confirmClearCaches by remember{mutableStateOf(false)}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)vm.exportSupportBundle(uri)}
 val cacheMaintenanceBlocked=state.activeSonarSurvey!=null||state.backup.running||state.supportBundle.running
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(tr("STORAGE HEALTH","存储健康"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   StorageMetric(tr("Database","数据库"),humanBytes(state.storageHealth.databaseBytes));StorageMetric(tr("Offline maps","离线地图"),humanBytes(state.storageHealth.offlineMapBytes));StorageMetric(tr("Temporary cache","临时缓存"),humanBytes(state.storageHealth.cacheBytes));StorageMetric(tr("Free space","可用空间"),humanBytes(state.storageHealth.freeBytes))
   HorizontalDivider();Text(tr("${state.storageHealth.anchorSessions} anchor sessions · ${state.storageHealth.trackPoints} track points · ${state.storageHealth.sonarSamples} soundings · ${state.storageHealth.sonarGridCells} derived cells","${state.storageHealth.anchorSessions} 次锚泊 · ${state.storageHealth.trackPoints} 个轨迹点 · ${state.storageHealth.sonarSamples} 个测深点 · ${state.storageHealth.sonarGridCells} 个派生网格"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(vm::refreshStorage){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(4.dp));Text(tr("Refresh","刷新"))};OutlinedButton({confirmClearCaches=true},enabled=!cacheMaintenanceBlocked){Text(tr("Clear caches","清理缓存"))}}
   if(cacheMaintenanceBlocked)Text(tr("Cache maintenance waits until the sonar survey and export/restore jobs finish.","声呐调查和导出/恢复任务结束前不能清理缓存。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(tr("INCIDENT LOG · 72 HOURS","事件日志 · 72 小时"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   Text(tr("Safety events are ring-limited to 10,000 rows. Exact positions and raw NMEA are not recorded here.","安全事件最多保留 1 万条并按环形清理；这里不记录精确位置或原始 NMEA。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("${state.storageHealth.incidentRows} events stored","已存储 ${state.storageHealth.incidentRows} 条事件"),fontWeight=FontWeight.Medium)
   state.incidents.take(8).forEach{event->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(if(event.severity=="CRITICAL")Icons.Default.Error else if(event.severity=="WARNING")Icons.Default.Warning else Icons.Default.Info,null,Modifier.size(16.dp),tint=if(event.severity=="CRITICAL")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant);Column{Text("${event.category} · ${event.event}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium);Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(java.util.Date(event.timestamp)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
   TextButton({confirmClearLog=true},Modifier.align(Alignment.End),enabled=!state.supportBundle.running){Text(tr("Clear incident log","清空事件日志"))}
   if(state.supportBundle.running)Text(tr("The incident log is locked until the current support bundle has captured a consistent snapshot.","当前支持诊断包完成一致性快照前，事件日志会保持锁定。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(tr("SUPPORT BUNDLE","支持诊断包"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   Text(tr("Exports app/runtime/storage summaries and the recent incident log. It excludes raw NMEA, API keys and exact vessel positions.","导出应用、运行时、存储摘要和最近事件日志；不包含原始 NMEA、API key 或精确船位。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Button({export.launch("Boat-Watch-support-${java.time.LocalDate.now()}.zip")},Modifier.fillMaxWidth(),enabled=!state.supportBundle.running){Icon(Icons.Default.BugReport,null);Spacer(Modifier.width(6.dp));Text(tr("Export diagnostics","导出诊断包"))}
   if(state.supportBundle.running)LinearProgressIndicator(Modifier.fillMaxWidth())
   state.supportBundle.message?.let{AssistChip(vm::clearSupportBundleResult,{Text(localizeKnownMessage(it))})};state.supportBundle.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
  }}
 }
 if(confirmClearLog)AlertDialog({confirmClearLog=false},title={Text(tr("Clear incident log?","清空事件日志？"))},text={Text(if(state.supportBundle.running)tr("Wait for the support bundle export to finish so it contains one consistent incident snapshot.","请等待支持诊断包导出完成，以保证其中包含同一份完整事件快照。")else tr("Recent operational evidence will be deleted. Anchor history and sonar data are not affected.","最近的运行证据会被删除；锚泊历史和声呐数据不受影响。"))},confirmButton={Button({vm.clearIncidentLog();confirmClearLog=false},enabled=!state.supportBundle.running,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Clear","清空"))}},dismissButton={TextButton({confirmClearLog=false}){Text(tr("Cancel","取消"))}})
 if(confirmClearCaches)AlertDialog({confirmClearCaches=false},title={Text(tr("Clear rebuildable caches?","清理可重建缓存？"))},text={Text(tr("Sonar grid, LINZ depth and tide caches will be rebuilt from source data or the network. Raw soundings and the imported offline map remain.","声呐网格、LINZ 水深和潮汐缓存会从原始数据或网络重建；原始测深与导入的离线地图会保留。"))},confirmButton={Button({vm.clearRebuildableCaches();confirmClearCaches=false}){Text(tr("Clear caches","清理缓存"))}},dismissButton={TextButton({confirmClearCaches=false}){Text(tr("Cancel","取消"))}})
}

@Composable private fun StorageMetric(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text(value,fontWeight=FontWeight.Medium)}}
private fun humanBytes(value:Long):String=when{value>=1024L*1024L*1024L->"%.1f GB".format(value/(1024.0*1024.0*1024.0));value>=1024L*1024L->"%.1f MB".format(value/(1024.0*1024.0));value>=1024L->"%.1f KB".format(value/1024.0);else->"$value B"}

@Composable internal fun PreflightRow(label:String,ok:Boolean,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Cancel,null,Modifier.size(18.dp),tint=if(ok)SafetyColors.Safe else SafetyColors.Alarm);Spacer(Modifier.width(8.dp));Text(label,Modifier.weight(1f));Text(value,style=MaterialTheme.typography.labelMedium)}}

@Composable private fun BackgroundReliabilityCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val locationGranted=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val notificationsGranted=android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;val unrestricted=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
 val fullScreen=state.watchSafety.checks.firstOrNull{it.id=="full_screen_alarm"};val fullScreenAllowed=fullScreen?.status!=com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("Background reliability","后台可靠性"),style=MaterialTheme.typography.titleMedium);PreflightRow(tr("System GPS / proxy permission","系统 GPS / 代理权限"),locationGranted,if(locationGranted)tr("OK","正常") else tr("REQUIRED WHEN USED","使用时必需"));PreflightRow(tr("Alarm notifications","锚警通知"),notificationsGranted,if(notificationsGranted)tr("OK","正常") else tr("REQUIRED","必需"));if(android.os.Build.VERSION.SDK_INT>=34){PreflightRow(tr("Full-screen alarm","全屏警报"),fullScreenAllowed,if(fullScreenAllowed)tr("ALLOWED","已允许")else tr("NEEDS ACTION","需要处理"));if(!fullScreenAllowed)OutlinedButton(vm::openFullScreenAlarmSettings){Text(tr("Open full-screen alarm settings","打开全屏警报设置"))}};PreflightRow(tr("Battery optimization","电池优化"),unrestricted,if(unrestricted)tr("UNRESTRICTED","不受限制") else tr("SYSTEM MAY RESTRICT","系统可能限制"));SettingSwitch(tr("Keep Wi-Fi awake","保持 Wi-Fi 唤醒"),tr("Hold CPU and Wi-Fi awake while monitoring or proxying","监控或代理期间保持 CPU 与 Wi-Fi 唤醒"),state.settings.keepWifiAwake){vm.updateSettings(state.settings.copy(keepWifiAwake=it))};OutlinedButton(vm::openBatteryOptimization){Text(tr("Open battery optimization settings","打开电池优化设置"))};Text(tr("For overnight monitoring, keep the phone on reliable power and verify alarm volume before sleeping.","夜间监控时，请保持设备可靠供电，并在休息前确认报警音量。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
}

@Composable internal fun connectionStateLabel(state:NmeaConnectionState):String=when(state){
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

@Composable private fun settingsGpsSourceLabel(source:GpsDataSource):String=when(source){GpsDataSource.SYSTEM->tr("System GPS","系统 GPS");GpsDataSource.NMEA->"NMEA GPS";GpsDataSource.DEMO->tr("Demo GPS","演示 GPS")}
@Composable private fun linzStatusLabel(value:String):String=when(value){"IDLE"->tr("Idle","待命");"LOADING"->tr("Loading","正在加载");"AVAILABLE"->tr("Available","可用");"NO_DATA"->tr("No data","无数据");"OFFLINE"->tr("Offline","离线");"NOT_CONFIGURED"->tr("Not configured","未配置");"ERROR"->tr("Error","错误");else->value}

@Composable internal fun localizeKnownMessage(message:String):String{
 if(!LocalAppLanguage.current.usesChinese())return message
 if(message.startsWith("The live NMEA connection could not be opened:"))return "无法建立实时 NMEA 连接：${message.substringAfter(':').trim()}"
 return when(message){
  "Port must be between 1 and 65535."->"端口必须在 1 到 65535 之间。"
  "Host or IP address is required."->"必须填写主机名或 IP 地址。"
  "Enter a host name or IP address, not a URL."->"请输入主机名或 IP 地址，不要填写网址。"
  "Host name is too long."->"主机名过长。"
  "Testing the endpoint and waiting for valid NMEA data…"->"正在测试端点并等待有效 NMEA 数据…"
  "Opening the live endpoint and waiting for valid NMEA data…"->"正在建立唯一的实时连接并等待有效 NMEA 数据…"
  "The RX socket was opened and is being kept alive, but no valid NMEA sentence has arrived yet. It will continue listening; do not reconnect repeatedly. Check RX port, checksum and server output."->"RX Socket 已建立并会保持开启，但尚未收到有效 NMEA 语句。应用将继续监听，请不要反复重连；请检查 RX 端口、校验和设置及服务器输出。"
  "The live NMEA connection could not be opened. Check the RX host, port and network."->"无法建立实时 NMEA 连接，请检查 RX 主机、端口和网络。"
  "The NMEA endpoint test failed."->"NMEA 端点测试失败。"
  "The endpoint responded, but no valid NMEA sentence arrived within 4 seconds."->"端点已有响应，但 4 秒内没有收到有效 NMEA 语句。"
  "The endpoint test passed, but the live NMEA connection did not deliver a fresh position."->"端点测试通过，但实时 NMEA 连接没有提供新的位置。"
  "NMEA connected, but the active anchor watch could not complete a safe GPS handover."->"NMEA 已连接，但当前锚警无法完成安全的 GPS 切换。"
  "This active anchor session is locked to NMEA. Pause the watch before disconnecting, or lift the anchor to end the session."->"当前锚泊会话已锁定 NMEA。断开前请先暂停锚警，或起锚结束本次会话。"
  "Precise location permission is required before an active watch can switch to System GPS."->"当前锚警切换到系统 GPS 前需要精确位置权限。"
  "Disable the global NMEA GPS proxy before switching an anchor watch to System GPS."->"锚警切换到系统 GPS 前请先关闭全局 NMEA GPS 代理。"
  "Disable NMEA Sharing before disconnecting its shared upstream connection."->"断开共享上游连接前，请先关闭 NMEA Sharing。"
  "End the active anchor session before restoring a backup."->"恢复备份前请先结束当前锚泊会话。"
  "End the active Trip Watch session before restoring a backup."->"恢复备份前请先结束当前航程监控会话。"
  "Stop the active sonar survey before restoring a backup."->"恢复备份前请先停止当前声呐调查。"
  "Disable the global GPS proxy before restoring a backup."->"恢复备份前请先关闭全局 GPS 代理。"
  "Disable NMEA Sharing before restoring a backup."->"恢复备份前请先关闭 NMEA 共享。"
  "Turn off all phone-to-boat NMEA outputs before restoring a backup."->"恢复备份前请先关闭全部手机到船网的 NMEA 输出。"
  "Disconnect the live NMEA endpoint before restoring a backup."->"恢复备份前请先断开实时 NMEA 端点。"
  "Acquiring a fresh System GPS fix before disconnecting NMEA…"->"正在获取新的系统 GPS 定位，确认后再断开 NMEA…"
  "NMEA stayed connected because a fresh System GPS fix was not available."->"由于没有新的系统 GPS 定位，NMEA 仍保持连接。"
  "Precise location permission is required before switching this anchor session to System GPS."->"当前锚泊会话切换到系统 GPS 前需要精确位置权限。"
  "Acquiring a fresh System GNSS fix before switching this anchor session…"->"正在获取新鲜系统 GNSS 定位后切换本次会话…"
  "Validating the fresh NMEA position before switching this anchor session…"->"正在校验新鲜 NMEA 定位后切换本次会话…"
  "The session stayed on NMEA because a fresh, precise System GNSS fix was not available."->"没有可用的新鲜精确系统 GNSS 定位，本次会话仍使用 NMEA。"
  "The session kept its previous source because NMEA was not connected with a fresh valid position."->"NMEA 未连接或没有新鲜有效定位，本次会话保留原数据源。"
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
  "GPS source is locked for the whole active anchor session, including while paused. Lift the anchor before changing source."->"整个锚泊会话（包括暂停期间）都锁定 GPS 来源；请先起锚再更换来源。"
  "Not recording"->"未在记录"
  "Recording restored"->"已恢复记录"
  "Rebuilding sonar map…"->"正在重建声呐地图…"
  "Waiting for fresh depth and accepted position"->"正在等待新的水深与可信定位"
  "Depth sample recorded"->"已记录水深样本"
  "Survey saved"->"调查已保存"
  "Survey grid rebuilt from raw soundings"->"已根据原始测深重建调查网格"
  "Stop the survey before rebuilding its map"->"请先停止调查，再重建其地图"
  "Ready · live NMEA depth received"->"已就绪 · 收到实时 NMEA 水深"
  "Ready · live Demo sonar received"->"已就绪 · 收到实时演示声呐"
  "Depth received; waiting for Accepted Position"->"已收到水深，正在等待可信定位"
  "Real sonar was not paired with Demo GPS"->"真实声呐不会与演示 GPS 配对"
  "Demo sonar is waiting for Demo GPS"->"演示声呐正在等待演示 GPS"
  "Select NMEA GPS before enabling the global proxy."->"开启全局代理前请先选择 NMEA GPS。"
  "Connect to the NMEA source first."->"请先连接 NMEA 数据源。"
  "Connect a writable TCP NMEA endpoint before enabling Phone GPS output."->"开启手机 GPS 输出前，请先连接一个可写入的 TCP NMEA 端点。"
  "Select at least one NMEA output stream first."->"请先选择至少一个 NMEA 输出数据流。"
  "Complete phone vessel-sensor calibration before starting NMEA output."->"启动 NMEA 输出前，请先完成手机船舶传感器校准。"
  "Complete phone vessel-sensor calibration before testing NMEA output."->"测试 NMEA 输出前，请先完成手机船舶传感器校准。"
  "Secure the calibrated phone to the vessel and mark it vessel-mounted before publishing heading or motion."->"发送船首向或船体运动前，请固定已校准手机并将其标记为“固定在船体”。"
  "This phone has no pressure sensor for the selected BARO stream."->"本手机没有可用于所选 BARO 数据流的气压传感器。"
  "Stop NMEA output before changing its destination."->"更改发送目标前请先停止 NMEA 输出。"
  "Choose an NMEA output destination before enabling a stream."->"启用数据流前请先选择 NMEA 输出目标。"
	  "Connect the matching TCP NMEA input endpoint before starting output. The App will reuse that connection automatically."->"开始发送前请先连接对应的 TCP NMEA 输入端点；App 会自动复用该连接。"
	  "Enter a valid TCP output host and port first."->"请先填写有效的 TCP 输出主机和端口。"
  "Enter a valid UDP output host and port first."->"请先填写有效的 UDP 输出主机和端口。"
  "The NMEA connection has not supplied a valid position yet."->"NMEA 连接尚未提供有效位置。"
  "Checking Android mock-location access…"->"正在检查 Android 模拟位置权限…"
  "Android GPS is using the normal system source."->"Android GPS 正在使用正常的系统数据源。"
  "NMEA is feeding Fused Location and GPS_PROVIDER."->"NMEA 正在向融合定位和 GPS_PROVIDER 提供位置。"
  "NMEA is feeding Fused Location. Direct GPS compatibility is unavailable."->"NMEA 正在向融合定位提供位置；直接 GPS 兼容模式不可用。"
  "GPS proxy was not enabled. Turn on Developer Options and select Boat Watch as the location override app."->"GPS 代理未开启。请启用开发者选项，并将 Boat Watch 设为模拟位置应用。"
  "Android GPS restored to the normal system source."->"Android GPS 已恢复到正常系统数据源。"
  "Connect the NMEA server before enabling the sonar chart layer. Demo mode is the only offline exception."->"开启声呐海图前必须先连接 NMEA 服务器；演示模式是唯一的离线例外。"
  "Fresh spatial cache hit"->"已命中新鲜的空间缓存"
  "Querying LINZ vector depth"->"正在查询 LINZ 矢量水深"
  "LINZ vector depth available"->"LINZ 矢量水深可用"
  "Offline · cached LINZ depth"->"离线 · 使用缓存的 LINZ 水深"
  "LINZ unavailable offline"->"离线状态下 LINZ 不可用"
  "LINZ vector query failed"->"LINZ 矢量查询失败"
  "Requesting LINZ chart tiles"->"正在请求 LINZ 海图瓦片"
  "LINZ chart tile loaded"->"LINZ 海图瓦片已加载"
  "Listening on all interfaces"->"正在所有网络接口上监听"
  "Rebinding NMEA Sharing on all interfaces"->"正在所有网络接口上重新绑定 NMEA 共享"
  "Unable to start NMEA sharing"->"无法启动 NMEA 共享"
  "NMEA Sharing waiting for input"->"NMEA 共享正在等待输入"
  "Sharing will not connect a saved NMEA endpoint automatically. Open the NMEA connection when you want to publish boat data."->"共享不会自动连接已保存的 NMEA 端点；需要发布船载数据时，请手动打开 NMEA 连接。"
  "GPS_SPIKE_CLEARED"->"GPS 跳点已排除"
  "SUSTAINED_POSITION_CHANGE_CONFIRMED"->"已确认持续位置变化"
  "AWAITING_FRESH_CONFIRMATION"->"等待新鲜定位确认"
  else->message
 }
}

@Composable internal fun PageHeader(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled:Boolean=true, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change,enabled=enabled) } }
