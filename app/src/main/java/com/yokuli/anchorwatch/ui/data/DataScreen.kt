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
import com.yokuli.anchorwatch.domain.sonar.TideMode
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun DataPage(state:MainUiState,vm:MainViewModel){
    var section by remember{mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize()){
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)){
            SegmentedButton(section==0,{section=0},shape=SegmentedButtonDefaults.itemShape(0,3)){Text(tr("NMEA","连接"))}
            SegmentedButton(section==1,{section=1},shape=SegmentedButtonDefaults.itemShape(1,3)){Text(tr("Raw data","原始数据"))}
            SegmentedButton(section==2,{section=2},shape=SegmentedButtonDefaults.itemShape(2,3)){Text(tr("Sonar","声呐"))}
        }
        Box(Modifier.weight(1f)){when(section){1->NmeaDataPage(state,vm);2->SonarSurveyPage(state,vm);else->ConnectionPage(state,vm)}}
    }
}

@Composable
private fun SonarSurveyPage(state:MainUiState,vm:MainViewModel){
    var showStart by remember{mutableStateOf(false)};var rename by remember{mutableStateOf<com.yokuli.anchorwatch.data.database.SonarSurveyEntity?>(null)};var delete by remember{mutableStateOf<com.yokuli.anchorwatch.data.database.SonarSurveyEntity?>(null)}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader(tr("Personal sonar mapping","个人声呐测绘"),tr("Record DPT/DBT depth against Accepted Position, then build a robust 5 m local grid.","将 DPT/DBT 水深与可信定位配对，并生成稳健的 5 米本地网格。"))}
        item{Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("Observation aid only — not a certified chart or a substitute for safe navigation, tide planning or depth instruments.","仅供观测辅助——不是认证海图，也不能替代安全航行、潮汐计划或测深仪判断。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}}
        if(state.activeSonarSurvey==null)item{Card{Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Waves,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(tr("Live NMEA depth","实时 NMEA 水深"),fontWeight=FontWeight.SemiBold);Text(localizeKnownMessage(state.sonarRecorder.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(state.sonarRecorder.lastDepthMeters?.let{"%.2f m".format(it)}?:"—",style=MaterialTheme.typography.titleMedium)}}}
        state.activeSonarSurvey?.let{survey->item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Sensors,null);Spacer(Modifier.width(8.dp));Text(tr("Recording","正在记录"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));AssistChip({},label={Text("${survey.sampleCount}")})};Text(survey.name,fontWeight=FontWeight.SemiBold);Text(localizeKnownMessage(state.sonarRecorder.message),style=MaterialTheme.typography.bodySmall);state.sonarRecorder.lastDepthMeters?.let{depth->Text(if(state.sonarRecorder.lastDepthIsChartDatum)tr("Latest chart-datum depth  ${"%.2f".format(depth)} m","最新海图基准水深  ${"%.2f".format(depth)} 米")else tr("Latest measured depth  ${"%.2f".format(depth)} m","最新实测水深  ${"%.2f".format(depth)} 米"))};Text(tr("GPS ${state.fix?.horizontalAccuracyMeters?.let{"±${it.toInt()} m"}?:"waiting"} · coverage ${"%.4f".format(state.sonarGrid.cells.size*25.0/1_000_000.0)} km²","GPS ${state.fix?.horizontalAccuracyMeters?.let{"±${it.toInt()} 米"}?:"等待中"} · 覆盖 ${"%.4f".format(state.sonarGrid.cells.size*25.0/1_000_000.0)} 平方公里"),style=MaterialTheme.typography.bodySmall);OutlinedButton(vm::stopSonarSurvey,Modifier.fillMaxWidth()){Icon(Icons.Default.StopCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Stop and save survey","停止并保存调查"))}}}}}
        if(state.activeSonarSurvey==null)item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Button({showStart=true},Modifier.fillMaxWidth(),enabled=!state.settings.demoMode){Icon(Icons.Default.PlayCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Start sonar survey","开始声呐调查"))};if(state.settings.demoMode)Text(tr("Disable Demo mode before recording a real personal depth map.","记录真实个人水深图前请关闭演示模式。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}}
        item{Text(tr("Saved surveys","已保存调查"),style=MaterialTheme.typography.titleMedium)}
        if(state.sonarSurveys.any{it.tideMode==TideMode.MANUAL.name})item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Chart-datum corrected history","海图基准修正历史"),fontWeight=FontWeight.SemiBold);Text(tr("Combines only usable samples with a non-null manual tide normalization.","只合并已通过质量检查且已手动潮汐归一化的样本。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)};if(state.selectedSonarSurveyId==CORRECTED_SONAR_HISTORY_ID)AssistChip({},label={Text(tr("ON MAP","地图中"))})else TextButton({vm.selectCorrectedSonarHistory();vm.page(0)}){Icon(Icons.Default.Map,null);Text(tr("Map","地图"))}}}}
        if(state.sonarSurveys.isEmpty())item{Text(tr("No sonar surveys yet.","还没有声呐调查。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.sonarSurveys,key={it.id}){survey->Card{Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(survey.name,fontWeight=FontWeight.SemiBold);Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(survey.startedAt))} · ${survey.sampleCount} ${tr("samples","个样本")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};when{survey.active->AssistChip({},label={Text(tr("ACTIVE","进行中"))});state.selectedSonarSurveyId==survey.id->AssistChip({},label={Text(tr("ON MAP","地图中"))})}};Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){TextButton({vm.selectSonarSurvey(survey.id);vm.page(0)}){Icon(Icons.Default.Map,null);Text(tr("Map","地图"))};IconButton({vm.exportSonarCsv(survey)}){Icon(Icons.Default.Share,"CSV")};IconButton({vm.rebuildSonarSurvey(survey.id)}){Icon(Icons.Default.Refresh,tr("Rebuild","重建"))};IconButton({rename=survey}){Icon(Icons.Default.Edit,tr("Rename","重命名"))};IconButton({delete=survey},enabled=!survey.active){Icon(Icons.Default.Delete,tr("Delete","删除"))}}}}}
    }
    if(showStart)SonarStartDialog(state,{showStart=false}){name,mode,tide->vm.startSonarSurvey(name,mode,tide);showStart=false}
    rename?.let{survey->var value by remember(survey.id){mutableStateOf(survey.name)};AlertDialog(onDismissRequest={rename=null},title={Text(tr("Rename survey","重命名调查"))},text={OutlinedTextField(value,{value=it},singleLine=true,label={Text(tr("Name","名称"))})},confirmButton={Button({vm.renameSonarSurvey(survey.id,value);rename=null},enabled=value.isNotBlank()){Text(tr("Save","保存"))}},dismissButton={TextButton({rename=null}){Text(tr("Cancel","取消"))}})}
    delete?.let{survey->AlertDialog(onDismissRequest={delete=null},title={Text(tr("Delete sonar survey?","删除声呐调查？"))},text={Text(tr("All raw and normalized soundings in this survey will be permanently deleted.","该调查中的全部原始和归一化测深数据都会被永久删除。"))},confirmButton={Button({vm.deleteSonarSurvey(survey.id);delete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({delete=null}){Text(tr("Cancel","取消"))}})}
}

@Composable
private fun SonarStartDialog(state:MainUiState,dismiss:()->Unit,start:(String,TideMode,Double)->Unit){
    var name by remember{mutableStateOf("")};var tideMode by remember{mutableStateOf(TideMode.OFF)};var tide by remember{mutableStateOf("0.0")};val tideValue=tide.toDoubleOrNull()
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Start sonar survey","开始声呐调查"))},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(name,{name=it},label={Text(tr("Survey name (optional)","调查名称（可选）"))},singleLine=true);Text(tr("Tide correction","潮汐修正"),style=MaterialTheme.typography.labelLarge);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){SegmentedButton(tideMode==TideMode.OFF,{tideMode=TideMode.OFF},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Off","关闭"))};SegmentedButton(tideMode==TideMode.MANUAL,{tideMode=TideMode.MANUAL},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Manual","手动"))}};if(tideMode==TideMode.MANUAL){OutlinedTextField(tide,{tide=it.filter{c->c.isDigit()||c=='.'||c=='-'}},label={Text(tr("Tide height above datum","高于基准面的潮高"))},suffix={Text(tr("m","米"))},isError=tideValue==null,singleLine=true);Text(tr("Manual tide correction is only as accurate as the entered tide height and configured depth reference.","手动潮汐修正的精度取决于输入潮高和已配置的深度参考面。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)};Text(tr("Recording requires fresh NMEA depth and an Accepted Position no more than 2 seconds apart. It runs independently of anchor watch.","记录要求新的 NMEA 水深与可信定位相差不超过 2 秒，并且独立于锚警运行。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},confirmButton={Button({start(name,tideMode,tideValue?:0.0)},enabled=tideMode==TideMode.OFF||tideValue!=null){Text(tr("Start recording","开始记录"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}

@Composable
internal fun ConnectionPage(state: MainUiState, vm: MainViewModel) {
    var profile by remember(state.settings.profile) { mutableStateOf(state.settings.profile) }
    var showWatchDisconnect by remember { mutableStateOf(false) }
    val connectionRunning = state.connection != NmeaConnectionState.DISCONNECTED
    val testing=state.connectionAttempt.state==ConnectionAttemptState.TESTING
    val controlsEnabled=!connectionRunning&&!testing
    val activeWatchUsesNmea=state.active?.paused==false&&state.active.positionSource==GpsDataSource.NMEA.name
    val validationError=vm.validateProfile(profile)
    fun edit(next:ConnectionProfile){profile=next;vm.clearConnectionAttempt()}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader(tr("NMEA connection","NMEA 连接"), tr("Configure and verify live traffic. A successful connection becomes the next default unless a session is open.","配置并验证实时数据。连接成功后会成为下次默认来源，但不会改变已开启会话。")) }
        if(activeWatchUsesNmea&&state.connection!=NmeaConnectionState.CONNECTED)item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Anchor watch needs NMEA","锚警需要 NMEA 数据"),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer);Text(tr("The session remains locked to NMEA with no silent failover. Restore this connection, or pause the watch before disconnecting.","本次会话仍锁定 NMEA，不会静默切源。请恢复连接，或先暂停锚警再断开。"),color=MaterialTheme.colorScheme.onErrorContainer);OutlinedButton({showWatchDisconnect=true}){Text(tr("Pause safely","安全暂停"))}}} }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(connectionRunning) AssistChip({}, { Text(tr("Configuration locked while connected","连接期间配置已锁定")) }, leadingIcon={Icon(Icons.Default.Lock,null,Modifier.size(18.dp))}, enabled=false)
            OutlinedTextField(profile.name, { edit(profile.copy(name = it)) }, label = { Text(tr("Profile name","配置名称")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled)
            Text(tr("Protocol","协议"), style = MaterialTheme.typography.labelLarge); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(profile.protocol == Protocol.TCP, { edit(profile.copy(protocol = Protocol.TCP)) }, label = { Text(tr("TCP client","TCP 客户端")) },enabled=controlsEnabled); FilterChip(profile.protocol == Protocol.UDP, { edit(profile.copy(protocol = Protocol.UDP)) }, label = { Text(tr("UDP listener","UDP 监听")) },enabled=controlsEnabled) }
            if (profile.protocol == Protocol.TCP) OutlinedTextField(profile.host, { edit(profile.copy(host = it)) }, label = { Text(tr("Host or IP address","主机名或 IP 地址")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled,isError=validationError!=null,supportingText={if(validationError!=null)Text(localizeKnownMessage(validationError))})
            OutlinedTextField(profile.port.toString(), { v -> edit(profile.copy(port=v.filter(Char::isDigit).toIntOrNull()?:0)) }, label = { Text(if (profile.protocol == Protocol.TCP) tr("Server port","服务器端口") else tr("Listen port","监听端口")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),isError=profile.port !in 1..65535)
            OutlinedTextField(profile.noDataTimeoutSeconds.toString(),{v->edit(profile.copy(noDataTimeoutSeconds=v.filter(Char::isDigit).toIntOrNull()?:0))},label={Text(tr("No-data timeout","无数据超时"))},suffix={Text(tr("s","秒"))},supportingText={Text(tr("3–120 seconds; drives No data / Stale states and reconnect.","3–120 秒；用于无数据/过期状态与重连。"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=controlsEnabled,isError=profile.noDataTimeoutSeconds !in 3..120,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            SettingSwitch(tr("Require checksum","要求校验和"), tr("Reject sentences without a checksum","拒绝没有校验和的语句"), profile.requireChecksum,enabled=controlsEnabled) { edit(profile.copy(requireChecksum = it)) }; SettingSwitch(tr("Auto reconnect","自动重连"), tr("Reconnect after network loss","网络中断后自动重新连接"), profile.autoReconnect,enabled=controlsEnabled) { edit(profile.copy(autoReconnect = it)) }
            if(connectionRunning) Button({if(activeWatchUsesNmea)showWatchDisconnect=true else vm.disconnect()},Modifier.fillMaxWidth(),enabled=!testing){Icon(Icons.Default.LinkOff,null);Spacer(Modifier.width(6.dp));Text(tr("Disconnect","断开连接"))}
            else Button({vm.saveAndConnect(profile)},Modifier.fillMaxWidth(),enabled=!testing&&validationError==null){if(testing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Default.Link,null);Spacer(Modifier.width(6.dp));Text(if(testing)tr("Testing NMEA…","正在测试 NMEA…") else tr("Test, save & connect","测试、保存并连接"))}
            if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(localizeKnownMessage(state.connectionAttempt.message),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(testing)Text(localizeKnownMessage(state.connectionAttempt.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(testing&&!connectionRunning)Text(tr("The app must receive at least one valid NMEA sentence before it will connect.","应用必须收到至少一条有效 NMEA 语句后才会正式连接。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.settings.nmeaSharingEnabled)Text(tr("NMEA Sharing does not own or auto-connect this endpoint. If disconnected, the server stays up and waits for accepted input.","NMEA 共享不会占用或自动连接此端点；断开后共享服务器会继续运行并等待可信输入。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { ConnectionResultCard(state, vm) }
        item { NmeaSharingCard(state,vm) }
    }
    if(showWatchDisconnect)ActiveWatchDisconnectDialog(pauseWatch={showWatchDisconnect=false;vm.stopActiveWatchAndDisconnect()},dismiss={showWatchDisconnect=false})
}

@Composable private fun NmeaSharingCard(state:MainUiState,vm:MainViewModel){
 var portText by remember(state.settings.nmeaSharingPort){mutableStateOf(state.settings.nmeaSharingPort.toString())}
 val enabled=state.settings.nmeaSharingEnabled;val port=portText.toIntOrNull();val valid=port!=null&&port in 1024..65535
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("NMEA Sharing","NMEA 共享"),style=MaterialTheme.typography.titleMedium);Text(tr("Share this App's single upstream stream with chartplotters and other devices.","把本应用的单一上游数据流共享给海图仪和其他设备。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(enabled,{checked->if(valid)vm.setNmeaSharing(checked,port!!)})}
  OutlinedTextField(portText,{portText=it.filter(Char::isDigit)},label={Text(tr("TCP server port","TCP 服务器端口"))},enabled=!enabled,isError=!valid,supportingText={if(!valid)Text(tr("Use port 1024–65535","请输入 1024–65535"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
  if(!enabled&&valid&&port!=state.settings.nmeaSharingPort)OutlinedButton({vm.setNmeaSharing(false,port!!)},Modifier.fillMaxWidth()){Text(tr("Save port","保存端口"))}
  if(enabled){PreflightRow(tr("Server","服务器"),state.nmeaSharing.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING,state.nmeaSharing.state.name);Text(tr("${state.nmeaSharing.clientCount} connected clients · ${state.nmeaSharing.sentSentences} output sentences","${state.nmeaSharing.clientCount} 个客户端已连接 · 已输出 ${state.nmeaSharing.sentSentences} 条语句"),style=MaterialTheme.typography.bodySmall);state.nmeaSharing.addresses.forEach{address->val host=if(':' in address)"[$address]" else address;SelectionContainer{Text("tcp://$host:${state.settings.nmeaSharingPort}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}};state.nmeaSharing.clients.forEach{client->val minutes=((System.currentTimeMillis()-client.connectedAtMillis).coerceAtLeast(0L)/60_000L);Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(8.dp)){SelectionContainer{Text(client.address,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)};Text(tr("Connected ${minutes} min · ${client.sentSentences} sentences","已连接 ${minutes} 分钟 · ${client.sentSentences} 条语句"),style=MaterialTheme.typography.labelSmall)}}};if(state.nmeaSharing.droppedSlowClients>0)Text(tr("${state.nmeaSharing.droppedSlowClients} slow clients were safely disconnected.","已安全断开 ${state.nmeaSharing.droppedSlowClients} 个过慢客户端。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);if(state.nmeaSharing.message.isNotBlank())Text(state.nmeaSharing.message,style=MaterialTheme.typography.bodySmall,color=if(state.nmeaSharing.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
  val sharingSource=state.active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}?:state.settings.gpsDataSource
  val mode=when(sharingSource){GpsDataSource.NMEA->tr("Output: accepted NMEA position plus non-position boat instruments. Raw position spikes are never forwarded.","输出：可信 NMEA 船位与非定位仪表数据；原始船位跳点不会被转发。");GpsDataSource.SYSTEM->tr("Output: accepted System-GNSS position plus boat instruments.","输出：可信系统 GNSS 船位与船载仪表数据。");GpsDataSource.DEMO->tr("Output: accepted Demo position plus boat instruments.","输出：可信演示船位与船载仪表数据。")};Text(mode,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(enabled){val gpsReady=state.fix?.valid==true&&state.positionHealth!=com.yokuli.anchorwatch.domain.model.PositionHealth.GPS_LOST;PreflightRow(tr("Shared position source","共享定位源"),gpsReady,if(gpsReady)tr("Ready","就绪")else tr("Waiting for an accepted fix","等待可信定位"))}
  Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("No authentication or encryption. Enable only on a trusted boat LAN or VPN. Never connect the App's own input to this same address and port.","没有身份验证或加密。只应在可信船载局域网或 VPN 中开启；不要把本应用的输入连接回同一地址和端口。"),Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}
 }}
}

@Composable private fun ActiveWatchDisconnectDialog(pauseWatch:()->Unit,dismiss:()->Unit){
 AlertDialog(onDismissRequest=dismiss,title={Text(tr("Anchor watch is locked to NMEA","锚警已锁定 NMEA"))},text={Text(tr("The GPS source cannot change during an open session, even while paused. Pause and disconnect now, or cancel and restore NMEA. Lift anchor before choosing another source.","会话未结束时不能更换 GPS 来源，暂停期间也一样。你可以暂停并断开，或取消后恢复 NMEA；要更换来源请先起锚。"))},confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedButton(pauseWatch,Modifier.fillMaxWidth()){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Pause watch & disconnect","暂停锚警并断开"))}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
 }})
}

@Composable private fun ConnectionResultCard(state: MainUiState, vm: MainViewModel) { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr("Live status","实时状态"), style = MaterialTheme.typography.titleMedium); Text(connectionStateLabel(state.connection), color = if(state.connection==NmeaConnectionState.CONNECTED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
    HorizontalDivider(); Text(tr("${state.diagnostics.validSentences} valid sentences • ${state.diagnostics.invalidSentences} invalid","${state.diagnostics.validSentences} 条有效语句 · ${state.diagnostics.invalidSentences} 条无效语句")); state.nmeaFix?.let { Text(tr("Latest position  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}","最新位置  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}")) } ?: Text(tr("No parsed GPS position yet","暂时没有解析出的 GPS 位置"));Text(tr("Open Raw data above for the NMEA stream.","可在上方切换到“原始数据”查看 NMEA 数据流。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
} } }

@Composable
internal fun NmeaDataPage(state: MainUiState, vm: MainViewModel) {
    var paused by remember { mutableStateOf(false) }; var displayed by remember { mutableStateOf(state.diagnostics.raw) }; val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.diagnostics.raw, paused) { if (!paused) displayed = state.diagnostics.raw }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader(tr("Live NMEA data","实时 NMEA 数据"), tr("Parsed values and the latest 200 raw sentences.","查看解析值和最近 200 条原始语句。"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompactStat(tr("VALID","有效"), state.diagnostics.validSentences.toString(), Modifier.weight(1f)); CompactStat(tr("INVALID","无效"), state.diagnostics.invalidSentences.toString(), Modifier.weight(1f)); CompactStat(tr("CHECKSUM","校验错误"), state.diagnostics.checksumErrors.toString(), Modifier.weight(1f)) }
            state.nmeaFix?.let { fix -> Card { Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("LAT", "%.6f".format(fix.latitude)); Metric("LON", "%.6f".format(fix.longitude)); Metric("SOG", fix.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric("HDG", fix.headingTrueDegrees?.let { "${it.toInt()}°" } ?: "—") };Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("HDOP",fix.hdop?.let{"%.1f".format(it)}?:"—");Metric(tr("Provider","提供者"),fix.positionProvider.name);Metric(tr("Wind","风"),fix.windSpeedKnots?.let{"%.1f kn".format(it)}?:"—");Metric(tr("Depth","水深"),fix.depthMeters?.let{"%.1f m".format(it)}?:"—")}} } }
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(tr("Accepted Position pipeline","可信船位管线"),fontWeight=FontWeight.SemiBold);Text("${state.acceptedPosition.selectedSource.name} · ${state.acceptedPosition.disposition} · ${state.acceptedPosition.trust?.name?:"—"}",fontFamily=FontFamily.Monospace);Text(tr("Raw: ${state.acceptedPosition.rawFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—"}","原始：${state.acceptedPosition.rawFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—"}"),style=MaterialTheme.typography.bodySmall);Text(tr("Accepted: ${state.acceptedPosition.acceptedFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—"}","可信：${state.acceptedPosition.acceptedFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—"}"),style=MaterialTheme.typography.bodySmall);state.acceptedPosition.reason?.let{Text(tr("Reason: $it","原因：$it"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(tr("Raw sentences","原始语句"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton({ paused = !paused }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Text(if (paused) tr("Resume","继续") else tr("Pause","暂停")) }; TextButton({ vm.clearDiagnostics(); displayed = emptyList() }) { Icon(Icons.Default.DeleteSweep, null); Text(tr("Clear","清空")) }; TextButton({ clipboard.setText(AnnotatedString(displayed.joinToString("\n"))) }, enabled = displayed.isNotEmpty()) { Icon(Icons.Default.ContentCopy, null); Text(tr("Copy","复制")) } }
        }
        SelectionContainer { LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 8.dp)) { if (displayed.isEmpty()) item { Text(if (state.connection == NmeaConnectionState.CONNECTED) tr("Connected. Waiting for NMEA sentences…","已连接，正在等待 NMEA 语句…") else tr("Connect to a data source to view raw NMEA.","连接数据源后即可查看原始 NMEA 数据。"), color = Color.Gray, fontFamily = FontFamily.Monospace) }; items(displayed.asReversed()) { Text(it, color = Color(0xFFB9F6CA), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } } }
    }
}
@Composable private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } } }
