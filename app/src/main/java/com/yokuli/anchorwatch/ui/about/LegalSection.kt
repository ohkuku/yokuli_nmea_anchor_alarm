package com.yokuli.anchorwatch.ui.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.R
import com.yokuli.anchorwatch.brand.ProductBrand

@Composable
internal fun LegalSection(open: (String) -> Unit) {
    AboutSection(aboutString(R.string.about_legal_eyebrow), aboutString(R.string.about_legal_title)) {
        Text(aboutString(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
        Text(aboutString(R.string.about_privacy_body), style = MaterialTheme.typography.bodySmall)
        Text(aboutString(R.string.about_regional_data), style = MaterialTheme.typography.bodySmall)
        Text(
            aboutString(R.string.about_license_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ProductBrand.privacyPolicyUrl.isNotBlank()) {
            OutlinedButton({ open(ProductBrand.privacyPolicyUrl) }) {
                Icon(Icons.Default.PrivacyTip, null)
                Spacer(Modifier.width(8.dp))
                Text(aboutString(R.string.about_privacy_action))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            }
        }
        if (ProductBrand.websiteUrl.isNotBlank()) {
            OutlinedButton({ open(ProductBrand.websiteUrl) }) {
                Icon(Icons.Default.Language, null)
                Spacer(Modifier.width(8.dp))
                Text(aboutString(R.string.about_website_action))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            }
        }
        if (ProductBrand.contactUrl.isNotBlank()) {
            OutlinedButton({ open(ProductBrand.contactUrl) }) {
                Icon(Icons.Default.Email, null)
                Spacer(Modifier.width(8.dp))
                Text(aboutString(R.string.about_contact_action))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            }
        }
        if (ProductBrand.sourceCodeUrl.isNotBlank()) {
            OutlinedButton({ open(ProductBrand.sourceCodeUrl) }) {
                Icon(Icons.Default.Code, null)
                Spacer(Modifier.width(8.dp))
                Text(aboutString(R.string.about_source_action))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            }
        }
    }
}
