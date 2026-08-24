package com.yokuli.anchorwatch

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.location.GpsSourceSafety
import com.yokuli.anchorwatch.location.NmeaSourceSelectionPolicy
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.usesChinese
import com.yokuli.anchorwatch.map.FollowCameraMove
import com.yokuli.anchorwatch.map.MapCameraPolicy
import com.yokuli.anchorwatch.map.TrailVisibilityPolicy
import com.yokuli.anchorwatch.ui.theme.SafetyColors
import com.yokuli.anchorwatch.ui.theme.YokuliTheme
import java.text.DateFormat
import com.yokuli.anchorwatch.data.linz.LinzDepthPresentation
import com.yokuli.anchorwatch.data.linz.LinzDepthStatus

@Composable
internal fun WatchPanel(state: MainUiState, boatHeading:Double?,arm: () -> Unit, adjust:()->Unit,phoneHeading:(Boolean)->Unit,resetCentreAnalysis:()->Unit,conditionUpdate:(com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig)->Unit,resetWindBaseline:()->Unit,viewNearby:(List<Long>)->Unit,nearbyActions:SavedAnchorageCardActions,pause:()->Unit,resume:()->Unit,lift:()->Unit,openAnchorMap:()->Unit,recalculateCentre:()->Unit,reconnectNmea:()->Unit,openNmea:()->Unit,switchToSystemGps:()->Unit) {
    val fix = state.fix; val active = state.active; val now=android.os.SystemClock.elapsedRealtime()
    var showHealth by remember{mutableStateOf(false)};var showDepthDetails by remember{mutableStateOf(false)};var showConditions by remember{mutableStateOf(false)};var confirmAnalysisReset by remember{mutableStateOf(false)}
    val freshFix = when(state.settings.gpsDataSource){
        GpsDataSource.NMEA->NmeaSourceSelectionPolicy.isUsablePosition(state.connection,state.nmeaFix,state.nmeaConnectionStartedElapsed,now,state.settings.gpsLossSeconds*1_000L)
        GpsDataSource.SYSTEM->state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&now-it.receivedElapsedRealtime in 0L until state.settings.gpsLossSeconds*1_000L}==true&&!GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state)
        GpsDataSource.DEMO->state.systemFix?.let{it.valid&&it.positionProvider==com.yokuli.anchorwatch.domain.model.PositionProvider.ANDROID_GNSS&&(it.horizontalAccuracyMeters?:Double.POSITIVE_INFINITY)<=30.0&&now-it.receivedElapsedRealtime in 0L until state.settings.gpsLossSeconds*1_000L}==true
    }
    val centerReady=active?.centerStatus==AnchorCenterStatus.RESOLVED.name
    val learningDistance=if(fix!=null&&active!=null&&!centerReady)AnchorGeometry.distanceMeters(active.learningReferenceLatitude?:active.anchorLatitude,active.learningReferenceLongitude?:active.anchorLongitude,fix.latitude,fix.longitude)else null
    val distance = if (fix != null && active != null&&centerReady) AnchorGeometry.distanceMeters(active.anchorLatitude, active.anchorLongitude, fix.latitude, fix.longitude) else null
    // The one-shot map prompt may be dismissed for the current approach episode,
    // but Watch must retain a discoverable reference while the boat remains within
    // the same 1 NM policy used by the approach engine. Do not reintroduce the old
    // 250 m point-distance rule here: the saved object is an anchoring area.
    val nearby=if(active==null)state.anchorageApproach.nearbyClusters else emptyList()
    Surface(tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        if(active!=null&&!centerReady)LearningGeometryCard(active)
        if(active!=null&&active.anchorPositionMode==AnchorPositionMode.ESTIMATE.name){Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(tr("Phone heading evidence","手机船首向证据"),fontWeight=FontWeight.SemiBold);Text(tr("Uses the phone orientation sensors, not GPS course. Estimation continues after a safety centre is adopted.","使用手机方向传感器，不把 GPS 航迹向当船首向；采用安全中心后仍会继续估算。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(active.usePhoneHeading,{phoneHeading(it)},modifier=Modifier.testTag("active_phone_heading_switch"))};Text(if(active.usePhoneHeading)tr("ON · New stable samples are added. Turning it off later keeps all evidence already used.","已开启 · 继续加入新的稳定样本；之后关闭也会保留已经使用的证据。")else tr("OFF · Existing phone-heading evidence remains in the estimate; no new samples are added.","已关闭 · 已有手机船首向证据仍参与估算，只是不再加入新样本。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton({confirmAnalysisReset=true},Modifier.align(Alignment.End).testTag("reset_centre_analysis")){Text(tr("Start a new analysis epoch","开始新的估算阶段"))}}}}
        if(active==null){Button(arm,Modifier.fillMaxWidth(),enabled=state.settingsReady&&state.activeTrip==null){Text(tr("Set anchor","设置锚点"))};if(state.activeTrip!=null)Text(tr("End the current Trip Watch session before arming Anchor Watch.","结束当前航程监控后才能设置锚警。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(if(active.paused)resume else pause,Modifier.weight(1f)){Icon(if(active.paused)Icons.Default.PlayArrow else Icons.Default.Pause,null);Spacer(Modifier.width(6.dp));Text(if(active.paused)tr("Resume","继续") else tr("Pause","暂停"))};OutlinedButton(adjust,Modifier.weight(1f)){Icon(Icons.Default.Tune,null);Spacer(Modifier.width(6.dp));Text(tr("Adjust range","调整范围"))}};OutlinedButton({showConditions=true},Modifier.fillMaxWidth()){Icon(Icons.Default.Air,null);Spacer(Modifier.width(6.dp));Text(tr("Condition alerts","环境警戒"))};if(centerReady){OutlinedButton(recalculateCentre,Modifier.fillMaxWidth().testTag("recalculate_centre_from_track")){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(6.dp));Text(tr("Recalculate centre from track","根据轨迹重新计算中心"))};Text(tr("Runs once on the accepted track and never moves the safety centre without confirmation.","只对可信轨迹执行一次分析，未经确认绝不会移动安全中心。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton(openAnchorMap,Modifier.fillMaxWidth().testTag("open_anchor_google_maps")){Icon(Icons.Default.Place,null);Spacer(Modifier.width(6.dp));Text(tr("Open anchor in Google Maps","在 Google 地图中打开锚点"))}};TextButton(lift,Modifier.align(Alignment.End),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Default.Anchor,null);Spacer(Modifier.width(6.dp));Text(tr("Lift anchor","起锚"))}}
        val source=active?.positionSource?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
        val nmeaPositionFault=active!=null&&source==GpsDataSource.NMEA&&!NmeaSourceSelectionPolicy.isUsablePosition(
            state.connection,
            state.nmeaFix,
            state.nmeaConnectionStartedElapsed,
            now,
            state.settings.gpsLossSeconds*1_000L,
        )
        if(nmeaPositionFault)NmeaWatchRecoveryCard(
            paused=active?.paused==true,
            connection=state.connection,
            autoReconnect=state.settings.profile.autoReconnect,
            systemAvailable=!GpsSourceSafety.blocksSystemGps(state.settings.mockEnabled,state.mockGps.state),
            pause=pause,
            reconnect=reconnectNmea,
            openNmea=openNmea,
            switchToSystemGps=switchToSystemGps,
        )
        if(active?.paused==true&&active.positionSource!=GpsDataSource.DEMO.name){
            val missing=buildList{
                if(active.depthGuardEnabled&&!state.liveDepth.isFresh(now))add(localized(state.settings.appLanguage,"depth","水深"))
                if(active.windGuardEnabled&&state.liveWind.speed(now,active.windAllowApparentFallback)==null)add(localized(state.settings.appLanguage,"wind speed","风速"))
                if(active.windShiftEnabled&&state.liveWind.direction(now)==null)add(localized(state.settings.appLanguage,"true wind direction","真风向"))
            }
            if(missing.isNotEmpty())Surface(Modifier.fillMaxWidth().testTag("paused_condition_resume_warning"),color=MaterialTheme.colorScheme.tertiaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(tr("Environmental guards are not ready","环境警戒尚未就绪"),fontWeight=FontWeight.SemiBold)
                Text(tr("Missing ${missing.joinToString()}. Resume can restore the core GPS anchor watch, but these configured guards will enter an audible data-loss state after their grace period. Reconnect NMEA or disable them explicitly in Condition alerts.","缺少${missing.joinToString("、")}。继续后可以恢复核心 GPS 锚警，但这些已配置警戒会在宽限期后进入有声的数据丢失状态。请重连 NMEA，或在“环境警戒”中明确关闭。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onTertiaryContainer)
            }}
        }
        if(active!=null&&(active.depthGuardEnabled||active.windGuardEnabled||active.windShiftEnabled)){val conditions=state.conditions;val depthStatus=depthGuardLabel(conditions.depth.status);val windStatus=windGuardLabel(conditions.windSpeed.status);val shiftStatus=shiftGuardLabel(conditions.windShift.status);Text(buildString{if(active.depthGuardEnabled)append(tr("Depth","水深")+" ${conditions.depth.filteredDepthMeters?.let{"%.1f m".format(it)}?:"—"} · $depthStatus");if(active.windGuardEnabled){if(isNotEmpty())append("\n");append(tr("Wind","风速")+" ${conditions.windSpeed.filteredSpeedKnots?.let{"%.1f kn".format(it)}?:"—"} ${conditions.windSpeed.source?:""} · $windStatus")};if(active.windShiftEnabled){if(isNotEmpty())append("\n");append(tr("Shift","风向变化")+" ${conditions.windShift.shiftDegrees?.let{"${it.toInt()}°"}?:"—"} · $shiftStatus")}},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(nearby.isNotEmpty())NearbyAnchorageCard(
            nearby=nearby,
            savedAnchorages=state.savedAnchorages,
            actions=nearbyActions,
            openList=viewNearby,
            modifier=Modifier.testTag("watch_nearby_anchorage"),
        )
        val quality=gpsQualityMetric(state.settings.gpsDataSource,fix)
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric(tr("SOG","航速"), fix?.sogKnots?.let { "%.1f kn".format(it) } ?: "—"); Metric(tr("Heading","艏向"), boatHeading?.let { "${it.toInt()}°" } ?: "—"); Metric(quality.first,quality.second) }
        DepthSummary(state){showDepthDetails=true}
        OutlinedButton({showHealth=true},Modifier.fillMaxWidth().testTag("watch_health_button")){Icon(Icons.Default.HealthAndSafety,null);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(tr("Continuous watch health","持续监控健康"));Text(tr("Open every live safety check","查看全部实时安全检查"),style=MaterialTheme.typography.labelSmall)};Text(watchHealthStatus(state.watchSafety),color=watchHealthColor(state.watchSafety),style=MaterialTheme.typography.labelMedium)}
    } }
    if(showDepthDetails)DepthDetailsDialog(state){showDepthDetails=false}
    if(showConditions&&active!=null){
        val instrumentStreamAvailable=state.connection in setOf(NmeaConnectionState.CONNECTED,NmeaConnectionState.CONNECTED_NO_FIX,NmeaConnectionState.STALE)
        val demo=active.positionSource==GpsDataSource.DEMO.name
        val nowElapsed=android.os.SystemClock.elapsedRealtime()
        val sensors=com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability.Sensors(
            instrumentStream=instrumentStreamAvailable,
            freshDepth=state.liveDepth.isFresh(nowElapsed),
            freshWindSpeed=state.liveWind.speed(nowElapsed,active.windAllowApparentFallback)!=null,
            freshTrueWindDirection=state.liveWind.direction(nowElapsed)!=null,
            demoSession=demo,
        )
        ConditionSettingsDialog(active,sensors,{showConditions=false},{config->conditionUpdate(config);showConditions=false},resetWindBaseline)
    }
    if(showHealth)WatchHealthSheet(state.watchSafety){showHealth=false}
    if(confirmAnalysisReset)AlertDialog(onDismissRequest={confirmAnalysisReset=false},title={Text(tr("Restart centre analysis?","重新开始中心估算？"))},text={Text(tr("The adopted safety anchor, alarm range and complete track stay unchanged. Existing estimator evidence is archived and new evidence starts from now.","已采用的安全锚点、报警范围和完整轨迹都不会改变；旧估算证据会保留，从现在开始积累新的估算阶段。"))},confirmButton={Button({confirmAnalysisReset=false;resetCentreAnalysis()}){Text(tr("Restart analysis","重新估算"))}},dismissButton={TextButton({confirmAnalysisReset=false}){Text(tr("Cancel","取消"))}})
}

