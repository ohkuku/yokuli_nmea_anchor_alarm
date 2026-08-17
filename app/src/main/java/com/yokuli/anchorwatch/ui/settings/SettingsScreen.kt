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
import com.yokuli.anchorwatch.domain.sonar.DepthReference
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.ui.theme.SafetyColors
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat

private enum class SettingsDestination{ROOT,ALARM,VESSEL,DEPTH_SOUNDER,POSITIONING,MAP_DEPTH,BACKGROUND,DATA_BACKUP,STORAGE_SUPPORT,DEVELOPER}

@Composable internal fun SettingsScreen(state:MainUiState,vm:MainViewModel)=SettingsPageContent(state,vm)

@Composable internal fun SettingsPageContent(state:MainUiState,vm:MainViewModel){
 var destination by rememberSaveable{mutableStateOf(SettingsDestination.ROOT)};var languageDialog by remember{mutableStateOf(false)};val chinese=state.settings.appLanguage==AppLanguage.SIMPLIFIED_CHINESE||(state.settings.appLanguage==AppLanguage.SYSTEM&&state.settings.appLanguage.usesChinese());BackHandler(destination!=SettingsDestination.ROOT){destination=SettingsDestination.ROOT}
 if(destination==SettingsDestination.ROOT)SettingsRoot(state,{destination=it},{languageDialog=true}) else SettingsSubPage(destination,state,vm){destination=SettingsDestination.ROOT}
 if(languageDialog)AlertDialog(onDismissRequest={languageDialog=false},title={Text(tr("Language","语言"))},text={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){FilterChip(chinese,{vm.updateSettings(state.settings.copy(appLanguage=AppLanguage.SIMPLIFIED_CHINESE));languageDialog=false},label={Text("🇨🇳",style=MaterialTheme.typography.headlineMedium)},modifier=Modifier.testTag("language_zh"));FilterChip(!chinese,{vm.updateSettings(state.settings.copy(appLanguage=AppLanguage.ENGLISH));languageDialog=false},label={Text("🇬🇧",style=MaterialTheme.typography.headlineMedium)},modifier=Modifier.testTag("language_en"))}},confirmButton={})
}

@Composable private fun SettingsRoot(state:MainUiState,open:(SettingsDestination)->Unit,language:()->Unit){
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp).testTag("settings_list"),contentPadding=PaddingValues(vertical=18.dp)){
  item{PageHeader(tr("Settings","设置"),tr("Choose a section; runtime NMEA and sonar controls stay in Data.","选择要配置的类别；NMEA 与声呐运行操作仍在“数据”页面。"));Spacer(Modifier.height(18.dp))}
  item{SettingsSection(tr("ALARM & WATCH","报警与监控"));SettingsRow(Icons.Default.NotificationsActive,tr("Alarm & notifications","报警与通知"),tr("Anchor alarm · ${state.settings.alarmSnoozeMinutes} min snooze","锚警 · ${state.settings.alarmSnoozeMinutes} 分钟后提醒"),"settings_alarm"){open(SettingsDestination.ALARM)}}
  item{SettingsSection(tr("VESSEL & SENSORS","船舶与传感器"));SettingsRow(Icons.Default.Sailing,tr("Vessel profile","船舶资料"),tr("${state.settings.boatLengthMeters} m · bow ${state.settings.bowRollerHeightMeters} m","${state.settings.boatLengthMeters} 米 · 船艏 ${state.settings.bowRollerHeightMeters} 米"),"settings_vessel"){open(SettingsDestination.VESSEL)};SettingsRow(Icons.Default.Waves,tr("Depth sounder","测深仪"),tr("Depth offset ${signed(state.settings.sounderOffsetMeters)} m","水深修正 ${signed(state.settings.sounderOffsetMeters)} 米"),"settings_depth_sounder"){open(SettingsDestination.DEPTH_SOUNDER)}}
  item{SettingsSection(tr("POSITION & MAP","定位与地图"));SettingsRow(Icons.Default.GpsFixed,tr("Positioning","定位"),tr("Default: ${state.settings.gpsDataSource.name} GPS","默认：${state.settings.gpsDataSource.name} GPS"),"settings_positioning"){open(SettingsDestination.POSITIONING)};SettingsRow(Icons.Default.Layers,tr("Map & depth","地图与水深"),tr("${if(state.settings.mapType==2)tr("Satellite","卫星")else tr("Default","默认")} · LINZ · Sonar","${if(state.settings.mapType==2)tr("卫星","卫星")else tr("默认","默认")} · LINZ · 声呐"),"settings_map_depth"){open(SettingsDestination.MAP_DEPTH)}}
  item{SettingsSection(tr("DEVICE & DATA","设备与数据"));SettingsRow(Icons.Default.BatterySaver,tr("Background reliability","后台可靠性"),tr("Permissions, power and Wi-Fi","权限、电源与 Wi-Fi"),"settings_background"){open(SettingsDestination.BACKGROUND)};SettingsRow(Icons.Default.Backup,tr("Data & backup","数据与备份"),tr("Export or replace from a Yokuli backup","导出或从 Yokuli 备份替换恢复"),"settings_data_backup"){open(SettingsDestination.DATA_BACKUP)};SettingsRow(Icons.Default.Storage,tr("Storage & support","存储与支持"),tr("Health, incident log and diagnostics","健康、事件日志与诊断包"),"settings_storage_support"){open(SettingsDestination.STORAGE_SUPPORT)};SettingsRow(Icons.Default.Language,tr("Language","语言"),if(state.settings.appLanguage==AppLanguage.SIMPLIFIED_CHINESE)"中文" else "English","settings_language",language)}
  item{SettingsSection(tr("ADVANCED","高级"));SettingsRow(Icons.Default.DeveloperMode,tr("Developer","开发者"),if(state.settings.demoMode)tr("Demo mode on","演示模式已开启")else tr("Demo mode off","演示模式已关闭"),"settings_developer"){open(SettingsDestination.DEVELOPER)}}
 }
}

