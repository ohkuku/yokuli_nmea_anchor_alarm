package com.yokuli.anchorwatch.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.tr
import com.yokuli.anchorwatch.data.vessel.NmeaDeviceOutputSettings
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference

/** Six short, domain-backed setup decisions. Optional equipment pages never
 * fabricate a connection or enable publication; they tell the user exactly
 * where the live control will remain available after setup. */
@Composable
fun FirstRunSetupScreen(
    initialBoatLengthMeters:Double,
    initialDraftMeters:Double?,
    nmeaConnection:NmeaConnectionState,
    headingAligned:Boolean,
    positionPreference:VesselSourcePreference,
    headingPreference:VesselSourcePreference,
    output:NmeaDeviceOutputSettings,
    alarmState:AlarmState,
    alarmType:AlarmType?,
    saveVessel:(Double,Double?)->Unit,
    testAlarm:()->Unit,
    confirmAlarm:()->Unit,
    stopAlarm:()->Unit,
    complete:()->Unit,
){
    var step by rememberSaveable{mutableIntStateOf(0)}
    var boatLength by rememberSaveable{mutableStateOf(initialBoatLengthMeters.toString())}
    var draft by rememberSaveable{mutableStateOf(initialDraftMeters?.toString().orEmpty())}
    val alarmTesting=alarmState==AlarmState.ALARM&&alarmType==AlarmType.ALARM_TEST
    val titles=listOf(tr("Vessel profile","船舶资料"),"NMEA Input",tr("Phone heading","手机船首向"),tr("Data sources","数据来源"),"NMEA Output",tr("Alarm test","警报测试"))
    Scaffold(bottomBar={Surface(shadowElevation=8.dp){Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        if(step>0)OutlinedButton({step--},Modifier.weight(1f)){Text(tr("Back","返回"))}
        val validVessel=boatLength.toDoubleOrNull()?.let{it>0}==true&&(draft.isBlank()||draft.toDoubleOrNull()?.let{it>=0}==true)
        Button({
            if(step==0)saveVessel(boatLength.toDouble(),draft.toDoubleOrNull())
            if(step<5)step++ else complete()
        },Modifier.weight(1f).testTag(if(step==5)"onboarding_finish" else "onboarding_setup_next"),enabled=step!=0||validVessel){Text(if(step==5)tr("Finish setup","完成设置")else if(step in setOf(1,2,4))tr("Skip / continue","跳过 / 继续")else tr("Continue","继续"))}
    }}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).testTag("onboarding_setup_step_$step"),verticalArrangement=Arrangement.spacedBy(14.dp)){
            LinearProgressIndicator(progress={(step+1)/6f},modifier=Modifier.fillMaxWidth())
            Text(tr("First-run setup","首次设置"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            Text("${step+1}/6 · ${titles[step]}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            when(step){
                0->{
                    Text(tr("These values are stored locally and used by anchor geometry and under-keel-clearance calculations.","这些数值只保存在本机，用于锚泊几何和龙骨下余量计算。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(boatLength,{boatLength=decimal(it)},Modifier.fillMaxWidth(),label={Text(tr("Boat length *","船长 *"))},suffix={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                    OutlinedTextField(draft,{draft=decimal(it)},Modifier.fillMaxWidth(),label={Text(tr("Draft (optional)","吃水（可选）"))},suffix={Text("m")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                }
                1->SetupStatusCard(Icons.Default.Cable,"NMEA Input",when(nmeaConnection){NmeaConnectionState.CONNECTED->tr("Connected and receiving valid NMEA","已连接并正在接收有效 NMEA");NmeaConnectionState.CONNECTED_NO_DATA,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE->tr("Endpoint exists, but live data is not ready","端点存在，但实时数据尚未就绪");else->tr("Not connected — configure it later in Data → NMEA Input","尚未连接，可稍后在“数据 → NMEA 输入”中配置")},nmeaConnection==NmeaConnectionState.CONNECTED)
                2->{SetupStatusCard(Icons.Default.Smartphone,tr("Phone heading","手机船首向"),if(headingAligned)tr("Phone-to-bow direction aligned · adjustable later","手机—船艏方向已对齐 · 以后可随时调整")else tr("Optional now — align later in Settings → Phone vessel sensors","现在可跳过；稍后在“设置 → 手机船舶传感器”对齐"),headingAligned);Text(tr("GNSS and pressure never require a mount. Heading can be realigned whenever the phone orientation changes; Trip attitude is confirmed separately only while sailing.","GNSS 与气压从不要求固定安装。手机方向改变后可随时重新对齐船首向；航程姿态只在航行时单独确认。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                3->{SetupStatusCard(Icons.Default.Hub,tr("Automatic source routing","自动来源路由"),tr("Position: ${preferenceLabel(positionPreference)} · Heading: ${preferenceLabel(headingPreference)}","位置：${preferenceLabel(positionPreference)} · 船艏向：${preferenceLabel(headingPreference)}"),true);Text(tr("Auto preserves every candidate, prefers healthy boat instruments, and falls back only after freshness and recovery checks. You can inspect or pin a source in Data → Vessel.","自动模式会保留所有候选来源，优先选择健康的船载仪表，并只在新鲜度与恢复检查后回退。可在“数据 → 船舶”查看或固定来源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                4->{
                    val body=when{
                        !output.transportConfigured->tr("Not configured — no output socket can start","尚未配置；不会启动任何输出 Socket")
                        !headingAligned->tr("Route saved — align the phone to the bow before sharing vessel heading","线路已保存；分享船首向前，请先把手机与船艏方向对齐")
                        output.publicationEnabled->tr("Canonical vessel-data sharing is running","统一船舶数据共享正在运行")
                        else->tr("Route and calibration are ready — output waits for an explicit Start","线路和校准已就绪；输出正在等待用户明确启动")
                    }
                    SetupStatusCard(Icons.Default.Output,"NMEA Output",body,output.publicationEnabled)
                    Text(tr("Output is optional and separate from input. Data → NMEA Output owns only the TX route, diagnostics and explicit Start/Stop. Metric sources are selected once in Data → Vessel; the shared feed re-encodes complete selected values, holds same-source unchanged heartbeats, and never sends a blank primary field.","输出是可选功能，并与输入完全分离。“数据 → NMEA 输出”只负责 TX 线路、诊断和明确启停；每项数据的来源只在“数据 → 船舶”选择一次，共享流会重新编码已选中的完整数值、保留同源未变化心跳，并且绝不会发送空的主字段。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else->{
                    SetupStatusCard(Icons.Default.NotificationsActive,tr("Audible safety check","可听见的安全检查"),if(alarmTesting)tr("Alarm test is sounding","警报测试正在响")else tr("Use the real global alarm path before relying on Boat Watch","依赖 Boat Watch 前，请通过真实全局警报链路完成试听"),alarmTesting)
                    if(alarmTesting){Button({confirmAlarm();stopAlarm()},Modifier.fillMaxWidth().testTag("onboarding_alarm_heard")){Icon(Icons.Default.Hearing,null);Spacer(Modifier.width(8.dp));Text(tr("I can hear it","我能听见"))};OutlinedButton(stopAlarm,Modifier.fillMaxWidth()){Text(tr("Stop test","停止测试"))}}
                    else OutlinedButton(testAlarm,Modifier.fillMaxWidth().testTag("onboarding_alarm_test")){Icon(Icons.Default.Campaign,null);Spacer(Modifier.width(8.dp));Text(tr("Start alarm test","开始警报测试"))}
                    Text(tr("You may finish setup without confirming, but Watch Preflight will keep this safety check visible.","可以暂不确认并完成设置，但监控布防检查会持续显示这项安全风险。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun SetupStatusCard(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,body:String,ready:Boolean){ElevatedCard(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=if(ready)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(if(ready)Icons.Default.CheckCircle else Icons.Default.Info,null,tint=if(ready)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)}}}
private fun decimal(value:String)=value.filter{it.isDigit()||it=='.'}.take(8)
@Composable private fun preferenceLabel(value:VesselSourcePreference)=when(value){VesselSourcePreference.AUTO->tr("Auto","自动");VesselSourcePreference.BOAT->tr("Boat","船载");VesselSourcePreference.PHONE->tr("Phone","手机");VesselSourcePreference.DERIVED->tr("Derived","推算")}
