import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun brandConfig(key: String, defaultValue: String = ""): String {
    if (local.containsKey(key)) return local.getProperty(key).orEmpty().trim()
    System.getenv(key)?.let { return it.trim() }
    providers.gradleProperty(key).orNull?.let { return it.trim() }
    return defaultValue
}
val yokuliYoutubeUrl = brandConfig("YOKULI_YOUTUBE_URL", "https://www.youtube.com/@yokuli_ocean_diary")
val yokuliBuyMeACoffeeUrl = brandConfig("YOKULI_BUYMEACOFFEE_URL", "https://buymeacoffee.com/ukus3yya8a")
val yokuliWebsiteUrl = brandConfig("YOKULI_WEBSITE_URL")
val yokuliContactEmail = brandConfig("YOKULI_CONTACT_EMAIL", "kuku.the.developer@gmail.com")
val yokuliPrivacyUrl = brandConfig("YOKULI_PRIVACY_URL")
val yokuliSourceCodeUrl = brandConfig("YOKULI_SOURCE_CODE_URL", "https://github.com/ohkuku/yokuli_nmea_anchor_alarm")
val mapsApiKey = local.getProperty("MAPS_API_KEY")
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("MAPS_API_KEY")?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("YOKULI_MAPS_API_KEY").orNull.orEmpty()
val linzApiKey = local.getProperty("LINZ_API_KEY")?.takeIf { it.isNotBlank() }
    ?: System.getenv("LINZ_API_KEY")?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("LINZ_API_KEY").orNull?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("YOKULI_LINZ_API_KEY").orNull.orEmpty()
val linzHydroTileTemplateOverride = local.getProperty("LINZ_HYDRO_TILE_TEMPLATE")?.takeIf { it.isNotBlank() }
    ?: System.getenv("LINZ_HYDRO_TILE_TEMPLATE")?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("LINZ_HYDRO_TILE_TEMPLATE").orNull?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("YOKULI_LINZ_HYDRO_TILE_TEMPLATE").orNull?.takeIf { it.isNotBlank() }
val linzChartSetTemplates = if (linzHydroTileTemplateOverride != null) {
    listOf(linzHydroTileTemplateOverride)
} else if (linzApiKey.isNotBlank()) {
    // Official LINZ hydrographic raster-chart sets. A set id remains stable as
    // individual chart revisions are published, unlike a single layer id.
    listOf(4758, 4759, 4767).map { setId ->
        "https://tiles-a.data-cdn.linz.govt.nz/services;key=$linzApiKey/tiles/v4/set=$setId/EPSG:3857/{z}/{x}/{y}.png"
    }
} else emptyList()
val linzHydroConfigured = linzChartSetTemplates.isNotEmpty() && linzChartSetTemplates.all { template ->
    template.startsWith("https://") && listOf("{z}", "{x}", "{y}").all(template::contains)
}
// Public S-57-derived layers, ordered from the most detailed chart scale to
// the least detailed. The key stays build-time/private; layer IDs are public.
val linzSoundingLayerIds = "50858|50866|50506|50418|51612"
val linzDepthAreaLayerIds = "50671|50553|50447|50852|51639"
val linzDepthContourLayerIds = "50672|50554|50448|50849|51638"
fun String.asBuildConfigString() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseSigningAvailable = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "com.yokuli.anchorwatch"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yokuli.anchorwatch"
        minSdk = 28
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("boolean", "MAPS_CONFIGURED", mapsApiKey.isNotBlank().toString())
        buildConfigField("String", "LINZ_HYDRO_TILE_TEMPLATES", linzChartSetTemplates.joinToString("|").asBuildConfigString())
        buildConfigField("boolean", "LINZ_HYDRO_CONFIGURED", linzHydroConfigured.toString())
        buildConfigField("String", "LINZ_API_KEY", linzApiKey.asBuildConfigString())
        buildConfigField("String", "LINZ_SOUNDING_LAYER_IDS", linzSoundingLayerIds.asBuildConfigString())
        buildConfigField("String", "LINZ_DEPTH_AREA_LAYER_IDS", linzDepthAreaLayerIds.asBuildConfigString())
        buildConfigField("String", "LINZ_DEPTH_CONTOUR_LAYER_IDS", linzDepthContourLayerIds.asBuildConfigString())
        buildConfigField("String", "YOKULI_YOUTUBE_URL", yokuliYoutubeUrl.asBuildConfigString())
        buildConfigField("String", "YOKULI_BUYMEACOFFEE_URL", yokuliBuyMeACoffeeUrl.asBuildConfigString())
        buildConfigField("String", "YOKULI_WEBSITE_URL", yokuliWebsiteUrl.asBuildConfigString())
        buildConfigField("String", "YOKULI_CONTACT_EMAIL", yokuliContactEmail.asBuildConfigString())
        buildConfigField("String", "YOKULI_PRIVACY_URL", yokuliPrivacyUrl.asBuildConfigString())
        buildConfigField("String", "YOKULI_SOURCE_CODE_URL", yokuliSourceCodeUrl.asBuildConfigString())
    }
    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    // Languages are switched inside the app; keep every packaged translation
    // available even when distributing an Android App Bundle.
    bundle { language { enableSplit = false } }
    testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    kotlinOptions { jvmTarget = "17" }
    sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation)
    implementation(libs.maps.compose)
    implementation(libs.play.location)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.gson)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
