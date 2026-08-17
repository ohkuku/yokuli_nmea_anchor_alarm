package com.yokuli.anchorwatch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageApproachState
import com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterDistance
import com.yokuli.anchorwatch.domain.anchorage.ApproachDirectionPolicy
import com.yokuli.anchorwatch.domain.anchorage.ApproachDirectionReference
import com.yokuli.anchorwatch.domain.anchorage.ApproachDistanceFormatter
import com.yokuli.anchorwatch.domain.anchorage.ApproachPhase
import com.yokuli.anchorwatch.ui.theme.SafetyColors

data class AnchorageSetupReference(
    val alarmRadiusMeters: Double?,
    val waterDepthMeters: Double?,
    val rodeMeters: Double?,
)

fun AnchorageCluster.setupReference() = AnchorageSetupReference(
    alarmRadiusMeters = maxAlarmRadiusMeters,
    waterDepthMeters = maxDepthMeters,
    rodeMeters = maxRodeMeters,
)

@Composable
internal fun NearbyAnchorageCard(
    nearby: List<AnchorageClusterDistance>,
    details: (AnchorageCluster) -> Unit,
    approach: (AnchorageCluster) -> Unit,
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (nearby.isEmpty()) return
    ElevatedCard(modifier.fillMaxWidth().testTag("anchorage_nearby_prompt")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Anchor, null, tint = ApproachTeal)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (nearby.size == 1) tr("SAVED ANCHORAGE NEARBY", "收藏锚地在附近")
                        else tr("${nearby.size} SAVED ANCHORING AREAS NEARBY", "附近有 ${nearby.size} 个收藏锚地范围"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tr("Direct reference only · not navigation", "仅提供直线参考 · 不是导航"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(dismiss, Modifier.testTag("anchorage_nearby_dismiss")) {
                    Icon(Icons.Default.Close, tr("Dismiss", "忽略"))
                }
            }
            nearby.forEach { candidate ->
                if (nearby.size > 1) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(candidate.cluster.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${ApproachDistanceFormatter.format(candidate.distanceToAreaMeters)} · " +
                                tr("${candidate.cluster.savedPointCount} saved anchor points", "${candidate.cluster.savedPointCount} 个收藏锚点"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton({ details(candidate.cluster) }) { Text(tr("Details", "详情")) }
                    Button(
                        { approach(candidate.cluster) },
                        Modifier.testTag("approach_${candidate.cluster.id}"),
                    ) { Text(tr("Approach", "接近指引")) }
                }
            }
        }
    }
}

