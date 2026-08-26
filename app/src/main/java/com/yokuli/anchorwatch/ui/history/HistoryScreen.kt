package com.yokuli.anchorwatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.anchorage.SeabedType
import com.yokuli.anchorwatch.data.anchorage.AnchorageCoordinateSource
import com.yokuli.anchorwatch.data.anchorage.AnchorageSavePositionPolicy
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.report.TripReport
import com.yokuli.anchorwatch.domain.report.AnchorReport
import com.yokuli.anchorwatch.data.trip.TripReplayData
import com.yokuli.anchorwatch.localization.usesChinese
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HistoryPage(state:MainUiState,vm:MainViewModel,fixedTab:Int?=null){
    var selectedTab by remember(fixedTab){mutableStateOf(fixedTab?:0)}
    val tab=fixedTab?:selectedTab
    var expanded by remember{mutableStateOf<Long?>(null)}
    var expandedTrip by remember{mutableStateOf<Long?>(null)}
    var pendingDelete by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    var pendingTripDelete by remember{mutableStateOf<TripSessionEntity?>(null)}
    var reportTrip by remember{mutableStateOf<TripSessionEntity?>(null)}
    var reportAnchor by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    var pendingAiTrip by remember{mutableStateOf<TripSessionEntity?>(null)}
    var pendingAiAnchor by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    var replayTrip by remember{mutableStateOf<TripSessionEntity?>(null)}
    var saveSession by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    var editingAnchorage by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var pendingAnchorageDelete by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var detailAnchorage by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var showQrScanner by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(fixedTab==null)item{PageHeader(tr("History","历史"),tr("Anchor sessions, saved anchorages and recorded trips stay separate and local.","锚泊会话、收藏锚地和航程记录彼此独立，并保存在本机。"));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(tab==0,{selectedTab=0},label={Text(tr("Anchors","锚泊"))});FilterChip(tab==1,{selectedTab=1},label={Text(tr("Anchorages","收藏锚地"))});FilterChip(tab==2,{selectedTab=2},label={Text(tr("Trips","航程"))})}}
        if(tab==1)item{OutlinedButton({showQrScanner=true},Modifier.fillMaxWidth().testTag("scan_anchorage_qr")){Icon(Icons.Default.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text(tr("Scan anchorage QR","扫描锚地二维码"))}}
        if(tab==0&&state.sessions.isEmpty())item{Text(tr("No anchor sessions recorded.","还没有锚泊记录。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(tab==0)items(state.sessions,key={"session-${it.id}"}){session->
            val events=state.eventsBySession[session.id].orEmpty()
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(DateFormat.getDateTimeInstance().format(Date(session.startedAt)),fontWeight=FontWeight.Medium)
                            Text("${when{!session.active->tr("LIFTED","已起锚");session.paused->tr("PAUSED","已暂停");else->tr("ACTIVE","监控中")}} · ${historySourceLabel(session.positionSource)} · ${historyCenterLabel(session.centerSource)}",style=MaterialTheme.typography.bodySmall)
                            Text("${"%.5f".format(session.anchorLatitude)}, ${"%.5f".format(session.anchorLongitude)} · ${session.alarmRadiusMeters.toInt()} m · ${tr("max","最大")} ${session.maxDistanceMeters.toInt()} m",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(!session.active)IconButton({pendingDelete=session}){Icon(Icons.Default.DeleteForever,tr("Delete session","删除会话"),tint=MaterialTheme.colorScheme.error)}
                    }
                    Text(tr("Max radius ${session.maxDistanceMeters.toInt()} m · ${session.alarmCount+session.depthAlarmCount+session.windAlarmCount} alarms","最大半径 ${session.maxDistanceMeters.toInt()} 米 · ${session.alarmCount+session.depthAlarmCount+session.windAlarmCount} 次报警"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({val next=if(expanded==session.id)null else session.id;expanded=next;if(next!=null)vm.loadHistoryEvents(next)},Modifier.fillMaxWidth()){Text(if(expanded==session.id)tr("Close details","收起详情")else tr("Open","打开"))}
                    if(expanded==session.id){
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({reportAnchor=session},Modifier.weight(1f)){Text(tr("Report","报告"))};OutlinedButton({vm.exportCsv(session)},Modifier.weight(1f)){Text("CSV")};OutlinedButton({vm.exportGpx(session)},Modifier.weight(1f)){Text("GPX")}}
                        if(!session.active)OutlinedButton({pendingAiAnchor=session},Modifier.fillMaxWidth()){Text(tr("Export data for AI","导出源数据给 AI"))}
                        if(session.anchorageVisitId==null)OutlinedButton({saveSession=session},Modifier.fillMaxWidth()){Text(if(session.centerStatus==AnchorCenterStatus.RESOLVED.name)tr("☆ Save anchorage","☆ 收藏锚地")else tr("☆ Save approximate reference","☆ 收藏估算参考位置"))}else OutlinedButton({},Modifier.fillMaxWidth(),enabled=false){Text(tr("Already saved to anchorage library","已保存到锚地库"))}
                        if(!session.active||session.centerStatus==AnchorCenterStatus.RESOLVED.name)OutlinedButton({vm.recalculateCentreFromTrack(session)},Modifier.fillMaxWidth().testTag("analyze_centre_from_track_${session.id}")){Text(if(session.active)tr("Recalculate centre from track","根据轨迹重新计算中心")else tr("Analyze centre from track","按轨迹分析中心"))}
                        Text(tr("Event timeline","事件时间线"),style=MaterialTheme.typography.labelLarge)
                        if(events.isEmpty())Text(tr("No recorded events.","没有事件记录。"),style=MaterialTheme.typography.bodySmall)
                        else events.sortedByDescending{it.timestamp}.take(30).forEach{event->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Column{Text(historyEventLabel(event.type),style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium);if(event.detail.isNotBlank())Text(historyEventDetail(event.detail),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
            }
        }
        if(tab==1&&state.savedAnchorages.isEmpty())item{Text(tr("No saved anchorages yet. Save a confirmed anchor or a clearly labelled approximate reference from session history.","还没有收藏锚地。可从会话历史收藏已确认锚点，或带明确标记的估算参考位置。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(tab==1)items(state.savedAnchorages,key={"anchorage-${it.id}"}){saved->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(saved.name,fontWeight=FontWeight.SemiBold);Text("${"%.5f".format(saved.latitude)}, ${"%.5f".format(saved.longitude)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(saved.coordinateSource!=AnchorageCoordinateSource.CONFIRMED_ANCHOR.name)Text(anchorageCoordinateQuality(saved),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)};Text(saved.rating?.let{"★".repeat(it)}?:"—",color=MaterialTheme.colorScheme.primary)}
                Text(listOfNotNull(saved.preferredAlarmRadiusMeters?.let{tr("${it.toInt()} m radius","${it.toInt()} 米范围")},saved.typicalWaterDepthMeters?.let{tr("${"%.1f".format(it)} m depth","${"%.1f".format(it)} 米水深")},saved.typicalRodeLengthMeters?.let{tr("${it.toInt()} m rode","${it.toInt()} 米锚链")}).joinToString(" · ").ifBlank{tr("No setup values saved","未保存设置参数")},style=MaterialTheme.typography.bodySmall)
                if(saved.notes.isNotBlank())Text(saved.notes.replace('\n',' ').take(120),maxLines=2,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({detailAnchorage=saved},Modifier.weight(1f)){Text(tr("View details","查看详情"))};Button({vm.approachSavedAnchorage(saved.id)},Modifier.weight(1f).testTag("saved_anchorage_approach_${saved.id}"),enabled=state.active==null){Text(tr("Approach","接近指引"))}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton({editingAnchorage=saved}){Text(tr("Edit","编辑"))};IconButton({pendingAnchorageDelete=saved}){Icon(Icons.Default.DeleteForever,tr("Delete anchorage","删除锚地"),tint=MaterialTheme.colorScheme.error)}}
            }}
        }
        if(tab==2&&state.tripSessions.isEmpty())item{Text(tr("No trips recorded. Open Watch → Trip Watch to view live instruments or start a recording.","还没有航程记录。请前往“监控 → 航程监控”查看实时仪表或开始记录。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(tab==2)items(state.tripSessions,key={"trip-${it.id}"}){trip->
            Card(Modifier.fillMaxWidth().testTag("trip_history_${trip.id}")){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(trip.name,fontWeight=FontWeight.SemiBold)
                            Text("${when{!trip.active->tr("COMPLETED","已完成");trip.paused->tr("PAUSED","已暂停");else->tr("RECORDING","记录中")}} · ${DateFormat.getDateTimeInstance().format(Date(trip.startedAt))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(!trip.active)IconButton({pendingTripDelete=trip}){Icon(Icons.Default.DeleteForever,tr("Delete trip","删除航程"),tint=MaterialTheme.colorScheme.error)}
                    }
                    Text(tr("${"%.2f".format(trip.distanceMeters/1000.0)} km · ${trip.sampleCount} samples · ${trip.waypointCount} waypoints","${"%.2f".format(trip.distanceMeters/1000.0)} 公里 · ${trip.sampleCount} 个样本 · ${trip.waypointCount} 个航点"),style=MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(trip.maxSogKnots?.let{tr("Max SOG ${"%.1f".format(it)} kn","最大对地航速 ${"%.1f".format(it)} 节")},trip.maxAbsHeelDegrees?.let{tr("Max heel ${"%.1f".format(it)}°","最大横倾 ${"%.1f".format(it)}°")},trip.minDepthMeters?.let{tr("Min depth ${"%.1f".format(it)} m","最小水深 ${"%.1f".format(it)} 米")}).joinToString(" · ").ifBlank{tr("No summary metrics yet","暂无汇总指标")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!trip.active){Button({expandedTrip=if(expandedTrip==trip.id)null else trip.id},Modifier.fillMaxWidth()){Text(if(expandedTrip==trip.id)tr("Close details","收起详情")else tr("Open","打开"))};if(expandedTrip==trip.id){HorizontalDivider();TripHistoryRoutePreview(trip,vm);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({reportTrip=trip},Modifier.weight(1f)){Text(tr("Report","报告"))};OutlinedButton({replayTrip=trip},Modifier.weight(1f)){Text(tr("Replay","回放"))}};Text(tr("Export","导出"),style=MaterialTheme.typography.labelLarge);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedButton({vm.exportTripCsv(trip)},Modifier.weight(1f)){Text("CSV")};OutlinedButton({vm.exportTripGpx(trip)},Modifier.weight(1f)){Text("GPX")};OutlinedButton({vm.exportTripKml(trip)},Modifier.weight(1f)){Text("KML")};OutlinedButton({vm.exportTripKmz(trip)},Modifier.weight(1f)){Text("KMZ")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton({vm.shareTripReportSnapshot(trip)},Modifier.weight(1f)){Text(tr("Image","图片"))};TextButton({vm.exportTripEvents(trip)},Modifier.weight(1f)){Text(tr("Events","事件"))};TextButton({vm.exportTripWaypoints(trip)},Modifier.weight(1f)){Text(tr("Waypoints","航点"))}};TextButton({pendingAiTrip=trip},Modifier.fillMaxWidth()){Text(tr("AI source ZIP","AI 源数据 ZIP"))}}}
                    if(trip.active)OutlinedButton({vm.page(1)},Modifier.fillMaxWidth()){Text(tr("Open Sail Live","打开实时航行"))}
                }
            }
        }
    }
    pendingDelete?.let{session->AlertDialog(onDismissRequest={pendingDelete=null},title={Text(tr("Delete this anchor history?","删除这条锚泊历史？"))},text={Text(tr("The session, its complete track and event timeline will be permanently deleted.","该会话、完整轨迹和事件时间线都会被永久删除。"))},confirmButton={Button({vm.deleteHistorySession(session);if(expanded==session.id)expanded=null;pendingDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({pendingDelete=null}){Text(tr("Cancel","取消"))}})}
    pendingTripDelete?.let{trip->AlertDialog(onDismissRequest={pendingTripDelete=null},title={Text(tr("Delete this trip?","删除这次航程？"))},text={Text(tr("Its samples, events and waypoints will be permanently deleted. Exported files are not removed.","航程样本、事件和航点会被永久删除；已导出的文件不会被删除。"))},confirmButton={Button({vm.deleteTrip(trip);pendingTripDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({pendingTripDelete=null}){Text(tr("Cancel","取消"))}})}
    reportTrip?.let{trip->TripReportDialog(trip,vm){reportTrip=null}}
    reportAnchor?.let{session->AnchorReportDialog(session,vm){reportAnchor=null}}
    replayTrip?.let{trip->TripReplayDialog(trip,vm){replayTrip=null}}
    pendingAiTrip?.let{trip->AiExportPrivacyDialog({pendingAiTrip=null}){pendingAiTrip=null;vm.exportTripAiSource(trip)}}
    pendingAiAnchor?.let{session->AiExportPrivacyDialog({pendingAiAnchor=null}){pendingAiAnchor=null;vm.exportAnchorAiSource(session)}}
    saveSession?.let{session->com.yokuli.anchorwatch.ui.anchor.anchorages.AnchorageSaveFlow(session=session,dismiss={saveSession=null},complete={saveSession=null})}
    editingAnchorage?.let{value->AnchorageEditor(value,{editingAnchorage=null}){vm.saveAnchorage(it);editingAnchorage=null}}
    detailAnchorage?.let{saved->AnchorageDetailDialog(saved,{detailAnchorage=null},{vm.openAnchorageInGoogleMaps(saved)},{vm.shareAnchorageQr(saved)},{detailAnchorage=null;vm.approachSavedAnchorage(saved.id)},approachEnabled=state.active==null)}
    pendingAnchorageDelete?.let{value->AlertDialog(onDismissRequest={pendingAnchorageDelete=null},title={Text(tr("Delete saved anchorage?","删除收藏锚地？"))},text={Text(tr("This removes only the saved place. Anchor session history is unchanged.","只会删除收藏地点，不影响锚泊会话历史。"))},confirmButton={Button({vm.deleteAnchorage(value.id);pendingAnchorageDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({pendingAnchorageDelete=null}){Text(tr("Cancel","取消"))}})}
    state.anchorageDuplicateExisting?.let{existing->AlertDialog(
        onDismissRequest=vm::dismissAnchorageDuplicate,
        title={Text(tr("Anchorage already saved","锚地已经收藏"))},
        text={Text(tr("“${existing.name}” is within 75 m. A duplicate was not created.","“${existing.name}”距离不足 75 米，因此没有重复收藏。"))},
        confirmButton={Button({detailAnchorage=existing;vm.dismissAnchorageDuplicate()}){Text(tr("View existing","查看已有记录"))}},
        dismissButton={TextButton(vm::dismissAnchorageDuplicate){Text(tr("Close","关闭"))}},
    )}
    if(showQrScanner)Dialog(onDismissRequest={showQrScanner=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){AnchorageQrScannerScreen(onClose={showQrScanner=false},onSave=vm::saveAnchorage)}
    state.anchorageOperationError?.let{message->AlertDialog(
        onDismissRequest=vm::dismissAnchorageOperationError,
        title={Text(tr("Anchorage library error","收藏锚地操作失败"))},
        text={Text(if(message.startsWith("Could not delete"))tr(message,"无法删除这个收藏锚地，原记录仍然保留。")else tr(message,"无法保存这个收藏锚地，数据没有发生变化。"))},
        confirmButton={TextButton(vm::dismissAnchorageOperationError){Text(tr("OK","知道了"))}},
    )}
    AnchorCentreRecalculationDialog(state.centreRecalculation,vm)
}

@Composable
private fun AiExportPrivacyDialog(dismiss:()->Unit,confirm:()->Unit){
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Export private session data?","导出私密会话数据？"))},
        text={Text(tr("This export contains precise locations, timestamps, sensor data, notes and waypoints from this session. Boat Watch does not upload it automatically. Continue only if you intend to share this data.","导出文件包含本次会话的精确位置、时间、传感器数据、备注和航点。Boat Watch 不会自动上传；仅在你确实准备分享时继续。"))},
        confirmButton={Button(confirm){Text(tr("Create local ZIP","生成本地 ZIP"))}},
        dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},
    )
}

@Composable
private fun AnchorReportDialog(session:AnchorSessionEntity,vm:MainViewModel,dismiss:()->Unit){
    val report by produceState<AnchorReport?>(initialValue=null,session.id){value=vm.anchorReport(session.id)}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Anchor report","锚泊报告"))},confirmButton={TextButton(dismiss){Text(tr("Close","关闭"))}},text={val value=report;if(value==null)Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}else Column(Modifier.heightIn(max=590.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(DateFormat.getDateTimeInstance().format(Date(value.session.startedAt)),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Text("${tr("Quality","质量")} ${reportQualityLabel(value.quality)} · GPS ${value.positionCoveragePercent.toInt()}% · ${tr("swing sectors","摆动扇区")} ${value.circularCoveragePercent.toInt()}%",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider();Text(tr("Maximum distance ${value.maximumDistanceMeters.toInt()} m · near boundary ${value.timeNearAlarmPercent.toInt()}%","最大距离 ${value.maximumDistanceMeters.toInt()} 米 · 接近边界 ${value.timeNearAlarmPercent.toInt()}%"));Text(tr("GPS gaps ${value.gpsGapCount} · NMEA interruptions ${value.nmeaDisconnectCount} · centre changes ${value.centreChangeCount}","GPS 缺口 ${value.gpsGapCount} · NMEA 中断 ${value.nmeaDisconnectCount} · 锚位变更 ${value.centreChangeCount}"));Text(tr("Depth ${value.depthCoveragePercent.toInt()}% · wind ${value.windCoveragePercent.toInt()}% · unavailable ${value.depthUnavailableCount} / ${value.windUnavailableCount}","水深覆盖 ${value.depthCoveragePercent.toInt()}% · 风覆盖 ${value.windCoveragePercent.toInt()}% · 不可用 ${value.depthUnavailableCount} / ${value.windUnavailableCount}"));Text(tr("Minimum depth ${value.minimumDepthMeters?.let{"%.1f m".format(it)}?:"—"} · maximum wind ${value.maximumWindKnots?.let{"%.1f kn".format(it)}?:"—"}","最小水深 ${value.minimumDepthMeters?.let{"%.1f 米".format(it)}?:"—"} · 最大风速 ${value.maximumWindKnots?.let{"%.1f 节".format(it)}?:"—"}"));Text(tr("Heel mean / P95 / max ${value.meanAbsoluteHeelDegrees?.let{"%.1f".format(it)}?:"—"} / ${value.p95AbsoluteHeelDegrees?.let{"%.1f".format(it)}?:"—"} / ${value.maximumHeelDegrees?.let{"%.1f°".format(it)}?:"—"}","横倾平均 / P95 / 最大 ${value.meanAbsoluteHeelDegrees?.let{"%.1f".format(it)}?:"—"} / ${value.p95AbsoluteHeelDegrees?.let{"%.1f".format(it)}?:"—"} / ${value.maximumHeelDegrees?.let{"%.1f°".format(it)}?:"—"}"));Text(tr("Motion mean / P95 / max ${value.meanMotionScore?.let{"%.0f".format(it)}?:"—"} / ${value.p95MotionScore?.let{"%.0f".format(it)}?:"—"} / ${value.maximumMotionScore?.let{"%.0f".format(it)}?:"—"} · roll ${value.medianRollPeriodSeconds?.let{"%.1f s".format(it)}?:"—"}","运动平均 / P95 / 最大 ${value.meanMotionScore?.let{"%.0f".format(it)}?:"—"} / ${value.p95MotionScore?.let{"%.0f".format(it)}?:"—"} / ${value.maximumMotionScore?.let{"%.0f".format(it)}?:"—"} · 横摇周期 ${value.medianRollPeriodSeconds?.let{"%.1f 秒".format(it)}?:"—"}"));Text(tr("Pressure start / end / change ${value.pressureStartHpa?.let{"%.1f".format(it)}?:"—"} / ${value.pressureEndHpa?.let{"%.1f".format(it)}?:"—"} / ${value.pressureChangeHpa?.let{"%+.1f hPa".format(it)}?:"—"}","气压起始 / 结束 / 变化 ${value.pressureStartHpa?.let{"%.1f".format(it)}?:"—"} / ${value.pressureEndHpa?.let{"%.1f".format(it)}?:"—"} / ${value.pressureChangeHpa?.let{"%+.1f hPa".format(it)}?:"—"}"));Text("${value.eventCount} events · ${value.reportEngineVersion}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(value.findings.isNotEmpty()){HorizontalDivider();Text(tr("Findings","观察结论"),fontWeight=FontWeight.SemiBold);value.findings.forEach{Text("${it.severity} · ${it.title}\n${it.detail}",style=MaterialTheme.typography.bodySmall)}}
    }})
}

@Composable
private fun TripReportDialog(session:TripSessionEntity,vm:MainViewModel,dismiss:()->Unit){
    val report by produceState<TripReport?>(initialValue=null,session.id){value=vm.tripReport(session.id)}
    val replay by produceState<TripReplayData?>(initialValue=null,session.id){value=vm.tripReplay(session.id)}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("Trip report","航程报告"))},confirmButton={TextButton(dismiss){Text(tr("Close","关闭"))}},text={
        val value=report
        if(value==null)Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        else Column(Modifier.heightIn(max=590.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(value.session.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("${tr("Quality","质量")} ${reportQualityLabel(value.quality)} · ${tr("position","位置")} ${value.positionCoveragePercent.toInt()}% · ${tr("wind","风")} ${value.windCoveragePercent.toInt()}% · ${tr("depth","水深")} ${value.depthCoveragePercent.toInt()}% · ${tr("attitude","姿态")} ${value.attitudeCoveragePercent.toInt()}%",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ReportHeading(tr("Sailing track","航行轨迹"))
            TripReportRouteMap(replay)
            if(value.sourceTimeline.isNotEmpty()){
                ReportHeading(tr("Source timeline","来源时间线"))
                value.sourceTimeline.takeLast(20).forEach{entry->
                    Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.timestamp))} · ${entry.type.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()}}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium)
                    Text(entry.detailJson,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ReportHeading(tr("Overview","概览"))
            ReportLine(tr("Distance","距离"),"%.2f km".format(value.distanceMeters/1000))
            ReportLine(tr("Recorded / moving","记录 / 航行时间"),"${durationText(value.durationMillis)} / ${durationText(value.movingMillis)}")
            ReportLine(tr("Start → end","起点 → 终点"),coordinatePair(value.startLatitude,value.startLongitude,value.endLatitude,value.endLongitude))
            ReportHeading(tr("Speed & distance","速度与距离"))
            ReportLine(tr("SOG avg / median / P95 / max","对地航速 平均 / 中位 / P95 / 最大"),listOf(value.averageSogKnots,value.medianSogKnots,value.p95SogKnots,value.maxSogKnots).joinToString(" / "){it?.let{"%.1f".format(it)}?:"—"}+" kn")
            Text(tr("Moving time and average SOG use a 0.5 kn threshold.","航行时间和平均对地航速使用 0.5 节阈值。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ReportHeading(tr("Sailing","航行"))
            ReportLine(tr("Heel avg abs / RMS / P95","横倾绝对均值 / RMS / P95"),listOf(value.averageAbsHeelDegrees,value.rmsHeelDegrees,value.p95AbsHeelDegrees).joinToString(" / "){it?.let{"%.1f°".format(it)}?:"—"})
            ReportLine(tr("Fastest synchronized SOG · heel","同步样本最快对地航速 · 横倾"),if(value.maximumSogWithAttitudeKnots!=null&&value.heelAtMaximumSogDegrees!=null)"%.1f kn · %+.1f°".format(value.maximumSogWithAttitudeKnots,value.heelAtMaximumSogDegrees) else "—")
            value.speedHeelBands.filter{it.sampleCount>0}.forEach{band->
                ReportLine(tr("Heel ${band.label} · SOG avg / max","横倾 ${band.label} · 对地航速平均 / 最大"),"${band.averageSogKnots?.let{"%.1f".format(it)}?:"—"} / ${band.maximumSogKnots?.let{"%.1f kn".format(it)}?:"—"} · n=${band.sampleCount}")
                if(band.averageBoatSpeedKnots!=null||band.maximumBoatSpeedKnots!=null)ReportLine(tr("Heel ${band.label} · STW avg / max","横倾 ${band.label} · 对水航速平均 / 最大"),"${band.averageBoatSpeedKnots?.let{"%.1f".format(it)}?:"—"} / ${band.maximumBoatSpeedKnots?.let{"%.1f kn".format(it)}?:"—"}")
            }
            if(value.attitudeArtifactFilteredCount>0)Text(tr("${value.attitudeArtifactFilteredCount} isolated short attitude spikes were excluded from report statistics. Runtime recording was not stopped.","报告统计已排除 ${value.attitudeArtifactFilteredCount} 个孤立的短时姿态尖峰；运行时记录没有被自动停止。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ReportLine(tr("Port / starboard max","左舷 / 右舷最大横倾"),"${value.maxPortHeelDegrees?.let{"%.1f°".format(it)}?:"—"} / ${value.maxStarboardHeelDegrees?.let{"%+.1f°".format(it)}?:"—"}")
            ReportLine(tr("Heel distribution 0–10 / 10–20 / 20–30 / 30+","横倾分布 0–10 / 10–20 / 20–30 / 30+"),listOf(value.heelDistribution.zeroToTenPercent,value.heelDistribution.tenToTwentyPercent,value.heelDistribution.twentyToThirtyPercent,value.heelDistribution.overThirtyPercent).joinToString(" / "){"${it.toInt()}%"})
            ReportLine(tr("Pitch mean / P95 abs / bow up / bow down","纵倾均值 / 绝对 P95 / 艏升 / 艏沉"),listOf(value.meanPitchDegrees,value.p95AbsPitchDegrees,value.maxBowUpDegrees,value.maxBowDownDegrees).joinToString(" / "){it?.let{"%+.1f°".format(it)}?:"—"})
            ReportLine(tr("Heading / COG median · P95 abs","船首向 / COG 中位偏差 · 绝对 P95"),"${value.headingCogMedianDegrees?.let{"%+.1f°".format(it)}?:"—"} · ${value.headingCogP95AbsoluteDegrees?.let{"%.1f°".format(it)}?:"—"}")
            ReportLine(tr("Estimated set / drift","估算流向 / 流速"),if(value.estimatedSetDegrees!=null&&value.estimatedDriftKnots!=null)"%03.0f° · %.2f kn · n=%d".format(value.estimatedSetDegrees,value.estimatedDriftKnots,value.setDriftSampleCount) else "—")
            ReportLine(tr("Port / starboard tack time","左舷 / 右舷航行时间"),"${durationText(value.portTackMillis)} / ${durationText(value.starboardTackMillis)}")
            ReportLine(tr("Tacks / gybes (confirmed)","换舷 / 顺风换舷（已确认）"),"${value.tackCount} / ${value.gybeCount}")
            value.pointOfSailMillis.filterValues{it>0}.forEach{(point,millis)->ReportLine(point.name.lowercase().replace('_',' ').replaceFirstChar{it.titlecase()},durationText(millis))}
            Text(tr("Set/drift may include vessel leeway and sensor error.","流向/流速估算可能包含船舶侧滑和传感器误差。"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ReportHeading(tr("Motion","运动"))
            ReportLine(tr("Motion mean / P95 / max","运动评分平均 / P95 / 最大"),listOf(value.motionMean,value.motionP95,value.motionMaximum).joinToString(" / "){it?.let{"%.0f".format(it)}?:"—"})
            ReportLine(tr("Roll period · roll/pitch rate RMS","横摇周期 · 横摇/纵摇角速度 RMS"),"${value.dominantRollPeriodSeconds?.let{"%.1f s".format(it)}?:"—"} · ${value.rollRateRmsDegreesPerSecond?.let{"%.1f".format(it)}?:"—"} / ${value.pitchRateRmsDegreesPerSecond?.let{"%.1f°/s".format(it)}?:"—"}")
            ReportLine(tr("Impact candidates / high motion","冲击候选 / 高运动"),"${value.impactCandidateCount} / ${value.highMotionCount}")
            ReportHeading(tr("Depth / UKC","水深 / 龙骨下余量"))
            ReportLine(tr("Depth min / P05 / median","水深最小 / P05 / 中位"),listOf(value.minDepthMeters,value.p05DepthMeters,value.medianDepthMeters).joinToString(" / "){it?.let{"%.1f m".format(it)}?:"—"})
            ReportLine(tr("UKC min / P05","龙骨下余量最小 / P05"),listOf(value.minUkcMeters,value.p05UkcMeters).joinToString(" / "){it?.let{"%.1f m".format(it)}?:"—"})
            ReportHeading(tr("Weather","天气与风"))
            ReportLine(tr("Apparent wind avg / P95 / max","视风平均 / P95 / 最大"),listOf(value.apparentWindMeanKnots,value.apparentWindP95Knots,value.apparentWindMaximumKnots).joinToString(" / "){it?.let{"%.1f kn".format(it)}?:"—"})
            ReportLine(tr("True wind avg / P95 / max","真风平均 / P95 / 最大"),listOf(value.trueWindMeanKnots,value.trueWindP95Knots,value.maximumTrueWindKnots).joinToString(" / "){it?.let{"%.1f kn".format(it)}?:"—"})
            ReportLine(tr("True wind direction circular mean","真风向圆周均值"),value.meanTrueWindDirectionDegrees?.let{"%03.0f°T".format(it)}?:"—")
            value.windHeelBands.filter{it.sampleCount>0}.forEach{band->ReportLine(tr("AWS ${band.label} · heel median / P95","视风 ${band.label} · 横倾中位 / P95"),"${band.medianAbsHeelDegrees?.let{"%.1f°".format(it)}?:"—"} / ${band.p95AbsHeelDegrees?.let{"%.1f°".format(it)}?:"—"} · n=${band.sampleCount}")}
            ReportLine(tr("Pressure start / end / min / max","气压起始 / 结束 / 最小 / 最大"),listOf(value.pressureStartHpa,value.pressureEndHpa,value.pressureMinimumHpa,value.pressureMaximumHpa).joinToString(" / "){it?.let{"%.1f".format(it)}?:"—"}+" hPa")
            ReportHeading(tr("Events & waypoints","事件与航点"))
            ReportLine(tr("Events / waypoints","事件 / 航点"),"${value.eventCount} / ${value.waypointCount}")
            value.legs.forEach{leg->ReportLine(leg.name,"%.2f NM · %s · %s".format(leg.distanceMeters/1852.0,durationText(leg.durationMillis),leg.averageSogKnots?.let{"%.1f kn".format(it)}?:"—"))}
            ReportLine(tr("NMEA / depth / wind gaps","NMEA / 水深 / 风数据缺口"),"${value.nmeaGapCount} / ${value.depthGapCount} / ${value.windGapCount}")
            ReportLine(tr("Position / heading source changes","位置 / 航向来源切换"),"${value.positionSourceChangeCount} / ${value.headingSourceChangeCount}")
            if(value.findings.isNotEmpty()){ReportHeading(tr("Issues & observations","问题与观察"));value.findings.forEach{finding->Text("${finding.severity} · ${finding.title}\n${finding.detail}",style=MaterialTheme.typography.bodySmall)}}
            ReportHeading(tr("Data quality","数据质量"))
            Text(tr("Position ${value.positionCoveragePercent.toInt()}% · depth ${value.depthCoveragePercent.toInt()}% · attitude ${value.attitudeCoveragePercent.toInt()}% · true/apparent wind ${value.trueWindCoveragePercent.toInt()}%/${value.apparentWindCoveragePercent.toInt()}%","位置 ${value.positionCoveragePercent.toInt()}% · 水深 ${value.depthCoveragePercent.toInt()}% · 姿态 ${value.attitudeCoveragePercent.toInt()}% · 真风/视风 ${value.trueWindCoveragePercent.toInt()}%/${value.apparentWindCoveragePercent.toInt()}%"),style=MaterialTheme.typography.bodySmall)
            Text(tr("True wind source: external ${value.externalTrueWindCoveragePercent.toInt()}% · derived water ${value.derivedWaterTrueWindCoveragePercent.toInt()}% · derived ground ${value.derivedGroundTrueWindCoveragePercent.toInt()}%","真风来源：外部 ${value.externalTrueWindCoveragePercent.toInt()}% · 水参考推算 ${value.derivedWaterTrueWindCoveragePercent.toInt()}% · 地面参考推算 ${value.derivedGroundTrueWindCoveragePercent.toInt()}%"),style=MaterialTheme.typography.bodySmall)
            Text("${value.reportEngineVersion} · ${value.motionAlgorithmVersion}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}

@Composable private fun reportQualityLabel(value:com.yokuli.anchorwatch.domain.report.ReportQuality)=when(value){
    com.yokuli.anchorwatch.domain.report.ReportQuality.GOOD->tr("Good","良好")
    com.yokuli.anchorwatch.domain.report.ReportQuality.PARTIAL->tr("Partial","部分可用")
    com.yokuli.anchorwatch.domain.report.ReportQuality.LIMITED->tr("Limited","有限")
}

@Composable private fun ReportHeading(value:String){HorizontalDivider();Text(value,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleSmall)}
@Composable private fun ReportLine(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodySmall)}}
private fun durationText(value:Long)="${value/3_600_000}h ${(value/60_000)%60}m"
private fun coordinatePair(startLat:Double?,startLon:Double?,endLat:Double?,endLon:Double?)=if(startLat!=null&&startLon!=null&&endLat!=null&&endLon!=null)"%.4f, %.4f → %.4f, %.4f".format(startLat,startLon,endLat,endLon)else"—"

@Composable private fun TripHistoryRoutePreview(session:TripSessionEntity,vm:MainViewModel){
    val replay by produceState<TripReplayData?>(null,session.id){value=vm.tripReplay(session.id)}
    Column(Modifier.fillMaxWidth().testTag("trip_history_route_${session.id}"),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(tr("Recorded route","已记录航迹"),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
        TripReportRouteMap(replay)
    }
}

@Composable internal fun TripReportRouteMap(data:TripReplayData?){
    val route=data?.points.orEmpty().mapNotNull{point->if(point.latitude!=null&&point.longitude!=null)LatLng(point.latitude,point.longitude)else null}
    if(data==null){Box(Modifier.fillMaxWidth().height(120.dp).testTag("trip_route_loading"),contentAlignment=Alignment.Center){CircularProgressIndicator()};return}
    if(route.isEmpty()){Card(Modifier.fillMaxWidth().testTag("trip_route_empty")){Text(tr("No usable coordinates were recorded for this trip. Instrument samples and events remain available below.","本次航程没有记录到可用坐标；仪表样本与事件仍可在下方查看。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};return}
    if(!BuildConfig.MAPS_CONFIGURED){Card(Modifier.fillMaxWidth().testTag("trip_route_map_unavailable")){Text(tr("The route has ${route.size} coordinate samples, but the map is unavailable in this build. Replay and GPX/KML export still work.","航迹包含 ${route.size} 个坐标样本，但当前构建无法显示地图；回放与 GPX / KML 导出仍可使用。"),Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};return}
    val first=route.first();val last=route.last();val camera=rememberCameraPositionState{position=CameraPosition.fromLatLngZoom(first,11f)}
    var loaded by remember(route){mutableStateOf(false)}
    val bounds=remember(route){LatLngBounds.builder().also{builder->route.forEach{point->builder.include(point)}}.build()}
    androidx.compose.runtime.LaunchedEffect(loaded,bounds){if(loaded)runCatching{camera.animate(CameraUpdateFactory.newLatLngBounds(bounds,48))}}
    GoogleMap(Modifier.fillMaxWidth().height(180.dp).testTag("trip_route_map"),cameraPositionState=camera,onMapLoaded={loaded=true},uiSettings=MapUiSettings(compassEnabled=false,mapToolbarEnabled=false,myLocationButtonEnabled=false,zoomControlsEnabled=false,scrollGesturesEnabled=false,zoomGesturesEnabled=false,rotationGesturesEnabled=false,tiltGesturesEnabled=false)){
        Polyline(points=route,color=MaterialTheme.colorScheme.primary,width=5f)
        Marker(state=remember(first){MarkerState(first)},title=tr("Trip start","航程起点"))
        Marker(state=remember(last){MarkerState(last)},title=tr("Trip end","航程终点"))
    }
}

@Composable
internal fun AnchorageDetailDialog(saved:SavedAnchorageEntity,dismiss:()->Unit,openGoogleMaps:()->Unit,shareQr:()->Unit,approach:()->Unit,approachEnabled:Boolean=true){
    AlertDialog(
        onDismissRequest=dismiss,
        title={Column{Text(saved.name);Text("${"%.7f".format(saved.latitude)}, ${"%.7f".format(saved.longitude)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},
        confirmButton={TextButton(dismiss){Text(tr("Close","关闭"))}},
        text={SavedAnchorageDetailsContent(
            saved=saved,
            actions=SavedAnchorageCardActions({approach()},{openGoogleMaps()},{shareQr()},approachEnabled),
            modifier=Modifier.heightIn(max=590.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            showHeading=false,
        )},
    )
}

internal data class SavedAnchorageCardActions(
    val approach:(SavedAnchorageEntity)->Unit,
    val openGoogleMaps:(SavedAnchorageEntity)->Unit,
    val shareQr:(SavedAnchorageEntity)->Unit,
    val approachEnabled:Boolean=true,
)

@Composable
internal fun SavedAnchorageCard(
    saved:SavedAnchorageEntity,
    actions:SavedAnchorageCardActions,
    modifier:Modifier=Modifier,
    distanceText:String?=null,
){
    Card(modifier.fillMaxWidth().testTag("saved_anchorage_card_${saved.id}")){
        SavedAnchorageDetailsContent(
            saved=saved,
            actions=actions,
            modifier=Modifier.fillMaxWidth().padding(12.dp),
            showHeading=true,
            distanceText=distanceText,
        )
    }
}

@Composable
internal fun SavedAnchorageDetailsContent(
    saved:SavedAnchorageEntity,
    actions:SavedAnchorageCardActions,
    modifier:Modifier=Modifier,
    showHeading:Boolean=true,
    distanceText:String?=null,
    trailingHeaderAction:(@Composable ()->Unit)?=null,
){
    Column(modifier,verticalArrangement=Arrangement.spacedBy(9.dp)){
        if(showHeading){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(saved.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text("${"%.6f".format(saved.latitude)}, ${"%.6f".format(saved.longitude)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                distanceText?.let{Text(it,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
                trailingHeaderAction?.invoke()
            }
        }
        AnchorageDetailLine(tr("Alarm radius","报警半径"),saved.preferredAlarmRadiusMeters?.let{"${it.toInt()} m"}?:"—")
        AnchorageDetailLine(tr("Coordinate quality","坐标性质"),anchorageCoordinateQuality(saved))
        AnchorageDetailLine(tr("Water depth","水深"),saved.typicalWaterDepthMeters?.let{"%.1f m".format(it)}?:"—")
        AnchorageDetailLine(tr("Rode / chain","锚缆 / 锚链"),saved.typicalRodeLengthMeters?.let{"${it.toInt()} m"}?:"—")
        AnchorageDetailLine(tr("Seabed","底质"),seabedLabel(saved))
        AnchorageDetailLine(tr("Rating","评分"),saved.rating?.let{"★".repeat(it)}?:"—")
        if(saved.notes.isNotBlank()){
            HorizontalDivider()
            Text(tr("Notes","备注"),style=MaterialTheme.typography.labelLarge)
            Text(saved.notes,style=MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
        Text(
            tr("Personal reference only. Check present depth, traffic, weather and surroundings before anchoring.","仅供个人参考。下锚前请重新确认当前水深、周围船只、天气和环境。"),
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button({actions.approach(saved)},Modifier.fillMaxWidth().testTag("saved_anchorage_approach_${saved.id}"),enabled=actions.approachEnabled){
            Text(tr("Approach","接近指引"))
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({actions.openGoogleMaps(saved)},Modifier.weight(1f).testTag("saved_anchorage_maps_${saved.id}")){
                Text(tr("Google Maps","Google 地图"))
            }
            OutlinedButton({actions.shareQr(saved)},Modifier.weight(1f).testTag("saved_anchorage_share_${saved.id}")){
                Text(tr("Share QR","分享二维码"))
            }
        }
    }
}

@Composable private fun AnchorageDetailLine(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,fontWeight=FontWeight.Medium)}}

@Composable private fun AnchorageEditor(initial:SavedAnchorageEntity,dismiss:()->Unit,save:(SavedAnchorageEntity)->Unit){
    var name by remember(initial.id){mutableStateOf(initial.name)};var radius by remember(initial.id){mutableStateOf(initial.preferredAlarmRadiusMeters?.toString()?:"")};var depth by remember(initial.id){mutableStateOf(initial.typicalWaterDepthMeters?.toString()?:"")};var rode by remember(initial.id){mutableStateOf(initial.typicalRodeLengthMeters?.toString()?:"")};var seabed by remember(initial.id){mutableStateOf(runCatching{SeabedType.valueOf(initial.seabedType)}.getOrDefault(SeabedType.UNKNOWN))};var customSeabed by remember(initial.id){mutableStateOf(initial.customSeabedText?:"")};var rating by remember(initial.id){mutableStateOf(initial.rating)};var notes by remember(initial.id){mutableStateOf(initial.notes)}
    fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(initial.id==0L)tr("Save anchorage","收藏锚地")else tr("Edit anchorage","编辑锚地"))},confirmButton={Button({save(initial.copy(name=name.trim(),updatedAt=System.currentTimeMillis(),preferredAlarmRadiusMeters=radius.toDoubleOrNull(),typicalWaterDepthMeters=depth.toDoubleOrNull(),typicalRodeLengthMeters=rode.toDoubleOrNull(),seabedType=seabed.name,customSeabedText=customSeabed.trim().takeIf{seabed==SeabedType.OTHER&&it.isNotBlank()},rating=rating,notes=notes.trim()))},enabled=name.isNotBlank()&&name.length<=200&&notes.length<=20_000&&customSeabed.length<=200){Text(tr("Save","保存"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it.take(200)},label={Text(tr("Name *","名称 *"))},singleLine=true);Text("${"%.6f".format(initial.latitude)}, ${"%.6f".format(initial.longitude)}",style=MaterialTheme.typography.bodySmall);if(initial.coordinateSource!=AnchorageCoordinateSource.CONFIRMED_ANCHOR.name)Text(tr("This is ${anchorageCoordinateQuality(initial).lowercase()}, not a confirmed anchor. It is the centre of the best region currently available and remains clearly labelled when saved.","这不是已确认锚点，而是${anchorageCoordinateQuality(initial)}。它取当前最佳可用区域的中心，保存后仍会明确标记。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(radius,{radius=numeric(it)},label={Text(tr("Radius","范围"))},suffix={Text("m")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(depth,{depth=numeric(it)},label={Text(tr("Depth","水深"))},suffix={Text("m")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(rode,{rode=numeric(it)},label={Text(tr("Rode","锚链"))},suffix={Text("m")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))};Text(tr("Seabed","底质"),style=MaterialTheme.typography.labelLarge);SeabedType.entries.chunked(3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){row.forEach{type->FilterChip(seabed==type,{seabed=type},label={Text(seabedTypeLabel(type),style=MaterialTheme.typography.labelSmall)},modifier=Modifier.weight(1f))};repeat(3-row.size){Spacer(Modifier.weight(1f))}}};if(seabed==SeabedType.OTHER)OutlinedTextField(customSeabed,{customSeabed=it.take(200)},label={Text(tr("Other seabed","其他底质"))},modifier=Modifier.fillMaxWidth());Text(tr("Your rating","你的评分"),style=MaterialTheme.typography.labelLarge);Row{(1..5).forEach{value->TextButton({rating=if(rating==value)null else value}){Text(if((rating?:0)>=value)"★" else "☆")}}};OutlinedTextField(notes,{notes=it.take(20_000)},label={Text(tr("Notes","备注"))},minLines=2)}})
}

@Composable private fun anchorageCoordinateQuality(value:SavedAnchorageEntity):String=when(value.coordinateSource){
    AnchorageCoordinateSource.ESTIMATED_REGION_CENTRE.name->value.coordinateUncertaintyMeters?.let{tr("Estimated region centre · ±${it.toInt()} m","估算区域中心 · ±${it.toInt()} 米") }?:tr("Estimated region centre","估算区域中心")
    AnchorageCoordinateSource.TEMPORARY_WATCH_REFERENCE.name->value.coordinateUncertaintyMeters?.let{tr("Temporary watch reference · ±${it.toInt()} m","临时警戒参考 · ±${it.toInt()} 米") }?:tr("Temporary watch reference","临时警戒参考")
    else->tr("Confirmed anchor","已确认锚点")
}

@Composable private fun historySourceLabel(value:String):String=when(value){"SYSTEM"->tr("System GPS","系统 GPS");"DEMO"->tr("Demo GPS","演示 GPS");"NMEA"->"NMEA GPS";else->value}
@Composable private fun historyCenterLabel(value:String):String=when(value){"CURRENT_POSITION"->tr("Current-position centre","当前位置中心");"ESTIMATED_USER_ACCEPTED"->tr("Accepted estimated centre","已接受的估算中心");"MANUAL_COORDINATES"->tr("Manual coordinates","手动坐标");"MAP_PICK"->tr("Map-selected centre","地图选定中心");"UNKNOWN"->tr("Centre learning","中心学习中");else->value.replace('_',' ')}
@Composable private fun historyEventLabel(value:String):String=when(value){
 "SESSION_STARTED"->tr("Session started","会话已开始");"SESSION_STARTED_CENTER_LEARNING"->tr("Session started · learning centre","会话已开始 · 学习中心");"SESSION_PAUSED"->tr("Watch paused","监控已暂停");"SESSION_RESUMED"->tr("Watch resumed","监控已继续");"ANCHOR_LIFTED"->tr("Anchor lifted","已起锚")
 "ALARM_TRIGGERED"->tr("Alarm triggered","已触发警报");"ALARM_SNOOZED"->tr("Alarm snoozed","警报稍后提醒");"ALARM_RANGE_CHANGED"->tr("Alarm range changed","报警范围已调整");"ALARM_CLEARED_BY_RANGE_CHANGE"->tr("Alarm cleared by range change","调整范围后警报解除")
 "NMEA_CONNECTION_LOST"->tr("NMEA connection lost","NMEA 连接丢失");"NMEA_CONNECTION_RESTORED"->tr("NMEA connection restored","NMEA 连接恢复");"LOW_BATTERY"->tr("Low battery","电量低");"MONITORING_INTERRUPTED_BY_REBOOT"->tr("Monitoring interrupted by reboot","重启中断监控")
 "ANCHOR_CENTER_ACCEPTED_BY_USER"->tr("Estimated centre accepted","已接受估算中心");"ANCHOR_CENTER_CURRENT_KEPT"->tr("Current centre kept","保留当前中心");"ANCHOR_CENTER_ESTIMATION_CONTINUED"->tr("Centre estimation continued","继续估算中心");"ESTIMATED_CENTER_HIGH"->tr("High-confidence centre ready","高置信度中心已就绪");"POSSIBLE_ANCHOR_DRAG_TREND"->tr("Possible slow anchor movement","可能存在缓慢走锚趋势")
 "PHONE_HEADING_ENABLED"->tr("Phone heading enabled","手机船首向已开启");"PHONE_HEADING_DISABLED"->tr("Phone heading disabled","手机船首向已关闭")
 "ANCHOR_HEADING_EVIDENCE_ENABLED"->tr("Heading assistance enabled","船首向辅助已开启");"ANCHOR_HEADING_EVIDENCE_DISABLED"->tr("Heading assistance disabled","船首向辅助已关闭");"ANCHOR_HEADING_EVIDENCE_SOURCE_CHANGED"->tr("Heading evidence source changed","船首向证据来源已切换");"ANCHOR_HEADING_EVIDENCE_PAUSED_UNSTABLE"->tr("Phone heading evidence paused","手机船首向证据已暂停");"ANCHOR_HEADING_EVIDENCE_RESUMED"->tr("Phone heading evidence resumed","手机船首向证据已恢复")
 "ANCHOR_CENTRE_RECALCULATION_REQUESTED"->tr("Track centre analysis requested","已请求轨迹中心分析");"ANCHOR_CENTRE_RECALCULATION_INSUFFICIENT"->tr("Track centre evidence insufficient","轨迹中心证据不足");"ANCHOR_CENTRE_RECALCULATION_READY"->tr("Alternative track centre ready","备选轨迹中心已就绪");"ANCHOR_CENTRE_RECALCULATED_APPLIED"->tr("Recalculated centre applied","已应用重新计算的中心");"ANCHOR_CENTRE_RECALCULATION_REJECTED"->tr("Current centre retained","已保留当前中心")
 "CONDITION_SETTINGS_CHANGED"->tr("Condition alert settings changed","环境警戒设置已更改")
 "DEPTH_GUARD_ENABLED"->tr("Depth guard enabled","水深警戒已开启");"DEPTH_GUARD_DISABLED"->tr("Depth guard disabled","水深警戒已关闭");"DEPTH_GUARD_UPDATED"->tr("Depth guard updated","水深警戒已更新")
 "DEPTH_SHALLOW_ALARM"->tr("Shallow-depth alarm","浅水警报");"DEPTH_SHALLOW_CLEARED"->tr("Shallow-depth alarm cleared","浅水警报已解除");"DEPTH_DEEP_ALARM"->tr("Deep-water alarm","深水警报");"DEPTH_DEEP_CLEARED"->tr("Deep-water alarm cleared","深水警报已解除");"DEPTH_DATA_LOST"->tr("Depth data unavailable","水深数据不可用");"DEPTH_DATA_RESTORED"->tr("Depth data restored","水深数据已恢复")
 "WIND_GUARD_ENABLED"->tr("Wind guard enabled","风警戒已开启");"WIND_GUARD_DISABLED"->tr("Wind guard disabled","风警戒已关闭");"WIND_GUARD_UPDATED"->tr("Wind guard updated","风警戒已更新")
 "WIND_WARNING"->tr("High-wind warning","大风提醒");"WIND_WARNING_CLEARED"->tr("High-wind warning cleared","大风提醒已解除");"WIND_ALARM"->tr("High-wind alarm","大风警报");"WIND_ALARM_CLEARED"->tr("High-wind alarm cleared","大风警报已解除");"WIND_DATA_LOST"->tr("Wind data unavailable","风数据不可用");"WIND_DATA_RESTORED"->tr("Wind data restored","风数据已恢复")
 "WIND_BASELINE_ESTABLISHED"->tr("Wind baseline established","风向基线已建立");"WIND_BASELINE_RESET"->tr("Wind baseline reset","风向基线已重置");"WIND_SHIFT_ALARM"->tr("Wind-shift alarm","风向突变警报");"WIND_SHIFT_CLEARED"->tr("Wind-shift alarm cleared","风向突变警报已解除")
 else->if(LocalAppLanguage.current.usesChinese())value.replace('_',' ')else value.replace('_',' ').lowercase().replaceFirstChar{it.titlecase()}
}
@Composable private fun historyEventDetail(value:String):String=when(value){"USER_MUST_RESUME"->tr("User must resume monitoring","需要用户手动继续监控");"HISTORICAL_EVIDENCE_RETAINED"->tr("Previously used evidence was retained","已保留此前使用的证据");else->value}
@Composable private fun seabedLabel(value:SavedAnchorageEntity)=if(value.seabedType==SeabedType.OTHER.name&&!value.customSeabedText.isNullOrBlank())value.customSeabedText else seabedTypeLabel(runCatching{SeabedType.valueOf(value.seabedType)}.getOrDefault(SeabedType.UNKNOWN))
@Composable private fun seabedTypeLabel(value:SeabedType)=when(value){SeabedType.UNKNOWN->tr("Unknown","未知");SeabedType.MUD->tr("Mud","泥");SeabedType.SAND->tr("Sand","沙");SeabedType.MUD_SAND->tr("Mud / sand","泥沙");SeabedType.GRAVEL->tr("Gravel","砾石");SeabedType.ROCK->tr("Rock","岩石");SeabedType.WEED->tr("Weed","水草");SeabedType.SHELL->tr("Shell","贝壳");SeabedType.OTHER->tr("Other","其他")}
