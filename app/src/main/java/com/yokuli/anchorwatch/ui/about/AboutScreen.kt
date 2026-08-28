package com.yokuli.anchorwatch.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.R
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.brand.ProductBrand
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(onBack: () -> Unit, onFeedback: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val launcher = remember(context) { ExternalLinkLauncher(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkFailedText = aboutString(R.string.about_link_failed)
    var pendingSupportUrl by remember { mutableStateOf<String?>(null) }

    fun open(url: String) {
        if (launcher.open(url) != ExternalLinkResult.OPENED) {
            scope.launch { snackbar.showSnackbar(linkFailedText) }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { scaffoldPadding ->
        Column(Modifier.fillMaxSize().padding(scaffoldPadding).testTag("about_page")) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onBack, Modifier.testTag("about_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, aboutString(R.string.about_back))
                }
                Text(aboutString(R.string.about_page_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("about_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { AboutHero() }
                item { StorySection() }
                item(key = "about_crew") { CrewSection() }
                item { YouTubeSection(::open) }
                item(key = "about_support") { SupportSection { pendingSupportUrl = it } }
                item { RoadmapSection() }
                if (ProductBrand.contactEmail.isNotBlank()) {
                    item {
                        AboutSection(aboutString(R.string.about_feedback_eyebrow), aboutString(R.string.about_feedback_title)) {
                            Text(aboutString(R.string.about_feedback_body), style = MaterialTheme.typography.bodySmall)
                            Button(onFeedback, Modifier.fillMaxWidth().testTag("about_feedback")) {
                                Text(aboutString(R.string.feedback_open_page))
                            }
                        }
                    }
                }
                item { LegalSection(::open) }
                item { BuildIdentitySection() }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    pendingSupportUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingSupportUrl = null },
            title = { Text(aboutString(R.string.about_support_confirm_title)) },
            text = { Text(aboutString(R.string.about_support_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { pendingSupportUrl = null; open(url) },
                    modifier = Modifier.testTag("about_support_continue"),
                ) { Text(aboutString(R.string.about_continue)) }
            },
            dismissButton = { TextButton({ pendingSupportUrl = null }) { Text(aboutString(R.string.about_cancel)) } },
        )
    }
}

@Composable
private fun BuildIdentitySection(){
    AboutSection("BUILD IDENTITY","Verifiable build"){
        Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_CHANNEL}",fontWeight=FontWeight.SemiBold)
        Text("Git ${BuildConfig.BUILD_GIT_SHA.take(12)}${if(BuildConfig.BUILD_GIT_DIRTY)" · DIRTY" else " · clean"}",style=MaterialTheme.typography.bodySmall)
        Text("${BuildConfig.BUILD_GIT_BRANCH} · ${BuildConfig.BUILD_TIMESTAMP_UTC}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("CI ${BuildConfig.BUILD_IN_CI} · DB schema ${BuildConfig.DATABASE_SCHEMA_VERSION}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutHero() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painterResource(R.drawable.anchor_watch_logo),
                contentDescription = aboutString(R.string.about_logo_description),
                modifier = Modifier.size(150.dp).clip(RoundedCornerShape(28.dp)),
                contentScale = ContentScale.Crop,
            )
            Text(aboutString(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(aboutString(R.string.about_maker_line), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(aboutString(R.string.about_core_line), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(
                aboutString(R.string.about_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