@Composable
private fun NmeaWatchRecoveryCard(
    paused:Boolean,
    connection:NmeaConnectionState,
    autoReconnect:Boolean,
    systemAvailable:Boolean,
    pause:()->Unit,
    reconnect:()->Unit,
    openNmea:()->Unit,
    switchToSystemGps:()->Unit,
){
    Surface(
        modifier=Modifier.fillMaxWidth().testTag("nmea_watch_recovery"),
        color=MaterialTheme.colorScheme.errorContainer,
        shape=MaterialTheme.shapes.medium,
    ){
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Icon(Icons.Default.LinkOff,null,tint=MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)){
                    Text(tr("NMEA position source needs action","NMEA 定位源需要处理"),fontWeight=FontWeight.SemiBold)
                    Text(connectionStateLabel(connection),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text(
                if(paused)tr(
                    "This session is safely paused. Reconnect or configure NMEA, or verify Phone GPS for the same session; then Resume.",
                    "本会话已安全暂停。请重连或重新配置 NMEA，也可以为同一会话验证手机 GPS；完成后再继续监控。",
                )else if(autoReconnect)tr(
                    "The watch never switches silently. Automatic retry is enabled for a broken transport; quiet, stale or no-fix connections may still need Reconnect now. A GPS-data-loss alarm follows on timeout. Pause to recover without losing the session.",
                    "锚警绝不会静默切源。连接真正断开时会自动重试；但连接无数据、定位过期或无定位时仍可能需要立即点“重连”。GPS 超时后会报警，暂停恢复不会丢失 session。",
                )else tr(
                    "The watch never switches silently, and automatic reconnect is off. Reconnect now, or Pause to configure another server or verify Phone GPS without losing this session.",
                    "锚警绝不会静默切源，且自动重连已关闭。请立即重连，或暂停后配置另一台服务器/验证手机 GPS；本次会话不会丢失。",
                ),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onErrorContainer,
            )
            if(!paused)Button(pause,Modifier.fillMaxWidth().testTag("pause_for_source_recovery")){Icon(Icons.Default.PauseCircle,null);Spacer(Modifier.width(6.dp));Text(tr("Pause safely to recover","安全暂停并恢复"))}
            else{
                Button(switchToSystemGps,Modifier.fillMaxWidth().testTag("recover_with_system_gps"),enabled=systemAvailable){Icon(Icons.Default.GpsFixed,null);Spacer(Modifier.width(6.dp));Text(tr("Use Phone GPS for this session","本会话改用手机 GPS"))}
                if(!systemAvailable)Text(tr("Phone GPS is unavailable while the global NMEA GPS proxy is active. Disable the proxy first.","全局 NMEA GPS 代理开启时，手机 GPS 不是独立来源；请先关闭代理。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(reconnect,Modifier.weight(1f)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(4.dp));Text(tr("Reconnect","重连"))}
                OutlinedButton(openNmea,Modifier.weight(1f)){Icon(Icons.Default.SettingsEthernet,null);Spacer(Modifier.width(4.dp));Text(tr("Server","服务器"))}
            }
        }
    }
}

@Composable
private fun LearningGeometryCard(session:com.yokuli.anchorwatch.data.database.AnchorSessionEntity){
    val hasEstimate=session.centerSampleCount>0
    val radialReady=hasEstimate&&session.candidateRadialObservable
    Surface(
        color=if(radialReady)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape=MaterialTheme.shapes.medium,
        modifier=Modifier.fillMaxWidth().testTag("anchor_centre_learning_geometry"),
    ){
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(
                when{
                    !hasEstimate->tr("Collecting accepted movement geometry","正在积累可信运动几何")
                    radialReady->tr("Rode-scale radial movement observed","已观测到锚链尺度的径向运动")
                    session.candidateDirectionEvidenceConsistent->tr("Direction is useful, but radial distance is not observable yet","方向证据可用，但径向距离尚不可观测")
                    else->tr("Anchor distance is not observable yet","锚点距离尚不可观测")
                },
                fontWeight=FontWeight.SemiBold,
            )
            if(hasEstimate&&!radialReady)Text(
                tr(
                    "A local loop or GPS jitter cannot define the anchor centre. The possible region remains deliberately conservative.",
                    "局部小圆或 GPS 抖动不能确定锚点中心；可能锚位范围会刻意保持宽松。",
                ),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LearningMetric(tr("Track span","轨迹跨度"),if(hasEstimate)"${session.candidateTrackDiameterMeters.toInt()} m" else "—")
            LearningMetric(tr("Horizontal rode constraint","水平锚链约束"),"~${session.expectedSwingRadiusMeters.toInt()} m")
            LearningMetric(tr("Fitted swing radius","拟合摆动半径"),session.candidateFittedRadiusMeters?.let{"${it.toInt()} m"}?:"—")
            LearningMetric(tr("GPS uncertainty","GPS 不确定性"),if(hasEstimate)"±${session.candidateGpsMarginMeters.toInt()} m" else "—")
            LearningMetric(tr("Angular evidence","角度证据"),if(hasEstimate)"${session.candidateAngularCoverageDegrees?.toInt()?:0}° · ${session.candidateAngularSectorCount} ${tr("sectors","扇区")}" else "—")
            LearningMetric(tr("Direction reversals","方向反转"),if(hasEstimate)session.candidateSwingReversalCount.toString() else "—")
            LearningMetric(tr("Direction evidence","方向证据"),if(hasEstimate&&session.candidateDirectionEvidenceConsistent)tr("Sufficient","充分")else tr("Developing","积累中"))
            LearningMetric(tr("Radial evidence","径向证据"),if(radialReady)tr("Sufficient","充分")else tr("Insufficient","不足"))
            LearningMetric(tr("Possible anchor region","可能锚位范围"),session.provisionalRadiusMeters?.let{"±${it.toInt()} m"}?:"—")
        }
    }
}

@Composable
private fun LearningMetric(label:String,value:String){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium)
    }
}

