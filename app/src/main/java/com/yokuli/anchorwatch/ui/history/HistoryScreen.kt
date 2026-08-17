package com.yokuli.anchorwatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.data.anchorage.SeabedType
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.localization.usesChinese
import java.text.DateFormat

@Composable
internal fun HistoryPage(state:MainUiState,vm:MainViewModel){
    var tab by remember{mutableStateOf(0)}
    var expanded by remember{mutableStateOf<Long?>(null)}
    var pendingDelete by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    var saveSession by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    var editingAnchorage by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var pendingAnchorageDelete by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    var detailAnchorage by remember{mutableStateOf<SavedAnchorageEntity?>(null)}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{PageHeader(tr("History & anchorages","历史与收藏锚地"),tr("Review safety sessions or keep a private, local anchorage library.","查看安全会话，或管理仅保存在本机的锚地收藏。"));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(tab==0,{tab=0},label={Text(tr("Sessions","会话"))});FilterChip(tab==1,{tab=1},label={Text(tr("Saved anchorages","收藏锚地"))})}}
        if(tab==0&&state.sessions.isEmpty())item{Text(tr("No anchor sessions recorded.","还没有锚泊记录。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(tab==0)items(state.sessions,key={"session-${it.id}"}){session->
            val events=state.eventsBySession[session.id].orEmpty()
            val alreadySaved=state.savedAnchorages.firstOrNull{saved->AnchorGeometry.distanceMeters(session.anchorLatitude,session.anchorLongitude,saved.latitude,saved.longitude)<=com.yokuli.anchorwatch.data.anchorage.AnchorageRepository.DUPLICATE_RADIUS_METERS}
            Card(Modifier.fillMaxWidth().clickable{val next=if(expanded==session.id)null else session.id;expanded=next;if(next!=null)vm.loadHistoryEvents(next)}){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(DateFormat.getDateTimeInstance().format(session.startedAt),fontWeight=FontWeight.Medium)
                            Text("${when{!session.active->tr("LIFTED","已起锚");session.paused->tr("PAUSED","已暂停");else->tr("ACTIVE","监控中")}} · ${historySourceLabel(session.positionSource)} · ${historyCenterLabel(session.centerSource)}",style=MaterialTheme.typography.bodySmall)
                            Text("${"%.5f".format(session.anchorLatitude)}, ${"%.5f".format(session.anchorLongitude)} · ${session.alarmRadiusMeters.toInt()} m · ${tr("max","最大")} ${session.maxDistanceMeters.toInt()} m",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if(expanded==session.id)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton({vm.exportCsv(session)}){Text("CSV")}
                        OutlinedButton({vm.exportGpx(session)}){Text("GPX")}
                        Text(tr("${session.alarmCount+session.depthAlarmCount+session.windAlarmCount} alarms","${session.alarmCount+session.depthAlarmCount+session.windAlarmCount} 次报警"),style=MaterialTheme.typography.labelMedium,modifier=Modifier.align(Alignment.CenterVertically))
                        Spacer(Modifier.weight(1f))
                        if(!session.active)IconButton({pendingDelete=session}){Icon(Icons.Default.DeleteForever,tr("Delete session","删除会话"),tint=MaterialTheme.colorScheme.error)}
                    }
                    if(session.minObservedDepthMeters!=null||session.maxObservedWindKnots!=null)Text(buildString{session.minObservedDepthMeters?.let{append(tr("Depth","水深")+" ${"%.1f".format(it)}–${"%.1f".format(session.maxObservedDepthMeters?:it)} m")};session.maxObservedWindKnots?.let{if(isNotEmpty())append(" · ");append(tr("Wind max","最大风速")+" ${"%.1f".format(it)} kn ${session.maxObservedWindSource?:""}")};if(session.depthAlarmCount+session.windAlarmCount>0){append("\n");append(tr("Anchor ${session.alarmCount} · Depth ${session.depthAlarmCount} · Wind ${session.windAlarmCount}","锚警 ${session.alarmCount} · 水深 ${session.depthAlarmCount} · 风 ${session.windAlarmCount}"))}},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(session.centerStatus==AnchorCenterStatus.RESOLVED.name){
                        if(alreadySaved==null)OutlinedButton({saveSession=session},Modifier.fillMaxWidth()){Text(tr("☆ Save anchorage","☆ 收藏锚地"))}
                        else OutlinedButton({detailAnchorage=alreadySaved},Modifier.fillMaxWidth()){Text(tr("Already saved · View details","已收藏 · 查看详情"))}
                    }
                    if(expanded==session.id){
                        HorizontalDivider();Text(tr("Event timeline","事件时间线"),style=MaterialTheme.typography.labelLarge)
                        if(events.isEmpty())Text(tr("No recorded events.","没有事件记录。"),style=MaterialTheme.typography.bodySmall)
                        else events.sortedByDescending{it.timestamp}.take(30).forEach{event->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(event.timestamp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Column{Text(historyEventLabel(event.type),style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium);if(event.detail.isNotBlank())Text(historyEventDetail(event.detail),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
            }
        }
        if(tab==1&&state.savedAnchorages.isEmpty())item{Text(tr("No saved anchorages yet. Save one from a resolved active or historical session.","还没有收藏锚地。可从已确定锚点的当前或历史会话中收藏。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(tab==1)items(state.savedAnchorages,key={"anchorage-${it.id}"}){saved->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(saved.name,fontWeight=FontWeight.SemiBold);Text("${"%.5f".format(saved.latitude)}, ${"%.5f".format(saved.longitude)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(saved.rating?.let{"★".repeat(it)}?:"—",color=MaterialTheme.colorScheme.primary)}
                Text(listOfNotNull(saved.preferredAlarmRadiusMeters?.let{tr("${it.toInt()} m radius","${it.toInt()} 米范围")},saved.typicalWaterDepthMeters?.let{tr("${"%.1f".format(it)} m depth","${"%.1f".format(it)} 米水深")},saved.typicalRodeLengthMeters?.let{tr("${it.toInt()} m rode","${it.toInt()} 米锚链")}).joinToString(" · ").ifBlank{tr("No setup values saved","未保存设置参数")},style=MaterialTheme.typography.bodySmall)
                if(saved.notes.isNotBlank())Text(saved.notes.replace('\n',' ').take(120),maxLines=2,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({detailAnchorage=saved},Modifier.fillMaxWidth()){Text(tr("View details","查看详情"))}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton({editingAnchorage=saved}){Text(tr("Edit","编辑"))};IconButton({pendingAnchorageDelete=saved}){Icon(Icons.Default.DeleteForever,tr("Delete anchorage","删除锚地"),tint=MaterialTheme.colorScheme.error)}}
            }}
        }
    }
    pendingDelete?.let{session->AlertDialog(onDismissRequest={pendingDelete=null},title={Text(tr("Delete this anchor history?","删除这条锚泊历史？"))},text={Text(tr("The session, its complete track and event timeline will be permanently deleted.","该会话、完整轨迹和事件时间线都会被永久删除。"))},confirmButton={Button({vm.deleteHistorySession(session);if(expanded==session.id)expanded=null;pendingDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({pendingDelete=null}){Text(tr("Cancel","取消"))}})}
    saveSession?.let{session->AnchorageEditor(initial=SavedAnchorageEntity(name="",latitude=session.anchorLatitude,longitude=session.anchorLongitude,createdAt=System.currentTimeMillis(),updatedAt=System.currentTimeMillis(),preferredAlarmRadiusMeters=session.alarmRadiusMeters,typicalWaterDepthMeters=session.waterDepthMeters?:session.minObservedDepthMeters,typicalRodeLengthMeters=session.rodeLengthMeters,sourceSessionId=session.id),dismiss={saveSession=null}){value->vm.saveAnchorage(value);saveSession=null;tab=1}}
    editingAnchorage?.let{value->AnchorageEditor(value,{editingAnchorage=null}){vm.saveAnchorage(it);editingAnchorage=null}}
    detailAnchorage?.let{saved->AnchorageDetailDialog(saved,{detailAnchorage=null},{vm.openAnchorageInGoogleMaps(saved)},{vm.shareAnchorageQr(saved)})}
    pendingAnchorageDelete?.let{value->AlertDialog(onDismissRequest={pendingAnchorageDelete=null},title={Text(tr("Delete saved anchorage?","删除收藏锚地？"))},text={Text(tr("This removes only the saved place. Anchor session history is unchanged.","只会删除收藏地点，不影响锚泊会话历史。"))},confirmButton={Button({vm.deleteAnchorage(value.id);pendingAnchorageDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({pendingAnchorageDelete=null}){Text(tr("Cancel","取消"))}})}
    state.anchorageDuplicateExisting?.let{existing->AlertDialog(
        onDismissRequest=vm::dismissAnchorageDuplicate,
        title={Text(tr("Anchorage already saved","锚地已经收藏"))},
        text={Text(tr("“${existing.name}” is within 75 m. A duplicate was not created.","“${existing.name}”距离不足 75 米，因此没有重复收藏。"))},
        confirmButton={Button({detailAnchorage=existing;vm.dismissAnchorageDuplicate()}){Text(tr("View existing","查看已有记录"))}},
        dismissButton={TextButton(vm::dismissAnchorageDuplicate){Text(tr("Close","关闭"))}},
    )}
    state.anchorageOperationError?.let{message->AlertDialog(
        onDismissRequest=vm::dismissAnchorageOperationError,
        title={Text(tr("Anchorage library error","收藏锚地操作失败"))},
        text={Text(if(message.startsWith("Could not delete"))tr(message,"无法删除这个收藏锚地，原记录仍然保留。")else tr(message,"无法保存这个收藏锚地，数据没有发生变化。"))},
        confirmButton={TextButton(vm::dismissAnchorageOperationError){Text(tr("OK","知道了"))}},
    )}
}

@Composable
internal fun AnchorageDetailDialog(saved:SavedAnchorageEntity,dismiss:()->Unit,openGoogleMaps:()->Unit,shareQr:()->Unit){
    AlertDialog(
        onDismissRequest=dismiss,
        title={Column{Text(saved.name);Text("${"%.7f".format(saved.latitude)}, ${"%.7f".format(saved.longitude)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},
        confirmButton={TextButton(dismiss){Text(tr("Close","关闭"))}},
        text={Column(Modifier.heightIn(max=590.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("Saved anchorage details","收藏锚地详情"),style=MaterialTheme.typography.labelLarge)
            DetailLine(tr("Alarm radius","报警半径"),saved.preferredAlarmRadiusMeters?.let{"${it.toInt()} m"}?:"—")
            DetailLine(tr("Water depth","水深"),saved.typicalWaterDepthMeters?.let{"%.1f m".format(it)}?:"—")
            DetailLine(tr("Rode / chain","锚缆 / 锚链"),saved.typicalRodeLengthMeters?.let{"${it.toInt()} m"}?:"—")
            DetailLine(tr("Seabed","底质"),seabedLabel(saved))
            DetailLine(tr("Rating","评分"),saved.rating?.let{"★".repeat(it)}?:"—")
            if(saved.notes.isNotBlank()){HorizontalDivider();Text(tr("Notes","备注"),style=MaterialTheme.typography.labelLarge);Text(saved.notes)}
            HorizontalDivider()
            Text(tr("This is a personal reference, not a verified safe anchoring position. Arrive, assess conditions and deploy the anchor before starting a watch.","这是个人参考记录，并非经验证的安全锚位。请抵达现场、判断环境并完成下锚后再启动锚警。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(openGoogleMaps,Modifier.fillMaxWidth()){Text(tr("Open in Google Maps","在 Google 地图中打开"))}
            OutlinedButton(shareQr,Modifier.fillMaxWidth()){Text(tr("Share coordinate QR image","分享坐标二维码图片"))}
        }},
    )
}

@Composable private fun DetailLine(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,fontWeight=FontWeight.Medium)}}

@Composable private fun AnchorageEditor(initial:SavedAnchorageEntity,dismiss:()->Unit,save:(SavedAnchorageEntity)->Unit){
    var name by remember(initial.id){mutableStateOf(initial.name)};var radius by remember(initial.id){mutableStateOf(initial.preferredAlarmRadiusMeters?.toString()?:"")};var depth by remember(initial.id){mutableStateOf(initial.typicalWaterDepthMeters?.toString()?:"")};var rode by remember(initial.id){mutableStateOf(initial.typicalRodeLengthMeters?.toString()?:"")};var seabed by remember(initial.id){mutableStateOf(runCatching{SeabedType.valueOf(initial.seabedType)}.getOrDefault(SeabedType.UNKNOWN))};var customSeabed by remember(initial.id){mutableStateOf(initial.customSeabedText?:"")};var rating by remember(initial.id){mutableStateOf(initial.rating)};var notes by remember(initial.id){mutableStateOf(initial.notes)}
    fun numeric(value:String)=value.filter{it.isDigit()||it=='.'}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(initial.id==0L)tr("Save anchorage","收藏锚地")else tr("Edit anchorage","编辑锚地"))},confirmButton={Button({save(initial.copy(name=name.trim(),updatedAt=System.currentTimeMillis(),preferredAlarmRadiusMeters=radius.toDoubleOrNull(),typicalWaterDepthMeters=depth.toDoubleOrNull(),typicalRodeLengthMeters=rode.toDoubleOrNull(),seabedType=seabed.name,customSeabedText=customSeabed.trim().takeIf{seabed==SeabedType.OTHER&&it.isNotBlank()},rating=rating,notes=notes.trim()))},enabled=name.isNotBlank()&&name.length<=200&&notes.length<=20_000&&customSeabed.length<=200){Text(tr("Save","保存"))}},dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},text={Column(Modifier.heightIn(max=620.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it.take(200)},label={Text(tr("Name","名称"))},singleLine=true);Text("${"%.6f".format(initial.latitude)}, ${"%.6f".format(initial.longitude)}",style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(radius,{radius=numeric(it)},label={Text(tr("Radius","范围"))},suffix={Text("m")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(depth,{depth=numeric(it)},label={Text(tr("Depth","水深"))},suffix={Text("m")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(rode,{rode=numeric(it)},label={Text(tr("Rode","锚链"))},suffix={Text("m")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))};Text(tr("Seabed","底质"),style=MaterialTheme.typography.labelLarge);SeabedType.entries.chunked(3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){row.forEach{type->FilterChip(seabed==type,{seabed=type},label={Text(seabedTypeLabel(type),style=MaterialTheme.typography.labelSmall)},modifier=Modifier.weight(1f))};repeat(3-row.size){Spacer(Modifier.weight(1f))}}};if(seabed==SeabedType.OTHER)OutlinedTextField(customSeabed,{customSeabed=it.take(200)},label={Text(tr("Other seabed","其他底质"))},modifier=Modifier.fillMaxWidth());Text(tr("Your rating","你的评分"),style=MaterialTheme.typography.labelLarge);Row{(1..5).forEach{value->TextButton({rating=if(rating==value)null else value}){Text(if((rating?:0)>=value)"★" else "☆")}}};OutlinedTextField(notes,{notes=it.take(20_000)},label={Text(tr("Notes","备注"))},minLines=2)}})
}

@Composable private fun historySourceLabel(value:String):String=when(value){"SYSTEM"->tr("System GPS","系统 GPS");"DEMO"->tr("Demo GPS","演示 GPS");"NMEA"->"NMEA GPS";else->value}
@Composable private fun historyCenterLabel(value:String):String=when(value){"CURRENT_POSITION"->tr("Current-position centre","当前位置中心");"ESTIMATED_USER_ACCEPTED"->tr("Accepted estimated centre","已接受的估算中心");"MANUAL_COORDINATES"->tr("Manual coordinates","手动坐标");"MAP_PICK"->tr("Map-selected centre","地图选定中心");"UNKNOWN"->tr("Centre learning","中心学习中");else->value.replace('_',' ')}
@Composable private fun historyEventLabel(value:String):String=when(value){
 "SESSION_STARTED"->tr("Session started","会话已开始");"SESSION_STARTED_CENTER_LEARNING"->tr("Session started · learning centre","会话已开始 · 学习中心");"SESSION_PAUSED"->tr("Watch paused","监控已暂停");"SESSION_RESUMED"->tr("Watch resumed","监控已继续");"ANCHOR_LIFTED"->tr("Anchor lifted","已起锚")
 "ALARM_TRIGGERED"->tr("Alarm triggered","已触发警报");"ALARM_SNOOZED"->tr("Alarm snoozed","警报稍后提醒");"ALARM_RANGE_CHANGED"->tr("Alarm range changed","报警范围已调整");"ALARM_CLEARED_BY_RANGE_CHANGE"->tr("Alarm cleared by range change","调整范围后警报解除")
 "NMEA_CONNECTION_LOST"->tr("NMEA connection lost","NMEA 连接丢失");"NMEA_CONNECTION_RESTORED"->tr("NMEA connection restored","NMEA 连接恢复");"LOW_BATTERY"->tr("Low battery","电量低");"MONITORING_INTERRUPTED_BY_REBOOT"->tr("Monitoring interrupted by reboot","重启中断监控")
 "ANCHOR_CENTER_ACCEPTED_BY_USER"->tr("Estimated centre accepted","已接受估算中心");"ANCHOR_CENTER_CURRENT_KEPT"->tr("Current centre kept","保留当前中心");"ANCHOR_CENTER_ESTIMATION_CONTINUED"->tr("Centre estimation continued","继续估算中心");"ESTIMATED_CENTER_HIGH"->tr("High-confidence centre ready","高置信度中心已就绪");"POSSIBLE_ANCHOR_DRAG_TREND"->tr("Possible slow anchor movement","可能存在缓慢走锚趋势")
 "PHONE_HEADING_ENABLED"->tr("Phone heading enabled","手机船首向已开启");"PHONE_HEADING_DISABLED"->tr("Phone heading disabled","手机船首向已关闭")
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
