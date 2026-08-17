package com.yokuli.anchorwatch.ui.about

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yokuli.anchorwatch.LocalAppLanguage
import com.yokuli.anchorwatch.domain.model.AppLanguage
import java.util.Locale

@Composable
internal fun aboutString(@StringRes id: Int, vararg args: Any): String {
    val language = LocalAppLanguage.current
    if (language == AppLanguage.SYSTEM) return stringResource(id, *args)
    val context = LocalContext.current
    val configuration = Configuration(LocalConfiguration.current).apply {
        setLocale(if (language == AppLanguage.SIMPLIFIED_CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
    }
    return context.createConfigurationContext(configuration).resources.getString(id, *args)
}
