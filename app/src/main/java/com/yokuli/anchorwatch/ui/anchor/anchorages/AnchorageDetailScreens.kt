package com.yokuli.anchorwatch.ui.anchor.anchorages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yokuli.anchorwatch.data.anchorage.AnchoragePlaceBundle
import com.yokuli.anchorwatch.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnchoragePlaceDetailDialog(
    bundle: AnchoragePlaceBundle,
    dismiss: () -> Unit,
    approach: (Long) -> Unit,
    openMap: (Double, Double) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(.94f),
        title = {
            Column {
                Text(bundle.place.displayName)
                Text(
                    bundle.regionPath.joinToString(" · ") { it.displayName }.ifBlank { tr("Unclassified region", "未归类区域") },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(dismiss) { Text(tr("Close", "关闭")) } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)) {
                PrimaryTabRow(tab) {
                    listOf(tr("Overview", "概览"), tr("Spots", "锚点"), tr("Visits", "访问"), tr("Notes", "备注")).forEachIndexed { index, label ->
                        Tab(tab == index, { tab = index }, text = { Text(label) })
                    }
                }
                when (tab) {
                    0 -> AnchorageOverview(bundle)
                    1 -> AnchorageSpots(bundle, approach, openMap)
                    2 -> AnchorageVisits(bundle)
                    else -> AnchorageNotes(bundle)
                }
            }
        },
    )
}

@Composable
private fun AnchorageOverview(bundle: AnchoragePlaceBundle) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(tr("Personal observations, not a safety rating.", "个人观测记录，不是安全评级。"), color = MaterialTheme.colorScheme.tertiary) }
        item { DetailLine(tr("Visits", "访问次数"), (bundle.place.visitCountCached + bundle.place.legacyVisitCount).toString()) }
        item { DetailLine(tr("Planning", "规划状态"), bundle.place.planningStatus) }
        bundle.rating?.let { rating -> item { DetailLine(tr("Preference", "个人偏好"), rating.overallPreference) } }
        if (bundle.facilities.isNotEmpty()) item { Text(bundle.facilities.joinToString { it.type }, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AnchorageSpots(bundle: AnchoragePlaceBundle, approach: (Long) -> Unit, openMap: (Double, Double) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(bundle.spots, key = { it.id }) { spot ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spot.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            spot.typicalWaterDepthMeters?.let { "%.1f m depth".format(it) },
                            spot.typicalRodeLengthMeters?.let { "${it.toInt()} m rode" },
                            spot.preferredAlarmRadiusMeters?.let { "${it.toInt()} m radius" },
                        ).joinToString(" · ").ifBlank { "—" },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ approach(spot.id) }) { Text(tr("Approach", "接近")) }
                        OutlinedButton({ openMap(spot.latitude, spot.longitude) }) { Text(tr("Map", "地图")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchorageVisits(bundle: AnchoragePlaceBundle) {
    if (bundle.visits.isEmpty()) {
        Box(Modifier.padding(12.dp)) { Text(tr("No recorded visits yet.", "还没有访问记录。")) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(bundle.visits, key = { it.id }) { visit ->
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(visit.startedAt)), fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            visit.waterDepthMeters?.let { "%.1f m depth".format(it) },
                            visit.rodeLengthMeters?.let { "${it.toInt()} m rode" },
                            visit.maxExcursionMeters?.let { "${it.toInt()} m max excursion" },
                            "${visit.alarmCount} alarms",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (visit.userNotes.isNotBlank()) Text(visit.userNotes, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (visit.anchorSessionId == null) tr("Saved visit snapshot; source session unavailable.", "访问摘要已保留；原会话不可用。")
                        else tr("Linked to immutable Anchor report #${visit.anchorSessionId}.", "已关联不可变锚泊报告 #${visit.anchorSessionId}。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnchorageNotes(bundle: AnchoragePlaceBundle) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(bundle.place.description.ifBlank { tr("No objective description saved.", "尚未保存客观描述。") }) }
        item { Spacer(Modifier.height(12.dp)); Text(bundle.place.personalNotes.ifBlank { tr("No personal notes saved.", "尚未保存个人备注。") }) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