@Composable private fun SettingsSubPage(destination:SettingsDestination,state:MainUiState,vm:MainViewModel,back:()->Unit){Column(Modifier.fillMaxSize()){Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Filled.ArrowBack,tr("Back","返回"))};Text(when(destination){SettingsDestination.ALARM->tr("Alarm & notifications","报警与通知");SettingsDestination.VESSEL->tr("Vessel profile","船舶资料");SettingsDestination.DEPTH_SOUNDER->tr("Depth sounder","测深仪");SettingsDestination.POSITIONING->tr("Positioning","定位");SettingsDestination.MAP_DEPTH->tr("Map & depth","地图与水深");SettingsDestination.BACKGROUND->tr("Background reliability","后台可靠性");SettingsDestination.DATA_BACKUP->tr("Data & backup","数据与备份");SettingsDestination.STORAGE_SUPPORT->tr("Storage & support","存储与支持");SettingsDestination.DEVELOPER->tr("Developer","开发者");else->""},style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)};LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{when(destination){SettingsDestination.ALARM->AlarmSettingsPage(state,vm);SettingsDestination.VESSEL->VesselProfileCard(state,vm);SettingsDestination.DEPTH_SOUNDER->DepthSounderPage(state,vm);SettingsDestination.POSITIONING->GpsDataSourceCard(state,vm);SettingsDestination.MAP_DEPTH->MapDepthSettingsPage(state,vm);SettingsDestination.BACKGROUND->BackgroundReliabilityCard(state,vm);SettingsDestination.DATA_BACKUP->DataBackupPage(state,vm);SettingsDestination.STORAGE_SUPPORT->StorageSupportPage(state,vm);SettingsDestination.DEVELOPER->DeveloperSettingsCard(state,vm);else->{}}}}}}

