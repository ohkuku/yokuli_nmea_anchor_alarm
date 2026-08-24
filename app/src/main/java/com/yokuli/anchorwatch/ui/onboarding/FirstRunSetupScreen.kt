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
import com.yokuli.anchorwatch.data.vessel.effectivePositionPolicy
import com.yokuli.anchorwatch.data.vessel.effectiveHeadingPolicy
import com.yokuli.anchorwatch.data.vessel.effectiveMotionPolicy
import com.yokuli.anchorwatch.data.vessel.effectivePressurePolicy
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.vessel.PublicationPolicy
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.location.vessel.PhoneVesselMountState

/** Six short, domain-backed setup decisions. Optional equipment pages never
 * fabricate a connection or enable publication; they tell the user exactly
 * where the live control will remain available after setup. */
@Composable
fun FirstRunSetupScreen(
    initialBoatLengthMeters:Double,
    initialDraftMeters:Double?,
    nmeaConnection:NmeaConnectionState,
    mountState:PhoneVesselMountState,
    mountCalibrated:Boolean,
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
    val titles=listOf(tr("Vessel profile","船舶资料"),"NMEA Input",tr("Phone mount","手机安装"),tr("Data sources","数据来源"),"NMEA Output",tr("Alarm test","警报测试"))
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
                2->{SetupStatusCard(Icons.Default.Smartphone,tr("Phone mount","手机安装"),when{!mountCalibrated->tr("Not calibrated — handheld device navigation remains available","尚未校准；手持设备导航仍然可用");mountState==PhoneVesselMountState.VESSEL_MOUNTED->tr("Calibrated and fixed to the vessel","已校准并固定在船体");else->tr("Calibrated, currently handheld","已校准，当前为手持模式")},mountState==PhoneVesselMountState.VESSEL_MOUNTED);Text(tr("Only calibrate after the phone is physically secured. Vessel heading and motion publication remain suppressed while handheld.","只有手机牢固固定后才应校准。手持状态下，船艏向与船体运动发布会保持抑制。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                3->{SetupStatusCard(Icons.Default.Hub,tr("Automatic source routing","自动来源路由"),tr("Position: ${preferenceLabel(positionPreference)} · Heading: ${preferenceLabel(headingPreference)}","位置：${preferenceLabel(positionPreference)} · 船艏向：${preferenceLabel(headingPreference)}"),true);Text(tr("Auto preserves every candidate, prefers healthy boat instruments, and falls back only after freshness and recovery checks. You can inspect or pin a source in Data → Vessel.","自动模式会保留所有候选来源，优先选择健康的船载仪表，并只在新鲜度与恢复检查后回退。可在“数据 → 船舶”查看或固定来源。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                4->{
                    val active=listOf(output.effectivePositionPolicy,output.effectiveHeadingPolicy,output.effectiveMotionPolicy,output.effectivePressurePolicy,output.derivedWindPolicy).any{it!=PublicationPolicy.OFF}
                    SetupStatusCard(Icons.Default.Output,"NMEA Output",if(!output.transportConfigured)tr("Not configured — nothing will be transmitted","尚未配置，不会发送任何数据")else if(active)tr("A destination and at least one publication policy are configured","已配置发送目标及至少一项发布策略")else tr("Destination saved; every publication policy is Off","已保存发送目标；全部发布策略均为关闭"),output.transportConfigured&&active)
                    Text(tr("Output is optional and separate from input. Configure Boat Gateway and Off / Backup / Always policies later in Data → NMEA Output.","输出是可选功能，并与输入完全分离。可稍后在“数据 → NMEA 输出”配置船载网关和关闭 / 备用 / 始终策略。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else->{
                    SetupStatusCard(Icons.Default.NotificationsActive,tr("Audible safety check","可听见的安全检查"),if(alarmTesting)tr("Alarm test is sounding","警报测试正在响")else tr("Use the real global alarm path before relying on Anchor Watch","依赖 Anchor Watch 前，请通过真实全局警报链路完成试听"),alarmTesting)
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
