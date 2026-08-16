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
@OptIn(ExperimentalMaterial3Api::class)
internal fun DataPage(state:MainUiState,vm:MainViewModel){
    var diagnostics by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize()){
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)){
            SegmentedButton(!diagnostics,{diagnostics=false},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(tr("Connection & data","连接与数据"))}
            SegmentedButton(diagnostics,{diagnostics=true},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(tr("Diagnostics","诊断"))}
        }
        Box(Modifier.weight(1f)){if(diagnostics)NmeaDataPage(state,vm) else ConnectionPage(state,vm)}
    }
}

@Composable
internal fun ConnectionPage(state: MainUiState, vm: MainViewModel) {
    var profile by remember(state.settings.profile) { mutableStateOf(state.settings.profile) }
    var showWatchDisconnect by remember { mutableStateOf(false) }
    val connectionRunning = state.connection != NmeaConnectionState.DISCONNECTED
    val testing=state.connectionAttempt.state==ConnectionAttemptState.TESTING
    val controlsEnabled=!connectionRunning&&!testing
    val activeWatchUsesNmea=state.active?.paused==false&&state.settings.gpsDataSource==GpsDataSource.NMEA
    val validationError=vm.validateProfile(profile)
    fun edit(next:ConnectionProfile){profile=next;vm.clearConnectionAttempt()}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader(tr("NMEA connection","NMEA 连接"), tr("Configure and verify live traffic. A successful connection becomes the next default unless a session is open.","配置并验证实时数据。连接成功后会成为下次默认来源，但不会改变已开启会话。")) }
        if(activeWatchUsesNmea&&state.connection!=NmeaConnectionState.CONNECTED)item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("Anchor watch needs NMEA","锚警需要 NMEA 数据"),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onErrorContainer);Text(tr("The watch remains armed on NMEA with no silent failover. Reconnect, switch safely to a fresh System fix, or pause the watch.","锚警仍使用 NMEA 保持布防，不会静默切源。请恢复连接、安全切到新鲜系统定位，或暂停锚警。"),color=MaterialTheme.colorScheme.onErrorContainer);OutlinedButton({showWatchDisconnect=true}){Text(tr("Resolve active watch","处理当前锚警"))}}} }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(connectionRunning) AssistChip({}, { Text(tr("Configuration locked while connected","连接期间配置已锁定")) }, leadingIcon={Icon(Icons.Default.Lock,null,Modifier.size(18.dp))}, enabled=false)
            OutlinedTextField(profile.name, { edit(profile.copy(name = it)) }, label = { Text(tr("Profile name","配置名称")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled)
            Text(tr("Protocol","协议"), style = MaterialTheme.typography.labelLarge); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(profile.protocol == Protocol.TCP, { edit(profile.copy(protocol = Protocol.TCP)) }, label = { Text(tr("TCP client","TCP 客户端")) },enabled=controlsEnabled); FilterChip(profile.protocol == Protocol.UDP, { edit(profile.copy(protocol = Protocol.UDP)) }, label = { Text(tr("UDP listener","UDP 监听")) },enabled=controlsEnabled) }
            if (profile.protocol == Protocol.TCP) OutlinedTextField(profile.host, { edit(profile.copy(host = it)) }, label = { Text(tr("Host or IP address","主机名或 IP 地址")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled,isError=validationError!=null,supportingText={if(validationError!=null)Text(localizeKnownMessage(validationError))})
            OutlinedTextField(profile.port.toString(), { v -> edit(profile.copy(port=v.filter(Char::isDigit).toIntOrNull()?:0)) }, label = { Text(if (profile.protocol == Protocol.TCP) tr("Server port","服务器端口") else tr("Listen port","监听端口")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled=controlsEnabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),isError=profile.port !in 1..65535)
            SettingSwitch(tr("Require checksum","要求校验和"), tr("Reject sentences without a checksum","拒绝没有校验和的语句"), profile.requireChecksum,enabled=controlsEnabled) { edit(profile.copy(requireChecksum = it)) }; SettingSwitch(tr("Auto reconnect","自动重连"), tr("Reconnect after network loss","网络中断后自动重新连接"), profile.autoReconnect,enabled=controlsEnabled) { edit(profile.copy(autoReconnect = it)) }
            if(connectionRunning) Button({if(activeWatchUsesNmea)showWatchDisconnect=true else vm.disconnect()},Modifier.fillMaxWidth(),enabled=!testing&&!state.settings.nmeaSharingEnabled){Icon(if(state.settings.nmeaSharingEnabled)Icons.Default.Share else Icons.Default.LinkOff,null);Spacer(Modifier.width(6.dp));Text(if(state.settings.nmeaSharingEnabled)tr("Upstream held by NMEA Sharing","NMEA 共享正在使用上游")else tr("Disconnect","断开连接"))}
            else Button({vm.saveAndConnect(profile)},Modifier.fillMaxWidth(),enabled=!testing&&validationError==null){if(testing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Default.Link,null);Spacer(Modifier.width(6.dp));Text(if(testing)tr("Testing NMEA…","正在测试 NMEA…") else tr("Test, save & connect","测试、保存并连接"))}
            if(state.connectionAttempt.state==ConnectionAttemptState.FAILED)Text(localizeKnownMessage(state.connectionAttempt.message),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(testing)Text(localizeKnownMessage(state.connectionAttempt.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(testing&&!connectionRunning)Text(tr("The app must receive at least one valid NMEA sentence before it will connect.","应用必须收到至少一条有效 NMEA 语句后才会正式连接。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.settings.nmeaSharingEnabled)Text(tr("Disable NMEA Sharing below before closing its shared upstream connection.","如需关闭共享的上游连接，请先在下方关闭 NMEA Sharing。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { ConnectionResultCard(state, vm) }
        item { NmeaSharingCard(state,vm) }
    }
    if(showWatchDisconnect)ActiveWatchDisconnectDialog(
        canSwitchToSystem=!GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state),
        switchToSystem={showWatchDisconnect=false;vm.switchActiveWatchToSystemAndDisconnect()},
        pauseWatch={showWatchDisconnect=false;vm.stopActiveWatchAndDisconnect()},
        dismiss={showWatchDisconnect=false},
    )
}

@Composable private fun NmeaSharingCard(state:MainUiState,vm:MainViewModel){
 var portText by remember(state.settings.nmeaSharingPort){mutableStateOf(state.settings.nmeaSharingPort.toString())}
 val enabled=state.settings.nmeaSharingEnabled;val port=portText.toIntOrNull();val valid=port!=null&&port in 1024..65535
 Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("NMEA Sharing","NMEA 共享"),style=MaterialTheme.typography.titleMedium);Text(tr("Share this App's single upstream stream with chartplotters and other devices.","把本应用的单一上游数据流共享给海图仪和其他设备。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(enabled,{checked->if(valid)vm.setNmeaSharing(checked,port!!)})}
  OutlinedTextField(portText,{portText=it.filter(Char::isDigit)},label={Text(tr("TCP server port","TCP 服务器端口"))},enabled=!enabled,isError=!valid,supportingText={if(!valid)Text(tr("Use port 1024–65535","请输入 1024–65535"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
  if(!enabled&&valid&&port!=state.settings.nmeaSharingPort)OutlinedButton({vm.setNmeaSharing(false,port!!)},Modifier.fillMaxWidth()){Text(tr("Save port","保存端口"))}
  if(enabled){PreflightRow(tr("Server","服务器"),state.nmeaSharing.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.RUNNING,state.nmeaSharing.state.name);Text(tr("${state.nmeaSharing.clientCount} connected clients · ${state.nmeaSharing.sentSentences} output sentences","${state.nmeaSharing.clientCount} 个客户端已连接 · 已输出 ${state.nmeaSharing.sentSentences} 条语句"),style=MaterialTheme.typography.bodySmall);state.nmeaSharing.addresses.forEach{address->val host=if(':' in address)"[$address]" else address;SelectionContainer{Text("tcp://$host:${state.settings.nmeaSharingPort}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}};state.nmeaSharing.clients.forEach{client->val minutes=((System.currentTimeMillis()-client.connectedAtMillis).coerceAtLeast(0L)/60_000L);Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){Column(Modifier.fillMaxWidth().padding(8.dp)){SelectionContainer{Text(client.address,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)};Text(tr("Connected ${minutes} min · ${client.sentSentences} sentences","已连接 ${minutes} 分钟 · ${client.sentSentences} 条语句"),style=MaterialTheme.typography.labelSmall)}}};if(state.nmeaSharing.droppedSlowClients>0)Text(tr("${state.nmeaSharing.droppedSlowClients} slow clients were safely disconnected.","已安全断开 ${state.nmeaSharing.droppedSlowClients} 个过慢客户端。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error);if(state.nmeaSharing.message.isNotBlank())Text(state.nmeaSharing.message,style=MaterialTheme.typography.bodySmall,color=if(state.nmeaSharing.state==com.yokuli.anchorwatch.data.sharing.SharingServerState.ERROR)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
  val mode=when(state.settings.gpsDataSource){GpsDataSource.NMEA->tr("Output: complete valid boat NMEA passthrough.","输出：完整透传有效船载 NMEA。");GpsDataSource.SYSTEM->tr("Output: boat instruments plus filtered System-GNSS GNRMC/GNGGA/GNVTG.","输出：船载仪表数据，加过滤后的系统 GNSS GNRMC/GNGGA/GNVTG。");GpsDataSource.DEMO->tr("Output: boat instruments only while Demo GPS is active.","演示 GPS 开启时仅输出船载仪表数据。")};Text(mode,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(enabled){val gpsReady=when(state.settings.gpsDataSource){GpsDataSource.NMEA->state.connection==NmeaConnectionState.CONNECTED&&state.nmeaFix?.valid==true;GpsDataSource.SYSTEM->state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS}==true;GpsDataSource.DEMO->true};PreflightRow(tr("Shared position source","共享定位源"),gpsReady,if(gpsReady)tr("Ready","就绪")else tr("Waiting for a trusted fix","等待可信定位"))}
  Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(tr("No authentication or encryption. Enable only on a trusted boat LAN or VPN. Never connect the App's own input to this same address and port.","没有身份验证或加密。只应在可信船载局域网或 VPN 中开启；不要把本应用的输入连接回同一地址和端口。"),Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)}
 }}
}

@Composable private fun ActiveWatchDisconnectDialog(canSwitchToSystem:Boolean,switchToSystem:()->Unit,pauseWatch:()->Unit,dismiss:()->Unit){
 AlertDialog(onDismissRequest=dismiss,title={Text(tr("Anchor watch is using NMEA","锚警正在使用 NMEA"))},text={Text(if(canSwitchToSystem)tr("Disconnect safely by first acquiring a fresh precise System GNSS fix, or pause this session. The anchor centre, range and track are preserved when switching.","可先获取新鲜精确的系统 GNSS 定位再安全断开，或暂停本次会话。切换时会保留锚中心、范围和轨迹。")else tr("Global NMEA GPS proxy is active, so System GPS is not independent. Disable the proxy first, or pause this session before disconnecting.","全局 NMEA GPS 代理已开启，系统 GPS 并非独立来源。请先关闭代理，或暂停本次会话后再断开。"))},confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Button(switchToSystem,Modifier.fillMaxWidth(),enabled=canSwitchToSystem){Icon(Icons.Default.SwapHoriz,null);Spacer(Modifier.width(6.dp));Text(tr("Switch to System GPS & disconnect","切换到系统 GPS 并断开"))}
  OutlinedButton(pauseWatch,Modifier.fillMaxWidth()){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Pause watch & disconnect","暂停锚警并断开"))}
  TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Cancel","取消"))}
 }})
}

@Composable private fun ConnectionResultCard(state: MainUiState, vm: MainViewModel) { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(tr("Live status","实时状态"), style = MaterialTheme.typography.titleMedium); Text(connectionStateLabel(state.connection), color = if(state.connection==NmeaConnectionState.CONNECTED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
    HorizontalDivider(); Text(tr("${state.diagnostics.validSentences} valid sentences • ${state.diagnostics.invalidSentences} invalid","${state.diagnostics.validSentences} 条有效语句 · ${state.diagnostics.invalidSentences} 条无效语句")); state.nmeaFix?.let { Text(tr("Latest position  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}","最新位置  ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}")) } ?: Text(tr("No parsed GPS position yet","暂时没有解析出的 GPS 位置"));Text(tr("Open Diagnostics above for the raw NMEA stream.","可在上方切换到“诊断”查看原始 NMEA 数据。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(tr("Raw sentences","原始语句"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton({ paused = !paused }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Text(if (paused) tr("Resume","继续") else tr("Pause","暂停")) }; TextButton({ vm.clearDiagnostics(); displayed = emptyList() }) { Icon(Icons.Default.DeleteSweep, null); Text(tr("Clear","清空")) }; TextButton({ clipboard.setText(AnnotatedString(displayed.joinToString("\n"))) }, enabled = displayed.isNotEmpty()) { Icon(Icons.Default.ContentCopy, null); Text(tr("Copy","复制")) } }
        }
        SelectionContainer { LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 8.dp)) { if (displayed.isEmpty()) item { Text(if (state.connection == NmeaConnectionState.CONNECTED) tr("Connected. Waiting for NMEA sentences…","已连接，正在等待 NMEA 语句…") else tr("Connect to a data source to view raw NMEA.","连接数据源后即可查看原始 NMEA 数据。"), color = Color.Gray, fontFamily = FontFamily.Monospace) }; items(displayed.asReversed()) { Text(it, color = Color(0xFFB9F6CA), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } } }
    }
}
@Composable private fun CompactStat(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge) } } }
