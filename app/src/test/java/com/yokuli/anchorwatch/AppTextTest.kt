package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.localization.localized
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
    }
}
