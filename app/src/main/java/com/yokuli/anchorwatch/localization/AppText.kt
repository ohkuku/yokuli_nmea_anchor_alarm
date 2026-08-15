package com.yokuli.anchorwatch.localization

import com.yokuli.anchorwatch.domain.model.AppLanguage
import java.util.Locale

fun AppLanguage.usesChinese(systemLocale: Locale = Locale.getDefault()): Boolean = when (this) {
    AppLanguage.SYSTEM -> systemLocale.language.equals("zh", ignoreCase = true)
    AppLanguage.ENGLISH -> false
    AppLanguage.SIMPLIFIED_CHINESE -> true
}

fun localized(language: AppLanguage, english: String, chinese: String): String =
    if (language.usesChinese()) chinese else english
