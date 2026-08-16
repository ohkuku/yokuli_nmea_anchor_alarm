package com.yokuli.anchorwatch.data.preferences
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.domain.model.DemoScenario
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.AppLanguage
import com.yokuli.anchorwatch.domain.model.AlarmSound
import kotlinx.coroutines.flow.*
private val Context.store by preferencesDataStore("settings")
data class AppSettings(
    val profile: ConnectionProfile = ConnectionProfile(),
    val alarmPersistenceSeconds: Int = 8,
    val gpsLossSeconds: Int = 15,
    val alarmSnoozeMinutes: Int = 5,
    val alarmSound: AlarmSound = AlarmSound.SYSTEM_ALARM,
    val customAlarmSoundUri: String? = null,
    val hdopLimit: Double = 5.0,
    val safetyMargin: Double = 10.0,
    val keepWifiAwake: Boolean = true,
    val mapType: Int = 1,
    val gpsDataSource: GpsDataSource = GpsDataSource.SYSTEM,
    val mockEnabled: Boolean = false,
    val enhancedMock: Boolean = true,
    val mockHz: Int = 1,
    val demoMode: Boolean = false,
    val demoScenario: DemoScenario = DemoScenario.SAFE_SWING,
    val demoSpeedMultiplier: Int = 1,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val boatLengthMeters: Double = 10.0,
    val bowRollerHeightMeters: Double = 1.5,
    val nmeaGpsAntennaToBowMeters: Double = 0.0,
    val preferredAlarmRadiusMeters: Double = 50.0,
    val nmeaSharingEnabled: Boolean = false,
    val nmeaSharingPort: Int = 10111,
    val linzHydroEnabled: Boolean = false,
    val linzHydroOpacity: Double = 0.70,
    val linzHydroDisclaimerAccepted: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val name=stringPreferencesKey("name");val host=stringPreferencesKey("host");val port=intPreferencesKey("port");val protocol=stringPreferencesKey("protocol");val checksum=booleanPreferencesKey("checksum");val auto=booleanPreferencesKey("auto");val map=intPreferencesKey("map");val wifi=booleanPreferencesKey("wifi");val gpsSource=stringPreferencesKey("gps_source");val mock=booleanPreferencesKey("mock");val enhanced=booleanPreferencesKey("mock_enhanced");val hz=intPreferencesKey("mock_hz");val gpsLoss=intPreferencesKey("gps_loss");val snooze=intPreferencesKey("alarm_snooze_minutes");val alarmSound=stringPreferencesKey("alarm_sound");val customAlarmSound=stringPreferencesKey("custom_alarm_sound_uri");val demoMode=booleanPreferencesKey("developer_demo_mode");val demoScenario=stringPreferencesKey("demo_scenario");val demoSpeed=intPreferencesKey("demo_speed_multiplier");val language=stringPreferencesKey("app_language");val boatLength=doublePreferencesKey("vessel_boat_length_m");val bowHeight=doublePreferencesKey("vessel_bow_roller_height_m");val antennaToBow=doublePreferencesKey("vessel_nmea_antenna_to_bow_m");val preferredRadius=doublePreferencesKey("preferred_alarm_radius_m");val sharingEnabled=booleanPreferencesKey("nmea_sharing_enabled");val sharingPort=intPreferencesKey("nmea_sharing_port");val linzEnabled=booleanPreferencesKey("linz_hydro_enabled");val linzOpacity=doublePreferencesKey("linz_hydro_opacity");val linzDisclaimer=booleanPreferencesKey("linz_hydro_disclaimer_accepted")
    }

    val settings=context.store.data.map { p ->
        val demoEnabled=p[K.demoMode]?:false
        val storedSource=p[K.gpsSource]?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}
            ?:GpsDataSource.SYSTEM
        AppSettings(
            profile=ConnectionProfile(name=p[K.name]?:"Boat",host=p[K.host]?:"192.168.1.100",port=p[K.port]?:10110,protocol=runCatching{Protocol.valueOf(p[K.protocol]?:"TCP")}.getOrDefault(Protocol.TCP),requireChecksum=p[K.checksum]?:true,autoReconnect=p[K.auto]?:true),
            mapType=(p[K.map]?:1).takeIf{it in 1..2}?:1,
            keepWifiAwake=p[K.wifi]?:true,
            gpsDataSource=if(demoEnabled)GpsDataSource.DEMO else if(storedSource==GpsDataSource.DEMO)GpsDataSource.SYSTEM else storedSource,
            mockEnabled=p[K.mock]?:false,
            enhancedMock=p[K.enhanced]?:true,
            mockHz=p[K.hz]?:1,
            gpsLossSeconds=p[K.gpsLoss]?:15,
            alarmSnoozeMinutes=(p[K.snooze]?:5).coerceIn(1,30),
            alarmSound=p[K.alarmSound]?.let{runCatching{AlarmSound.valueOf(it)}.getOrNull()}?.let{if(it==AlarmSound.CUSTOM)it else AlarmSound.SYSTEM_ALARM}?:AlarmSound.SYSTEM_ALARM,
            customAlarmSoundUri=p[K.customAlarmSound],
            demoMode=demoEnabled,
            demoScenario=p[K.demoScenario]?.let{runCatching{DemoScenario.valueOf(it)}.getOrNull()}?:DemoScenario.SAFE_SWING,
            demoSpeedMultiplier=(p[K.demoSpeed]?:1).takeIf{it in listOf(1,2,5)}?:1,
            appLanguage=p[K.language]?.let{runCatching{AppLanguage.valueOf(it)}.getOrNull()}?:AppLanguage.SYSTEM,
            boatLengthMeters=(p[K.boatLength]?:10.0).coerceAtLeast(0.0),
            bowRollerHeightMeters=(p[K.bowHeight]?:1.5).coerceAtLeast(0.0),
            nmeaGpsAntennaToBowMeters=(p[K.antennaToBow]?:0.0).coerceAtLeast(0.0),
            preferredAlarmRadiusMeters=(p[K.preferredRadius]?:50.0).coerceAtLeast(1.0),
            nmeaSharingEnabled=p[K.sharingEnabled]?:false,
            nmeaSharingPort=(p[K.sharingPort]?:10111).takeIf{it in 1024..65535}?:10111,
            linzHydroEnabled=p[K.linzEnabled]?:false,
            linzHydroOpacity=(p[K.linzOpacity]?:.70).coerceIn(.30,1.0),
            linzHydroDisclaimerAccepted=p[K.linzDisclaimer]?:false,
        )
    }

    suspend fun save(s:AppSettings)=context.store.edit { p ->
        val safeSource=if(s.demoMode)GpsDataSource.DEMO else if(s.gpsDataSource==GpsDataSource.DEMO)GpsDataSource.SYSTEM else s.gpsDataSource
        p[K.name]=s.profile.name;p[K.host]=s.profile.host;p[K.port]=s.profile.port;p[K.protocol]=s.profile.protocol.name;p[K.checksum]=s.profile.requireChecksum;p[K.auto]=s.profile.autoReconnect;p[K.map]=s.mapType;p[K.wifi]=s.keepWifiAwake;p[K.gpsSource]=safeSource.name;p[K.mock]=s.mockEnabled;p[K.enhanced]=s.enhancedMock;p[K.hz]=s.mockHz;p[K.gpsLoss]=s.gpsLossSeconds;p[K.snooze]=s.alarmSnoozeMinutes.coerceIn(1,30);p[K.alarmSound]=if(s.alarmSound==AlarmSound.CUSTOM)AlarmSound.CUSTOM.name else AlarmSound.SYSTEM_ALARM.name;if(s.customAlarmSoundUri==null)p.remove(K.customAlarmSound)else p[K.customAlarmSound]=s.customAlarmSoundUri;p[K.demoMode]=s.demoMode;p[K.demoScenario]=s.demoScenario.name;p[K.demoSpeed]=s.demoSpeedMultiplier.takeIf{it in listOf(1,2,5)}?:1;p[K.language]=s.appLanguage.name;p[K.boatLength]=s.boatLengthMeters.coerceAtLeast(0.0);p[K.bowHeight]=s.bowRollerHeightMeters.coerceAtLeast(0.0);p[K.antennaToBow]=s.nmeaGpsAntennaToBowMeters.coerceAtLeast(0.0);p[K.preferredRadius]=s.preferredAlarmRadiusMeters.coerceAtLeast(1.0);p[K.sharingEnabled]=s.nmeaSharingEnabled;p[K.sharingPort]=s.nmeaSharingPort.takeIf{it in 1024..65535}?:10111;p[K.linzEnabled]=s.linzHydroEnabled;p[K.linzOpacity]=s.linzHydroOpacity.coerceIn(.30,1.0);p[K.linzDisclaimer]=s.linzHydroDisclaimerAccepted
    }

    suspend fun setMockEnabled(enabled:Boolean)=context.store.edit{it[K.mock]=enabled}
}
