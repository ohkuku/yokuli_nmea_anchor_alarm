package com.yokuli.anchorwatch.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.yokuli.anchorwatch.R

@Composable
internal fun RoadmapSection() {
    AboutSection(aboutString(R.string.about_roadmap_eyebrow), aboutString(R.string.about_roadmap_title)) {
        Text(aboutString(R.string.about_roadmap_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            aboutString(R.string.about_roadmap_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
