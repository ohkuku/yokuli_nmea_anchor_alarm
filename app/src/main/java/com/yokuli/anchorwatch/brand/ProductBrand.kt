package com.yokuli.anchorwatch.brand

import androidx.annotation.StringRes
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.R

object ProductBrand {
    const val PRODUCT_NAME = "Boat Watch"
    const val MAKER_NAME = "Yokuli"
    const val MAKER_LINE = "Developed aboard SV Yokuli"

    val youtubeChannelUrl: String get() = BuildConfig.YOKULI_YOUTUBE_URL.trim()
    val buyMeACoffeeUrl: String get() = BuildConfig.YOKULI_BUYMEACOFFEE_URL.trim()
    val websiteUrl: String get() = BuildConfig.YOKULI_WEBSITE_URL.trim()
    val contactEmail: String get() = BuildConfig.YOKULI_CONTACT_EMAIL.trim()
    val contactUrl: String get() = contactEmail.takeIf(String::isNotBlank)?.let { if (it.startsWith("mailto:", true)) it else "mailto:$it" }.orEmpty()
    val privacyPolicyUrl: String get() = BuildConfig.YOKULI_PRIVACY_URL.trim()
    val sourceCodeUrl: String get() = BuildConfig.YOKULI_SOURCE_CODE_URL.trim()

    val supportProviders: List<SupportProvider>
        get() = SupportProviderCatalog.forBuyMeACoffee(buyMeACoffeeUrl)
}

enum class SupportProviderType { BUY_ME_A_COFFEE, OTHER }

data class SupportProvider(
    val type: SupportProviderType,
    val title: String,
    val url: String,
    val enabled: Boolean,
)

object SupportProviderCatalog {
    fun forBuyMeACoffee(url: String): List<SupportProvider> = listOf(
        SupportProvider(
            type = SupportProviderType.BUY_ME_A_COFFEE,
            title = "Buy Me a Coffee",
            url = url.trim(),
            enabled = url.isNotBlank(),
        ),
    ).filter(SupportProvider::enabled)
}

data class CrewMember(
    val id: String,
    val name: String,
    @StringRes val roleRes: Int,
    @StringRes val bioRes: Int? = null,
    val imageRes: Int? = null,
)

object ProductCrew {
    val members = listOf(
        CrewMember(
            id = "kuku",
            name = "kuku",
            roleRes = R.string.about_crew_role_developer,
        ),
        CrewMember("yoyo", "yoyo", R.string.about_crew_role_captain),
        CrewMember("lili", "lili", R.string.about_crew_role_member),
    )
}
