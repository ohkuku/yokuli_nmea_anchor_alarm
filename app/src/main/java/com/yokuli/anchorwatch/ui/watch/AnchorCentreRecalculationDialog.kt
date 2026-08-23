package com.yokuli.anchorwatch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.domain.anchor.AnchorCentreRecalculationStatus

@Composable
internal fun AnchorCentreRecalculationDialog(state:CentreRecalculationUiState,vm:MainViewModel){
    if(state.sessionId==null)return
    val result=state.result
    AlertDialog(
        onDismissRequest=vm::dismissCentreRecalculation,
        title={Text(tr("Recalculated anchor centre","重新计算锚点中心"))},
        confirmButton={
            when{
                state.loading->Unit
                result?.status==AnchorCentreRecalculationStatus.READY&&state.sessionActive->Button(vm::applyRecalculatedCentre,Modifier.testTag("apply_recalculated_centre")){Text(tr("Use recalculated centre","使用重新计算的中心"))}
                result?.status==AnchorCentreRecalculationStatus.READY->Button(vm::saveRecalculatedCentreAsAnchorage,Modifier.testTag("save_recalculated_anchorage")){Text(tr("Save as anchorage reference","保存为锚地参考"))}
                else->TextButton(vm::dismissCentreRecalculation){Text(tr("Close","关闭"))}
            }
        },
        dismissButton={if(!state.loading&&result?.status==AnchorCentreRecalculationStatus.READY)OutlinedButton(if(state.sessionActive)vm::keepCurrentRecalculatedCentre else vm::dismissCentreRecalculation){Text(if(state.sessionActive)tr("Keep current","保留当前中心")else tr("Compare only","仅比较"))}},
        text={
            if(state.loading)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){CircularProgressIndicator();Text(tr("Analyzing the complete accepted track…","正在分析完整的可信轨迹……"))}
            else if(result!=null)Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(recalculationStatusText(result.status),fontWeight=FontWeight.SemiBold,color=if(result.status==AnchorCentreRecalculationStatus.READY)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                Text(recalculationMessage(result.status),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                MetricLine(tr("Current centre","当前中心"),"%.6f, %.6f".format(result.currentLatitude,result.currentLongitude))
                result.candidate?.let{candidate->
                    MetricLine(tr("Track estimate","轨迹估算"),"%.6f, %.6f".format(candidate.latitude,candidate.longitude))
                    MetricLine(tr("Shift","偏移"),result.shiftMeters?.let{"${it.toInt()} m · ${(result.shiftBearingDegrees?:0.0).toInt()}°T"}?:"—")
                    MetricLine(tr("Uncertainty","不确定范围"),"±${candidate.uncertaintyRadiusMeters.toInt()} m")
                    MetricLine(tr("Track span","轨迹跨度"),"${candidate.trackDiameterMeters.toInt()} m")
                    MetricLine(tr("Rode geometry","锚链几何"),"${candidate.maximumRodeMeters.toInt()} m")
                    MetricLine(tr("Fitted swing radius","拟合摆动半径"),candidate.fittedRadiusMeters?.let{"${it.toInt()} m"}?:"—")
                    MetricLine(tr("Direction evidence","方向证据"),if(candidate.directionEvidenceConsistent)tr("Good","良好")else tr("Still developing","仍在积累"))
                    MetricLine(tr("Radial evidence","径向证据"),if(candidate.radialObservable)tr("Good","良好")else tr("Insufficient","不足")+" · "+observabilityReasonText(candidate.observabilityReason))
                }
                if(state.sessionActive&&result.status==AnchorCentreRecalculationStatus.READY)Text(tr("The active safety centre changes only after you confirm. Alarm radius and the complete track are preserved.","只有确认后才会更改正在生效的安全中心；报警半径和完整轨迹都会保留。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable private fun MetricLine(label:String,value:String){Column{Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.bodyMedium)}}
@Composable private fun recalculationStatusText(status:AnchorCentreRecalculationStatus)=when(status){AnchorCentreRecalculationStatus.READY->tr("HIGH-CONFIDENCE ALTERNATIVE READY","高置信度备选中心已就绪");AnchorCentreRecalculationStatus.INSUFFICIENT_GEOMETRY->tr("NOT ENOUGH GEOMETRY","轨迹几何不足");AnchorCentreRecalculationStatus.RADIAL_NOT_OBSERVABLE->tr("RODE-SCALE SWING NOT OBSERVED","尚未观测到锚链尺度摆动");AnchorCentreRecalculationStatus.DATA_QUALITY_INSUFFICIENT->tr("GPS QUALITY INSUFFICIENT","GPS 质量不足")}
@Composable private fun recalculationMessage(status:AnchorCentreRecalculationStatus)=when(status){AnchorCentreRecalculationStatus.READY->tr("A safe candidate is available for comparison. It has not been applied.","已有可安全比较的候选中心，但尚未应用。");AnchorCentreRecalculationStatus.INSUFFICIENT_GEOMETRY->tr("More time, swing angle and genuine direction reversals are required.","还需要更多时间、摆动角度和真实方向反转。");AnchorCentreRecalculationStatus.RADIAL_NOT_OBSERVABLE->tr("A neat local loop is not enough. The track must expose movement meaningful for the stored rode geometry.","漂亮的局部小圆并不够；轨迹必须体现与已保存锚链几何相符的摆动。");AnchorCentreRecalculationStatus.DATA_QUALITY_INSUFFICIENT->tr("GPS uncertainty currently dominates the observed movement.","当前 GPS 不确定性大于可观测移动。")}
@Composable private fun observabilityReasonText(reason:com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason)=when(reason){
    com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.OBSERVABLE->tr("observable","可观测")
    com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.TRACK_TOO_SMALL->tr("track span is too small","轨迹跨度太小")
    com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.FIT_RADIUS_TOO_SMALL->tr("fitted radius is too small","拟合半径太小")
    com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.FIT_RADIUS_IMPLAUSIBLE->tr("fit conflicts with rode geometry","拟合与锚链几何冲突")
    com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.GPS_UNCERTAINTY_DOMINATES->tr("GPS uncertainty dominates","GPS 不确定性占主导")
    com.yokuli.anchorwatch.domain.anchor.AnchorCentreObservabilityReason.NO_USABLE_CIRCLE_FIT->tr("no usable circle fit","没有可用的圆拟合")
}
