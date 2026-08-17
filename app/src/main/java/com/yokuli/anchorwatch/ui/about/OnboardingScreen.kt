package com.yokuli.anchorwatch.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.yokuli.anchorwatch.LocalAppLanguage
import com.yokuli.anchorwatch.LanguagePickerDialog
import com.yokuli.anchorwatch.brand.ProductBrand
import com.yokuli.anchorwatch.brand.ProductCrew
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.localization.nativeName
import kotlinx.coroutines.launch

/** The calm, non-commercial last page of first-run onboarding. */
@Composable
fun OnboardingMakerScreen(onContinue: () -> Unit, onLanguageChange: (AppLanguage) -> Unit) {
    val context = LocalContext.current
    val launcher = remember(context) { ExternalLinkLauncher(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkFailed = aboutString(R.string.about_link_failed)
    var showCrew by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 22.dp, vertical = 28.dp)).testTag("onboarding_maker"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth()){
                OutlinedButton(
                    onClick={showLanguagePicker=true},
                    modifier=Modifier.align(Alignment.CenterEnd).testTag("onboarding_language"),
                ){
                    Icon(Icons.Default.Language,null)
                    Spacer(Modifier.size(6.dp))
                    Text(LocalAppLanguage.current.nativeName)
                }
            }
            Image(
                painterResource(R.drawable.anchor_watch_logo),
                aboutString(R.string.about_logo_description),
                Modifier.size(170.dp).clip(RoundedCornerShape(32.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(18.dp))
            Text(aboutString(R.string.about_maker_line), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                aboutString(R.string.onboarding_maker_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton({ showCrew = !showCrew }, Modifier.fillMaxWidth().testTag("onboarding_meet_crew")) {
                Icon(Icons.Default.Groups, null)
                Spacer(Modifier.size(8.dp))
                Text(aboutString(R.string.onboarding_meet_crew))
            }
            if (showCrew) {
                Card(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("onboarding_crew")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProductCrew.members.forEach { member ->
                            Text("${member.name} · ${aboutString(member.roleRes)}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (ProductBrand.youtubeChannelUrl.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        if (launcher.open(ProductBrand.youtubeChannelUrl) != ExternalLinkResult.OPENED) {
                            scope.launch { snackbar.showSnackbar(linkFailed) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("onboarding_youtube"),
                ) {
                    Icon(Icons.Default.PlayCircle, null)
                    Spacer(Modifier.size(8.dp))
                    Text(aboutString(R.string.onboarding_watch_yokuli))
                }
            }
            Button(onContinue, Modifier.fillMaxWidth().padding(top = 22.dp).testTag("onboarding_continue")) {
                Text(aboutString(R.string.onboarding_continue))
            }
            Text(
                aboutString(R.string.onboarding_no_support),
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    if(showLanguagePicker)LanguagePickerDialog(LocalAppLanguage.current,{showLanguagePicker=false}){language->onLanguageChange(language);showLanguagePicker=false}
}