@Composable private fun DataBackupPage(state:MainUiState,vm:MainViewModel){
 var privacyConfirm by remember{mutableStateOf(false)};var restoreUri by remember{mutableStateOf<Uri?>(null)}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)vm.exportBackup(uri)}
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)restoreUri=uri}
 val blocked=state.active!=null||state.activeSonarSurvey!=null||state.settings.mockEnabled||state.settings.nmeaSharingEnabled
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("BACKUP","备份"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(tr("Export Yokuli backup","导出 Yokuli 备份"),style=MaterialTheme.typography.titleMedium);Text(tr("Includes settings, anchor history, tracks, alarm events, sonar surveys and raw soundings. Derived chart and grid caches are rebuilt after restore.","包含设置、锚泊历史、轨迹、报警事件、声呐调查和原始测深点。海图与网格派生缓存会在恢复后重建。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);state.backup.lastBackupAt?.let{Text(tr("Last backup: ${DateFormat.getDateTimeInstance().format(java.util.Date(it))}","上次备份：${DateFormat.getDateTimeInstance().format(java.util.Date(it))}"),style=MaterialTheme.typography.bodySmall)};Button({privacyConfirm=true},Modifier.fillMaxWidth(),enabled=!state.backup.running){Icon(Icons.Default.FileUpload,null);Spacer(Modifier.width(6.dp));Text(tr("Export Yokuli backup","导出 Yokuli 备份"))}}}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("RESTORE · REPLACE","恢复 · 替换"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.error);Text(tr("Restore from Yokuli backup","从 Yokuli 备份恢复"),style=MaterialTheme.typography.titleMedium);Text(tr("The archive is fully checked before local data changes. Restore replaces local Yokuli history; merge is intentionally unavailable.","备份会先完成全部校验，再改动本机数据。恢复会替换本机 Yokuli 历史；本版本故意不提供合并。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(blocked)Text(tr("End the anchor session and sonar survey, disable GPS proxy and NMEA Sharing before restore.","恢复前必须结束锚泊会话和声呐调查，并关闭 GPS 代理与 NMEA 共享。"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);OutlinedButton({restore.launch(arrayOf("application/zip","application/octet-stream","*/*"))},Modifier.fillMaxWidth(),enabled=!blocked&&!state.backup.running){Icon(Icons.Default.Restore,null);Spacer(Modifier.width(6.dp));Text(tr("Choose backup to restore","选择要恢复的备份"))}}}
  if(state.backup.running){LinearProgressIndicator(Modifier.fillMaxWidth());Text(state.backup.progress,style=MaterialTheme.typography.bodySmall)}
  state.backup.result?.let{AssistChip(vm::clearBackupResult,{Text(localizeKnownMessage(it))},leadingIcon={Icon(Icons.Default.CheckCircle,null)})}
  state.backup.error?.let{Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp)){Text(localizeKnownMessage(it),color=MaterialTheme.colorScheme.onErrorContainer);TextButton(vm::clearBackupResult){Text(tr("Dismiss","关闭"))}}}}
 }
 if(privacyConfirm)AlertDialog(onDismissRequest={privacyConfirm=false},title={Text(tr("Precise location history","精确位置历史"))},text={Text(tr("This backup contains anchoring locations, tracks and sonar positions. Protect it like a vessel logbook. Yokuli backup v1 is not encrypted.","此备份包含锚泊位置、轨迹和声呐坐标。请像保护航海日志一样保护该文件；Yokuli 备份 v1 未加密。"))},confirmButton={Button({privacyConfirm=false;export.launch("Yokuli-${java.time.LocalDate.now()}.yokuli-backup")}){Text(tr("Choose export location","选择导出位置"))}},dismissButton={TextButton({privacyConfirm=false}){Text(tr("Cancel","取消"))}})
 restoreUri?.let{uri->AlertDialog(onDismissRequest={restoreUri=null},title={Text(tr("Replace all local Yokuli data?","替换全部本机 Yokuli 数据？"))},text={Text(tr("Validation happens first. If it passes, local anchor and sonar history will be replaced in one database transaction. This cannot be undone without another backup.","系统会先完整校验；通过后，本机锚泊与声呐历史将在一个数据库事务中被替换。除非另有备份，否则无法撤销。"))},confirmButton={Button({restoreUri=null;vm.restoreBackup(uri)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Validate & replace","校验并替换"))}},dismissButton={TextButton({restoreUri=null}){Text(tr("Cancel","取消"))}})}
}

@Composable private fun SettingsSection(title:String){Text(title,Modifier.padding(top=14.dp,bottom=5.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}
@Composable private fun SettingsRow(icon:ImageVector,title:String,summary:String,tag:String,click:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=66.dp).testTag(tag).clickable(onClick=click).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(16.dp));Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);Text(summary,maxLines=1,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)};HorizontalDivider()}
internal fun signed(value:Double)=if(value>=0)"+${"%.1f".format(value)}" else "%.1f".format(value)

@Composable private fun AlarmSettingsPage(state:MainUiState,vm:MainViewModel){
 var radius by remember(state.settings.preferredAlarmRadiusMeters){mutableStateOf(state.settings.preferredAlarmRadiusMeters.toString())};val value=radius.toDoubleOrNull()
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){AlarmBehaviourCard(state,vm);Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Default range","默认范围"),style=MaterialTheme.typography.titleMedium);OutlinedTextField(radius,{radius=it.filter{c->c.isDigit()||c=='.'}},label={Text(tr("Preferred alarm radius","首选报警半径"))},suffix={Text(tr("m","米"))},isError=value==null||value<=0,modifier=Modifier.fillMaxWidth());Button({vm.updateSettings(state.settings.copy(preferredAlarmRadiusMeters=value!!))},enabled=value!=null&&value>0&&value!=state.settings.preferredAlarmRadiusMeters,modifier=Modifier.fillMaxWidth()){Text(tr("Save range","保存范围"))}}}}
}

@Composable private fun DepthSounderPage(state:MainUiState,vm:MainViewModel){
 var offset by remember(state.settings.sounderOffsetMeters){mutableStateOf(state.settings.sounderOffsetMeters.toString())};var saved by remember{mutableStateOf(false)};val value=offset.toDoubleOrNull();val valid=value!=null&&value in -20.0..20.0;val dirty=valid&&value!=state.settings.sounderOffsetMeters
 LaunchedEffect(dirty){if(dirty)saved=false}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(tr("Simple depth calibration","简化水深校准"),style=MaterialTheme.typography.titleMedium);Text(tr("Yokuli displays and maps: sounder-reported depth + this fixed offset.","Yokuli 显示并绘制：测深仪报告的水深 + 此固定修正值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric(tr("Sounder reports","测深仪报告"),state.sonarRecorder.lastRawDepthMeters?.let{"%.2f m".format(it)}?:"—");Metric(tr("After offset","修正后"),state.sonarRecorder.lastDepthMeters?.let{"%.2f m".format(it)}?:"—")}
  OutlinedTextField(offset,{text->offset=text.filterIndexed{index,c->c.isDigit()||c=='.'||(c=='-'&&index==0)}},label={Text(tr("Depth offset","水深 offset"))},prefix={Text(if((value?:0.0)>=0)"+" else "")},suffix={Text(tr("m","米"))},supportingText={Text(tr("Example: instrument 6.0 m, offset +0.4 m → Yokuli 6.4 m.","例如：仪器显示 6.0 米，offset 为 +0.4 米 → Yokuli 显示 6.4 米。"))},isError=!valid,modifier=Modifier.fillMaxWidth())
  Text(tr("GPS only places the sounding on the map. Its accuracy remains quality metadata and never changes the depth number.","GPS 只负责把测深点放到地图上；定位精度仅作为质量信息，不会修改水深数值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Button({vm.updateSettings(state.settings.copy(sounderOffsetMeters=value!!));saved=true},Modifier.fillMaxWidth(),enabled=dirty){Icon(Icons.Default.Save,null);Spacer(Modifier.width(6.dp));Text(if(saved)tr("Saved","已保存")else tr("Save offset","保存 offset"))}
 }}
}

@Composable private fun MapDepthSettingsPage(state:MainUiState,vm:MainViewModel){
 var showLinzDisclaimer by remember{mutableStateOf(false)};var showSonarDisclaimer by remember{mutableStateOf(false)}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(tr("Default base map","默认底图"),style=MaterialTheme.typography.labelLarge);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){SegmentedButton(state.settings.mapType==1,{vm.setMapType(1)},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Default","默认"))};SegmentedButton(state.settings.mapType==2,{vm.setMapType(2)},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Satellite","卫星"))}}
  HorizontalDivider();SettingSwitch(tr("LINZ chart image","LINZ 海图影像"),if(BuildConfig.LINZ_HYDRO_CONFIGURED)tr("Official hydrographic image overlay","官方水文海图影像叠加层")else tr("Not configured in this build","当前构建未配置"),state.settings.linzHydroEnabled,BuildConfig.LINZ_HYDRO_CONFIGURED){enabled->if(enabled&&!state.settings.linzHydroDisclaimerAccepted)showLinzDisclaimer=true else vm.updateSettings(state.settings.copy(linzHydroEnabled=enabled))}
  if(state.settings.linzHydroEnabled){Text(tr("LINZ opacity ${"%.0f".format(state.settings.linzHydroOpacity*100)}%","LINZ 不透明度 ${"%.0f".format(state.settings.linzHydroOpacity*100)}%"));Slider(state.settings.linzHydroOpacity.toFloat(),{vm.updateSettings(state.settings.copy(linzHydroOpacity=it.toDouble()))},valueRange=.30f..1f)}
  SettingSwitch(tr("Current-position LINZ depth","当前位置 LINZ 水深"),tr("Vector reference; never presented as live sonar","矢量海图参考；绝不会冒充实时声呐"),state.settings.showLinzDepthReference,BuildConfig.LINZ_API_KEY.isNotBlank()){vm.updateSettings(state.settings.copy(showLinzDepthReference=it))}
  Text(tr("LINZ vector status: ${state.linzDepth.status.name}","LINZ 矢量状态：${state.linzDepth.status.name}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  HorizontalDivider();SettingSwitch(tr("Personal sonar layer","个人声呐图层"),tr("Only this switch controls whether survey cells are drawn","只有此开关决定是否在地图上绘制调查网格"),state.settings.sonarLayerEnabled){enabled->if(enabled&&!state.settings.sonarDisclaimerAccepted)showSonarDisclaimer=true else vm.updateSettings(state.settings.copy(sonarLayerEnabled=enabled))}
  if(state.settings.sonarLayerEnabled){Text(tr("Sonar opacity ${"%.0f".format(state.settings.sonarLayerOpacity*100)}%","声呐不透明度 ${"%.0f".format(state.settings.sonarLayerOpacity*100)}%"));Slider(state.settings.sonarLayerOpacity.toFloat(),{vm.updateSettings(state.settings.copy(sonarLayerOpacity=it.toDouble()))},valueRange=.20f..1f)}
  SettingSwitch(tr("Current-position personal depth","当前位置个人水深"),tr("Show measured/interpolated status in Watch","在监控页显示实测/插值状态"),state.settings.showPersonalMapReference){vm.updateSettings(state.settings.copy(showPersonalMapReference=it))}
 }}
 if(showLinzDisclaimer)AlertDialog(onDismissRequest={showLinzDisclaimer=false},title={Text(tr("LINZ hydrographic chart overlay","LINZ 水文海图叠加层"))},text={Text(tr("This chart image layer is an aid only and does not replace official charts, Notices to Mariners, depth instruments or a passage plan.","该海图影像层仅供辅助，不能替代官方海图、航海通告、测深仪或航行计划。"))},confirmButton={Button({vm.updateSettings(state.settings.copy(linzHydroEnabled=true,linzHydroDisclaimerAccepted=true));showLinzDisclaimer=false}){Text(tr("I understand · Enable","我已了解 · 开启"))}},dismissButton={TextButton({showLinzDisclaimer=false}){Text(tr("Cancel","取消"))}})
 if(showSonarDisclaimer)SonarSafetyDisclaimerDialog({showSonarDisclaimer=false}){vm.updateSettings(state.settings.copy(sonarLayerEnabled=true,sonarDisclaimerAccepted=true));showSonarDisclaimer=false}
}

@Composable private fun VesselProfileCard(state:MainUiState,vm:MainViewModel){
 var boat by remember(state.settings.boatLengthMeters){mutableStateOf(state.settings.boatLengthMeters.toString())}
 var bow by remember(state.settings.bowRollerHeightMeters){mutableStateOf(state.settings.bowRollerHeightMeters.toString())}
 var antenna by remember(state.settings.nmeaGpsAntennaToBowMeters){mutableStateOf(state.settings.nmeaGpsAntennaToBowMeters.toString())}
 var savedFeedback by remember{mutableStateOf(false)}
 fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
 val valid=listOf(boat,bow,antenna).all{it.toDoubleOrNull()!=null}
 val dirty=valid&&(boat.toDouble()!=state.settings.boatLengthMeters||bow.toDouble()!=state.settings.bowRollerHeightMeters||antenna.toDouble()!=state.settings.nmeaGpsAntennaToBowMeters)
 LaunchedEffect(dirty){if(dirty)savedFeedback=false}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(tr("Vessel profile","船舶资料"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));when{!valid->AssistChip({},label={Text(tr("Invalid values","数值无效"))});dirty->AssistChip({},label={Text(tr("Unsaved changes","尚未保存"))});savedFeedback->AssistChip({},label={Text(tr("Saved","已保存"))})}};Text(tr("Defaults for new anchor setups. System GPS does not assume a fixed antenna position.","用于新锚泊设置的默认值；系统 GPS 不假设手机有固定安装位置。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(boat,{boat=numeric(it)},label={Text(tr("Boat length","船长"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(bow,{bow=numeric(it)},label={Text(tr("Bow roller height","船艏滚轮高度"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(antenna,{antenna=numeric(it)},label={Text(tr("NMEA GPS antenna to bow roller","NMEA GPS 天线到船艏滚轮距离"))},suffix={Text(tr("m","米"))},modifier=Modifier.fillMaxWidth());Button({vm.updateSettings(state.settings.copy(boatLengthMeters=boat.toDouble(),bowRollerHeightMeters=bow.toDouble(),nmeaGpsAntennaToBowMeters=antenna.toDouble()));savedFeedback=true},Modifier.fillMaxWidth(),enabled=dirty){Icon(Icons.Default.Save,null);Spacer(Modifier.width(6.dp));Text(tr("Save changes","保存修改"))}}}
}

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
 val sessionOpen=state.active!=null
 val lockedSource=state.active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
 val proxyActive=GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
 val nmeaAvailability=NmeaSourceSelectionPolicy.availability(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,android.os.SystemClock.elapsedRealtime(),state.settings.gpsLossSeconds*1000L)
 val nmeaReady=nmeaAvailability==NmeaSourceAvailability.AVAILABLE
 val selectedFixReady=state.fix?.valid==true&&(state.settings.gpsDataSource!=GpsDataSource.DEMO||state.demoGps.signalAvailable)
 val context=androidx.compose.ui.platform.LocalContext.current;val permissionReady=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){vm.onPermissionsChanged()}
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 Text(tr("GPS data source","GPS 数据源"),style=MaterialTheme.typography.titleMedium)
  if(state.settings.demoMode){
   Text(tr("Demo mode locks this App to Demo GPS. System and NMEA choices return after Demo mode is disabled.","演示模式会锁定本应用使用演示 GPS；关闭演示模式后才会重新显示系统与 NMEA 选项。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   GpsSourceRow(tr("Demo GPS · locked","演示 GPS · 已锁定"),tr("Every Set anchor captures the real System-GNSS boat position; the simulated anchor centre is hidden and offset from it.","每次设置锚点都会获取真实系统 GNSS 船位；模拟锚中心会隐藏并与该船位保持偏移。"),true,false,"gps_source_demo"){}
  }else{
   Text(if(sessionOpen)tr("Source locked to ${lockedSource?.name?:"—"} until Lift anchor. Pausing does not unlock it.","数据源已锁定为 ${lockedSource?.name?:"—"}，起锚结束会话后才能更换；暂停不会解锁。")else tr("Choose the default for the next anchor setup. Start validates the selected source again.","选择下一次锚警的默认来源；启动时还会再次校验。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   GpsSourceRow(tr("System GPS","系统 GPS"),if(proxyActive)tr("Unavailable while the global NMEA GPS proxy owns Android location.","全局 NMEA GPS 代理接管 Android 定位时不可使用。") else tr("Use precise phone/tablet GNSS; coarse network location is diagnostics-only.","使用手机或平板精确 GNSS；网络粗略定位仅供诊断。"),lockedSource?.let{it==GpsDataSource.SYSTEM}?: (state.settings.gpsDataSource==GpsDataSource.SYSTEM),!sessionOpen&&!switching&&!proxyActive,"gps_source_system"){vm.switchGpsDataSource(GpsDataSource.SYSTEM)}
   HorizontalDivider()
   GpsSourceRow("NMEA GPS",when(nmeaAvailability){NmeaSourceAvailability.AVAILABLE->tr("Connected with a fresh valid position.","连接正常，且已有新鲜有效的定位。");NmeaSourceAvailability.NOT_CONNECTED->tr("Connect the NMEA server before selecting this source.","请先连接 NMEA 服务器，之后才能选择此数据源。");NmeaSourceAvailability.NO_VALID_FIX->tr("Connected, but waiting for a valid NMEA position.","服务器已连接，但仍在等待有效的 NMEA 定位。");NmeaSourceAvailability.STALE_FIX->tr("The last NMEA position is stale; wait for a fresh fix.","最后一个 NMEA 定位已过期，请等待新定位。")},lockedSource?.let{it==GpsDataSource.NMEA}?: (state.settings.gpsDataSource==GpsDataSource.NMEA),!sessionOpen&&!switching&&nmeaReady,"gps_source_nmea"){vm.switchGpsDataSource(GpsDataSource.NMEA)}
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
   Text(if(sessionOpen)tr("Demo settings are locked for this session. Lift anchor to change or leave Demo mode.","本次会话已锁定演示设置；起锚后才能修改或退出演示模式。") else tr("Set anchor starts the boat at fresh System GNSS. A hidden offset centre, gradual payout, sector dwell, correlated noise and slow direction changes drive the real estimator.","下锚时船从新鲜系统 GNSS 位置开始；隐藏偏移圆心、逐步放缆、扇区停留、相关噪声和缓慢换向共同驱动真实估算器。"),style=MaterialTheme.typography.bodySmall,color=if(sessionOpen)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }}
}

@Composable internal fun GpsSourceRow(title:String,subtitle:String,selected:Boolean,enabled:Boolean,testTag:String,click:()->Unit){Row(Modifier.fillMaxWidth().testTag(testTag).clickable(enabled=enabled,onClick=click),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,null,enabled=enabled);Column(Modifier.weight(1f)){Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable internal fun GpsProxyCard(state:MainUiState,vm:MainViewModel){
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
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(tr("STORAGE HEALTH","存储健康"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   StorageMetric(tr("Database","数据库"),humanBytes(state.storageHealth.databaseBytes));StorageMetric(tr("Offline maps","离线地图"),humanBytes(state.storageHealth.offlineMapBytes));StorageMetric(tr("Temporary cache","临时缓存"),humanBytes(state.storageHealth.cacheBytes));StorageMetric(tr("Free space","可用空间"),humanBytes(state.storageHealth.freeBytes))
   HorizontalDivider();Text(tr("${state.storageHealth.anchorSessions} anchor sessions · ${state.storageHealth.trackPoints} track points · ${state.storageHealth.sonarSamples} soundings · ${state.storageHealth.sonarGridCells} derived cells","${state.storageHealth.anchorSessions} 次锚泊 · ${state.storageHealth.trackPoints} 个轨迹点 · ${state.storageHealth.sonarSamples} 个测深点 · ${state.storageHealth.sonarGridCells} 个派生网格"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(vm::refreshStorage){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(4.dp));Text(tr("Refresh","刷新"))};OutlinedButton({confirmClearCaches=true}){Text(tr("Clear caches","清理缓存"))}}
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(tr("INCIDENT LOG · 72 HOURS","事件日志 · 72 小时"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   Text(tr("Safety events are ring-limited to 10,000 rows. Exact positions and raw NMEA are not recorded here.","安全事件最多保留 1 万条并按环形清理；这里不记录精确位置或原始 NMEA。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Text(tr("${state.storageHealth.incidentRows} events stored","已存储 ${state.storageHealth.incidentRows} 条事件"),fontWeight=FontWeight.Medium)
   state.incidents.take(8).forEach{event->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(if(event.severity=="CRITICAL")Icons.Default.Error else if(event.severity=="WARNING")Icons.Default.Warning else Icons.Default.Info,null,Modifier.size(16.dp),tint=if(event.severity=="CRITICAL")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant);Column{Text("${event.category} · ${event.event}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium);Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(java.util.Date(event.timestamp)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
   TextButton({confirmClearLog=true},Modifier.align(Alignment.End)){Text(tr("Clear incident log","清空事件日志"))}
  }}
  Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text(tr("SUPPORT BUNDLE","支持诊断包"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
   Text(tr("Exports app/runtime/storage summaries and the recent incident log. It excludes raw NMEA, API keys and exact vessel positions.","导出应用、运行时、存储摘要和最近事件日志；不包含原始 NMEA、API key 或精确船位。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Button({export.launch("Yokuli-support-${java.time.LocalDate.now()}.zip")},Modifier.fillMaxWidth(),enabled=!state.supportBundle.running){Icon(Icons.Default.BugReport,null);Spacer(Modifier.width(6.dp));Text(tr("Export diagnostics","导出诊断包"))}
   if(state.supportBundle.running)LinearProgressIndicator(Modifier.fillMaxWidth())
   state.supportBundle.message?.let{AssistChip(vm::clearSupportBundleResult,{Text(localizeKnownMessage(it))})};state.supportBundle.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
  }}
 }
 if(confirmClearLog)AlertDialog({confirmClearLog=false},title={Text(tr("Clear incident log?","清空事件日志？"))},text={Text(tr("Recent operational evidence will be deleted. Anchor history and sonar data are not affected.","最近的运行证据会被删除；锚泊历史和声呐数据不受影响。"))},confirmButton={Button({vm.clearIncidentLog();confirmClearLog=false},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Clear","清空"))}},dismissButton={TextButton({confirmClearLog=false}){Text(tr("Cancel","取消"))}})
 if(confirmClearCaches)AlertDialog({confirmClearCaches=false},title={Text(tr("Clear rebuildable caches?","清理可重建缓存？"))},text={Text(tr("Sonar grid, LINZ depth and tide caches will be rebuilt from source data or the network. Raw soundings and the imported offline map remain.","声呐网格、LINZ 水深和潮汐缓存会从原始数据或网络重建；原始测深与导入的离线地图会保留。"))},confirmButton={Button({vm.clearRebuildableCaches();confirmClearCaches=false}){Text(tr("Clear caches","清理缓存"))}},dismissButton={TextButton({confirmClearCaches=false}){Text(tr("Cancel","取消"))}})
}

@Composable private fun StorageMetric(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text(value,fontWeight=FontWeight.Medium)}}
private fun humanBytes(value:Long):String=when{value>=1024L*1024L*1024L->"%.1f GB".format(value/(1024.0*1024.0*1024.0));value>=1024L*1024L->"%.1f MB".format(value/(1024.0*1024.0));value>=1024L->"%.1f KB".format(value/1024.0);else->"$value B"}

@Composable internal fun PreflightRow(label:String,ok:Boolean,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Cancel,null,Modifier.size(18.dp),tint=if(ok)SafetyColors.Safe else SafetyColors.Alarm);Spacer(Modifier.width(8.dp));Text(label,Modifier.weight(1f));Text(value,style=MaterialTheme.typography.labelMedium)}}

@Composable private fun BackgroundReliabilityCard(state:MainUiState,vm:MainViewModel){
 val context=androidx.compose.ui.platform.LocalContext.current;val locationGranted=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;val notificationsGranted=android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;val unrestricted=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(tr("Background reliability","后台可靠性"),style=MaterialTheme.typography.titleMedium);PreflightRow(tr("System GPS / proxy permission","系统 GPS / 代理权限"),locationGranted,if(locationGranted)tr("OK","正常") else tr("REQUIRED WHEN USED","使用时必需"));PreflightRow(tr("Alarm notifications","锚警通知"),notificationsGranted,if(notificationsGranted)tr("OK","正常") else tr("REQUIRED","必需"));PreflightRow(tr("Battery optimization","电池优化"),unrestricted,if(unrestricted)tr("UNRESTRICTED","不受限制") else tr("SYSTEM MAY RESTRICT","系统可能限制"));SettingSwitch(tr("Keep Wi-Fi awake","保持 Wi-Fi 唤醒"),tr("Hold CPU and Wi-Fi awake while monitoring or proxying","监控或代理期间保持 CPU 与 Wi-Fi 唤醒"),state.settings.keepWifiAwake){vm.updateSettings(state.settings.copy(keepWifiAwake=it))};OutlinedButton(vm::openBatteryOptimization){Text(tr("Open battery optimization settings","打开电池优化设置"))};Text(tr("For overnight monitoring, keep the phone on reliable power and verify alarm volume before sleeping.","夜间监控时，请保持设备可靠供电，并在休息前确认报警音量。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
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

@Composable internal fun localizeKnownMessage(message:String):String{
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
  "This active anchor session is locked to NMEA. Pause the watch before disconnecting, or lift the anchor to end the session."->"当前锚泊会话已锁定 NMEA。断开前请先暂停锚警，或起锚结束本次会话。"
  "Precise location permission is required before an active watch can switch to System GPS."->"当前锚警切换到系统 GPS 前需要精确位置权限。"
  "Disable the global NMEA GPS proxy before switching an anchor watch to System GPS."->"锚警切换到系统 GPS 前请先关闭全局 NMEA GPS 代理。"
  "Disable NMEA Sharing before disconnecting its shared upstream connection."->"断开共享上游连接前，请先关闭 NMEA Sharing。"
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

@Composable internal fun PageHeader(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun SettingSwitch(title: String, subtitle: String, checked: Boolean, enabled:Boolean=true, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,color=if(enabled)Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change,enabled=enabled) } }