@Composable
internal fun AnchorageApproachOverlay(
    state: AnchorageApproachState,
    details: (AnchorageCluster) -> Unit,
    cancel: () -> Unit,
    setAnchorWatch: (AnchorageSetupReference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = state.target ?: return
    val approachColor = when (state.phase) {
        ApproachPhase.INSIDE_AREA -> SafetyColors.Safe
        ApproachPhase.NEAR -> SafetyColors.Warning
        else -> ApproachTeal
    }
    Surface(
        modifier.fillMaxSize().testTag("anchorage_approach"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp)) {
            IconButton(cancel, Modifier.align(Alignment.TopEnd).testTag("cancel_anchorage_approach")) {
                Icon(Icons.Default.Close, tr("Cancel approach", "取消接近指引"))
            }
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    !state.positionAvailable -> {
                        Icon(Icons.Default.Info, null, Modifier.size(84.dp), tint = SafetyColors.Warning)
                        Text(tr("POSITION UNAVAILABLE", "定位不可用"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            tr("Waiting for a fresh Accepted Position. Direction and distance are paused.", "正在等待新鲜的已接受定位；方向和距离已暂停。"),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.phase == ApproachPhase.INSIDE_AREA -> {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(112.dp), tint = SafetyColors.Safe)
                        Text(tr("SAVED ANCHORING AREA", "已进入收藏锚地参考范围"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text(target.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(tr("${target.savedPointCount} saved anchor points", "${target.savedPointCount} 个收藏锚点"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HistoricalReferenceSummary(target)
                        Text(
                            tr(
                                "Previous use only. Check current depth, traffic, weather and surroundings before anchoring.",
                                "这里只代表历史使用记录。下锚前请重新确认当前水深、周围船只、天气和环境。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({ details(target) }, Modifier.weight(1f)) { Text(tr("Details", "详情")) }
                            Button({ setAnchorWatch(target.setupReference()) }, Modifier.weight(1f).testTag("approach_set_anchor_watch")) { Text(tr("Set Anchor Watch", "设置锚警")) }
                        }
                    }
                    else -> {
                        SmoothDirectionArrow(state.relativeBearingDegrees ?: 0.0, approachColor)
                        state.targetBearingTrueDegrees?.let {
                            Text("%03d°T".format(it.toInt().mod(360)), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        }
                        state.distanceToAreaMeters?.let {
                            Text(ApproachDistanceFormatter.format(it), style = MaterialTheme.typography.displayMedium, color = approachColor, fontWeight = FontWeight.Bold)
                        }
                        Text(target.displayName.uppercase(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(tr("TO SAVED ANCHORING AREA", "前往收藏锚地参考范围"), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Text(directionReferenceLabel(state.directionReference), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton({ details(target) }) { Text(tr("Details", "详情")) }
                    }
                }
            }
            Text(
                tr("Direct bearing only · not a navigation route", "仅显示直线方位 · 不是导航航线"),
                Modifier.align(Alignment.BottomCenter),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SmoothDirectionArrow(angle: Double, color: Color) {
    val animation = remember { Animatable(angle.toFloat()) }
    LaunchedEffect(angle) {
        val delta = ApproachDirectionPolicy.signedAngle(angle - animation.value).toFloat()
        animation.animateTo(animation.value + delta, tween(350))
    }
    Icon(
        Icons.Default.Navigation,
        null,
        Modifier.size(124.dp).rotate(animation.value),
        tint = color,
    )
}

@Composable
private fun directionReferenceLabel(value: ApproachDirectionReference) = when (value) {
    ApproachDirectionReference.HDT -> tr("Direction reference: NMEA HDT", "方向参考：NMEA HDT 真船首向")
    ApproachDirectionReference.COG -> tr("Direction reference: COG", "方向参考：地面航向 COG")
    ApproachDirectionReference.PHONE -> tr("Direction reference: phone heading", "方向参考：手机船首向")
    ApproachDirectionReference.NORTH_UP -> tr("North-up bearing", "正北朝上的绝对方位")
}

@Composable
internal fun AnchorageApproachDisclaimerDialog(confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Before using Approach", "使用接近指引前")) },
        text = {
            Text(
                tr(
                    "Approach shows only direct bearing and distance to a previously used anchoring area.\n\nIt does not check depth, hazards, traffic or safe passage.",
                    "接近指引只显示前往历史下锚范围的直线方位和距离。\n\n它不会检查水深、障碍物、船只交通或航路是否安全。",
                ),
            )
        },
        confirmButton = { Button(confirm, Modifier.testTag("confirm_approach_disclaimer")) { Text(tr("I understand", "我已了解")) } },
        dismissButton = { TextButton(dismiss) { Text(tr("Cancel", "取消")) } },
    )
}

@Composable
internal fun AnchorageClusterDetailsDialog(
    cluster: AnchorageCluster,
    members: List<SavedAnchorageEntity>,
    dismiss: () -> Unit,
    approach: () -> Unit,
    openMember: (SavedAnchorageEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(cluster.displayName) },
        confirmButton = { TextButton(dismiss) { Text(tr("Close", "关闭")) } },
        text = {
            Column(
                Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(tr("${cluster.savedPointCount} saved anchor points", "${cluster.savedPointCount} 个收藏锚点"), style = MaterialTheme.typography.titleMedium)
                HistoricalReferenceSummary(cluster)
                if (cluster.radiusEstimated) Text(tr("Reference area estimated at 40 m", "参考范围按 40 米估算"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text(tr("SAVED ANCHOR POINTS", "收藏锚点"), style = MaterialTheme.typography.labelLarge)
                members.forEach { saved ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.name, fontWeight = FontWeight.SemiBold)
                                Text("%.5f, %.5f".format(saved.latitude, saved.longitude), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton({ openMember(saved) }) { Text(tr("View", "查看")) }
                        }
                    }
                }
                Button(approach, Modifier.fillMaxWidth()) { Text(tr("Approach", "接近指引")) }
            }
        },
    )
}

@Composable
private fun HistoricalReferenceSummary(cluster: AnchorageCluster) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ReferenceLine(tr("Saved depth reference", "收藏水深参考"), range(cluster.minDepthMeters, cluster.maxDepthMeters, "m"))
        ReferenceLine(tr("Saved rode", "收藏锚链"), range(cluster.minRodeMeters, cluster.maxRodeMeters, "m", whole = true))
        ReferenceLine(tr("Saved radius", "收藏范围"), range(cluster.minAlarmRadiusMeters, cluster.maxAlarmRadiusMeters, "m", whole = true))
    }
}

@Composable
private fun ReferenceLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun range(min: Double?, max: Double?, unit: String, whole: Boolean = false): String {
    if (min == null || max == null) return "—"
    fun value(number: Double) = if (whole) number.toInt().toString() else "%.1f".format(number)
    return if (kotlin.math.abs(max - min) < .05) "${value(min)} $unit" else "${value(min)}–${value(max)} $unit"
}

private val ApproachTeal = Color(0xFF087F8C)
