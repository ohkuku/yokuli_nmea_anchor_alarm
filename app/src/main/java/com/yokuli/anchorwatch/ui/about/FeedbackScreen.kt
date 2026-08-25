package com.yokuli.anchorwatch.ui.about

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.R
import com.yokuli.anchorwatch.brand.ProductBrand
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

object FeedbackEmailComposer {
    private val addressPattern = Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)

    fun mailto(recipient: String, subject: String, body: String): String? {
        val address = recipient.trim()
        if (!addressPattern.matches(address)) return null
        val parameters = buildList {
            if (subject.isNotBlank()) add("subject=${encode(subject.trim())}")
            if (body.isNotBlank()) add("body=${encode(body.trim())}")
        }
        return "mailto:$address" + if (parameters.isEmpty()) "" else parameters.joinToString(separator = "&", prefix = "?")
    }

    fun requestBody(
        details: String,
        versionName: String,
        androidVersion: String,
        deviceModel: String,
    ): String = buildString {
        if (details.isNotBlank()) append(details.trim()).append("\n\n")
        append("---\n")
        append("Boat Watch version: ").append(versionName.ifBlank { "unknown" }).append('\n')
        append("Android version: ").append(androidVersion.ifBlank { "unknown" }).append('\n')
        append("Device model: ").append(deviceModel.ifBlank { "unknown" })
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val launcher = remember(context) { ExternalLinkLauncher(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val defaultSubject = aboutString(R.string.feedback_default_subject)
    val unavailable = aboutString(R.string.feedback_email_unavailable)
    var subject by rememberSaveable { mutableStateOf(defaultSubject) }
    var details by rememberSaveable { mutableStateOf("") }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("feedback_page")) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onBack, Modifier.testTag("feedback_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, aboutString(R.string.about_back))
                }
                Text(aboutString(R.string.feedback_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(aboutString(R.string.feedback_intro_title), style = MaterialTheme.typography.titleMedium)
                        Text(aboutString(R.string.feedback_intro_body), style = MaterialTheme.typography.bodyMedium)
                        Text(ProductBrand.contactEmail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it.take(160) },
                    label = { Text(aboutString(R.string.feedback_subject)) },
                    modifier = Modifier.fillMaxWidth().testTag("feedback_subject"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(8_000) },
                    label = { Text(aboutString(R.string.feedback_details)) },
                    supportingText = { Text(aboutString(R.string.feedback_details_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("feedback_details"),
                    minLines = 6,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PrivacyTip, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(aboutString(R.string.feedback_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        val body = FeedbackEmailComposer.requestBody(
                            details = details,
                            versionName = BuildConfig.VERSION_NAME,
                            androidVersion = Build.VERSION.RELEASE,
                            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" "),
                        )
                        val url = FeedbackEmailComposer.mailto(ProductBrand.contactEmail, subject, body)
                        if (url == null || launcher.open(url) != ExternalLinkResult.OPENED) {
                            scope.launch { snackbar.showSnackbar(unavailable) }
                        }
                    },
                    enabled = ProductBrand.contactEmail.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("feedback_open_email"),
                ) {
                    Icon(Icons.Default.Email, null)
                    Text(aboutString(R.string.feedback_open_email), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
