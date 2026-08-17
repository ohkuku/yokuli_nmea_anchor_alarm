package com.yokuli.anchorwatch

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.AlarmUiRepository
import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.DepthSampleEntity
import com.yokuli.anchorwatch.data.database.SonarDao
import com.yokuli.anchorwatch.data.database.SonarSurveyEntity
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.data.preferences.AppSettings
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.data.sharing.NmeaSharingServer
import com.yokuli.anchorwatch.data.sharing.SharingServerState
import com.yokuli.anchorwatch.data.sonar.SonarSurveyRecorder
import com.yokuli.anchorwatch.di.AnchorWatchEntryPoint
import com.yokuli.anchorwatch.domain.model.AnchorCenterStatus
import com.yokuli.anchorwatch.domain.model.AnchorCenterSource
import com.yokuli.anchorwatch.domain.model.AnchorPositionMode
import com.yokuli.anchorwatch.domain.model.CandidateDecision
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import com.yokuli.anchorwatch.domain.model.AlarmState
import com.yokuli.anchorwatch.domain.model.AlarmType
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.AnchorPlacementMode
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.AcceptedPositionRepository
import com.yokuli.anchorwatch.map.MapRuntimePolicy
import com.yokuli.anchorwatch.service.AnchorForegroundService
import com.yokuli.anchorwatch.runtime.RuntimeDiagnosticsRepository
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import dagger.hilt.android.EntryPointAccessors
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorSafetyFlowTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var dao: AnchorDao
    private lateinit var preferences: SettingsRepository
    private lateinit var navigation: NavigationRepository
    private lateinit var alarmUi:AlarmUiRepository
    private lateinit var sharingServer:NmeaSharingServer
    private lateinit var sonarDao:SonarDao
    private lateinit var sonarRecorder:SonarSurveyRecorder
    private lateinit var acceptedPosition:AcceptedPositionRepository
    private lateinit var runtimeDiagnostics:RuntimeDiagnosticsRepository

    @Before fun prepare() = runBlocking<Unit> {
        MapRuntimePolicy.renderGoogleEngine=false
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, AnchorWatchEntryPoint::class.java)
        dao = entry.dao();sonarDao=entry.sonarDao();sonarRecorder=entry.sonarRecorder();acceptedPosition=entry.acceptedPosition();runtimeDiagnostics=entry.runtimeDiagnostics();preferences = entry.preferences(); navigation = entry.navigation();alarmUi=entry.alarmUi();sharingServer=entry.sharingServer()
        context.stopService(Intent(context, AnchorForegroundService::class.java))
        delay(250)
        navigation.disconnectAll()
        dao.active()?.let { dao.updateSession(it.copy(active = false, endedAt = System.currentTimeMillis())) }
        sonarDao.active()?.let{sonarDao.finish(it.id,System.currentTimeMillis())}
        acceptedPosition.unlockSource(null)
        acceptedPosition.selectSource(GpsDataSource.SYSTEM)
        preferences.save(AppSettings(gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 2))
    }

    @After fun cleanup() = runBlocking<Unit> {
        MapRuntimePolicy.renderGoogleEngine=true
        if(::context.isInitialized)context.stopService(Intent(context, AnchorForegroundService::class.java))
        if(::navigation.isInitialized)navigation.disconnectAll()
        if(::dao.isInitialized)dao.active()?.let { dao.updateSession(it.copy(active = false, endedAt = System.currentTimeMillis())) }
        if(::sonarDao.isInitialized)sonarDao.active()?.let{sonarDao.finish(it.id,System.currentTimeMillis())}
    }

    @Test fun activeNmeaWatchDisconnectDialogDoesNotOfferUnsafeHotSwitch() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            connectAndAwaitFix(profile)
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Anchor watch is locked to NMEA").assertExists()
                compose.onNodeWithText("Pause watch & disconnect").assertExists()
                compose.onNodeWithText("Switch to System GPS & disconnect").assertDoesNotExist()
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(sessionId, dao.active()?.id)
                assertEquals(GpsDataSource.NMEA,preferences.settings.first().gpsDataSource)
            }
        }
    }

    @Test fun activeWatchCanBePausedDisconnectedAndResumedAsTheSameSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server, autoReconnect = true)
            preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA))
            connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Pause watch & disconnect").performClick()
                withTimeout(5_000) { while (dao.active()?.paused != true) delay(50) }
                withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.DISCONNECTED } }
                assertEquals(sessionId,dao.active()?.id)
                val pointsAtPause=dao.points(sessionId).first().size;delay(1_500);assertEquals(pointsAtPause,dao.points(sessionId).first().size)
                ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.RESUME_WATCH))
                val resumed=withTimeoutOrNull(20_000){while(dao.active()?.paused!=false)delay(50);dao.active()}
                val latestFix=navigation.fix.value
                assertNotNull(
                    "Watch did not resume; session=${dao.active()}; connection=${navigation.connectionState.value}; " +
                        "acceptedSockets=${server.accepted.get()}; fix=$latestFix; " +
                        "fixAge=${latestFix?.let{android.os.SystemClock.elapsedRealtime()-it.receivedElapsedRealtime}}; " +
                        "events=${dao.events(sessionId).first().takeLast(8)}",
                    resumed,
                )
                assertEquals(sessionId,dao.active()?.id)
                assertEquals(-36.8485,dao.active()!!.anchorLatitude,0.000001)
            }
        }
    }

    @Test fun liftAnchorPermanentlyClosesTheSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch();startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.LIFT_ANCHOR))
            withTimeout(10_000){while(dao.active()!=null)delay(50)}
            val closed=dao.sessions().first().first{it.id==sessionId}
            assertTrue(!closed.active&&closed.endedAt!=null)
            assertTrue(dao.events(sessionId).first().any{it.type=="ANCHOR_LIFTED"})
        }
    }

    @Test fun cancellingDisconnectLeavesWatchAndNmeaConnectionUntouched() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            connectAndAwaitFix(liveProfile(server, autoReconnect = true))
            val sessionId = seedActiveWatch()
            val acceptedBefore = server.accepted.get()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                openDisconnectDecision()
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithText("Anchor watch is locked to NMEA").assertDoesNotExist()
                assertEquals(sessionId, dao.active()?.id)
                assertEquals(NmeaConnectionState.CONNECTED, navigation.connectionState.value)
                assertEquals(acceptedBefore, server.accepted.get())
            }
        }
    }

    @Test fun satelliteLayerCanBeSelectedBeforeNmeaConnects() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA,mapType=1))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("map_layers").fetchSemanticsNode();true}.getOrDefault(false)}
            compose.onNodeWithTag("map_layers").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("Satellite").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Satellite").performClick()
            assertEquals(2,withTimeout(5_000){preferences.settings.first{it.mapType==2}}.mapType)
        }
    }

    @Test fun mapLayerSheetHasThreeBaseStylesAndOnlyLocalDepthOpacity() = runBlocking<Unit> {
        preferences.save(AppSettings(mapType=1,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("map_layers").fetchSemanticsNode();true}.getOrDefault(false)}
            compose.onNodeWithTag("map_layers").performClick()
            compose.onNodeWithTag("map_style_map").assertExists()
            compose.onNodeWithTag("map_style_satellite").assertExists()
            compose.onNodeWithTag("map_style_nautical").assertExists()
            compose.onNodeWithTag("local_depth_section").assertExists()
            compose.onNodeWithTag("local_depth_toggle").assertExists()
            compose.onNodeWithTag("local_depth_provider").assertExists()
            compose.onAllNodesWithTag("sonar_opacity").assertCountEquals(0)
            compose.onAllNodesWithTag("offline_map_opacity").assertCountEquals(0)
            compose.onAllNodesWithTag("base_map_opacity").assertCountEquals(0)
            compose.onNodeWithTag("map_style_nautical").performClick()
            compose.onNodeWithText("Nautical map is a visual aid").assertExists()
            compose.onNodeWithText("I understand · Use Nautical").performClick()
            val saved=withTimeout(5_000){preferences.settings.first{it.mapType==3&&it.nauticalDisclaimerAccepted}}
            assertEquals(3,saved.mapType)
        }
    }

    @Test fun realSonarStartNeedsFreshConnectedNmeaButDemoIsTheExplicitException() = runBlocking<Unit> {
        navigation.disconnectAll();preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,demoMode=false,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Data").performClick();compose.onNodeWithText("Sonar").performClick()
            compose.onNodeWithText("Start sonar survey").assertIsNotEnabled()
            compose.onNodeWithText("Connect the NMEA server before starting a real sonar survey.").assertExists()
        }
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,demoMode=true,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Data").performClick();compose.onNodeWithText("Sonar").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("Demo survey uses continuous simulated sonar",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Start sonar survey").assertIsEnabled()
            compose.onNodeWithText("Demo survey uses continuous simulated sonar",substring=true).assertExists()
        }
    }

    @Test fun mapCanSwitchBetweenLockedFollowAndFreeBrowsing() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("map_lock_toggle").assertExists()
            compose.onNodeWithContentDescription("Auto-return to boat · pan and zoom remain available").assertExists()
            compose.onNodeWithTag("map_lock_toggle").performClick()
            compose.onNodeWithContentDescription("Free map browsing").assertExists()
            compose.onNodeWithTag("map_recenter").assertExists().performClick()
        }
    }

    @Test fun successfulSaveAndConnectMakesNmeaTheDefaultGpsSource() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile=liveProfile(server,true),gpsDataSource=GpsDataSource.SYSTEM,demoMode=false))
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Data").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithText("Test, save & connect").fetchSemanticsNodes().isNotEmpty()}
                compose.waitUntil(5_000){runCatching{compose.onNodeWithText("Test, save & connect").assertIsEnabled();true}.getOrDefault(false)}
                compose.onNodeWithText("127.0.0.1").assertExists()
                compose.onNodeWithText(server.port.toString()).assertExists()
                compose.onNodeWithText("Test, save & connect").performScrollTo().performClick()
                val selected=withTimeoutOrNull(15_000){preferences.settings.first{it.gpsDataSource==GpsDataSource.NMEA}}
                val attemptNode=compose.onAllNodesWithTag("nmea_connection_attempt",useUnmergedTree=true).fetchSemanticsNodes().firstOrNull()
                val attemptText=attemptNode?.let{node->runCatching{node.config[SemanticsProperties.Text].joinToString()}.getOrNull()}
                assertNotNull(
                    "Save/connect did not select NMEA; acceptedSockets=${server.accepted.get()}, " +
                        "connection=${navigation.connectionState.value}, attempt=$attemptText, settings=${preferences.settings.first()}" +
                        "\nsemantics=${compose.onRoot(useUnmergedTree=true).printToString(maxDepth=12)}",
                    selected,
                )
                requireNotNull(selected)
                assertEquals(GpsDataSource.NMEA,selected.gpsDataSource)
                assertTrue(!selected.demoMode)
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_list").performScrollToIndex(3)
                compose.onNodeWithTag("settings_positioning").performClick()
                compose.onNodeWithTag("gps_source_nmea").assertIsEnabled()
            }
        }
    }

    @Test fun disconnectedNmeaSourceIsDisabledInSettings() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(3)
            compose.onNodeWithTag("settings_positioning").performClick()
            compose.onNodeWithTag("gps_source_nmea").assertIsNotEnabled()
            compose.onNodeWithText("Connect the NMEA server before selecting this source.").assertExists()
            assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
        }
    }

    @Test fun demoSourceIsForcedBackToSystemWhenDeveloperModeIsOff() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.DEMO,demoMode=true))
        assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.DEMO,demoMode=false))
        val restored=withTimeout(5_000){preferences.settings.first{!it.demoMode&&it.gpsDataSource==GpsDataSource.SYSTEM}}
        assertEquals(GpsDataSource.SYSTEM,restored.gpsDataSource)
    }

    @Test fun demoModeForcesAndLocksDemoSourceEvenWhenNmeaConnects() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile=liveProfile(server,true),gpsDataSource=GpsDataSource.SYSTEM,demoMode=true))
            assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Data").performClick()
                compose.waitUntil(5_000){compose.onAllNodesWithText("Test, save & connect").fetchSemanticsNodes().isNotEmpty()}
                compose.waitUntil(5_000){runCatching{compose.onNodeWithText("Test, save & connect").assertIsEnabled();true}.getOrDefault(false)}
                compose.onNodeWithText("Test, save & connect").performScrollTo().performClick()
                withTimeout(15_000){navigation.connectionState.first{it==NmeaConnectionState.CONNECTED}}
                assertEquals(GpsDataSource.DEMO,preferences.settings.first().gpsDataSource)
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("settings_list").performScrollToIndex(3)
                compose.onNodeWithTag("settings_positioning").performClick()
                compose.onNodeWithTag("gps_source_demo").assertIsNotEnabled()
                compose.onNodeWithTag("gps_source_system").assertDoesNotExist()
                compose.onNodeWithTag("gps_source_nmea").assertDoesNotExist()
            }
        }
    }

    @Test fun proxyButtonExplainsWhyItCannotStart() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Data").performClick()
            compose.onNodeWithTag("nmea_runtime_list").performScrollToIndex(3)
            compose.onNodeWithText("Enable global GPS proxy").performClick()
            compose.onNodeWithText("Connect to the NMEA source first.").assertExists()
            compose.onNodeWithText("Select mock location app → Anchor Watch.",substring=true).assertExists()
        }
    }

    @Test fun languageCanSwitchToChineseAndPersists() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            compose.onNodeWithTag("settings_language").performClick()
            compose.onNodeWithTag("language_zh").performClick()
            compose.waitUntil(5_000){compose.onAllNodesWithText("锚警").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("锚警").assertExists()
            assertEquals(AppLanguage.SIMPLIFIED_CHINESE,withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.SIMPLIFIED_CHINESE}}.appLanguage)
        }
    }

    @Test fun alarmSoundUiOnlyOffersAlarmAndCustomAndMigratesLegacyChoices() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.NMEA,appLanguage=AppLanguage.ENGLISH,alarmSound=AlarmSound.SYSTEM_NOTIFICATION))
        assertEquals(AlarmSound.SYSTEM_ALARM,preferences.settings.first().alarmSound)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(1)
            compose.onNodeWithTag("settings_alarm").performClick()
            compose.onNodeWithTag("alarm_sound_SYSTEM_ALARM").assertExists()
            compose.onNodeWithTag("alarm_sound_CUSTOM").assertExists()
            compose.onNodeWithTag("alarm_sound_SYSTEM_NOTIFICATION").assertDoesNotExist()
            compose.onNodeWithTag("alarm_sound_SYSTEM_RINGTONE").assertDoesNotExist()
        }
        val custom="content://com.yokuli.anchorwatch.test/alarm.ogg"
        preferences.save(preferences.settings.first().copy(alarmSound=AlarmSound.CUSTOM,customAlarmSoundUri=custom))
        val restored=withTimeout(15_000){preferences.settings.first{it.alarmSound==AlarmSound.CUSTOM}}
        assertEquals(custom,restored.customAlarmSoundUri)
    }

    @Test fun settingsRootIsShortAndEveryConfigurationSectionOpens() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            val pages=listOf(
                "settings_alarm" to 1,
                "settings_vessel" to 2,
                "settings_depth_sounder" to 2,
                "settings_positioning" to 3,
                "settings_map_depth" to 3,
                "settings_background" to 4,
                "settings_storage_support" to 4,
                "settings_developer" to 5,
            )
            pages.forEach{(tag,index)->
                compose.onNodeWithTag("settings_list").performScrollToIndex(index)
                compose.onNodeWithTag(tag).performClick()
                compose.onNodeWithContentDescription("Back").assertExists()
                compose.onNodeWithContentDescription("Back").performClick()
            }
            compose.onNodeWithTag("settings_list").performScrollToIndex(4)
            compose.onNodeWithTag("settings_language").performClick()
            compose.onNodeWithTag("language_en").assertExists().performClick()
            compose.onNodeWithText("Enable global GPS proxy").assertDoesNotExist()
        }
    }

    @Test fun aboutPageShowsRealMakerCrewAndOptionalSupportConfirmation() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_support_card").assertExists().performClick()
            compose.onNodeWithText("Support is optional and does not unlock app features.",substring=true).assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(6)
            compose.onNodeWithTag("settings_about").performClick()
            compose.onNodeWithTag("about_page").assertExists()
            compose.onNodeWithText("Made aboard Yokuli").assertExists()
            compose.onNodeWithText("kuku").assertExists()
            compose.onNodeWithText("yoyo").assertExists()
            compose.onNodeWithText("lili").assertExists()
            compose.onNodeWithTag("about_buy_me_a_coffee").performScrollTo().performClick()
            compose.onNodeWithText("Support is optional and does not unlock app features.",substring=true).assertExists()
            compose.onNodeWithTag("about_support_continue").assertExists()
        }
    }

    @Test fun firstRunMakerPageHasCrewAndVoyageButNeverAsksForMoney() = runBlocking<Unit> {
        preferences.save(AppSettings(onboardingCompleted=false,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("onboarding_maker").assertExists()
            compose.onNodeWithText("Made aboard Yokuli").assertExists()
            compose.onNodeWithTag("onboarding_language").assertExists().performClick()
            withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.SIMPLIFIED_CHINESE}}
            compose.onNodeWithText("诞生于 Yokuli 船上").assertExists()
            compose.onNodeWithTag("onboarding_language").performClick()
            withTimeout(5_000){preferences.settings.first{it.appLanguage==AppLanguage.ENGLISH}}
            compose.onNodeWithTag("about_buy_me_a_coffee").assertDoesNotExist()
            compose.onNodeWithTag("onboarding_meet_crew").performClick()
            compose.onNodeWithTag("onboarding_crew").assertExists()
            compose.onNodeWithText("kuku",substring=true).assertExists()
            compose.onNodeWithText("yoyo",substring=true).assertExists()
            compose.onNodeWithText("Captain",substring=true).assertExists()
            compose.onNodeWithText("lili",substring=true).assertExists()
            compose.onNodeWithTag("onboarding_continue").performClick()
            compose.onNodeWithTag("nav_watch").assertExists()
            assertTrue(preferences.settings.first().onboardingCompleted)
        }
    }

    @Test fun feedbackPageBuildsAnEditableEmailRequestWithoutSendingInsideTheApp() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToIndex(6)
            compose.onNodeWithTag("settings_feedback").performClick()
            compose.onNodeWithTag("feedback_page").assertExists()
            compose.onNodeWithText("kuku.the.developer@gmail.com").assertExists()
            compose.onNodeWithTag("feedback_subject").assertExists()
            compose.onNodeWithTag("feedback_details").assertExists()
            compose.onNodeWithTag("feedback_open_email").assertIsEnabled()
            compose.onNodeWithText("Anchor Watch does not send or track the message itself.",substring=true).assertExists()
        }
    }

    @Test fun stoppingAlarmTestCannotBeOvertakenByAPendingStartCommand() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        repeat(4){
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.TEST_ALARM))
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_ALARM_TEST))
        }
        delay(1_000)
        assertEquals(AlarmState.IDLE,alarmUi.snapshot.value.state)
        assertTrue(alarmUi.snapshot.value.type!=AlarmType.ALARM_TEST)
    }

    @Test fun alarmTestRemainsGloballyActiveUntilUserStopsItAndUsesNonModalControls() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM,appLanguage=AppLanguage.ENGLISH))
        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.TEST_ALARM))
            withTimeout(5_000){alarmUi.snapshot.first{snapshot->snapshot.state==AlarmState.ALARM&&snapshot.type==AlarmType.ALARM_TEST}}
            compose.onNodeWithTag("alarm_test_banner").assertExists()
            compose.onNodeWithTag("confirm_alarm_audible_banner").assertIsEnabled()
            compose.onNodeWithTag("stop_alarm_test_banner").assertIsEnabled()
            delay(1_500)
            assertEquals(AlarmType.ALARM_TEST,alarmUi.snapshot.value.type)
            compose.onNodeWithTag("stop_alarm_test_banner").performClick()
            withTimeout(5_000){alarmUi.snapshot.first{snapshot->snapshot.state==AlarmState.IDLE&&snapshot.type!=AlarmType.ALARM_TEST}}
        }
    }

    @Test fun disablingPhoneHeadingDuringLearningKeepsHistoricalEvidence() = runBlocking<Unit> {
        val sessionId=seedPhoneHeadingLearningWatch()
        dao.insertPoint(com.yokuli.anchorwatch.data.database.TrackPointEntity(sessionId=sessionId,timestamp=System.currentTimeMillis(),latitude=-36.8485,longitude=174.7633,distanceFromAnchor=0.0,sog=0.1,cog=180.0,heading=123.0,hdop=1.0,headingMeasured=true,headingSampleSequence=17,positionSource=GpsDataSource.NMEA.name,headingSource=HeadingSource.PHONE.name,headingQuality=HeadingQuality.STABLE.name,headingEpoch=4))
        ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java))
        delay(400)
        ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_PHONE_HEADING).putExtra("enabled",false))
        withTimeout(5_000){dao.sessions().first{sessions->sessions.firstOrNull{it.id==sessionId}?.usePhoneHeading==false}}
        val historical=dao.points(sessionId).first().single()
        assertEquals(123.0,historical.heading?:Double.NaN,0.0)
        assertEquals(HeadingSource.PHONE.name,historical.headingSource)
        withTimeout(5_000){dao.events(sessionId).first{events->events.any{it.type=="PHONE_HEADING_DISABLED"&&it.detail=="HISTORICAL_EVIDENCE_RETAINED"}}}
    }

    @Test fun passiveLossKeepsWatchArmedAndRecordsImmediateAndTimedAlarms() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            preferences.save(AppSettings(profile = liveProfile(server, autoReconnect = false), gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 1))
            connectAndAwaitFix(liveProfile(server, autoReconnect = false))
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                server.closeConnections()
                withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.DISCONNECTED } }
                val lost = withTimeout(5_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "NMEA_CONNECTION_LOST" } } }
                assertTrue(lost.any { it.type == "NMEA_CONNECTION_LOST" })
                val alarm = withTimeout(10_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "ALARM_TRIGGERED" && it.detail == "GPS_DATA_LOST" } } }
                assertTrue(alarm.any { it.type == "ALARM_TRIGGERED" && it.detail == "GPS_DATA_LOST" })
                assertEquals(sessionId, dao.active()?.id)
            }
        }
    }

    @Test fun automaticReconnectRecordsRecoveryWithoutStoppingWatch() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            preferences.save(AppSettings(profile = profile, gpsDataSource = GpsDataSource.NMEA, gpsLossSeconds = 5))
            connectAndAwaitFix(profile)
            val sessionId = seedActiveWatch()
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                val acceptedBefore = server.accepted.get()
                server.closeConnections()
                withTimeout(8_000) { while (server.accepted.get() <= acceptedBefore) delay(50) }
                val events = withTimeout(8_000) { dao.events(sessionId).first { rows -> rows.any { it.type == "NMEA_CONNECTION_LOST" } && rows.any { it.type == "NMEA_CONNECTION_RESTORED" } } }
                assertTrue(events.any { it.type == "NMEA_CONNECTION_LOST" })
                assertTrue(events.any { it.type == "NMEA_CONNECTION_RESTORED" })
                assertEquals(sessionId, dao.active()?.id)
            }
        }
    }

    @Test fun armingDoesNotReplaceOrDropTheExistingNmeaConnection() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile = liveProfile(server, autoReconnect = true)
            preferences.save(AppSettings(profile = profile, gpsDataSource = GpsDataSource.NMEA))
            connectAndAwaitFix(profile)
            val acceptedBefore = server.accepted.get()
            ActivityScenario.launch(MainActivity::class.java).use {
                ContextCompat.startForegroundService(context, Intent(context, AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                    .putExtra("lat", -36.8485).putExtra("lon", 174.7633).putExtra("rode", 0.0)
                    .putExtra("depth", Double.NaN).putExtra("warning", 40.0).putExtra("alarm", 50.0))
                withTimeout(5_000) { while (dao.active() == null) delay(50) }
                delay(750)
                assertEquals(acceptedBefore, server.accepted.get())
                assertEquals(NmeaConnectionState.CONNECTED, navigation.connectionState.value)
            }
        }
    }

    @Test fun backdownStartsWithTemporaryBoundaryAndProvisionalCentre() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                .putExtra("lat",-36.8485).putExtra("lon",174.7633).putExtra("rode",40.0).putExtra("depth",8.0).putExtra("bowHeight",1.5).putExtra("boatLength",10.0)
                .putExtra("warning",56.0).putExtra("alarm",70.0).putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode","ADVANCED").putExtra("safetyPreset","BALANCED"))
            val active=withTimeout(5_000){while(dao.active()==null)delay(50);dao.active()!!}
            assertTrue(active.active&&!active.paused)
            assertEquals(AnchorCenterStatus.LEARNING.name,active.centerStatus)
            assertEquals(-36.8485,active.learningReferenceLatitude!!,0.000001)
            assertTrue(active.provisionalAnchorLatitude!=null&&active.provisionalRadiusMeters!=null)
            assertTrue(active.provisionalRadiusMeters!!>35.0)
            val learning=withTimeout(10_000){while((dao.active()?.centerSampleCount?:0)<=0)delay(50);dao.active()!!}
            assertEquals(AnchorCenterStatus.LEARNING.name,learning.centerStatus)
            assertTrue(learning.centerSampleCount>0)
            assertTrue(dao.events(active.id).first().any{it.type=="SESSION_STARTED_CENTER_LEARNING"})
        }
    }

    @Test fun backdownCannotStartWithoutAnExplicitBowHeight() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                .putExtra("lat",-36.8485).putExtra("lon",174.7633).putExtra("rode",40.0).putExtra("depth",8.0)
                .putExtra("warning",56.0).putExtra("alarm",70.0).putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode","BASIC"))
            delay(1_000)
            assertTrue(dao.active()==null)
        }
    }

    @Test fun straightBackdownNeverResolvesTheAnchorCentre() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val baseLat=-36.8485;val baseLon=174.7633;server.setFix(baseLat,baseLon)
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ARM)
                .putExtra("lat",baseLat).putExtra("lon",baseLon).putExtra("rode",40.0).putExtra("depth",8.0).putExtra("bowHeight",1.5).putExtra("boatLength",10.0)
                .putExtra("warning",56.0).putExtra("alarm",70.0).putExtra("placement",AnchorPlacementMode.BACKDOWN.name).putExtra("rangeMode","ADVANCED").putExtra("safetyPreset","BALANCED"))
            val sessionId=withTimeout(5_000){while(dao.active()==null)delay(50);dao.active()!!.id}
            delay(9_000)
            assertEquals(AnchorCenterStatus.LEARNING.name,dao.active()?.centerStatus)
            for(step in 1..22){server.setFix(baseLat+step/110_540.0,baseLon);delay(1_050)}
            val stillLearning=dao.active()!!
            assertEquals(sessionId,stillLearning.id)
            assertEquals(AnchorCenterStatus.LEARNING.name,stillLearning.centerStatus)
            assertTrue(dao.events(sessionId).first().none{it.type=="ANCHOR_CENTER_RESOLVED"})
        }
    }

    @Test fun alarmRangeCanChangeWithoutReplacingTheActiveSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch();startServiceForRestore()
            val before=dao.active()!!
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS)
                .putExtra("alarm",85.0).putExtra("rode",500.0).putExtra("depth",99.0).putExtra("bowHeight",20.0).putExtra("boatLength",90.0).putExtra("rangeMode","ADVANCED").putExtra("safetyPreset","TOLERANT"))
            withTimeout(5_000){while(dao.active()?.alarmRadiusMeters!=85.0)delay(50)}
            val after=dao.active()!!
            assertEquals(sessionId,after.id)
            assertEquals(85.0,after.alarmRadiusMeters,0.01)
            assertEquals(before.rodeLengthMeters,after.rodeLengthMeters,0.01)
            assertEquals(before.waterDepthMeters,after.waterDepthMeters)
            assertEquals(before.bowRollerHeightMeters,after.bowRollerHeightMeters,0.01)
            assertEquals(before.rangeMode,after.rangeMode)
            assertEquals(before.safetyPreset,after.safetyPreset)
            assertTrue(dao.events(sessionId).first().any{it.type=="ALARM_RANGE_CHANGED"})
        }
    }

    @Test fun wideningRangeCanClearAnAlarmWithoutEndingTheSession() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0);startServiceForRestore()
            withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",200.0).putExtra("rangeMode","BASIC"))
            withTimeout(5_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_CLEARED_BY_RANGE_CHANGE"}}}
            assertEquals(sessionId,dao.active()?.id)
        }
    }

    @Test fun foregroundAppShowsActionableAnchorDragDialog() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,alarmSnoozeMinutes=5));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0)
            ActivityScenario.launch(MainActivity::class.java).use {
                startServiceForRestore()
                withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
                compose.waitUntil(5_000){compose.onAllNodesWithText("ANCHOR DRAG ALARM").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("in_app_anchor_alarm").assertExists()
                compose.onNodeWithTag("alarm_snooze_action").performClick()
                val snoozed=withTimeout(5_000){while((dao.active()?.alarmSnoozedUntil?:0L)<=System.currentTimeMillis())delay(50);dao.active()!!}
                assertEquals(sessionId,snoozed.id)
            }
        }
    }

    @Test fun resolvedAnchorOffersGoogleMapsHandoff() = runBlocking<Unit> {
        val sessionId=seedActiveWatch()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000){runCatching{compose.onNodeWithTag("open_anchor_google_maps").fetchSemanticsNode();true}.getOrDefault(false)}
            compose.onNodeWithTag("open_anchor_google_maps").assertExists()
            assertEquals(sessionId,dao.active()?.id)
        }
    }

    @Test fun changingRangeDuringAlarmSnoozesIfDangerStillRemains() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,alarmSnoozeMinutes=5));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0);startServiceForRestore()
            withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.UPDATE_RADIUS).putExtra("alarm",20.0).putExtra("rangeMode","BASIC"))
            val updated=withTimeout(5_000){while(dao.active()?.alarmRadiusMeters!=20.0)delay(50);dao.active()!!}
            assertEquals(sessionId,updated.id)
            assertTrue((updated.alarmSnoozedUntil?:0L)>System.currentTimeMillis())
        }
    }

    @Test fun snoozePersistsAndPauseClearsThePendingReminder() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,alarmSnoozeMinutes=5));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(anchorLatitude=-36.8495,alarmRadius=10.0);startServiceForRestore()
            withTimeout(8_000){dao.events(sessionId).first{rows->rows.any{it.type=="ALARM_TRIGGERED"&&it.detail=="ANCHOR_RADIUS_EXCEEDED"}}}
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SNOOZE))
            val snoozed=withTimeout(5_000){while((dao.active()?.alarmSnoozedUntil?:0L)<=System.currentTimeMillis())delay(50);dao.active()!!}
            assertTrue(snoozed.alarmSnoozedUntil!!>=System.currentTimeMillis()+4*60_000L)
            assertTrue(dao.events(sessionId).first().any{it.type=="ALARM_SNOOZED"})
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.PAUSE_WATCH))
            withTimeout(5_000){while(dao.active()?.paused!=true)delay(50)}
            assertTrue(dao.active()?.alarmSnoozedUntil==null)
        }
    }

    @Test fun activeSystemWatchRemainsLockedWhenFreshNmeaConnects() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.SYSTEM));connectAndAwaitFix(profile)
            val sessionId=seedActiveWatch(positionSource=GpsDataSource.SYSTEM);ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java));delay(500)
            assertEquals(sessionId,dao.active()?.id)
            assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
            assertEquals(GpsDataSource.SYSTEM.name,dao.active()?.positionSource)
            assertTrue(dao.events(sessionId).first().none{it.type=="WATCH_GPS_SOURCE_CHANGED"})
        }
    }

    @Test fun restoredSessionKeepsItsSourceWhenNmeaIsDisconnected() = runBlocking<Unit> {
        preferences.save(AppSettings(gpsDataSource=GpsDataSource.SYSTEM))
        navigation.disconnectAll()
        val sessionId=seedActiveWatch(positionSource=GpsDataSource.SYSTEM);ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java));delay(500)
        assertEquals(GpsDataSource.SYSTEM.name,dao.active()?.positionSource)
        assertEquals(GpsDataSource.SYSTEM,preferences.settings.first().gpsDataSource)
    }

    @Test fun keepingCurrentCentreStopsEstimatorAndKeepsRadius() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val candidateId=77L;val sessionId=seedCandidateWatch(candidateId);startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.KEEP_CURRENT_CENTER).putExtra("sessionId",sessionId).putExtra("candidateId",candidateId))
            val rejected=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&it.candidateDecision==CandidateDecision.REJECTED.name}}.first{it.id==sessionId}}
            assertEquals(-36.8485,rejected.anchorLatitude,0.000001)
            assertEquals(45.0,rejected.alarmRadiusMeters,0.001)
            assertTrue(dao.events(sessionId).first().any{it.type=="ANCHOR_CENTER_CURRENT_KEPT"})
        }
    }

    @Test fun acceptingEstimatedCandidateMovesOnlyCentreAndKeepsRadius() = runBlocking<Unit> {
        TestNmeaServer().use { server ->
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val candidateId=88L;val sessionId=seedCandidateWatch(candidateId);startServiceForRestore()
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.ACCEPT_ESTIMATED_CENTER).putExtra("sessionId",sessionId).putExtra("candidateId",candidateId))
            val accepted=withTimeout(5_000){dao.sessions().first{rows->rows.any{it.id==sessionId&&it.candidateDecision==CandidateDecision.ACCEPTED.name}}.first{it.id==sessionId}}
            assertEquals(-36.8484,accepted.anchorLatitude,0.000001)
            assertEquals(174.7634,accepted.anchorLongitude,0.000001)
            assertEquals(45.0,accepted.alarmRadiusMeters,0.001)
            assertEquals(AnchorCenterSource.ESTIMATED_USER_ACCEPTED.name,accepted.centerSource)
            assertEquals(AnchorPositionMode.KNOWN.name,accepted.anchorPositionMode)
            assertTrue(accepted.provisionalAnchorLatitude==null&&accepted.provisionalRadiusMeters==null)
            assertTrue(dao.events(sessionId).first().any{it.type=="ANCHOR_CENTER_ACCEPTED_BY_USER"})
        }
    }

    @Test fun completedHistoryDeletionRemovesSessionTrackAndTimeline() = runBlocking<Unit> {
        val id=dao.insertSession(AnchorSessionEntity(startedAt=1,endedAt=2,anchorLatitude=1.0,anchorLongitude=2.0,rodeLengthMeters=0.0,waterDepthMeters=null,bowRollerHeightMeters=0.0,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=20.0,warningRadiusMeters=15.0,alarmRadiusMeters=20.0,active=false))
        dao.insertPoint(com.yokuli.anchorwatch.data.database.TrackPointEntity(sessionId=id,timestamp=1,latitude=1.0,longitude=2.0,distanceFromAnchor=0.0,sog=null,cog=null,heading=null,hdop=null))
        dao.insertEvent(com.yokuli.anchorwatch.data.database.AlarmEventEntity(sessionId=id,timestamp=1,type="TEST"))
        assertEquals(1,dao.deleteCompletedSession(id));assertTrue(dao.sessions().first().none{it.id==id});assertTrue(dao.points(id).first().isEmpty());assertTrue(dao.events(id).first().isEmpty())
    }

    @Test fun sharingAndLinzPreferencesRoundTrip() = runBlocking<Unit> {
        preferences.save(AppSettings(nmeaSharingEnabled=true,nmeaSharingPort=12001,linzHydroEnabled=true,linzHydroOpacity=.55,linzHydroDisclaimerAccepted=true,offlineMapEnabled=true,offlineMapName="Harbour.mbtiles",offlineMapAttribution="Licensed test chart",alarmAudibleConfirmedAt=1234,sounderOffsetMeters=.4,showLinzDepthReference=false,showPersonalMapReference=false))
        val restored=withTimeout(5_000){preferences.settings.first{it.nmeaSharingEnabled&&it.nmeaSharingPort==12001&&it.linzHydroEnabled}}
        assertEquals(.55,restored.linzHydroOpacity,.001);assertTrue(restored.linzHydroDisclaimerAccepted);assertTrue(restored.offlineMapEnabled);assertEquals("Harbour.mbtiles",restored.offlineMapName);assertEquals("Licensed test chart",restored.offlineMapAttribution);assertEquals(1234L,restored.alarmAudibleConfirmedAt);assertEquals(.4,restored.sounderOffsetMeters,.001);assertTrue(!restored.showLinzDepthReference&&!restored.showPersonalMapReference)
    }

    @Test fun sonarSurveyPersistsRawAndNormalizedDepthAndDeletesAsOneUnit() = runBlocking<Unit> {
        val surveyId=sonarDao.insertSurvey(SonarSurveyEntity(name="Harbour pass",startedAt=1,endedAt=2,active=false,transducerDraftMeters=1.2))
        sonarDao.insertSample(DepthSampleEntity(surveyId=surveyId,timestamp=2,latitude=-36.84,longitude=174.76,baseGridX=1,baseGridY=2,sourceElapsedRealtime=1,rawDepthMeters=8.0,measuredDepthMeters=9.2,normalizedDepthMeters=null,depthReference="BELOW_TRANSDUCER",sentenceType="DBT",horizontalAccuracyMeters=2.5,gpsSource="NMEA",positionProvider="NMEA",positionAgeMillis=100))
        val sample=sonarDao.samplesNow(surveyId).single();assertEquals(8.0,sample.rawDepthMeters,.001);assertEquals(9.2,sample.measuredDepthMeters,.001);assertTrue(sample.normalizedDepthMeters==null)
        assertEquals(1,sonarDao.deleteCompleted(surveyId));assertTrue(sonarDao.samplesNow(surveyId).isEmpty())
    }

    @Test fun sonarRuntimePairsDepthOnlyWithGpsFromTheSameNmeaServer() = runBlocking<Unit> {
        TestNmeaServer().use{server->
            server.setDepth(8.0,1.0)
            val profile=liveProfile(server,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA,sounderOffsetMeters=.4));connectAndAwaitFix(profile)
            withTimeout(10_000){while(true){
                val now=android.os.SystemClock.elapsedRealtime();val status=sonarRecorder.status.value
                if(status.hasFreshRealDepth(now)&&status.hasFreshNmeaPosition(now))break
                delay(25)
            }}
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.START_SONAR_SURVEY).putExtra("name","Runtime test").putExtra("tideMode","OFF"))
            val survey=withTimeoutOrNull(15_000){while(sonarDao.active()==null)delay(25);sonarDao.active()}
            assertNotNull("Survey was not started; recorder=${sonarRecorder.status.value}; connection=${navigation.connectionState.value}",survey)
            requireNotNull(survey)
            val sample=withTimeoutOrNull(15_000){sonarDao.samples(survey.id).first{it.isNotEmpty()}.single()}
            assertNotNull("Fresh same-stream depth was not recorded; recorder=${sonarRecorder.status.value}",sample)
            requireNotNull(sample)
            assertEquals(8.0,sample.rawDepthMeters,.001);assertEquals(9.4,sample.measuredDepthMeters,.001);assertTrue(sample.normalizedDepthMeters==null);assertTrue(sample.usable);assertTrue(!sample.positionCorrectionApplied);assertEquals("NMEA_SERVER",sample.gpsSource);assertEquals(PositionProvider.NMEA.name,sample.positionProvider)
            context.startService(Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.STOP_SONAR_SURVEY))
            withTimeout(5_000){while(sonarDao.active()!=null)delay(25)}
        }
    }

    @Test fun sharingReusesTheLiveUpstreamAndKeepsServingAfterActivityCloses() = runBlocking<Unit> {
        TestNmeaServer().use{boat->
            val profile=liveProfile(boat,true);preferences.save(AppSettings(profile=profile,gpsDataSource=GpsDataSource.NMEA));connectAndAwaitFix(profile)
            val upstreamConnections=boat.accepted.get();val port=ServerSocket(0).use{it.localPort}
            preferences.save(preferences.settings.first().copy(nmeaSharingEnabled=true,nmeaSharingPort=port))
            ContextCompat.startForegroundService(context,Intent(context,AnchorForegroundService::class.java).setAction(AnchorForegroundService.SET_NMEA_SHARING).putExtra("enabled",true).putExtra("port",port))
            withTimeout(5_000){sharingServer.status.first{it.state==SharingServerState.RUNNING}}
            ActivityScenario.launch(MainActivity::class.java).use{}
            Socket("127.0.0.1",port).use{client->client.soTimeout=3_000;withTimeout(5_000){sharingServer.status.first{it.clientCount==1}};val line=client.getInputStream().bufferedReader().readLine();assertTrue(line.startsWith("\$GPRMC")||line.startsWith("\$GNRMC"))}
            assertEquals(upstreamConnections,boat.accepted.get())
        }
    }

    private suspend fun connectAndAwaitFix(profile: ConnectionProfile) {
        val started = android.os.SystemClock.elapsedRealtime()
        navigation.connect(profile)
        withTimeout(5_000) { navigation.connectionState.first { it == NmeaConnectionState.CONNECTED } }
        withTimeout(5_000) { navigation.diagnostics.first { (it.lastFixElapsed ?: 0L) >= started } }
    }

    private suspend fun seedActiveWatch(anchorLatitude:Double=-36.8485,alarmRadius:Double=50.0,positionSource:GpsDataSource=GpsDataSource.NMEA): Long = dao.insertSession(
        AnchorSessionEntity(
            startedAt = System.currentTimeMillis(), anchorLatitude = anchorLatitude, anchorLongitude = 174.7633,
            rodeLengthMeters = 0.0, waterDepthMeters = null, bowRollerHeightMeters = 0.0,
            gpsAntennaOffsetMeters = 0.0, expectedSwingRadiusMeters = alarmRadius,
            warningRadiusMeters = maxOf(alarmRadius*.8,alarmRadius-10).coerceAtMost(alarmRadius-.1), alarmRadiusMeters = alarmRadius,
            positionSource = positionSource.name,
        )
    )

    private suspend fun seedCandidateWatch(candidateId:Long):Long=dao.insertSession(
        AnchorSessionEntity(
            startedAt=System.currentTimeMillis(),anchorLatitude=-36.8485,anchorLongitude=174.7633,
            rodeLengthMeters=45.0,waterDepthMeters=8.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,
            expectedSwingRadiusMeters=40.0,warningRadiusMeters=36.0,alarmRadiusMeters=45.0,
            placementMode=AnchorPlacementMode.BACKDOWN.name,centerStatus=AnchorCenterStatus.CANDIDATE_READY.name,
            centerConfidence="HIGH",positionSource=GpsDataSource.NMEA.name,anchorPositionMode=AnchorPositionMode.ESTIMATE.name,
            centerSource=AnchorCenterSource.UNKNOWN.name,provisionalAnchorLatitude=-36.8484,provisionalAnchorLongitude=174.7634,
            provisionalRadiusMeters=4.0,candidateId=candidateId,candidateCreatedAt=System.currentTimeMillis(),candidateDecision=CandidateDecision.AVAILABLE.name,
        )
    )

    private suspend fun seedPhoneHeadingLearningWatch():Long=dao.insertSession(
        AnchorSessionEntity(
            startedAt=System.currentTimeMillis(),anchorLatitude=-36.8485,anchorLongitude=174.7633,
            rodeLengthMeters=45.0,waterDepthMeters=8.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,
            expectedSwingRadiusMeters=40.0,warningRadiusMeters=36.0,alarmRadiusMeters=45.0,
            placementMode=AnchorPlacementMode.BACKDOWN.name,centerStatus=AnchorCenterStatus.LEARNING.name,
            centerConfidence="LOW",positionSource=GpsDataSource.NMEA.name,anchorPositionMode=AnchorPositionMode.ESTIMATE.name,
            centerSource=AnchorCenterSource.UNKNOWN.name,learningReferenceLatitude=-36.8485,learningReferenceLongitude=174.7633,
            provisionalAnchorLatitude=-36.8485,provisionalAnchorLongitude=174.7633,provisionalRadiusMeters=45.0,usePhoneHeading=true,
        )
    )

    private suspend fun startServiceForRestore() {
        val generation=runtimeDiagnostics.state.value.serviceGeneration
        ContextCompat.startForegroundService(context, Intent(context, AnchorForegroundService::class.java))
        withTimeout(15_000){runtimeDiagnostics.state.first{it.serviceGeneration>generation&&it.serviceReady}}
        if(dao.active()?.paused==false)assertTrue(RuntimeOwner.ANCHOR_WATCH in runtimeDiagnostics.state.value.activeOwners)
    }

    private fun openDisconnectDecision() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Data").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Data").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Disconnect").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Disconnect").performScrollTo().performClick()
    }

    private fun liveProfile(server: TestNmeaServer, autoReconnect: Boolean) = ConnectionProfile(
        host = "127.0.0.1", port = server.port, autoReconnect = autoReconnect,
    )
}