@Composable @OptIn(ExperimentalMaterial3Api::class)
internal fun WatchHealthSheet(report:com.yokuli.anchorwatch.domain.safety.WatchSafetyReport,dismiss:()->Unit){
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=sheetState,modifier=Modifier.testTag("watch_health_sheet")){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal=20.dp).padding(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Icon(Icons.Default.HealthAndSafety,null,tint=watchHealthColor(report),modifier=Modifier.size(28.dp))
                Column(Modifier.weight(1f)){Text(tr("Continuous watch health","持续监控健康"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold);Text(tr("Live checks used throughout the watch","监控期间持续更新的实时检查"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Text(watchHealthStatus(report),color=watchHealthColor(report),style=MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider()
            if(report.checks.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text(tr("Health checks are still loading.","健康检查仍在加载。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("watch_health_list"),contentPadding=PaddingValues(bottom=72.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(report.checks,key={it.id}){check->SafetyHealthRow(check)}
            }
            TextButton(dismiss,Modifier.align(Alignment.End)){Text(tr("Close","关闭"))}
        }
    }
}

@Composable private fun watchHealthStatus(report:com.yokuli.anchorwatch.domain.safety.WatchSafetyReport)=when{report.ready->tr("READY","就绪");report.canContinue->tr("WARNINGS","有警告");else->tr("ACTION REQUIRED","需要处理")}
@Composable private fun watchHealthColor(report:com.yokuli.anchorwatch.domain.safety.WatchSafetyReport)=when{report.ready->SafetyColors.Safe;report.canContinue->SafetyColors.Warning;else->SafetyColors.Alarm}

@Composable private fun ConditionSettingsDialog(session:AnchorSessionEntity,sensors:com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability.Sensors,dismiss:()->Unit,save:(com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig)->Unit,resetBaseline:()->Unit){
    var depthEnabled by remember(session.id){mutableStateOf(session.depthGuardEnabled)};var shallow by remember(session.id){mutableStateOf(session.shallowDepthAlarmMeters?.toString()?:"2.5")};var deepEnabled by remember(session.id){mutableStateOf(session.deepDepthAlarmMeters!=null)};var deep by remember(session.id){mutableStateOf(session.deepDepthAlarmMeters?.toString()?:"15")};var windEnabled by remember(session.id){mutableStateOf(session.windGuardEnabled)};var warning by remember(session.id){mutableStateOf(session.windWarningKnots?.toString()?:"25")};var alarm by remember(session.id){mutableStateOf(session.windAlarmKnots?.toString()?:"35")};var shiftEnabled by remember(session.id){mutableStateOf(session.windShiftEnabled)};var shift by remember(session.id){mutableStateOf(session.windShiftThresholdDegrees?.toString()?:"70")};var confirmReset by remember{mutableStateOf(false)}
    fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
    val shallowValue=shallow.toDoubleOrNull()
    val deepValue=deep.toDoubleOrNull()
    val warningValue=warning.toDoubleOrNull()
    val alarmValue=alarm.toDoubleOrNull()
    val shiftValue=shift.toDoubleOrNull()
    val valid=(!depthEnabled||(shallowValue!=null&&shallowValue>0&&(!deepEnabled||(deepValue!=null&&deepValue>=shallowValue+1))))&&(!windEnabled||(warningValue!=null&&alarmValue!=null&&alarmValue>=warningValue+3.0))&&(!shiftEnabled||(shiftValue!=null&&shiftValue in 15.0..180.0))
    val current=com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig(session.depthGuardEnabled,session.shallowDepthAlarmMeters,session.deepDepthAlarmMeters,session.windGuardEnabled,session.windWarningKnots,session.windAlarmKnots,session.windShiftEnabled,session.windShiftThresholdDegrees,session.windAllowApparentFallback)
    val proposed=com.yokuli.anchorwatch.domain.condition.ConditionGuardConfig(depthEnabled,shallowValue,deepValue.takeIf{deepEnabled},windEnabled,warningValue,alarmValue,shiftEnabled,shiftValue,session.windAllowApparentFallback).validated()
    val hasDiff=current.hasMeaningfulDiff(proposed)
    val allowed=com.yokuli.anchorwatch.domain.condition.ConditionGuardAvailability.canApply(current,proposed,sensors)
    val unavailableEnabled=(depthEnabled&&!sensors.depthReady)||(windEnabled&&!sensors.windSpeedReady)||(shiftEnabled&&!sensors.windShiftReady)
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Condition alerts","环境警戒"))},
        confirmButton={Button({save(proposed)},enabled=valid&&hasDiff&&allowed){Text(tr("Apply","应用"))}},
        dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},
        text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("These settings apply only to this active session. Resume requires fresh new sensor samples.","这些设置只作用于当前会话；恢复监控后必须重新取得新鲜传感器样本。"),style=MaterialTheme.typography.bodySmall)
            if(!sensors.depthReady||!sensors.windSpeedReady||!sensors.windShiftReady)Text(
                tr("Each switch unlocks only when its exact live NMEA instrument is fresh. Unrelated NMEA traffic never enables an alert.","只有对应的实时 NMEA 仪表数据新鲜时，该开关才会解锁；其他无关 NMEA 数据不会让警戒变为可用。"),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.error,
            )
            SettingSwitch(tr("Depth guard","水深警戒"),if(sensors.depthReady)tr("Fresh depth ready · shallow and optional deep boundary","新鲜水深已就绪 · 浅水及可选深水边界")else if(depthEnabled)tr("Data unavailable · you may switch this guard off","数据不可用 · 仍可关闭此警戒")else tr("Locked · fresh DPT/DBT depth required","已锁定 · 需要新鲜 DPT/DBT 水深"),depthEnabled,enabled=sensors.depthReady||depthEnabled){depthEnabled=it}
            if(depthEnabled){
                OutlinedTextField(shallow,{shallow=numeric(it)},enabled=sensors.depthReady,label={Text(tr("Shallow alarm *","浅水警报 *"))},suffix={Text("m")})
                SettingSwitch(tr("Deep alarm","深水警报"),if(!sensors.depthReady&&deepEnabled)tr("Data unavailable · you may switch this boundary off","数据不可用 · 仍可关闭此边界") else "",deepEnabled,enabled=sensors.depthReady||deepEnabled){deepEnabled=it}
                if(deepEnabled)OutlinedTextField(deep,{deep=numeric(it)},enabled=sensors.depthReady,label={Text(tr("Deep alarm *","深水警报 *"))},suffix={Text("m")})
            }
            SettingSwitch(tr("Wind speed","风速警戒"),if(sensors.windSpeedReady)tr("Fresh supported wind speed ready","支持的实时风速已就绪")else if(windEnabled)tr("Data unavailable · you may switch this guard off","数据不可用 · 仍可关闭此警戒")else tr("Locked · fresh supported NMEA wind required","已锁定 · 需要支持的实时 NMEA 风速"),windEnabled,enabled=sensors.windSpeedReady||windEnabled){windEnabled=it}
            if(windEnabled){
                OutlinedTextField(warning,{warning=numeric(it)},enabled=sensors.windSpeedReady,label={Text(tr("Warning *","提醒 *"))},suffix={Text("kn")})
                OutlinedTextField(alarm,{alarm=numeric(it)},enabled=sensors.windSpeedReady,label={Text(tr("Alarm *","警报 *"))},suffix={Text("kn")})
            }
            SettingSwitch(tr("Wind shift","风向突变"),if(sensors.windShiftReady)tr("Fresh true-wind direction ready","实时真风向已就绪")else if(shiftEnabled)tr("Data unavailable · you may switch this guard off","数据不可用 · 仍可关闭此警戒")else tr("Locked · MWD or coherent MWV-T + HDT required","已锁定 · 需要 MWD 或同步 MWV-T + HDT"),shiftEnabled,enabled=sensors.windShiftReady||shiftEnabled){shiftEnabled=it}
            if(shiftEnabled){
                OutlinedTextField(shift,{shift=numeric(it)},enabled=sensors.windShiftReady,label={Text(tr("Shift threshold *","变化阈值 *"))},suffix={Text("°")})
                Text(session.windBaselineDirectionDegrees?.let{tr("Baseline ${it.toInt()}° · fixed","基线 ${it.toInt()}° · 已固定")}?:tr("Learning baseline · at least 2 minutes of stable true wind","正在学习基线 · 至少 2 分钟稳定真风向"),style=MaterialTheme.typography.bodySmall)
                if(session.windBaselineDirectionDegrees!=null)OutlinedButton({confirmReset=true},enabled=sensors.windShiftReady){Text(tr("Reset baseline","重学基线"))}
            }
            if(unavailableEnabled)OutlinedButton({
                if(!sensors.depthReady){depthEnabled=false;deepEnabled=false}
                if(!sensors.windSpeedReady)windEnabled=false
                if(!sensors.windShiftReady)shiftEnabled=false
            },Modifier.fillMaxWidth().testTag("disable_unavailable_condition_alerts")){
                Icon(Icons.Default.NotificationsOff,null);Spacer(Modifier.width(6.dp));Text(tr("Disable unavailable alerts","关闭不可用的警戒"))
            }
            if(hasDiff&&!allowed)Text(tr("Reconnect the required instrument or use the explicit disable action above.","请重新连接所需仪表，或使用上方明确的关闭操作。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }},
    )
    if(confirmReset)AlertDialog(onDismissRequest={confirmReset=false},title={Text(tr("Reset wind baseline?","重新学习风向基线？"))},text={Text(tr("A new stable direction will be learned.","系统将重新学习一个稳定风向。"))},confirmButton={Button({resetBaseline();confirmReset=false}){Text(tr("Reset","重置"))}},dismissButton={TextButton({confirmReset=false}){Text(tr("Cancel","取消"))}})
}

@Composable private fun depthGuardLabel(value:com.yokuli.anchorwatch.domain.condition.DepthGuardStatus)=when(value){com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.OFF->tr("Off","关闭");com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.WAITING_FOR_DATA->tr("Waiting for data","等待数据");com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.MONITORING->tr("Monitoring","监控中");com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.SHALLOW_ALARM->tr("Shallow alarm","浅水警报");com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.DEEP_ALARM->tr("Deep alarm","深水警报");com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.DATA_UNAVAILABLE->tr("Data unavailable","数据不可用");com.yokuli.anchorwatch.domain.condition.DepthGuardStatus.PAUSED->tr("Paused","已暂停")}
@Composable private fun windGuardLabel(value:com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus)=when(value){com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.OFF->tr("Off","关闭");com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.WAITING_FOR_DATA->tr("Waiting for data","等待数据");com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.MONITORING->tr("Monitoring","监控中");com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.WARNING->tr("Warning","提醒");com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.ALARM->tr("Alarm","警报");com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.DATA_UNAVAILABLE->tr("Data unavailable","数据不可用");com.yokuli.anchorwatch.domain.condition.WindSpeedGuardStatus.PAUSED->tr("Paused","已暂停")}
@Composable private fun shiftGuardLabel(value:com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus)=when(value){com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.OFF->tr("Off","关闭");com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.WAITING_FOR_DIRECTION->tr("Waiting for direction","等待真风向");com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.LEARNING_BASELINE->tr("Learning baseline","学习基线中");com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.MONITORING->tr("Monitoring","监控中");com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.ALARM->tr("Alarm","警报");com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.DATA_UNAVAILABLE->tr("Data unavailable","数据不可用");com.yokuli.anchorwatch.domain.condition.WindShiftGuardStatus.PAUSED->tr("Paused","已暂停")}

internal fun gpsQualityMetric(source:GpsDataSource,fix:com.yokuli.anchorwatch.domain.model.NavigationFix?):Pair<String,String> = when(source){
    GpsDataSource.NMEA->"HDOP" to (fix?.hdop?.let{"%.1f".format(it)}?:"—")
    GpsDataSource.SYSTEM->"GPS" to (fix?.horizontalAccuracyMeters?.let{"±%.0f m".format(it)}?:"—")
    GpsDataSource.DEMO->"DEMO GPS" to (fix?.horizontalAccuracyMeters?.let{"±%.0f m".format(it)}?:"SIM")
}
@Composable private fun SafetyHealthRow(check:com.yokuli.anchorwatch.domain.safety.SafetyCheck){
    val color=when(check.status){com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.OK->SafetyColors.Safe;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING->SafetyColors.Warning;com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.BLOCKER->SafetyColors.Alarm}
    Surface(Modifier.fillMaxWidth().testTag("watch_health_${check.id}"),color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Icon(if(check.status==com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.OK)Icons.Default.CheckCircle else if(check.status==com.yokuli.anchorwatch.domain.safety.SafetyCheckStatus.WARNING)Icons.Default.Warning else Icons.Default.Cancel,null,Modifier.size(20.dp),tint=color)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(safetyTitle(check.id,check.title),fontWeight=FontWeight.Medium)
                Text(localizeSafetyText(check.detail),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                check.risk?.let{Text(localizeSafetyText(it),style=MaterialTheme.typography.bodySmall,color=color)}
            }
        }
    }
}
@Composable internal fun Metric(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyLarge) } }

@Composable private fun DepthSummary(state:MainUiState,open:()->Unit){
    val depth=state.depthUi;val linz=LinzDepthPresentation.text(depth.linz,LocalAppLanguage.current.usesChinese())
    Surface(Modifier.fillMaxWidth().clickable(onClick=open),color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(tr("DEPTH","水深"),style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f));Icon(Icons.Default.ChevronRight,tr("Depth details","水深详情"))}
        DepthLine(tr("Sonar depth","声呐水深"),depth.liveDepthMeters?.let{"%.1f m".format(it)}?:"—",depth.liveDepthAgeMillis?.let{tr("${depthHoldLabel(depth.liveDepthHoldState)} · %.1f s since update".format(it/1000.0),"${depthHoldLabel(depth.liveDepthHoldState)} · 距上次更新 %.1f 秒".format(it/1000.0))})
        if(state.settings.showLinzDepthReference)DepthLine(tr("LINZ chart reference","LINZ 海图参考"),linz.primary,linz.secondary)
        if(state.settings.showPersonalMapReference)DepthLine(tr("Personal sonar map","个人声呐地图"),depth.personalMapDepthMeters?.let{"%.1f m".format(it)}?:"—",depth.personalMapMeasured?.let{if(it)tr("Measured · ${depth.personalMapSamples?:0} samples · ±${"%.1f".format(depth.personalMapUncertaintyMeters?:0.0)} m","实测 · ${depth.personalMapSamples?:0} 个样本 · ±${"%.1f".format(depth.personalMapUncertaintyMeters?:0.0)} 米")else tr("Interpolated · ${depth.personalMapSamples?:0} nearby samples","插值 · ${depth.personalMapSamples?:0} 个附近样本")})
    }}
}
@Composable private fun DepthLine(label:String,value:String,detail:String?){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){Column(Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);detail?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}};Text(value,fontWeight=FontWeight.SemiBold)}}

