package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.vessel.anyEnabled
import com.yokuli.anchorwatch.data.vessel.anyStreamSelected
import com.yokuli.anchorwatch.data.vessel.NmeaOutputTransportMode
import com.yokuli.anchorwatch.data.vessel.PhoneHeadingOutputFormat
import com.yokuli.anchorwatch.data.vessel.effectivePositionPolicy
import com.yokuli.anchorwatch.data.vessel.effectiveHeadingPolicy
import com.yokuli.anchorwatch.data.vessel.effectiveMotionPolicy
import com.yokuli.anchorwatch.data.vessel.effectivePressurePolicy
import com.yokuli.anchorwatch.data.vessel.phonePositionPublishing
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.NmeaOutputPurpose
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
import com.yokuli.anchorwatch.location.vessel.DeviceBowAxis
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState
import java.text.DateFormat
import java.util.Date

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
 pendingSupportUrl?.let{url->AlertDialog(onDismissRequest={pendingSupportUrl=null},title={Text(tr("Open Buy Me a Coffee?","打开 Buy Me a Coffee？"))},text={Text(tr("You’re leaving Anchor Watch to visit Buy Me a Coffee in your browser. Support is optional and does not unlock app features.","即将离开 Anchor Watch，并在浏览器中打开 Buy Me a Coffee。支持完全自愿，不会解锁任何 App 功能。"))},confirmButton={Button({pendingSupportUrl=null;if(externalLauncher.open(url)!=ExternalLinkResult.OPENED)android.widget.Toast.makeText(context,localized(state.settings.appLanguage,"No app could open this secure link.","没有应用可以打开此安全链接。"),android.widget.Toast.LENGTH_SHORT).show()},Modifier.testTag("settings_support_continue")){Text(tr("Continue","继续"))}},dismissButton={TextButton({pendingSupportUrl=null}){Text(tr("Cancel","取消"))}})}
}

