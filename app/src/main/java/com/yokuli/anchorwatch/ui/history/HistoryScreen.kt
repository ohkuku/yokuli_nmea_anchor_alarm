package com.yokuli.anchorwatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
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
import java.text.DateFormat

@Composable
internal fun HistoryPage(state:MainUiState,vm:MainViewModel){
    var expanded by remember{mutableStateOf<Long?>(null)}
    var pendingDelete by remember{mutableStateOf<AnchorSessionEntity?>(null)}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{PageHeader(tr("Anchor history","锚泊历史"),tr("Sessions, safety timeline and portable track exports.","查看会话、安全事件时间线及轨迹导出。"))}
        if(state.sessions.isEmpty())item{Text(tr("No anchor sessions recorded.","还没有锚泊记录。"),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.sessions,key={it.id}){session->
            val events=state.eventsBySession[session.id].orEmpty()
            Card(Modifier.fillMaxWidth().clickable{expanded=if(expanded==session.id)null else session.id}){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(DateFormat.getDateTimeInstance().format(session.startedAt),fontWeight=FontWeight.Medium)
                            Text("${when{!session.active->tr("LIFTED","已起锚");session.paused->tr("PAUSED","已暂停");else->tr("ACTIVE","监控中")}} · ${session.positionSource} · ${session.centerSource}",style=MaterialTheme.typography.bodySmall)
                            Text("${"%.5f".format(session.anchorLatitude)}, ${"%.5f".format(session.anchorLongitude)} · ${session.alarmRadiusMeters.toInt()} m · ${tr("max","最大")} ${session.maxDistanceMeters.toInt()} m",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if(expanded==session.id)Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton({vm.exportCsv(session)}){Text("CSV")}
                        OutlinedButton({vm.exportGpx(session)}){Text("GPX")}
                        Text(tr("${session.alarmCount} alarms","${session.alarmCount} 次报警"),style=MaterialTheme.typography.labelMedium,modifier=Modifier.align(Alignment.CenterVertically))
                        Spacer(Modifier.weight(1f))
                        if(!session.active)IconButton({pendingDelete=session}){Icon(Icons.Default.DeleteForever,tr("Delete session","删除会话"),tint=MaterialTheme.colorScheme.error)}
                    }
                    if(expanded==session.id){
                        HorizontalDivider();Text(tr("Event timeline","事件时间线"),style=MaterialTheme.typography.labelLarge)
                        if(events.isEmpty())Text(tr("No recorded events.","没有事件记录。"),style=MaterialTheme.typography.bodySmall)
                        else events.sortedByDescending{it.timestamp}.take(30).forEach{event->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(event.timestamp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Column{Text(event.type.replace('_',' '),style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium);if(event.detail.isNotBlank())Text(event.detail,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let{session->AlertDialog(onDismissRequest={pendingDelete=null},title={Text(tr("Delete this anchor history?","删除这条锚泊历史？"))},text={Text(tr("The session, its complete track and event timeline will be permanently deleted.","该会话、完整轨迹和事件时间线都会被永久删除。"))},confirmButton={Button({vm.deleteHistorySession(session);if(expanded==session.id)expanded=null;pendingDelete=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(tr("Delete","删除"))}},dismissButton={TextButton({pendingDelete=null}){Text(tr("Cancel","取消"))}})}
}
