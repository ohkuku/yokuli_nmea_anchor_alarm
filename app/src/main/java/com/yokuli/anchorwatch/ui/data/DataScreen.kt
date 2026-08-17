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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartDecision
import com.yokuli.anchorwatch.domain.sonar.SonarSurveyStartPolicy
import com.yokuli.anchorwatch.data.tide.TideStationCatalog
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceAvailability
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.map.SonarTileDiagnostics
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
    var showStart by remember{mutableStateOf(false)};var showDisclaimer by remember{mutableStateOf(false)};var rename by remember{mutableStateOf<com.yokuli.anchorwatch.data.database.SonarSurveyEntity?>(null)};var delete by remember{mutableStateOf<com.yokuli.anchorwatch.data.database.SonarSurveyEntity?>(null)}
    val freshDepth=state.sonarRecorder.hasFreshRealDepth(android.os.SystemClock.elapsedRealtime())
    val freshNmeaPosition=state.sonarRecorder.hasFreshNmeaPosition(android.os.SystemClock.elapsedRealtime())
    val startDecision=SonarSurveyStartPolicy.evaluate(state.settings.demoMode,state.connection,freshDepth,freshNmeaPosition)
    val canStart=startDecision==SonarSurveyStartDecision.ALLOWED
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader(tr("Personal sonar mapping","个人声呐测绘"),tr("Pair DPT/DBT depth only with GPS from the same NMEA server, then build a robust 5 m local grid.","DPT/DBT 水深只与同一 NMEA 服务器的 GPS 配对，并生成稳健的 5 米本地网格。"))}
        item{Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("Observation aid only — not a certified chart or a substitute for safe navigation, tide planning or depth instruments.","仅供观测辅助——不是认证海图，也不能替代安全航行、潮汐计划或测深仪判断。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}}
        if(state.activeSonarSurvey==null)item{Card{Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Waves,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(if(state.settings.demoMode)tr("Live demo sonar","实时演示声呐")else tr("Live NMEA depth","实时 NMEA 水深"),fontWeight=FontWeight.SemiBold);Text(localizeKnownMessage(state.sonarRecorder.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(state.sonarRecorder.lastDepthMeters?.let{"%.2f m".format(it)}?:"—",style=MaterialTheme.typography.titleMedium)}}}
        state.activeSonarSurvey?.let{survey->item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Sensors,null);Spacer(Modifier.width(8.dp));Text(tr("Recording","正在记录"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));AssistChip({},label={Text("${survey.sampleCount}")})};Text(survey.name,fontWeight=FontWeight.SemiBold);Text(localizeKnownMessage(state.sonarRecorder.message),style=MaterialTheme.typography.bodySmall);state.sonarRecorder.lastDepthMeters?.let{depth->Text(tr("Mapped depth ${"%.2f".format(depth)} m · offset ${signed(survey.sounderOffsetMeters)} m","绘制水深 ${"%.2f".format(depth)} 米 · offset ${signed(survey.sounderOffsetMeters)} 米"))};Text(tr("${state.sonarGrid.cells.size} grid cells · incremental updates","${state.sonarGrid.cells.size} 个网格 · 增量更新"),style=MaterialTheme.typography.bodySmall);OutlinedButton(vm::stopSonarSurvey,Modifier.fillMaxWidth()){Icon(Icons.Default.StopCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Stop and save survey","停止并保存调查"))}}}}}
        if(state.activeSonarSurvey==null)item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Button({if(state.settings.sonarDisclaimerAccepted)showStart=true else showDisclaimer=true},Modifier.fillMaxWidth(),enabled=canStart){Icon(Icons.Default.PlayCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Start sonar survey","开始声呐调查"))};when{state.settings.demoMode->Text(tr("Demo survey uses continuous simulated sonar tied to the current Demo GPS track. The map is drawn only while the Personal sonar layer is enabled.","演示调查会生成与当前演示 GPS 轨迹连续对应的模拟声呐；只有开启“个人声呐”图层时才会绘制在地图上。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);startDecision==SonarSurveyStartDecision.NMEA_NOT_CONNECTED->Text(tr("Connect the NMEA server before starting a real sonar survey.","开始真实声呐调查前必须先连接 NMEA 服务器。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);startDecision==SonarSurveyStartDecision.DEPTH_NOT_FRESH->Text(tr("Connected, but waiting for fresh DPT/DBT depth data.","服务器已连接，正在等待新的 DPT/DBT 水深数据。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);startDecision==SonarSurveyStartDecision.NMEA_POSITION_NOT_FRESH->Text(tr("Depth is live, but the same NMEA server has not supplied a fresh valid GPS position.","水深数据正常，但同一 NMEA 服务器尚未提供新鲜有效的 GPS 船位。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}}}
        item{Text(tr("Saved surveys","已保存调查"),style=MaterialTheme.typography.titleMedium)}
        if(state.sonarSurveys.any{it.tideMode!=TideMode.OFF.name})item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Chart-datum corrected history","海图基准修正历史"),fontWeight=FontWeight.SemiBold);Text(tr("Combines only usable samples with a non-null manual or LINZ-predicted tide normalization.","只合并已通过质量检查且存在手动或 LINZ 预测潮汐归一化值的样本。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)};if(state.selectedSonarSurveyId==CORRECTED_SONAR_HISTORY_ID)AssistChip({},label={Text(tr("ON MAP","地图中"))})else TextButton({vm.selectCorrectedSonarHistory();vm.page(0)}){Icon(Icons.Default.Map,null);Text(tr("Map","地图"))}}}}
        if(state.sonarSurveys.isEmpty())item{Text(tr("No sonar surveys yet.","还没有声呐调查。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.sonarSurveys,key={it.id}){survey->Card{Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(survey.name,fontWeight=FontWeight.SemiBold);Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(java.util.Date(survey.startedAt))} · ${survey.sampleCount} ${tr("samples","个样本")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};when{survey.active->AssistChip({},label={Text(tr("ACTIVE","进行中"))});state.selectedSonarSurveyId==survey.id->AssistChip({},label={Text(tr("ON MAP","地图中"))})}};Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){TextButton({vm.selectSonarSurvey(survey.id);vm.page(0)}){Icon(Icons.Default.Map,null);Text(tr("Map","地图"))};IconButton({vm.exportSonarCsv(survey)}){Icon(Icons.Default.Share,"CSV")};IconButton({vm.rebuildSonarSurvey(survey.id)},enabled=!survey.active){Icon(Icons.Default.Refresh,tr("Rebuild","重建"))};IconButton({rename=survey}){Icon(Icons.Default.Edit,tr("Rename","重命名"))};IconButton({delete=survey},enabled=!survey.active){Icon(Icons.Default.Delete,tr("Delete","删除"))}}}}}
    }
    if(showStart)SonarStartDialog(state,{showStart=false}){name,tideMode,manualTide,stationId->vm.startSonarSurvey(name,tideMode,manualTide,stationId);showStart=false}
    if(showDisclaimer)SonarSafetyDisclaimerDialog({showDisclaimer=false}){vm.updateSettings(state.settings.copy(sonarDisclaimerAccepted=true));showDisclaimer=false;showStart=true}
    rename?.let{survey->var value by remember(survey.id){mutableStateOf(survey.name)};AlertDialog(onDismissRequest={rename=null},title={Text(tr("Rename survey","重命名调查"))},text={OutlinedTextField(value,{value=it},singleLine=true,label={Text(tr("Name","名称"))})},confirmButton={Button({vm.renameSonarSurvey(survey.id,value);rename=null},enabled=value.isNotBlank()){Text(tr("Save","保存"))}},dismissButton={TextButton({rename=null}){Text(tr("Cancel","取消"))}})}
    delete?.let{survey->AlertDialog(onDismissRequest={delete=null},title={Text(tr("Delete sonar survey?","删除声呐调查？"))},text={Text(tr("All raw and normalized soundings in this survey will be permanently deleted.","该调查中的全部原始和归一化测深数据都会被永久删除。"))},confirmButton={Button({vm.deleteSonarSurvey(survey.id);delete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({delete=null}){Text(tr("Cancel","取消"))}})}
}

@Composable
private fun SonarStartDialog(state:MainUiState,dismiss:()->Unit,start:(String,TideMode,Double,String?)->Unit){
    var name by remember{mutableStateOf("")}
    var tideMode by remember{mutableStateOf(TideMode.OFF)};var manualText by remember{mutableStateOf("0.0")}
    val position=if(state.settings.demoMode)state.fix else state.nmeaFix
    val recommended=position?.let{TideStationCatalog.nearest(it.latitude,it.longitude)}
    var selectedStationId by remember(recommended?.first?.id){mutableStateOf(recommended?.first?.id)}
    var stationMenu by remember{mutableStateOf(false)}
    val station=selectedStationId?.let(TideStationCatalog::byId)?.let{port->port to (position?.let{AnchorGeometry.distanceMeters(it.latitude,it.longitude,port.latitude,port.longitude)}?:Double.NaN)}
    val manual=manualText.toDoubleOrNull()
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Start sonar survey","开始声呐调查"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(name,{name=it},label={Text(tr("Survey name (optional)","调查名称（可选）"))},singleLine=true)
        Text(tr("Sounder depth + ${signed(state.settings.sounderOffsetMeters)} m offset will be stored.","将记录测深仪水深 + ${signed(state.settings.sounderOffsetMeters)} 米 offset。"),fontWeight=FontWeight.SemiBold)
        Text(if(state.settings.demoMode)tr("Demo sonar follows a smooth simulated seabed along the Demo GPS track.","演示声呐会沿演示 GPS 轨迹生成连续、平滑变化的模拟海床。")else tr("Recording requires fresh DPT/DBT and fresh GPS from the same connected NMEA server. The anchor-watch GPS selection does not affect sonar coordinates.","记录要求同一台已连接的 NMEA 服务器同时提供新的 DPT/DBT 与 GPS；锚警的数据源选择不会影响声呐坐标。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider();Text(tr("Tide correction","潮汐修正"),style=MaterialTheme.typography.labelLarge)
        listOf(TideMode.OFF to tr("Off","关闭"),TideMode.MANUAL to tr("Manual","手动"),TideMode.AUTO_PREDICTED to tr("Automatic · LINZ predicted","自动 · LINZ 预测")).forEach{(mode,label)->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{tideMode=mode},verticalAlignment=Alignment.CenterVertically){RadioButton(tideMode==mode,{tideMode=mode});Text(label)}}
        if(tideMode==TideMode.MANUAL)OutlinedTextField(manualText,{manualText=it.filter{character->character.isDigit()||character=='.'||character=='-'}},label={Text(tr("Tide height above chart datum","高于海图基准的潮高"))},suffix={Text("m")},isError=manual==null,singleLine=true)
        if(tideMode==TideMode.AUTO_PREDICTED){
            Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){
                Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(tr("Tide station","潮汐站"),style=MaterialTheme.typography.labelLarge)
                    Box{
                        OutlinedButton({stationMenu=true},Modifier.fillMaxWidth()){
                            Text(station?.let{(port,distance)->
                                if(distance.isFinite())tr("${port.name} · ${(distance/1000).toInt()} km","${port.name} · ${(distance/1000).toInt()} 公里") else port.name
                            }?:tr("No position available","暂无船位"))
                        }
                        DropdownMenu(stationMenu,{stationMenu=false}){
                            TideStationCatalog.stations.forEach{port->
                                DropdownMenuItem(
                                    text={Text(port.name)},
                                    onClick={selectedStationId=port.id;stationMenu=false},
                                )
                            }
                        }
                    }
                    station?.first?.referenceStationId?.let{referenceId->
                        TideStationCatalog.byId(referenceId)?.let{reference->
                            Text(tr("Reference port: ${reference.name}","基准港：${reference.name}"),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(tr("The nearest station is recommended, but you can choose another. Selection and distance use the same NMEA GPS as the sounding. This is predicted, not observed sea level.","默认推荐最近站点，但你可以改选。站点与距离依据测深点所用的同一 NMEA GPS；这是预测潮位，不是实测海平面。"),style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }},confirmButton={Button({start(name,tideMode,manual?:0.0,selectedStationId)},enabled=(tideMode!=TideMode.MANUAL||manual!=null)&&(tideMode!=TideMode.AUTO_PREDICTED||selectedStationId!=null)){Text(tr("Start recording","开始记录"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}})
}

@Composable
internal fun ConnectionPage(state: MainUiState, vm: MainViewModel) {
    var profile by remember(state.settings.profile) { mutableStateOf(state.settings.profile) }
    var showWatchDisconnect by remember { mutableStateOf(false) }
    val connectionRunning = state.connection != NmeaConnectionState.DISCONNECTED
    val testing=state.connectionAttempt.state==ConnectionAttemptState.TESTING
    val controlsEnabled=state.settingsReady&&!connectionRunning&&!testing
    val activeWatchUsesNmea=state.active?.paused==false&&state.active.positionSource==GpsDataSource.NMEA.name
    val validationError=vm.validateProfile(profile)
    fun edit(next:ConnectionProfile){profile=next;vm.clearConnectionAttempt()}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("nmea_runtime_list"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            else Button({vm.saveAndConnect(profile)},Modifier.fillMaxWidth(),enabled=state.settingsReady&&!testing&&validationError==null){if(testing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Default.Link,null);Spacer(Modifier.width(6.dp));Text(if(testing)tr("Testing NMEA…","正在测试 NMEA…") else tr("Test, save & connect","测试、保存并连接"))}
            if(!state.settingsReady)Text(tr("Loading saved connection settings…","正在加载已保存的连接设置…"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(localizeKnownMessage(state.connectionAttempt.message),Modifier.testTag("nmea_connection_attempt"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(testing)Text(localizeKnownMessage(state.connectionAttempt.message),Modifier.testTag("nmea_connection_attempt"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(testing&&!connectionRunning)Text(tr("The app must receive at least one valid NMEA sentence before it will connect.","应用必须收到至少一条有效 NMEA 语句后才会正式连接。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.settings.nmeaSharingEnabled)Text(tr("NMEA Sharing does not own or auto-connect this endpoint. If disconnected, the server stays up and waits for accepted input.","NMEA 共享不会占用或自动连接此端点；断开后共享服务器会继续运行并等待可信输入。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { ConnectionResultCard(state, vm) }
        item { GpsProxyCard(state,vm) }
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
  if(enabled){PreflightRow(tr("Server","服务器"),state.nmeaSharing.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING,sharingStateLabel(state.nmeaSharing.state));Text(tr("${state.nmeaSharing.clientCount} connected clients · ${state.nmeaSharing.sentSentences} output sentences","${state.nmeaSharing.clientCount} 个客户端已连接 · 已输出 ${state.nmeaSharing.sentSentences} 条语句"),style=MaterialTheme.typography.bodySmall);state.nmeaSharing.addresses.forEach{address->val host=if(':' in address)"[$address]" else address;SelectionContainer{Text("tcp://$host:${state.settings.nmeaSharingPort}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}};state.nmeaSharing.clients.forEach{client->val minutes=((System.currentTimeMillis()-client.connectedAtMillis).coerceAtLeast(0L)/60_000L);Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(8.dp)){SelectionContainer{Text(client.address,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)};Text(tr("Connected ${minutes} min · ${client.sentSentences} sentences","已连接 ${minutes} 分钟 · ${client.sentSentences} 条语句"),style=MaterialTheme.typography.labelSmall)}}};if(state.nmeaSharing.droppedSlowClients>0)Text(tr("${state.nmeaSharing.droppedSlowClients} slow clients were safely disconnected.","已安全断开 ${state.nmeaSharing.droppedSlowClients} 个过慢客户端。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);if(state.nmeaSharing.message.isNotBlank())Text(localizeKnownMessage(state.nmeaSharing.message),style=MaterialTheme.typography.bodySmall,color=if(state.nmeaSharing.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
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
    var paused by remember { mutableStateOf(false) };var healthExpanded by remember{mutableStateOf(false)};var displayed by remember { mutableStateOf(state.diagnostics.raw) }; val context=LocalContext.current;val tileDiagnostics by SonarTileDiagnostics.state.collectAsState()
    LaunchedEffect(state.diagnostics.raw, paused) { if (!paused) displayed = state.diagnostics.raw }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{PageHeader(tr("Live NMEA data","实时 NMEA 数据"), tr("Parsed values, readable health checks and the latest 200 raw sentences.","查看解析值、清晰的健康检查和最近 200 条原始语句。"))}
        item{Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { CompactStat(tr("VALID","有效"), state.diagnostics.validSentences.toString(), Modifier.weight(1f)); CompactStat(tr("INVALID","无效"), state.diagnostics.invalidSentences.toString(), Modifier.weight(1f)); CompactStat(tr("CHECKSUM","校验错误"), state.diagnostics.checksumErrors.toString(), Modifier.weight(1f)) }}
        state.nmeaFix?.let{fix->item{Card{Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("Latest NMEA values","最新 NMEA 数值"),fontWeight=FontWeight.SemiBold)
            DiagnosticsRow(tr("Position","船位"),"%.6f, %.6f".format(fix.latitude,fix.longitude))
            DiagnosticsRow(tr("Speed / heading","航速 / 航向"),"${fix.sogKnots?.let{"%.1f kn".format(it)}?:"—"}  ·  ${fix.headingTrueDegrees?.let{"${it.toInt()}°"}?:"—"}")
            DiagnosticsRow(tr("HDOP / provider","HDOP / 提供者"),"${fix.hdop?.let{"%.1f".format(it)}?:"—"}  ·  ${diagnosticState(fix.positionProvider.name)}")
            DiagnosticsRow(tr("Wind / depth","风 / 水深"),"${fix.windSpeedKnots?.let{"%.1f kn".format(it)}?:"—"}  ·  ${fix.depthMeters?.let{"%.1f m".format(it)}?:"—"}")
        }}}}
        item{RuntimeHealthCard(state,tileDiagnostics,healthExpanded){healthExpanded=!healthExpanded}}
        item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Raw sentences","原始语句"),style=MaterialTheme.typography.titleMedium);Text(if(paused)tr("Display paused; incoming data is not discarded.","显示已暂停；新到数据不会被丢弃。")else tr("Live display","实时显示"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton({paused=!paused}){Icon(if(paused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(4.dp));Text(if(paused)tr("Resume","继续")else tr("Pause","暂停"))}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({vm.clearDiagnostics();displayed=emptyList()},Modifier.weight(1f)){Icon(Icons.Default.DeleteSweep,null);Spacer(Modifier.width(4.dp));Text(tr("Clear","清空"))};OutlinedButton({context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("NMEA",displayed.joinToString("\n")))},Modifier.weight(1f),enabled=displayed.isNotEmpty()){Icon(Icons.Default.ContentCopy,null);Spacer(Modifier.width(4.dp));Text(tr("Copy","复制"))}}
        }}
        item{SelectionContainer{Surface(Modifier.fillMaxWidth(),color=Color.Black,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(displayed.isEmpty())Text(if(state.connection==NmeaConnectionState.CONNECTED)tr("Connected. Waiting for NMEA sentences…","已连接，正在等待 NMEA 语句…")else tr("Connect to a data source to view raw NMEA.","连接数据源后即可查看原始 NMEA 数据。"),color=Color.Gray,fontFamily=FontFamily.Monospace)
            else displayed.asReversed().forEach{sentence->Text(sentence,color=Color(0xFFB9F6CA),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
        }}}}
    }
}
@Composable private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } } }

@Composable private fun RuntimeHealthCard(state:MainUiState,tile:com.yokuli.anchorwatch.map.SonarTileDiagnosticsSnapshot,expanded:Boolean,toggle:()->Unit){
    val runtime=state.runtimeDiagnostics;val grid=state.sonarRecorder.gridDiagnostics
    val ownerLabels=mapOf("ANCHOR_WATCH" to tr("Anchor watch","锚警监控"),"NMEA_SHARING" to tr("NMEA sharing","NMEA 共享"),"GPS_PROXY" to tr("GPS proxy","GPS 代理"),"SONAR_MAPPING" to tr("Sonar mapping","声呐测绘"))
    Card{
        Column(Modifier.fillMaxWidth()){
            Row(Modifier.fillMaxWidth().clickable(onClick=toggle).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Icon(Icons.Default.HealthAndSafety,null,tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){Text(tr("Runtime & health","运行与健康"),fontWeight=FontWeight.SemiBold);Text(tr("${runtime.acceptedFixCount} accepted fixes · ${runtime.rejectedFixCount} rejected · ${runtime.activeOwners.size} active services","${runtime.acceptedFixCount} 个可信定位 · ${runtime.rejectedFixCount} 个拒绝定位 · ${runtime.activeOwners.size} 个活动服务"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Icon(if(expanded)Icons.Default.ExpandLess else Icons.Default.ExpandMore,if(expanded)tr("Collapse","收起")else tr("Expand","展开"))
            }
            if(expanded){HorizontalDivider();Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                DiagnosticsSection(tr("Position acceptance","船位接收"))
                DiagnosticsRow(tr("Selected source","选定来源"),diagnosticState(state.acceptedPosition.selectedSource.name))
                DiagnosticsRow(tr("Current result","当前结果"),diagnosticState(state.acceptedPosition.disposition))
                DiagnosticsRow(tr("Trust level","可信等级"),state.acceptedPosition.trust?.name?.let{diagnosticState(it)}?:"—")
                DiagnosticsRow(tr("Raw position","原始船位"),state.acceptedPosition.rawFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—")
                DiagnosticsRow(tr("Accepted position","可信船位"),state.acceptedPosition.acceptedFix?.let{"%.6f, %.6f".format(it.latitude,it.longitude)}?:"—")
                state.acceptedPosition.reason?.let{DiagnosticsRow(tr("Reason","原因"),localizeKnownMessage(it))}
                DiagnosticsRow(tr("Accepted / quarantined / rejected","可信 / 隔离 / 拒绝"),"${runtime.acceptedFixCount} / ${runtime.quarantinedFixCount} / ${runtime.rejectedFixCount}")
                DiagnosticsRow(tr("NMEA reconnects","NMEA 重连次数"),runtime.nmeaReconnectCount.toString())
                DiagnosticsRow(tr("Integrity time (last / max)","完整性检查耗时（最近 / 最大）"),"${runtime.positionIntegrityLastDurationMicros} / ${runtime.positionIntegrityMaxDurationMicros} µs")

                HorizontalDivider();DiagnosticsSection(tr("Anchor & sonar processing","锚点与声呐处理"))
                DiagnosticsRow(tr("Centre estimator runs","中心估算次数"),"${runtime.estimatorRuns}  ·  ${runtime.estimatorLastDurationMs}/${runtime.estimatorMaxDurationMs} ms")
                DiagnosticsRow(tr("Sonar samples written","已写入声呐样本"),runtime.sonarSamplesWritten.toString())
                DiagnosticsRow(tr("Grid updates (last / max)","网格更新（最近 / 最大）"),"${runtime.sonarGridUpdates}  ·  ${runtime.sonarGridLastDurationMs}/${runtime.sonarGridMaxDurationMs} ms")
                DiagnosticsRow(tr("Raw / stored / selected cells","原始样本 / 已存网格 / 当前网格"),"${grid.rawSamples} / ${grid.gridCells} / ${state.sonarGrid.cells.size}")
                DiagnosticsRow(tr("Grid mode","网格模式"),if(grid.rebuilding)tr("Rebuilding","正在重建")else tr("Incremental","增量更新"))
                DiagnosticsRow(tr("Tile cache / rendered","瓦片缓存 / 已渲染"),"${tile.cacheEntries} / ${tile.renderedTiles}  ·  ${tile.lastRenderDurationMillis}/${tile.maxRenderDurationMillis} ms")
                DiagnosticsRow(tr("Tide corrections","潮汐修正"),"${runtime.tideCorrections}  ·  ${runtime.tideLastDurationMs}/${runtime.tideMaxDurationMs} ms")

                HorizontalDivider();DiagnosticsSection(tr("LINZ vector depth","LINZ 矢量水深"))
                DiagnosticsRow(tr("Status / HTTP","状态 / HTTP"),"${diagnosticState(state.linzDepth.status.name)} / ${state.linzDepthDiagnostics.lastHttpCode?:"—"}")
                DiagnosticsRow(tr("Depth area","水深区域"),"${state.linzDepth.depthAreaMinMeters?:"—"}–${state.linzDepth.depthAreaMaxMeters?:"—"} m")
                DiagnosticsRow(tr("Nearest sounding","最近测深点"),"${state.linzDepth.nearestSoundingDepthMeters?:"—"} m  ·  ${state.linzDepth.nearestSoundingDistanceMeters?.toInt()?:"—"} m")
                DiagnosticsRow(tr("Nearest contour","最近等深线"),"${state.linzDepth.nearestContourDepthMeters?:"—"} m  ·  ${state.linzDepth.nearestContourDistanceMeters?.toInt()?:"—"} m")
                DiagnosticsRow(tr("Cache hits / misses","缓存命中 / 未命中"),"${state.linzDepthDiagnostics.cacheHits} / ${state.linzDepthDiagnostics.cacheMisses}")
                DiagnosticsRow(tr("Message","消息"),localizeKnownMessage(state.linzDepthDiagnostics.message))

                HorizontalDivider();DiagnosticsSection(tr("Background resources","后台资源"))
                DiagnosticsRow(tr("Active services","活动服务"),runtime.activeOwners.map{ownerLabels[it.name]?:it.name}.joinToString().ifBlank{"—"})
                DiagnosticsRow(tr("CPU wake lock","CPU 唤醒锁"),if(runtime.wakeLockHeld)tr("Held","已持有")else tr("Off","关闭"))
                DiagnosticsRow(tr("Wi-Fi lock","Wi-Fi 锁"),if(runtime.wifiLockHeld)tr("Held","已持有")else tr("Off","关闭"))
                DiagnosticsRow(tr("Sharing clients / dropped","共享客户端 / 已断开"),"${runtime.sharingClients} / ${runtime.sharingSlowClientsDropped}")
            }}
        }
    }
}

@Composable private fun DiagnosticsSection(title:String){Text(title,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)}
@Composable private fun DiagnosticsRow(label:String,value:String){Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodyMedium)}}
@Composable private fun diagnosticState(value:String):String=when(value){
    "SYSTEM"->tr("System GPS","系统 GPS");"NMEA"->"NMEA GPS";"DEMO"->tr("Demo GPS","演示 GPS")
    "ACCEPTED"->tr("Accepted","可信");"QUARANTINED"->tr("Quarantined","隔离");"REJECTED"->tr("Rejected","拒绝");"PENDING"->tr("Pending","等待中")
    "TRUSTED"->tr("Trusted","可信");"DEGRADED"->tr("Degraded","质量下降");"UNTRUSTED"->tr("Untrusted","不可信")
    "IDLE"->tr("Idle","待命");"LOADING"->tr("Loading","正在加载");"AVAILABLE"->tr("Available","可用");"NO_DATA"->tr("No data","无数据");"OFFLINE"->tr("Offline","离线");"NOT_CONFIGURED"->tr("Not configured","未配置");"ERROR"->tr("Error","错误")
    "ANCHOR_WATCH"->tr("Anchor watch","锚警监控");"NMEA_SHARING"->tr("NMEA sharing","NMEA 共享");"GPS_PROXY"->tr("GPS proxy","GPS 代理");"SONAR_MAPPING"->tr("Sonar mapping","声呐测绘")
    "ANDROID_GNSS"->tr("Android GNSS","安卓 GNSS");"ANDROID_NETWORK"->tr("Android network","安卓网络定位");"NMEA_GNSS"->tr("NMEA GNSS","NMEA GNSS");"DEMO_SIMULATED"->tr("Demo simulation","演示模拟")
    else->value.replace('_',' ').lowercase().replaceFirstChar{it.titlecase()}
}

@Composable private fun sharingStateLabel(state:com.yokuli.anchorwatch.data.sharing.SharingServerState):String=when(state){com.yokuli.anchorwatch.data.sharing.SharingServerState.STOPPED->tr("Stopped","已停止");com.yokuli.anchorwatch.data.sharing.SharingServerState.STARTING->tr("Starting","正在启动");com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING->tr("Running","运行中");com.yokuli.anchorwatch.data.sharing.SharingServerState.ERROR->tr("Error","错误")}
