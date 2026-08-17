package com.yokuli.anchorwatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.localization.nativeName
import com.yokuli.anchorwatch.localization.selectableAppLanguages

@Composable
internal fun LanguagePickerDialog(
    selected: AppLanguage,
    dismiss: () -> Unit,
    select: (AppLanguage) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Language", "语言")) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp).testTag("language_list")) {
                items(selectableAppLanguages, key = { it.name }) { language ->
                    val tag = when (language) {
                        AppLanguage.ENGLISH -> "language_en"
                        AppLanguage.SIMPLIFIED_CHINESE -> "language_zh_cn"
                        AppLanguage.TRADITIONAL_CHINESE -> "language_zh_tw"
                        AppLanguage.JAPANESE -> "language_ja"
                        AppLanguage.FRENCH -> "language_fr"
                        AppLanguage.SPANISH -> "language_es"
                        AppLanguage.SYSTEM -> "language_system"
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { select(language) }.padding(vertical = 10.dp)
                            .testTag(tag),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(selected == language, onClick = { select(language) })
                        Column(Modifier.weight(1f)) {
                            Text(language.nativeName, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (language != selectableAppLanguages.last()) HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text(tr("Close", "关闭")) } },
    )
}