@Composable private fun DepthDetailsDialog(state:MainUiState,dismiss:()->Unit){
    val depth=state.depthUi;val linz=depth.linz;val cacheAge=linz.queriedAt.takeIf{it>0}?.let{((System.currentTimeMillis()-it).coerceAtLeast(0)/60_000)}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Depth details","水深详情"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(tr("SONAR DEPTH","声呐水深"),style=MaterialTheme.typography.labelLarge);Text(depth.liveDepthMeters?.let{"${"%.2f".format(it)} m · ${depthReferenceLabel(depth.liveDepthReference)} · ${depthHoldLabel(depth.liveDepthHoldState)}"}?:tr("No valid DPT/DBT depth has been received","尚未收到有效的 DPT/DBT 水深"));Text(tr("${state.sonarRecorder.lastSentenceType?:"—"} · raw ${state.sonarRecorder.lastRawDepthMeters?.let{"%.2f m".format(it)}?:"—"} · age ${depth.liveDepthAgeMillis?.let{"${it} ms"}?:"—"}","${state.sonarRecorder.lastSentenceType?:"—"} · 原始值 ${state.sonarRecorder.lastRawDepthMeters?.let{"%.2f 米".format(it)}?:"—"} · 数据年龄 ${depth.liveDepthAgeMillis?.let{"${it} 毫秒"}?:"—"}"),style=MaterialTheme.typography.bodySmall);Text(tr("A held or expired value remains visible with its original age. Depth Guard still requires a fresh real NMEA sentence.","保持值或过期值仍会显示并保留原始年龄；水深警戒仍然只接受新鲜的真实 NMEA 语句。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(tr("NMEA offset ${state.sonarRecorder.lastNmeaOffsetMeters?.let{"${if(it>=0)"+" else ""}${"%.2f".format(it)} m"}?:"—"} · user offset ${state.sonarRecorder.lastUserOffsetMeters?.let{"${if(it>=0)"+" else ""}${"%.2f".format(it)} m"}?:"—"} · final ${state.sonarRecorder.lastMeasuredDepthMeters?.let{"%.2f m".format(it)}?:"—"}","NMEA offset ${state.sonarRecorder.lastNmeaOffsetMeters?.let{"${if(it>=0)"+" else ""}${"%.2f".format(it)} 米"}?:"—"} · 用户 offset ${state.sonarRecorder.lastUserOffsetMeters?.let{"${if(it>=0)"+" else ""}${"%.2f".format(it)} 米"}?:"—"} · 最终水深 ${state.sonarRecorder.lastMeasuredDepthMeters?.let{"%.2f 米".format(it)}?:"—"}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);depth.correctedDepthMeters?.let{Text(tr("Chart-datum corrected ${"%.2f".format(it)} m","海图基准修正值 ${"%.2f".format(it)} 米"),style=MaterialTheme.typography.bodySmall)}
        state.sonarRecorder.lastTideCorrection?.let{tide->HorizontalDivider();Text(tr("PREDICTED TIDE","预测潮位"),style=MaterialTheme.typography.labelLarge);Text(tide.tideHeightMetersAboveChartDatum?.let{tr("${"%.2f".format(it)} m above chart datum","高于海图基准 ${"%.2f".format(it)} 米")}?:tr("Prediction unavailable · raw depth is still recorded","预测不可用 · 仍会记录原始水深"));Text("${tide.stationName?:"—"} · ${tideStatusLabel(tide.status)} · ${tide.method?:"—"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(tr("LINZ prediction, not observed sea level. Weather and atmospheric pressure can change the actual level.","LINZ 预测值，并非实测海平面；天气和气压会改变实际水位。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(state.settings.showLinzDepthReference){HorizontalDivider();Text("LINZ",style=MaterialTheme.typography.labelLarge);Text(LinzDepthPresentation.text(linz,LocalAppLanguage.current.usesChinese()).primary);listOfNotNull(linz.depthAreaMinMeters?.let{tr("Depth area ${it}–${linz.depthAreaMaxMeters} m","水深区域 ${it}–${linz.depthAreaMaxMeters} 米")},linz.nearestSoundingDepthMeters?.let{tr("Nearest sounding ${it} m · ${linz.nearestSoundingDistanceMeters?.toInt()} m away","最近测深点 ${it} 米 · 距离 ${linz.nearestSoundingDistanceMeters?.toInt()} 米")},linz.nearestContourDepthMeters?.let{tr("Nearest contour ${it} m · ${linz.nearestContourDistanceMeters?.toInt()} m away","最近等深线 ${it} 米 · 距离 ${linz.nearestContourDistanceMeters?.toInt()} 米")},cacheAge?.let{tr("Cache age ${it} min${if(linz.cached)" · cached" else ""}","缓存时间 ${it} 分钟${if(linz.cached)" · 已缓存" else ""}")}).forEach{Text(it,style=MaterialTheme.typography.bodySmall)}}
        if(state.settings.showPersonalMapReference){HorizontalDivider();Text(tr("PERSONAL SONAR MAP","个人声呐地图"),style=MaterialTheme.typography.labelLarge);Text(depth.personalMapDepthMeters?.let{tr("${"%.2f".format(it)} m · ${if(depth.personalMapMeasured==true)"measured" else "interpolated"} · ${depth.personalMapSamples?:0} samples · ±${"%.2f".format(depth.personalMapUncertaintyMeters?:0.0)} m","${"%.2f".format(it)} 米 · ${if(depth.personalMapMeasured==true)"实测" else "插值"} · ${depth.personalMapSamples?:0} 个样本 · ±${"%.2f".format(depth.personalMapUncertaintyMeters?:0.0)} 米")}?:tr("No personal sonar cell here","当前位置没有个人声呐网格"));depth.personalSurveyName?.let{Text("$it · ${depth.personalSurveyStartedAt?.let{date->DateFormat.getDateInstance().format(java.util.Date(date))}}",style=MaterialTheme.typography.bodySmall)}}
        if(state.settings.showLinzDepthReference&&depth.correctedDepthMeters==null)Text(tr("Live sonar and LINZ may use different vertical datums; no numerical difference is calculated.","实时声呐与 LINZ 海图可能使用不同的垂直基准，因此不会计算两者差值。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(dismiss){Text(tr("Close","关闭"))}})
}

@Composable private fun depthReferenceLabel(value:com.yokuli.anchorwatch.domain.sonar.DepthReference?):String=when(value){com.yokuli.anchorwatch.domain.sonar.DepthReference.BELOW_TRANSDUCER->tr("Below transducer","探头以下");com.yokuli.anchorwatch.domain.sonar.DepthReference.BELOW_SURFACE->tr("Below surface","水面以下");com.yokuli.anchorwatch.domain.sonar.DepthReference.BELOW_KEEL->tr("Below keel","龙骨以下");com.yokuli.anchorwatch.domain.sonar.DepthReference.UNKNOWN,null->tr("Unknown reference","基准未知")}
@Composable private fun depthHoldLabel(value:com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState):String=when(value){com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.NO_DEPTH->tr("No value","无数据");com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.LIVE->tr("Live","实时");com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.HELD->tr("Held","保持值");com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.WARNING->tr("Held warning","保持值警告");com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.EXPIRED_TIME->tr("Expired by age","已因时间过期");com.yokuli.anchorwatch.domain.sonar.SonarDepthHoldState.EXPIRED_DISTANCE->tr("Expired by distance","已因航程过期")}
@Composable private fun tideStatusLabel(value:com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus):String=when(value){com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.AVAILABLE->tr("Available","可用");com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.INTERVAL_OUTSIDE_GUIDANCE->tr("Outside recommended interval","超出推荐时间间隔");com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.NO_STATION->tr("No station","无潮汐站");com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.DATA_MISSING->tr("Data missing","数据缺失");com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.OUTSIDE_DATA_RANGE->tr("Outside data range","超出数据范围");com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.OFFLINE_NO_CACHE->tr("Offline without cache","离线且无缓存");com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus.PARSE_ERROR->tr("Parse error","解析错误")}

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
