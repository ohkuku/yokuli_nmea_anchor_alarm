package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.brand.ProductBrand
import com.yokuli.anchorwatch.brand.ProductCrew
import com.yokuli.anchorwatch.brand.SupportProviderCatalog
import com.yokuli.anchorwatch.ui.about.ExternalLinkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductBrandTest {
    @Test fun productIdentityAndConfiguredChannelsAreExact() {
        assertEquals("Boat Watch", ProductBrand.PRODUCT_NAME)
        assertEquals("Developed aboard SV Yokuli", ProductBrand.MAKER_LINE)
        assertEquals("https://www.youtube.com/@yokuli_ocean_diary", ProductBrand.youtubeChannelUrl)
        assertEquals("https://buymeacoffee.com/ukus3yya8a", ProductBrand.buyMeACoffeeUrl)
        assertEquals("kuku.the.developer@gmail.com", ProductBrand.contactEmail)
        assertEquals("mailto:kuku.the.developer@gmail.com", ProductBrand.contactUrl)
    }

    @Test fun suppliedCrewIsRepresentedWithoutInventedImages() {
        assertEquals(listOf("kuku", "yoyo", "lili"), ProductCrew.members.map { it.name })
        assertEquals(R.string.about_crew_role_captain,ProductCrew.members.single{it.id=="yoyo"}.roleRes)
        assertTrue(ProductCrew.members.all { it.imageRes == null })
    }

    @Test fun anExplicitlyBlankSupportOverrideCreatesNoBrokenButton() {
        assertTrue(SupportProviderCatalog.forBuyMeACoffee("   ").isEmpty())
    }

    @Test fun configuredSupportCreatesOnlyTheOfficialExternalProvider() {
        val providers = SupportProviderCatalog.forBuyMeACoffee(" https://buymeacoffee.com/ukus3yya8a ")
        assertEquals(1, providers.size)
        assertEquals("Buy Me a Coffee", providers.single().title)
        assertEquals("https://buymeacoffee.com/ukus3yya8a", providers.single().url)
        assertTrue(providers.single().enabled)
    }

    @Test fun externalLinkPolicyOnlyAllowsExplicitSecureOrEmailDestinations() {
        assertTrue(ExternalLinkPolicy.isAllowed(ProductBrand.youtubeChannelUrl))
        assertTrue(ExternalLinkPolicy.isAllowed(ProductBrand.buyMeACoffeeUrl))
        assertTrue(ExternalLinkPolicy.isAllowed("mailto:crew@example.com"))
        assertFalse(ExternalLinkPolicy.isAllowed("http://example.com"))
        assertFalse(ExternalLinkPolicy.isAllowed("intent://example.com"))
        assertFalse(ExternalLinkPolicy.isAllowed("https://user@example.com/path"))
        assertFalse(ExternalLinkPolicy.isAllowed("javascript:alert(1)"))
    }
}
