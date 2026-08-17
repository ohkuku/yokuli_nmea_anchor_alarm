package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.localization.localized
import com.yokuli.anchorwatch.localization.nativeName
import com.yokuli.anchorwatch.localization.resolved
import com.yokuli.anchorwatch.localization.selectableAppLanguages
import com.yokuli.anchorwatch.localization.usesChinese
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTextTest {
    @Test fun aFreshInstallStartsInEnglish() {
        assertEquals(AppLanguage.ENGLISH,AppSettings().appLanguage)
    }
    @Test fun explicitLanguageOverridesTheSystemLocale() {
        assertTrue(AppLanguage.SIMPLIFIED_CHINESE.usesChinese(Locale.ENGLISH))
        assertFalse(AppLanguage.ENGLISH.usesChinese(Locale.SIMPLIFIED_CHINESE))
        assertEquals("锚警", localized(AppLanguage.SIMPLIFIED_CHINESE, "Watch", "锚警"))
        assertEquals("Watch", localized(AppLanguage.ENGLISH, "Watch", "锚警"))
    }

    @Test fun systemLanguageFollowsChineseAndEnglishLocales() {
        assertTrue(AppLanguage.SYSTEM.usesChinese(Locale.SIMPLIFIED_CHINESE))
        assertFalse(AppLanguage.SYSTEM.usesChinese(Locale.US))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE,AppLanguage.SYSTEM.resolved(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.JAPANESE,AppLanguage.SYSTEM.resolved(Locale.JAPANESE))
    }

    @Test fun pickerOffersSixExplicitLanguagesWithoutCountryFlags() {
        assertEquals(
            listOf("English","简体中文","繁體中文","日本語","Français","Español"),
            selectableAppLanguages.map{it.nativeName},
        )
    }

    @Test fun newLocalesTranslateCoreNavigationAndTraditionalChinese() {
        assertEquals("設定",localized(AppLanguage.TRADITIONAL_CHINESE,"Settings","设置"))
        assertEquals("アンカー監視",localized(AppLanguage.JAPANESE,"Watch","锚警"))
        assertEquals("Réglages",localized(AppLanguage.FRENCH,"Settings","设置"))
        assertEquals("Ajustes",localized(AppLanguage.SPANISH,"Settings","设置"))
        assertEquals("Untranslated safety detail",localized(AppLanguage.JAPANESE,"Untranslated safety detail","未翻译安全详情"))
    }
}