private class TestNmeaServer : Closeable {
    private val server = ServerSocket(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<Socket>()
    val accepted = AtomicInteger()
    private val sentence=AtomicReference(rmc(-36.8485,174.7633))
    private val depthSentence=AtomicReference<ByteArray?>(null)
    val port: Int get() = server.localPort

    init {
        scope.launch {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clients += socket; accepted.incrementAndGet()
                launch { writeFixes(socket) }
            }
        }
    }

    private suspend fun writeFixes(socket: Socket) {
        try {
            var emitted = 0
            while (scope.isActive && !socket.isClosed) {
                socket.getOutputStream().write(sentence.get())
                depthSentence.get()?.let{socket.getOutputStream().write(it)}
                socket.getOutputStream().flush()
                // Every newly accepted socket gets a short validation burst so both the
                // endpoint preflight and the real transport can prove that live NMEA is
                // present. Afterwards use a realistic low visual cadence: safety code still
                // sees every sentence, while Compose tests get an idle window between fixes.
                delay(if (emitted++ < 3) 100 else 2_000)
            }
        } catch (_: Exception) {
            // Closing a connection is how loss/reconnect scenarios are triggered.
        } finally { clients.remove(socket); runCatching { socket.close() } }
    }

    fun setFix(latitude:Double,longitude:Double){sentence.set(rmc(latitude,longitude))}
    fun setDepth(depthMeters:Double,offsetMeters:Double){depthSentence.set((NmeaChecksum.append("IIDPT,$depthMeters,$offsetMeters")+"\r\n").toByteArray())}
    fun closeConnections() { clients.toList().forEach { runCatching { it.close() } } }
    override fun close() { closeConnections(); runCatching { server.close() }; scope.cancel() }

    companion object{
        private fun rmc(latitude:Double,longitude:Double):ByteArray{
            fun coordinate(value:Double,degreeWidth:Int):String{val absolute=kotlin.math.abs(value);val degrees=absolute.toInt();val minutes=(absolute-degrees)*60;return String.format(Locale.US,"%0${degreeWidth}d%07.4f",degrees,minutes)}
            val body="GPRMC,073000.00,A,${coordinate(latitude,2)},${if(latitude<0)"S" else "N"},${coordinate(longitude,3)},${if(longitude<0)"W" else "E"},0.2,180.0,150826,,,A"
            val checksum=body.fold(0){value,char->value xor char.code}
            return "\$$body*${String.format(Locale.US,"%02X",checksum)}\r\n".toByteArray()
        }
    }
}
