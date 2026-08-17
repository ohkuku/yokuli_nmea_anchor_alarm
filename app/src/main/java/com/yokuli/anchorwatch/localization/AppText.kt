package com.yokuli.anchorwatch.localization

import com.yokuli.anchorwatch.domain.model.AppLanguage
import java.util.Locale

val selectableAppLanguages = listOf(
    AppLanguage.ENGLISH,
    AppLanguage.SIMPLIFIED_CHINESE,
    AppLanguage.TRADITIONAL_CHINESE,
    AppLanguage.JAPANESE,
    AppLanguage.FRENCH,
    AppLanguage.SPANISH,
)

fun AppLanguage.resolved(systemLocale: Locale = Locale.getDefault()): AppLanguage = when (this) {
    AppLanguage.SYSTEM -> when (systemLocale.language.lowercase(Locale.ROOT)) {
        "zh" -> if (
            systemLocale.script.equals("Hant", ignoreCase = true) ||
            systemLocale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
        ) AppLanguage.TRADITIONAL_CHINESE else AppLanguage.SIMPLIFIED_CHINESE
        "ja" -> AppLanguage.JAPANESE
        "fr" -> AppLanguage.FRENCH
        "es" -> AppLanguage.SPANISH
        else -> AppLanguage.ENGLISH
    }
    else -> this
}

fun AppLanguage.usesChinese(systemLocale: Locale = Locale.getDefault()): Boolean =
    resolved(systemLocale) in setOf(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.TRADITIONAL_CHINESE)

fun AppLanguage.locale(systemLocale: Locale = Locale.getDefault()): Locale = when (resolved(systemLocale)) {
    AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
    AppLanguage.JAPANESE -> Locale.JAPANESE
    AppLanguage.FRENCH -> Locale.FRENCH
    AppLanguage.SPANISH -> Locale.forLanguageTag("es")
    else -> Locale.ENGLISH
}

val AppLanguage.nativeName: String get() = when (this) {
    AppLanguage.SYSTEM -> "System"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.SIMPLIFIED_CHINESE -> "简体中文"
    AppLanguage.TRADITIONAL_CHINESE -> "繁體中文"
    AppLanguage.JAPANESE -> "日本語"
    AppLanguage.FRENCH -> "Français"
    AppLanguage.SPANISH -> "Español"
}

fun localized(language: AppLanguage, english: String, chinese: String): String =
    when (language.resolved()) {
        AppLanguage.SIMPLIFIED_CHINESE -> chinese
        AppLanguage.TRADITIONAL_CHINESE -> TraditionalChinese.convert(chinese)
        AppLanguage.JAPANESE, AppLanguage.FRENCH, AppLanguage.SPANISH ->
            AdditionalTranslations.translate(language.resolved(), english) ?: english
        else -> english
    }
