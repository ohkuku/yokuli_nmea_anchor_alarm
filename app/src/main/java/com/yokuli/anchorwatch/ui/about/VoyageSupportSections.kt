package com.yokuli.anchorwatch.ui.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.R
import com.yokuli.anchorwatch.brand.ProductBrand

@Composable
internal fun YouTubeSection(open: (String) -> Unit) {
    AboutSection(aboutString(R.string.about_voyage_eyebrow), aboutString(R.string.about_voyage_title)) {
        Text(aboutString(R.string.about_voyage_body), style = MaterialTheme.typography.bodyMedium)
        if (ProductBrand.youtubeChannelUrl.isNotBlank()) {
            OutlinedButton(
                onClick = { open(ProductBrand.youtubeChannelUrl) },
                modifier = Modifier.testTag("about_youtube"),
            ) {
                Icon(Icons.Default.PlayCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(aboutString(R.string.about_youtube_action))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            }
        }
    }
}

@Composable
internal fun SupportSection(requestSupport: (String) -> Unit) {
    AboutSection(aboutString(R.string.about_support_eyebrow), aboutString(R.string.about_support_title)) {
        Text(aboutString(R.string.about_support_body), style = MaterialTheme.typography.bodyMedium)
        val provider = ProductBrand.supportProviders.firstOrNull()
        if (provider != null) {
            Button(
                onClick = { requestSupport(provider.url) },
                modifier = Modifier.testTag("about_buy_me_a_coffee"),
            ) {
                Icon(Icons.Default.Coffee, null)
                Spacer(Modifier.width(8.dp))
                Text(aboutString(R.string.about_support_action))
            }
        } else {
            Text(
                aboutString(R.string.about_support_unconfigured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
