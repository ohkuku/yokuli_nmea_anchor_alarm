package com.yokuli.anchorwatch

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun SonarSafetyDisclaimerDialog(dismiss:()->Unit,accept:()->Unit){
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(tr("Personal sonar mapping","个人声呐测绘"))},
        text={Text(tr(
            "I understand that this is an observation aid, not a certified navigation chart. GPS, tide, sounder offset, bottom detection and interpolation can all be wrong. I will keep using official charts, Notices to Mariners, a depth sounder and safe navigation practices.",
            "我明白个人声呐测绘仅供观测辅助，不是认证航海图。GPS、潮汐、测深修正、海底识别和插值都可能有误；我仍会使用官方海图、航海通告、测深仪并遵守安全航行操作。",
        ))},
        confirmButton={Button(accept){Text(tr("I understand · Continue","我已了解 · 继续"))}},
        dismissButton={TextButton(dismiss){Text(tr("Cancel","取消"))}},
    )
}
