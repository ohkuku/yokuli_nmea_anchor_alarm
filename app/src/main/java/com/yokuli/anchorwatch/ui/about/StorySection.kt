package com.yokuli.anchorwatch.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yokuli.anchorwatch.R

@Composable
internal fun StorySection() {
    AboutSection(
        eyebrow = aboutString(R.string.about_story_eyebrow),
        title = aboutString(R.string.about_story_title),
        modifier = Modifier.testTag("about_story"),
    ) {
        Text(
            aboutString(R.string.about_story_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Unspecified,
        )
    }
}