@Composable private fun SettingsRoot(state:MainUiState,open:(SettingsDestination)->Unit,language:()->Unit,support:(String)->Unit){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp).testTag("settings_list"),contentPadding=PaddingValues(vertical=18.dp)){
  item{PageHeader(tr("Settings","设置"),tr("Choose a section; runtime NMEA and sonar controls stay in Data.","选择要配置的类别；NMEA 与声呐运行操作仍在“数据”页面。"));Spacer(Modifier.height(14.dp));ProductBrand.supportProviders.firstOrNull()?.let{provider->ElevatedCard(onClick={support(provider.url)},modifier=Modifier.fillMaxWidth().testTag("settings_support_card")){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Default.Coffee,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text(tr("Support Yokuli","支持 Yokuli"),fontWeight=FontWeight.SemiBold);Text(tr("Buy Me a Coffee · every app feature stays free","Buy Me a Coffee · App 全部功能始终免费"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.AutoMirrored.Filled.OpenInNew,null)}}};Spacer(Modifier.height(10.dp))}
  item{SettingsSection(tr("ALARM & WATCH","报警与监控"));SettingsRow(Icons.Default.NotificationsActive,tr("Alarm & notifications","报警与通知"),tr("Anchor alarm · ${state.settings.alarmSnoozeMinutes} min snooze","锚警 · ${state.settings.alarmSnoozeMinutes} 分钟后提醒"),"settings_alarm"){open(SettingsDestination.ALARM)}}
  item{SettingsSection(tr("VESSEL","船舶"));SettingsRow(Icons.Default.Sailing,tr("Vessel profile","船舶资料"),tr("${state.settings.boatLengthMeters} m · bow ${state.settings.bowRollerHeightMeters} m","${state.settings.boatLengthMeters} 米 · 船艏 ${state.settings.bowRollerHeightMeters} 米"),"settings_vessel"){open(SettingsDestination.VESSEL)};SettingsRow(Icons.Default.Sensors,tr("Phone vessel sensors","手机船舶传感器"),when(state.phoneVesselMountState){PhoneVesselMountState.VESSEL_MOUNTED->tr("Calibrated · vessel-mounted","已校准 · 固定在船体");PhoneVesselMountState.MOUNT_SUSPECT->tr("Mount movement detected","检测到安装位置移动");PhoneVesselMountState.HANDHELD->tr("Calibrated · handheld","已校准 · 手持模式");PhoneVesselMountState.UNCALIBRATED->tr("Calibration required for vessel output","船舶数据输出前必须校准")},"settings_phone_sensors"){open(SettingsDestination.PHONE_SENSORS)};SettingsRow(Icons.Default.Waves,tr("Depth sounder","测深仪"),tr("Depth offset ${signed(state.settings.sounderOffsetMeters)} m","水深修正 ${signed(state.settings.sounderOffsetMeters)} 米"),"settings_depth_sounder"){open(SettingsDestination.DEPTH_SOUNDER)}}
  item{SettingsSection(tr("POSITION & MAP","定位与地图"));SettingsRow(Icons.Default.GpsFixed,tr("Positioning","定位"),tr("Default: ${settingsGpsSourceLabel(state.settings.gpsDataSource)}","默认：${settingsGpsSourceLabel(state.settings.gpsDataSource)}"),"settings_positioning"){open(SettingsDestination.POSITIONING)};SettingsRow(Icons.Default.Layers,tr("Map data","地图数据"),tr("Offline MBTiles · personal sonar","离线 MBTiles · 个人声呐"),"settings_map_depth"){open(SettingsDestination.MAP_DEPTH)}}
  item{SettingsSection(tr("DEVICE & DATA","设备与数据"));SettingsRow(Icons.Default.BatterySaver,tr("Background reliability","后台可靠性"),tr("Permissions, power and Wi-Fi","权限、电源与 Wi-Fi"),"settings_background"){open(SettingsDestination.BACKGROUND)};SettingsRow(Icons.Default.Backup,tr("Data & backup","数据与备份"),tr("Export or replace from an Anchor Watch backup","导出或从 Anchor Watch 备份替换恢复"),"settings_data_backup"){open(SettingsDestination.DATA_BACKUP)};SettingsRow(Icons.Default.Storage,tr("Storage & support","存储与支持"),tr("Health, incident log and diagnostics","健康、事件日志与诊断包"),"settings_storage_support"){open(SettingsDestination.STORAGE_SUPPORT)};SettingsRow(Icons.Default.Language,tr("Language","语言"),state.settings.appLanguage.nativeName,"settings_language",language)}
  item{SettingsSection(tr("ADVANCED","高级"));SettingsRow(Icons.Default.DeveloperMode,tr("Developer","开发者"),if(state.settings.demoMode)tr("Demo mode on","演示模式已开启")else tr("Demo mode off","演示模式已关闭"),"settings_developer"){open(SettingsDestination.DEVELOPER)}}
  item{SettingsSection(tr("ABOUT","关于"));SettingsRow(Icons.Default.Info,tr("About & support","关于与支持"),tr("Made aboard Yokuli","诞生于 Yokuli 船上"),"settings_about"){open(SettingsDestination.ABOUT)};if(ProductBrand.contactEmail.isNotBlank())SettingsRow(Icons.Default.Feedback,tr("Feedback & feature requests","反馈与功能建议"),tr("Email kuku directly","直接给 kuku 发邮件"),"settings_feedback"){open(SettingsDestination.FEEDBACK)}}
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
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("BACKUP","备份"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(tr("Export Anchor Watch backup","导出 Anchor Watch 备份"),style=MaterialTheme.typography.titleMedium);Text(tr("Includes settings, anchor history, saved anchorages, Trip Watch sessions, tracks, events, waypoints, sonar surveys and raw soundings. Derived caches are rebuilt after restore.","包含设置、锚泊历史、收藏锚地、航程会话、轨迹、事件、航点、声呐调查和原始测深点。派生缓存会在恢复后重建。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);state.backup.lastBackupAt?.let{Text(tr("Last backup: ${DateFormat.getDateTimeInstance().format(java.util.Date(it))}","上次备份：${DateFormat.getDateTimeInstance().format(java.util.Date(it))}"),style=MaterialTheme.typography.bodySmall)};Button({privacyConfirm=true},Modifier.fillMaxWidth(),enabled=!state.backup.running){Icon(Icons.Default.FileUpload,null);Spacer(Modifier.width(6.dp));Text(tr("Export Anchor Watch backup","导出 Anchor Watch 备份"))}}}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("RESTORE · REPLACE","恢复 · 替换"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.error);Text(tr("Restore from Anchor Watch backup","从 Anchor Watch 备份恢复"),style=MaterialTheme.typography.titleMedium);Text(tr("The archive is fully checked before local data changes. Restore replaces local Anchor Watch history; merge is intentionally unavailable.","备份会先完成全部校验，再改动本机数据。恢复会替换本机 Anchor Watch 历史；本版本故意不提供合并。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);blockedReason?.let{Text(localizeKnownMessage(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)};OutlinedButton({restore.launch(arrayOf("application/zip","application/octet-stream","*/*"))},Modifier.fillMaxWidth(),enabled=!blocked&&!state.backup.running){Icon(Icons.Default.Restore,null);Spacer(Modifier.width(6.dp));Text(tr("Choose backup to restore","选择要恢复的备份"))}}}
  if(state.backup.running){LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.backup.progress,style=MaterialTheme.typography.bodySmall)}
  state.backup.result?.let{AssistChip(vm::clearBackupResult,{Text(localizeKnownMessage(it))},leadingIcon={Icon(Icons.Default.CheckCircle,null)})}
  state.backup.error?.let{Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp)){Text(localizeKnownMessage(it),color=MaterialTheme.colorScheme.onErrorContainer);TextButton(vm::clearBackupResult){Text(tr("Dismiss","关闭"))}}}}
 }
 if(privacyConfirm)AlertDialog(onDismissRequest={privacyConfirm=false},title={Text(tr("Precise location history","精确位置历史"))},text={Text(tr("This backup contains anchoring locations, saved anchorages, trips, tracks and sonar positions. Protect it like a vessel logbook. Anchor Watch backup v4 is not encrypted.","此备份包含锚泊位置、收藏锚地、航程、轨迹和声呐坐标。请像保护航海日志一样保护该文件；Anchor Watch 备份 v4 未加密。"))},confirmButton={Button({privacyConfirm=false;export.launch("Anchor-Watch-${java.time.LocalDate.now()}.yokuli-backup")}){Text(tr("Choose export location","选择导出位置"))}},dismissButton={TextButton({privacyConfirm=false}){Text(tr("Cancel","取消"))}})
 restoreUri?.let{uri->AlertDialog(onDismissRequest={restoreUri=null},title={Text(tr("Replace all local Anchor Watch data?","替换全部本机 Anchor Watch 数据？"))},text={Text(tr("Validation happens first. If it passes, local anchor and sonar history will be replaced in one database transaction. This cannot be undone without another backup.","系统会先完整校验；通过后，本机锚泊与声呐历史将在一个数据库事务中被替换。除非另有备份，否则无法撤销。"))},confirmButton={Button({restoreUri=null;vm.restoreBackup(uri)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Validate & replace","校验并替换"))}},dismissButton={TextButton({restoreUri=null}){Text(tr("Cancel","取消"))}})}
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
  Text(tr("Simple depth calibration","简化水深校准"),style=MaterialTheme.typography.titleMedium);Text(tr("Anchor Watch displays and maps: raw instrument depth + NMEA offset + your fixed offset.","Anchor Watch 显示并绘制：仪器原始水深 + NMEA 自带 offset + 你的固定 offset。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("LATEST DEPTH PROVENANCE","最近水深来源"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(tr("Raw instrument","仪器原始值"),depthMeters(state.sonarRecorder.lastRawDepthMeters));Metric(tr("NMEA offset","NMEA offset"),signedDepthMeters(state.sonarRecorder.lastNmeaOffsetMeters))}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(tr("User offset","用户 offset"),signedDepthMeters(state.sonarRecorder.lastUserOffsetMeters?:state.settings.sounderOffsetMeters));Metric(tr("Final depth","最终水深"),depthMeters(state.sonarRecorder.lastMeasuredDepthMeters))}
  OutlinedTextField(offset,{text->offset=text.filterIndexed{index,c->c.isDigit()||c=='.'||(c=='-'&&index==0)}},label={Text(tr("Depth offset","水深 offset"))},prefix={Text(if((value?:0.0)>=0)"+" else "")},suffix={Text(tr("m","米"))},supportingText={Text(tr("Example: instrument 6.0 m, offset +0.4 m → Anchor Watch 6.4 m.","例如：仪器显示 6.0 米，offset 为 +0.4 米 → Anchor Watch 显示 6.4 米。"))},isError=!valid,modifier=Modifier.fillMaxWidth(),enabled=state.activeSonarSurvey==null)
  Text(tr("GPS only places the sounding on the map. Its accuracy remains quality metadata and never changes the depth number.","GPS 只负责把测深点放到地图上；定位精度仅作为质量信息，不会修改水深数值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(state.activeSonarSurvey!=null)Text(tr("Stop and save the active sonar survey before changing its depth calibration.","请先停止并保存当前声呐调查，再修改本次测深校准。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
  Button({vm.updateSettings(state.settings.copy(sounderOffsetMeters=value!!));saved=true},Modifier.fillMaxWidth(),enabled=dirty&&state.activeSonarSurvey==null){Icon(Icons.Default.Save,null);Spacer(Modifier.width(6.dp));Text(if(saved)tr("Saved","已保存")else tr("Save offset","保存 offset"))}
 }}
}

@Composable private fun MapDepthSettingsPage(state:MainUiState,vm:MainViewModel){
 var showSonarDisclaimer by remember{mutableStateOf(false)}
 val offlineImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.importOfflineMap(uri)}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(tr("Map display controls","地图显示控制"),style=MaterialTheme.typography.titleMedium)
   Text(tr("Choose Map, Satellite or Nautical and control the regional Local depth chart directly from the map layer button.","请直接通过地图页的图层按钮选择地图、卫星或航海底图，并控制区域水深海图。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(tr("Imported nautical chart","已导入航海图"),style=MaterialTheme.typography.titleMedium)
   if(state.offlineMap.installed){
    Text("${state.offlineMap.name?:tr("User chart","用户海图")} · ${state.offlineMap.format?.uppercase()?:"Raster"} · z${state.offlineMap.minZoom?:"?"}–${state.offlineMap.maxZoom?:"?"} · ${humanBytes(state.offlineMap.sizeBytes)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    state.offlineMap.description?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
   }
   SettingSwitch(tr("Use imported chart in Nautical","航海模式优先使用已导入海图"),if(state.offlineMap.installed)tr("Uncovered areas fall back to the standard nautical view.","未覆盖区域会自动显示标准航海图。") else tr("No MBTiles chart installed","尚未安装 MBTiles 海图"),state.settings.offlineMapEnabled&&state.offlineMap.installed,state.offlineMap.installed,vm::setOfflineMapEnabled)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({offlineImport.launch(arrayOf("application/vnd.sqlite3","application/x-sqlite3","application/octet-stream","*/*"))}){Icon(Icons.Default.FileOpen,null);Spacer(Modifier.width(6.dp));Text(if(state.offlineMap.installed)tr("Replace MBTiles","替换 MBTiles")else tr("Import MBTiles","导入 MBTiles"))};if(state.offlineMap.installed)TextButton(vm::removeOfflineMap){Icon(Icons.Default.DeleteOutline,null);Spacer(Modifier.width(4.dp));Text(tr("Remove","删除"))}}
   Text(tr("When Nautical is selected, Anchor Watch prefers this chart. Map and Satellite remain unchanged. Google tiles are never cached.","选择航海模式时会优先使用此海图；普通地图和卫星图不受影响。Google 瓦片绝不会被缓存。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(tr("Depth data display","水深数据显示"),style=MaterialTheme.typography.titleMedium)
   SettingSwitch(tr("Current-position LINZ depth","当前位置 LINZ 水深"),tr("Vector reference; never presented as live sonar","矢量海图参考；绝不会冒充实时声呐"),state.settings.showLinzDepthReference,BuildConfig.LINZ_API_KEY.isNotBlank()){vm.updateSettings(state.settings.copy(showLinzDepthReference=it))}
   Text(tr("LINZ vector status: ${linzStatusLabel(state.linzDepth.status.name)}","LINZ 矢量状态：${linzStatusLabel(state.linzDepth.status.name)}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   HorizontalDivider()
   SettingSwitch(tr("Personal sonar layer","个人声呐图层"),tr("Saved survey cells are viewable offline; live same-stream NMEA is required to record new soundings","已保存的调查网格可离线查看；记录新测深点要求实时同源 NMEA"),state.settings.sonarLayerEnabled,true){enabled->if(enabled&&!state.settings.sonarDisclaimerAccepted)showSonarDisclaimer=true else vm.setSonarLayerEnabled(enabled)}
   Text(tr("Personal sonar uses a fixed 75% display opacity to keep alarm geometry readable.","个人声呐固定使用 75% 显示不透明度，以保持锚警范围清晰可读。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   SettingSwitch(tr("Current-position personal depth","当前位置个人水深"),tr("Show measured/interpolated status in Watch","在监控页显示实测/插值状态"),state.settings.showPersonalMapReference){vm.updateSettings(state.settings.copy(showPersonalMapReference=it))}
  }}
 }
 if(showSonarDisclaimer)SonarSafetyDisclaimerDialog({showSonarDisclaimer=false}){vm.setSonarLayerEnabled(true,acceptDisclaimer=true);showSonarDisclaimer=false}
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
 var axis by remember(state.vesselMountCalibration.bowAxis){mutableStateOf(state.vesselMountCalibration.bowAxis)}
 var alignment by remember(state.vesselMountCalibration.headingAlignmentOffsetDegrees){mutableStateOf("%.1f".format(state.vesselMountCalibration.headingAlignmentOffsetDegrees))}
 val alignmentValue=alignment.toDoubleOrNull()
 val alignmentDirty=alignmentValue!=null&&(!state.vesselMountCalibration.headingAligned||kotlin.math.abs(alignmentValue-state.vesselMountCalibration.headingAlignmentOffsetDegrees)>0.001)
 val caps=state.phoneSensorCapabilities
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text(tr("Phone vessel sensors","手机船舶传感器"),style=MaterialTheme.typography.titleMedium)
  Text(tr("Attitude is calculated in a calibrated vessel frame. Positive heel means starboard; positive pitch means bow up. It is used by Trip Watch, not as an Anchor GPS fallback.","姿态会在校准后的船体坐标系中计算：正横倾表示右舷，正纵倾表示船艏向上。它用于航程监控，不会成为锚警 GPS 回退源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(tr("Rotation ${if(caps.attitudeAvailable)"available" else "unavailable"} · Gyro ${if(caps.gyroAvailable)"available" else "unavailable"} · Pressure ${if(caps.pressureAvailable)"available" else "unavailable"}","旋转 ${if(caps.attitudeAvailable)"可用" else "不可用"} · 陀螺仪 ${if(caps.gyroAvailable)"可用" else "不可用"} · 气压 ${if(caps.pressureAvailable)"可用" else "不可用"}"),style=MaterialTheme.typography.bodySmall)
  HorizontalDivider()
  Text(tr("Live phone measurements","手机实时测量"),fontWeight=FontWeight.SemiBold)
  val liveAttitude=state.vesselData.attitude.value
  Text("${tr("Heading","方位")} ${state.phoneHeading.liveTrueHeadingDegrees?.let{"%03.0f°T".format(it)}?:"—"} · ${tr("Heel","横倾")} ${liveAttitude?.heelDegrees?.let{"%+.1f°".format(it)}?:"—"} · ${tr("Pitch","纵倾")} ${liveAttitude?.pitchDegrees?.let{"%+.1f°".format(it)}?:"—"}",style=MaterialTheme.typography.bodyMedium)
  Text("ROT ${liveAttitude?.yawRateDegreesPerSecond?.times(60.0)?.let{"%+.1f°/min".format(it)}?:"—"} · ${tr("Pressure","气压")} ${state.vesselData.pressureHpa.value?.let{"%.1f hPa".format(it)}?:"—"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  HorizontalDivider()
  Text(tr("Phone role","手机角色"),fontWeight=FontWeight.SemiBold)
  Text(
   when(state.phoneVesselMountState){
    PhoneVesselMountState.VESSEL_MOUNTED->tr("Vessel-mounted · eligible as a vessel sensor","已固定在船体 · 可作为船舶传感器")
    PhoneVesselMountState.MOUNT_SUSPECT->tr("Mount movement detected · vessel output is suspended","检测到安装位置移动 · 已暂停船舶数据输出")
    PhoneVesselMountState.HANDHELD->tr("Handheld · device navigation only","手持模式 · 仅用于设备导航")
    PhoneVesselMountState.UNCALIBRATED->tr("Not calibrated · device navigation only","尚未校准 · 仅用于设备导航")
   },style=MaterialTheme.typography.bodySmall,color=if(state.phoneVesselMountState==PhoneVesselMountState.MOUNT_SUSPECT)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  )
  SettingSwitch(
   tr("Fixed to the vessel","手机已固定在船体"),
   tr("Only enable this after the phone is secured and vessel zero is calibrated","仅在手机已牢固固定且完成船体零点校准后开启"),
   state.phoneVesselMountState==PhoneVesselMountState.VESSEL_MOUNTED,
   enabled=state.vesselMountCalibration.calibratedAt>0L,
  ){vm.setPhoneVesselMounted(it)}
  SettingSwitch(
   tr("Automatic mount recovery","安装状态自动恢复"),
   tr("After movement, restore vessel-sensor eligibility only after the phone is stable in its calibrated position for 7 seconds","检测到移动后，只有手机在已校准位置稳定 7 秒，才自动恢复船舶传感器资格"),
   state.vesselMountCalibration.automaticMountRecovery,
   enabled=state.vesselMountCalibration.calibratedAt>0L,
  ){vm.setAutomaticMountRecovery(it)}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   OutlinedTextField(alignment,{value->alignment=value.filter{it.isDigit()||it=='.'||it=='-'}},label={Text(tr("Heading alignment","艏向对齐偏差"))},suffix={Text("°")},singleLine=true,modifier=Modifier.weight(1f))
   Button({alignmentValue?.let(vm::setPhoneHeadingAlignment)},enabled=state.vesselMountCalibration.calibratedAt>0L&&alignmentDirty){Text(tr("Confirm alignment","确认对齐"))}
  }
  Text(tr("This offset aligns mounted-phone north with the vessel bow; it never changes handheld approach guidance.","该偏差仅将固定手机的北向与船艏对齐，不会影响手持接近导航。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(if(state.vesselMountCalibration.headingAligned)tr("Heading alignment completed","船首向对齐已完成")else tr("Heading output will wait until this alignment is explicitly confirmed.","明确确认艏向对齐前，船首向输出会保持等待。"),style=MaterialTheme.typography.bodySmall,color=if(state.vesselMountCalibration.headingAligned)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
  HorizontalDivider()
  Text(tr("Which phone edge points toward the bow?","手机哪一边指向船艏？"),fontWeight=FontWeight.SemiBold)
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){DeviceBowAxis.entries.forEachIndexed{index,value->SegmentedButton(axis==value,{axis=value},shape=SegmentedButtonDefaults.itemShape(index,DeviceBowAxis.entries.size)){Text(deviceBowAxisLabel(value))}}}
  Text(tr("Fix the phone securely and keep the vessel near neutral attitude, then set vessel zero.","请固定好手机，让船体尽量处于中性姿态，然后设置船体零点。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Button({vm.calibrateVesselMount(axis)},Modifier.fillMaxWidth(),enabled=caps.attitudeAvailable){Text(tr("Set vessel zero","设置船体零点"))}
  if(state.vesselMountCalibration.calibratedAt>0)Text(tr("Last calibration: ${DateFormat.getDateTimeInstance().format(Date(state.vesselMountCalibration.calibratedAt))}","上次校准：${DateFormat.getDateTimeInstance().format(Date(state.vesselMountCalibration.calibratedAt))}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  state.vesselCalibrationFeedback?.let{feedback->AssistChip(vm::clearVesselCalibrationFeedback,label={Text(when(feedback){"Vessel zero saved."->tr("Vessel zero saved","船体零点已保存");"End the active trip before changing vessel zero."->tr("End the active trip before changing vessel zero","请先结束活动航程，再修改船体零点");else->tr("No compatible rotation sample is available","没有可用的旋转传感器数据")})})}
 }}
}

@Suppress("DEPRECATION")
@Composable internal fun DataOutputSettingsPage(state:MainUiState,vm:MainViewModel){
 val activeNmea=state.active?.positionSource==GpsDataSource.NMEA.name
 val nmeaSelected=state.settings.gpsDataSource==GpsDataSource.NMEA
 val writable=state.settings.profile.protocol==Protocol.TCP&&state.connection in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)
 val outputReady=state.outputSettings.transportConfigured&&when(state.outputSettings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->writable;NmeaOutputTransportMode.DEDICATED_TCP,NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->state.outputSettings.outputHost.isNotBlank()&&state.outputSettings.outputPort in 1..65535}
 val outputActive=state.outputSettings.publicationEnabled
 val motionFrameReady=state.vesselMountCalibration.calibratedAt>0L&&state.phoneVesselMountState==PhoneVesselMountState.VESSEL_MOUNTED
 val headingFrameReady=motionFrameReady&&state.vesselMountCalibration.headingAligned
 val canEnable=!activeNmea&&!nmeaSelected&&outputReady
 var host by remember(state.outputSettings.outputHost){mutableStateOf(state.outputSettings.outputHost)}
 var port by remember(state.outputSettings.outputPort){mutableStateOf(state.outputSettings.outputPort.toString())}
 var pendingEnable by remember{mutableStateOf<String?>(null)}
 var testResult by remember{mutableStateOf<String?>(null)}
 var confirmHeadingDiagnostic by remember{mutableStateOf(false)}
 var showPublisherDiagnostics by rememberSaveable{mutableStateOf(false)}
 var showAdvancedTransport by rememberSaveable{mutableStateOf(state.outputSettings.transportMode in setOf(NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST))}
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
 fun applyOutput(id:String,enabled:Boolean){when(id){"position"->vm.setPhonePositionOutput(enabled);"heading"->vm.setPhoneHeadingOutput(enabled);"motion"->vm.setPhoneMotionOutput(enabled);"pressure"->vm.setPhonePressureOutput(enabled);else->vm.setPhoneProprietaryOutput(enabled)}}
 fun setOutput(id:String,enabled:Boolean){
  if(!enabled){applyOutput(id,false);return}
  val now=android.os.SystemClock.elapsedRealtime()
  // Duplicate warnings inspect the physical boat stream, not only the Vessel
  // Hub's currently selected display source.
  val duplicate=when(id){
   "position"->state.nmeaFix?.let{it.valid&&now-it.receivedElapsedRealtime in 0L..3_000L}==true
   "heading"->state.nmeaInstruments.headingTrue?.let{(_,received)->now-received in 0L..3_000L}==true||state.nmeaFix?.let{fix->fix.headingSource==com.yokuli.anchorwatch.domain.model.HeadingSource.NMEA_PHYSICAL&&(fix.headingReceivedElapsedRealtime?:fix.receivedElapsedRealtime).let{now-it in 0L..3_000L}}==true
   else->false
  }
  if(duplicate)pendingEnable=id else applyOutput(id,true)
 }
 fun setPolicy(id:String,policy:PublicationPolicy){
  if(policy==PublicationPolicy.OFF){vm.setPhoneOutputPolicy(id,policy);return}
  if(policy==PublicationPolicy.BACKUP){vm.setPhoneOutputPolicy(id,policy);return}
  val now=android.os.SystemClock.elapsedRealtime();val duplicate=when(id){"position"->state.nmeaFix?.let{it.valid&&now-it.receivedElapsedRealtime in 0L..3_000L}==true;"heading"->state.nmeaInstruments.headingTrue?.let{(_,received)->now-received in 0L..3_000L}==true;else->false}
  if(duplicate)pendingEnable=id else vm.setPhoneOutputPolicy(id,policy)
 }
 LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{PageHeader(tr("NMEA output","NMEA 输出"),tr("The server's transmit port and receive port may differ. Configure Server → App input and App → Server output independently.","服务器的发送端口与接收端口可能不同。请分别配置“服务器 → App”输入和“App → 服务器”输出。"))}
  item{Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(tr("Output purpose","输出用途"),style=MaterialTheme.typography.titleMedium)
   SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
    SegmentedButton(state.outputSettings.purpose==NmeaOutputPurpose.BOAT_BUS_INJECTION,{vm.setNmeaOutputPurpose(NmeaOutputPurpose.BOAT_BUS_INJECTION)},enabled=!outputActive,shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Boat injection","船载注入"))}
    SegmentedButton(state.outputSettings.purpose==NmeaOutputPurpose.CANONICAL_CLIENT_FEED,{vm.setNmeaOutputPurpose(NmeaOutputPurpose.CANONICAL_CLIENT_FEED)},enabled=!outputActive,shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Canonical client feed","统一客户端流"))}
   }
   Text(if(state.outputSettings.purpose==NmeaOutputPurpose.BOAT_BUS_INJECTION)tr("Publishes selected Phone/derived streams with Off, Backup or Always ownership so the App does not duplicate healthy boat instruments.","按关闭、备用或始终策略向船载总线注入手机/推算数据，避免与正常船载仪表重复。")else tr("Publishes the Vessel Data Hub's selected source for every available instrument at a fixed heartbeat. It never forwards the raw boat input or a losing candidate.","按固定心跳发布船舶数据中心为每项仪表选中的唯一来源；不会原样转发船载输入，也不会发布落选候选源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   SettingSwitch(tr("Auto-start saved output","自动启动已保存输出"),tr("Off by default. When enabled, the foreground runtime starts this saved route only after App startup restores its configuration.","默认关闭。开启后，前台运行服务只会在 App 启动并恢复配置后启动这条已保存线路。"),state.outputSettings.autoStartOutput,enabled=!outputActive){vm.setNmeaOutputAutoStart(it)}
  }}}
  item{Card(colors=CardDefaults.cardColors(containerColor=if(outputActive)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(outputActive)Icons.Default.Sensors else Icons.Default.StopCircle,null,tint=if(outputActive)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(if(outputActive)tr("NMEA output is running","NMEA 输出正在运行")else tr("NMEA output is stopped","NMEA 输出已停止"),fontWeight=FontWeight.SemiBold);Text(if(outputActive&&state.outputSettings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION)tr("TX is sharing the input TCP socket. Stop output here before stopping RX.","TX 正与输入共用 TCP Socket；停止 RX 前请先在这里停止输出。")else if(outputActive)tr("Dedicated TX is independent from NMEA input. Stop here to close the TX socket.","独立 TX 与 NMEA 输入相互独立；请在这里停止并关闭发送 Socket。")else tr("Saving an endpoint or stream does not send anything until Start output.","保存端点或数据流不会自动发送；必须明确点击“启动输出”。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
   if(outputActive)Button(vm::stopNmeaOutput,Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Stop,null);Spacer(Modifier.width(6.dp));Text(tr("Stop all NMEA output","停止全部 NMEA 输出"))}
   else Button(vm::startNmeaOutput,Modifier.fillMaxWidth(),enabled=state.outputSettings.anyStreamSelected){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(6.dp));Text(tr("Start NMEA output","启动 NMEA 输出"))}
   if(state.outputSettings.purpose==NmeaOutputPurpose.BOAT_BUS_INJECTION&&!headingFrameReady&&state.outputSettings.effectiveHeadingPolicy!=PublicationPolicy.OFF)Text(tr("Heading is selected but will wait for vessel zero, explicit heading alignment and a secure vessel mount. Other ready streams continue.","已选择船首向，但会等待船体零点、明确艏向对齐和牢固安装；其他已就绪数据流会继续。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
   if(state.outputSettings.purpose==NmeaOutputPurpose.BOAT_BUS_INJECTION&&!motionFrameReady&&state.outputSettings.effectiveMotionPolicy!=PublicationPolicy.OFF)Text(tr("Motion is selected but will wait for vessel-zero calibration and a secure mount. Other ready streams continue.","已选择船体运动，但会等待船体零点校准和牢固安装；其他已就绪数据流会继续。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
   if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(localizeKnownMessage(state.connectionAttempt.message),Modifier.testTag("nmea_output_error"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
  }}}
  item{Card(Modifier.testTag("nmea_output_route")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text(tr("Boat Gateway","船载网关"),style=MaterialTheme.typography.titleMedium)
   if(!state.outputSettings.transportConfigured)Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Text(tr("Choose where App/phone NMEA should be sent. Nothing is enabled until you make this explicit choice.","请先明确选择 App / 手机 NMEA 的发送位置；完成选择前不会启用任何输出。"),Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.bodySmall)}
   else if(!state.outputSettings.anyStreamSelected)Text(tr("Next: select only the phone streams the boat needs, then test and explicitly start output.","下一步：只选择船端需要的手机数据流，完成测试后再明确启动输出。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
    Text(tr("Connection direction","连接方向"),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
    Text("Server → App  (RX)  · ${state.settings.profile.protocol} · ${state.settings.profile.host}:${state.settings.profile.port}",style=MaterialTheme.typography.bodySmall)
    val outputEndpoint=if(state.outputSettings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION)state.settings.profile.host to state.settings.profile.port else state.outputSettings.outputHost to state.outputSettings.outputPort
    Text("App → Server  (TX) · ${outputTransportLabel(state.outputSettings.transportMode)} · ${outputEndpoint.first}:${outputEndpoint.second}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
   }}
   SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
    SegmentedButton(state.outputSettings.transportConfigured&&state.outputSettings.transportMode==NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,{vm.setNmeaOutputEndpoint(NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION,host,port.toIntOrNull()?:10110)},enabled=!outputActive&&state.settings.profile.protocol==Protocol.TCP,shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Same TCP socket","同一 TCP Socket"))}
    SegmentedButton(state.outputSettings.transportConfigured&&state.outputSettings.transportMode==NmeaOutputTransportMode.DEDICATED_TCP,{vm.setNmeaOutputEndpoint(NmeaOutputTransportMode.DEDICATED_TCP,host,port.toIntOrNull()?:10110)},enabled=!outputActive,shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Separate TX port","独立发送端口"))}
   }
   TextButton({showAdvancedTransport=!showAdvancedTransport},Modifier.align(Alignment.End)){Text(if(showAdvancedTransport)tr("Hide advanced transport","收起高级传输")else tr("Advanced transport","高级传输"))}
   if(showAdvancedTransport){
    Text(tr("UDP is advanced and connectionless: a successful send cannot prove that the receiver consumed it.","UDP 属于高级无连接传输：发送成功不能证明接收端已经处理。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
     SegmentedButton(state.outputSettings.transportConfigured&&state.outputSettings.transportMode==NmeaOutputTransportMode.UDP_UNICAST,{vm.setNmeaOutputEndpoint(NmeaOutputTransportMode.UDP_UNICAST,host,port.toIntOrNull()?:10110)},enabled=!outputActive,shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("UDP unicast","UDP 单播"))}
     SegmentedButton(state.outputSettings.transportConfigured&&state.outputSettings.transportMode==NmeaOutputTransportMode.UDP_BROADCAST,{vm.setNmeaOutputEndpoint(NmeaOutputTransportMode.UDP_BROADCAST,host.ifBlank{"255.255.255.255"},port.toIntOrNull()?:10110)},enabled=!outputActive,shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("UDP broadcast","UDP 广播"))}
    }
   }
   Text(when(state.outputSettings.transportMode){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->tr("Use only when the NMEA server explicitly supports bidirectional traffic on one TCP socket.","仅在 NMEA 服务器明确支持同一 TCP Socket 双向传输时使用。");NmeaOutputTransportMode.DEDICATED_TCP->tr("This write-only TCP client is independent from NMEA input. A TX failure never closes or restarts RX.","这个只写 TCP 客户端与 NMEA 输入完全独立；TX 失败不会关闭或重启 RX。");NmeaOutputTransportMode.UDP_UNICAST,NmeaOutputTransportMode.UDP_BROADCAST->tr("UDP output is independent from NMEA input and has no receiver acknowledgement.","UDP 输出与 NMEA 输入相互独立，并且没有接收确认。")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(!state.outputSettings.transportConfigured||state.outputSettings.transportMode!=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(host,{host=it},Modifier.weight(1f).testTag("nmea_output_tx_host"),singleLine=true,enabled=!outputActive,label={Text(tr("TX host","发送主机"))});OutlinedTextField(port,{port=it.filter(Char::isDigit).take(5)},Modifier.width(110.dp).testTag("nmea_output_tx_port"),singleLine=true,enabled=!outputActive,label={Text(tr("Port","端口"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))};TextButton({host=state.settings.profile.host},enabled=!outputActive&&state.settings.profile.host.isNotBlank()){Text(tr("Use current input host","使用当前输入主机"))};val endpointMode=state.outputSettings.transportMode.takeIf{state.outputSettings.transportConfigured&&it!=NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION}?:NmeaOutputTransportMode.DEDICATED_TCP;Button({vm.setNmeaOutputEndpoint(endpointMode,host,port.toIntOrNull()?:0)},Modifier.fillMaxWidth(),enabled=!outputActive&&(!state.outputSettings.transportConfigured||host.trim()!=state.outputSettings.outputHost||port.toIntOrNull()!=state.outputSettings.outputPort)){Text(when(endpointMode){NmeaOutputTransportMode.DEDICATED_TCP->tr("Save dedicated TX endpoint","保存独立发送端点");NmeaOutputTransportMode.UDP_UNICAST->tr("Save UDP unicast destination","保存 UDP 单播目标");NmeaOutputTransportMode.UDP_BROADCAST->tr("Save UDP broadcast destination","保存 UDP 广播目标");else->tr("Save output destination","保存输出目标")})}}
   Text(tr("Boat input is never forwarded. Recently transmitted sentences are quarantined if a gateway echoes them back.","船载输入绝不会原样转发；网关回显最近发送的语句时会被隔离。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(state.outputSettings.transportMode==NmeaOutputTransportMode.DEDICATED_TCP&&state.outputSettings.outputHost.equals(state.settings.profile.host,true)&&state.outputSettings.outputPort==state.settings.profile.port)Text(tr("TX matches the RX endpoint. Use this only when the gateway explicitly accepts a second client on that port; echoed App sentences remain quarantined.","TX 与 RX 端点相同。只有在网关明确支持该端口的第二个客户端时才应这样使用；应用发送后被回显的语句仍会被隔离。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
   Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
    Text("${outputTransportLabel(state.phonePositionOutputStatus.mode)} · ${state.phonePositionOutputStatus.endpointHost}:${state.phonePositionOutputStatus.endpointPort}",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
    Text("● ${txConnectionLabel(state.phonePositionOutputStatus.connectionState)} · ${tr("last socket TX","最近 Socket 发送")} ${txAge(state.phonePositionOutputStatus.lastWriteElapsed)}",style=MaterialTheme.typography.bodySmall,color=if(state.phonePositionOutputStatus.connectionState==com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.CONNECTED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    Text(tr("${state.phonePositionOutputStatus.writtenSentences} written / ${state.phonePositionOutputStatus.attemptedSentences} attempted · ${state.phonePositionOutputStatus.bytesWritten} bytes","${state.phonePositionOutputStatus.writtenSentences} 条写入 / ${state.phonePositionOutputStatus.attemptedSentences} 条尝试 · ${state.phonePositionOutputStatus.bytesWritten} 字节"),style=MaterialTheme.typography.labelSmall)
   }}
   if(state.outputSettings.purpose==NmeaOutputPurpose.BOAT_BUS_INJECTION){
   Text(tr("Phone as Vessel Sensor","手机作为船载传感器"),style=MaterialTheme.typography.titleMedium)
   Text(tr("App sensors keep working even when publication is Off. Backup waits for the external source to fail; Always may create duplicate sources.","即使发布关闭，App 内的手机传感器仍会工作。备用会等待外部来源失效；始终发送可能制造重复来源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   PublicationPolicyRow(tr("Phone Position (GPS)","手机船位 (GPS)"),state.outputSettings.effectivePositionPolicy,canEnable||state.outputSettings.effectivePositionPolicy!=PublicationPolicy.OFF){setPolicy("position",it)}
   HorizontalDivider()
   PublicationPolicyRow(tr("Phone vessel heading (HDG/HDT)","手机船体船首向 (HDG/HDT)"),state.outputSettings.effectiveHeadingPolicy,outputReady||state.outputSettings.effectiveHeadingPolicy!=PublicationPolicy.OFF){setPolicy("heading",it)}
   if(state.outputSettings.phoneHeadingEnabled){Text(tr("Heading sentence","船首向语句"),style=MaterialTheme.typography.labelLarge);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){PhoneHeadingOutputFormat.entries.forEachIndexed{index,format->SegmentedButton(state.outputSettings.phoneHeadingFormat==format,{vm.setPhoneHeadingOutputFormat(format)},shape=SegmentedButtonDefaults.itemShape(index,PhoneHeadingOutputFormat.entries.size)){Text(when(format){PhoneHeadingOutputFormat.HDT_TRUE->"HDT";PhoneHeadingOutputFormat.HDG_MAGNETIC->"HDG";PhoneHeadingOutputFormat.HDT_AND_HDG->"HDT+HDG"})}}}}
   if(state.outputSettings.phoneHeadingEnabled&&!state.phoneHeading.declinationReferenceReady)Text(tr("Waiting for a valid position before publishing true heading.","正在等待有效位置；取得磁偏角参考后才会发送真船首向。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
   PublicationPolicyRow(tr("Phone vessel motion (ROT/XDR)","手机船体运动 (ROT/XDR)"),state.outputSettings.effectiveMotionPolicy,state.outputSettings.effectiveMotionPolicy!=PublicationPolicy.OFF||outputReady){setPolicy("motion",it)}
   PublicationPolicyRow(tr("Phone pressure (BARO)","手机气压 (BARO)"),state.outputSettings.effectivePressurePolicy,state.outputSettings.effectivePressurePolicy!=PublicationPolicy.OFF||outputReady){setPolicy("pressure",it)}
   if(state.outputSettings.effectivePressurePolicy!=PublicationPolicy.OFF&&!state.phoneSensorCapabilities.pressureAvailable)Text(tr("This phone has no pressure sensor; BARO remains in Standby without blocking other streams.","此手机没有气压传感器；BARO 会保持备用状态，不会阻塞其他数据流。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
   PublicationPolicyRow(tr("Derived true wind (TWS/TWA/TWD)","推算真风 (TWS/TWA/TWD)"),state.outputSettings.derivedWindPolicy,outputReady||state.outputSettings.derivedWindPolicy!=PublicationPolicy.OFF){setPolicy("wind",it)}
   SettingSwitch(tr("Yokuli sensor status","Yokuli 传感器状态"),tr("Optional PYOK diagnostic sentence","可选 PYOK 诊断语句"),state.outputSettings.proprietaryStatusEnabled,outputReady||state.outputSettings.proprietaryStatusEnabled){setOutput("status",it)}
   }else Text(tr("Canonical feed includes each fresh selected Position/SOG/COG, Heading, STW, apparent/true wind, Depth, ROT and Pressure value automatically.","统一客户端流会自动包含每项新鲜且已选中的船位/SOG/COG、船首向、对水航速、视风/真风、水深、转向率和气压。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(state.outputSettings.anyEnabled)Text("${localizeKnownMessage(state.phonePositionOutputStatus.message)}${state.phonePositionOutputStatus.sentenceTypes.takeIf{it.isNotEmpty()}?.joinToString(prefix=" · ").orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
   state.phonePositionOutputStatus.lastError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
   OutlinedButton({testResult=null;vm.testNmeaDeviceOutput{success->testResult=if(success)testWrittenMessage else testFailedMessage}},Modifier.fillMaxWidth(),enabled=outputReady&&!outputActive){Text(tr("Test NMEA output","测试 NMEA 输出"))}
   testResult?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=if(it.contains("failed")||it.contains("失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
   TextButton({showPublisherDiagnostics=!showPublisherDiagnostics},Modifier.fillMaxWidth()){
    Icon(if(showPublisherDiagnostics)Icons.Default.ExpandLess else Icons.Default.Troubleshoot,null);Spacer(Modifier.width(6.dp));Text(if(showPublisherDiagnostics)tr("Hide stream diagnostics","收起数据流诊断")else tr("Stream diagnostics & recent TX","数据流诊断与最近发送"))
   }
   if(showPublisherDiagnostics){
    state.phonePositionOutputStatus.streams.filterKeys{it in setOf("POSITION","HEADING","MOTION","PRESSURE","DERIVED_WIND","STATUS","CANONICAL_FEED")}.forEach{(stream,status)->
     Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
      Text("${publisherStreamLabel(stream)} · ${publisherReadinessLabel(status.readiness)}",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,color=if(status.readiness in setOf(NmeaStreamReadiness.READY,NmeaStreamReadiness.PUBLISHING))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
      Text(publisherOwnershipLabel(status.ownership),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      Text(tr("Generated ${status.generatedCount} · written ${status.writtenCount} · dropped ${status.droppedCount}","生成 ${status.generatedCount} · 写入 ${status.writtenCount} · 丢弃 ${status.droppedCount}"),style=MaterialTheme.typography.labelSmall)
      Text("${"%.1f".format(status.generatedRateHz)} Hz → ${"%.1f".format(status.socketWriteRateHz)} Hz · seq ${status.lastWrittenSequence}/${status.lastGeneratedSequence}${status.suppressionReason?.let{" · ${publisherSuppressionLabel(it)}"}.orEmpty()}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }}
    }
     TextButton({confirmHeadingDiagnostic=true},Modifier.fillMaxWidth(),enabled=outputReady&&!outputActive){Text(tr("Developer diagnostic · 5 × IIHDG 123.4°","开发诊断 · 发送 5 条 IIHDG 123.4°"))}
   }
   Text(tr("Safety rule: Phone GPS output and NMEA Position input are mutually exclusive. NMEA depth, wind, heading, sonar and raw data continue normally.","安全规则：手机 GPS 输出与 NMEA 位置输入互斥；NMEA 水深、风、船首向、声呐和原始数据仍可正常使用。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
  }}}
  item{Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text(tr("NMEA Sharing Server","NMEA 共享服务器"),style=MaterialTheme.typography.titleMedium)
   Text(tr("Shares the App's accepted navigation stream to other clients on port ${state.settings.nmeaSharingPort}.","通过端口 ${state.settings.nmeaSharingPort} 向其他客户端共享本应用已接受的导航数据流。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   SettingSwitch(tr("Sharing server","共享服务器"),tr("${state.nmeaSharing.clientCount} connected clients","${state.nmeaSharing.clientCount} 个客户端已连接"),state.settings.nmeaSharingEnabled){vm.setNmeaSharing(it,state.settings.nmeaSharingPort)}
  }}}
  if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)item{Text(localizeKnownMessage(state.connectionAttempt.message),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
 }
 pendingEnable?.let{id->AlertDialog(onDismissRequest={pendingEnable=null},title={Text(if(id=="position")tr("Boat GPS is already present","已经存在船载 GPS")else tr("Boat heading is already present","已经存在船载船首向"))},text={Text(if(id=="position")tr("Sending Phone GPS may create duplicate position sources and make an MFD alternate between them.","发送手机 GPS 可能制造重复船位源，导致 MFD 在两者之间跳变。")else tr("Sending Phone heading may create duplicate heading sources and make an MFD alternate between them.","发送手机船首向可能制造重复方位源，导致 MFD 在两者之间跳变。"))},confirmButton={Button({pendingEnable=null;applyOutput(id,true)}){Text(tr("Send anyway","仍然发送"))}},dismissButton={TextButton({pendingEnable=null}){Text(tr("Cancel","取消"))}})}
 if(confirmHeadingDiagnostic)AlertDialog(onDismissRequest={confirmHeadingDiagnostic=false},title={Text(tr("Transmit diagnostic heading?","发送诊断船首向？"))},text={Text(tr("This sends five IIHDG sentences claiming 123.4°. Do not run it while an autopilot, heading-dependent display or another vessel heading source is in operational use.","这会发送 5 条声称船首向为 123.4° 的 IIHDG 语句。自动驾驶、依赖船首向的显示设备或其他船首向源正在工作时，绝对不要测试。"))},confirmButton={Button({confirmHeadingDiagnostic=false;testResult=null;vm.testKnownGoodHdgOutput{success->testResult=if(success)testWrittenMessage else testFailedMessage}}){Text(tr("Send 5 test sentences","发送 5 条测试语句"))}},dismissButton={TextButton({confirmHeadingDiagnostic=false}){Text(tr("Cancel","取消"))}})
}

@Composable private fun deviceBowAxisLabel(value:DeviceBowAxis)=when(value){DeviceBowAxis.TOP->tr("Top edge","上边");DeviceBowAxis.BOTTOM->tr("Bottom edge","下边");DeviceBowAxis.LEFT->tr("Left edge","左边");DeviceBowAxis.RIGHT->tr("Right edge","右边")}
@Composable private fun outputTransportLabel(value:NmeaOutputTransportMode)=when(value){NmeaOutputTransportMode.SAME_AS_INPUT_CONNECTION->tr("Same input TCP socket","同一输入 TCP Socket");NmeaOutputTransportMode.DEDICATED_TCP->tr("Dedicated TCP","独立 TCP");NmeaOutputTransportMode.UDP_UNICAST->tr("UDP unicast","UDP 单播");NmeaOutputTransportMode.UDP_BROADCAST->tr("UDP broadcast","UDP 广播")}

@Composable private fun PublicationPolicyRow(title:String,value:PublicationPolicy,enabled:Boolean,onChange:(PublicationPolicy)->Unit){
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text(title,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){PublicationPolicy.entries.forEachIndexed{index,policy->SegmentedButton(value==policy,{onChange(policy)},enabled=enabled||value==policy,shape=SegmentedButtonDefaults.itemShape(index,PublicationPolicy.entries.size)){Text(when(policy){PublicationPolicy.OFF->tr("Off","关闭");PublicationPolicy.BACKUP->tr("Backup","备用");PublicationPolicy.ALWAYS->tr("Always","始终")})}}}
 }
}

@Composable private fun txConnectionLabel(value:com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState)=when(value){
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.OFF->tr("Off","关闭")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.DISCONNECTED->tr("Disconnected","未连接")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.CONNECTING->tr("Connecting","连接中")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.CONNECTED->tr("Connected","已连接")
 com.yokuli.anchorwatch.data.nmea.output.NmeaTxConnectionState.ERROR->tr("Connection error","连接错误")
}
@Composable private fun publisherStreamLabel(value:String)=when(value){"POSITION"->tr("Position","船位");"HEADING"->tr("Vessel heading","船首向");"MOTION"->tr("Vessel motion","船体运动");"PRESSURE"->tr("Pressure","气压");"DERIVED_WIND"->tr("Derived true wind","推算真风");"CANONICAL_FEED"->tr("Canonical client feed","统一客户端流");else->tr("Sensor status","传感器状态")}
@Composable private fun publisherReadinessLabel(value:NmeaStreamReadiness)=when(value){NmeaStreamReadiness.READY->tr("Ready","已就绪");NmeaStreamReadiness.WAITING_CALIBRATION->tr("Waiting calibration","等待校准");NmeaStreamReadiness.WAITING_POSITION->tr("Waiting position","等待定位");NmeaStreamReadiness.STANDBY->tr("Standby","备用");NmeaStreamReadiness.PUBLISHING->tr("Publishing","正在发布")}
@Composable private fun publisherOwnershipLabel(value:com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState)=when(value){
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.STANDBY_EXTERNAL_PRESENT->tr("Standby · boat source present","备用 · 船载来源正常")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.TAKEOVER_PENDING->tr("Takeover pending","等待接管")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.PHONE_ACTIVE->tr("Phone active","手机已接管")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.SUPPRESSED->tr("Suppressed","已抑制")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.SOURCE_CONFLICT->tr("Duplicate/conflict","重复或冲突")
 com.yokuli.anchorwatch.domain.vessel.PublisherOwnershipState.ERROR->tr("Error","错误")
}
@Composable private fun publisherSuppressionLabel(value:String)=when(value){
 "USER_DISABLED"->tr("disabled by user","用户已关闭");"EXTERNAL_SOURCE_PRESENT"->tr("external source present","外部来源正常");"TAKEOVER_DELAY"->tr("takeover delay","等待接管");"PHONE_NOT_MOUNTED"->tr("phone not vessel-mounted","手机未固定在船体");"HEADING_NOT_ALIGNED"->tr("heading alignment not confirmed","尚未确认艏向对齐");"MOUNT_SUSPECT"->tr("mount movement detected","检测到安装移动");"NO_DECLINATION_REFERENCE"->tr("no declination reference","缺少磁偏角参考");"PHONE_HEADING_STALE"->tr("phone heading stale","手机方位已过期");"PHONE_GPS_STALE"->tr("phone GPS stale","手机 GPS 已过期");"NO_DERIVED_WIND"->tr("derived wind unavailable","无法推算真风");"OUTPUT_DISCONNECTED"->tr("output disconnected","输出未连接");"SOURCE_CONFLICT"->tr("source conflict","来源冲突");else->value.lowercase().replace('_',' ')
}

@Composable private fun GpsDataSourceCard(state:MainUiState,vm:MainViewModel){
 val switching=state.connectionAttempt.state==ConnectionAttemptState.TESTING
 val sessionOpen=state.active!=null
 val sessionPaused=state.active?.paused==true
 val lockedSource=state.active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
 val proxyActive=GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
 val nmeaAvailability=NmeaSourceSelectionPolicy.availability(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1000L)
 val nmeaQualityReady=com.yokuli.anchorwatch.location.NmeaFixQualityPolicy.allowsContinuation(state.nmeaFix)
 val nmeaReady=NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1_000L)&&!state.outputSettings.phonePositionPublishing
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
   GpsSourceRow("NMEA GPS",if(state.outputSettings.phonePositionPublishing)tr("Unavailable while Phone GPS is being shared to the boat network. Stop Phone Position output first.","手机 GPS 正在共享到船载网络时不可用；请先停止“手机位置输出”。")else if(nmeaAvailability==NmeaSourceAvailability.AVAILABLE&&!nmeaQualityReady)tr("The current NMEA fix has unacceptable quality; wait for fix quality/HDOP to recover.","当前 NMEA 定位质量不合格；请等待定位质量或 HDOP 恢复。")else when(nmeaAvailability){NmeaSourceAvailability.AVAILABLE->if(sessionPaused&&lockedSource!=GpsDataSource.NMEA)tr("Fresh NMEA position ready for this paused session.","已有新鲜 NMEA 定位，可绑定到当前暂停会话。")else tr("Connected with a fresh valid position.","连接正常，且已有新鲜有效的定位。");NmeaSourceAvailability.NOT_CONNECTED->tr("Connect the NMEA server before selecting this source.","请先连接 NMEA 服务器，之后才能选择此数据源。");NmeaSourceAvailability.NO_VALID_FIX->tr("Connected, but waiting for a valid NMEA position.","服务器已连接，但仍在等待有效的 NMEA 定位。");NmeaSourceAvailability.STALE_FIX->tr("The last NMEA position is stale; wait for a fresh fix.","最后一个 NMEA 定位已过期，请等待新定位。")},lockedSource?.let{it==GpsDataSource.NMEA}?: (state.settings.gpsDataSource==GpsDataSource.NMEA),(!sessionOpen||sessionPaused)&&!switching&&nmeaReady,"gps_source_nmea"){vm.switchGpsDataSource(GpsDataSource.NMEA)}
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
  Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(tr("One-time Android setup","Android 一次性设置"),fontWeight=FontWeight.SemiBold);Text(tr("1. Settings → About phone → tap Build number 7 times.\n2. Settings → System (or Additional settings) → Developer options.\n3. Select mock location app → Anchor Watch.\n4. Return here and tap Enable global GPS proxy.","1. 设置 → 关于手机 → 连续点击版本号 7 次。\n2. 设置 → 系统（或更多设置）→ 开发者选项。\n3. 选择模拟位置信息应用 → Anchor Watch。\n4. 返回这里，点击开启全局 GPS 代理。"),style=MaterialTheme.typography.bodySmall)}}
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
   Button({export.launch("Anchor-Watch-support-${java.time.LocalDate.now()}.zip")},Modifier.fillMaxWidth(),enabled=!state.supportBundle.running){Icon(Icons.Default.BugReport,null);Spacer(Modifier.width(6.dp));Text(tr("Export diagnostics","导出诊断包"))}
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
  "Connect a writable TCP NMEA input endpoint before enabling same-socket output."->"启用同 Socket 输出前，请先连接可写入的 TCP NMEA 输入端点。"
  "Enter a valid dedicated TCP output host and port first."->"请先填写有效的独立 TCP 输出主机和端口。"
  "Enter a valid UDP output host and port first."->"请先填写有效的 UDP 输出主机和端口。"
  "The NMEA connection has not supplied a valid position yet."->"NMEA 连接尚未提供有效位置。"
  "Checking Android mock-location access…"->"正在检查 Android 模拟位置权限…"
  "Android GPS is using the normal system source."->"Android GPS 正在使用正常的系统数据源。"
  "NMEA is feeding Fused Location and GPS_PROVIDER."->"NMEA 正在向融合定位和 GPS_PROVIDER 提供位置。"
  "NMEA is feeding Fused Location. Direct GPS compatibility is unavailable."->"NMEA 正在向融合定位提供位置；直接 GPS 兼容模式不可用。"
  "GPS proxy was not enabled. Turn on Developer Options and select Anchor Watch as the location override app."->"GPS 代理未开启。请启用开发者选项，并将 Anchor Watch 设为模拟位置应用。"
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
