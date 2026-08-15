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
)

class SettingsRepository(private val context: Context) {
    private object K {
        val name=stringPreferencesKey("name");val host=stringPreferencesKey("host");val port=intPreferencesKey("port");val protocol=stringPreferencesKey("protocol");val checksum=booleanPreferencesKey("checksum");val auto=booleanPreferencesKey("auto");val map=intPreferencesKey("map");val wifi=booleanPreferencesKey("wifi");val gpsSource=stringPreferencesKey("gps_source");val mock=booleanPreferencesKey("mock");val enhanced=booleanPreferencesKey("mock_enhanced");val hz=intPreferencesKey("mock_hz");val gpsLoss=intPreferencesKey("gps_loss");val snooze=intPreferencesKey("alarm_snooze_minutes");val alarmSound=stringPreferencesKey("alarm_sound");val customAlarmSound=stringPreferencesKey("custom_alarm_sound_uri");val demoMode=booleanPreferencesKey("developer_demo_mode");val demoScenario=stringPreferencesKey("demo_scenario");val demoSpeed=intPreferencesKey("demo_speed_multiplier");val language=stringPreferencesKey("app_language")
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
            alarmSound=p[K.alarmSound]?.let{runCatching{AlarmSound.valueOf(it)}.getOrNull()}?:AlarmSound.SYSTEM_ALARM,
            customAlarmSoundUri=p[K.customAlarmSound],
            demoMode=demoEnabled,
            demoScenario=p[K.demoScenario]?.let{runCatching{DemoScenario.valueOf(it)}.getOrNull()}?:DemoScenario.SAFE_SWING,
            demoSpeedMultiplier=(p[K.demoSpeed]?:1).takeIf{it in listOf(1,2,5)}?:1,
            appLanguage=p[K.language]?.let{runCatching{AppLanguage.valueOf(it)}.getOrNull()}?:AppLanguage.SYSTEM,
        )
    }

    suspend fun save(s:AppSettings)=context.store.edit { p ->
        val safeSource=if(s.demoMode)GpsDataSource.DEMO else if(s.gpsDataSource==GpsDataSource.DEMO)GpsDataSource.SYSTEM else s.gpsDataSource
        p[K.name]=s.profile.name;p[K.host]=s.profile.host;p[K.port]=s.profile.port;p[K.protocol]=s.profile.protocol.name;p[K.checksum]=s.profile.requireChecksum;p[K.auto]=s.profile.autoReconnect;p[K.map]=s.mapType;p[K.wifi]=s.keepWifiAwake;p[K.gpsSource]=safeSource.name;p[K.mock]=s.mockEnabled;p[K.enhanced]=s.enhancedMock;p[K.hz]=s.mockHz;p[K.gpsLoss]=s.gpsLossSeconds;p[K.snooze]=s.alarmSnoozeMinutes.coerceIn(1,30);p[K.alarmSound]=s.alarmSound.name;if(s.customAlarmSoundUri==null)p.remove(K.customAlarmSound)else p[K.customAlarmSound]=s.customAlarmSoundUri;p[K.demoMode]=s.demoMode;p[K.demoScenario]=s.demoScenario.name;p[K.demoSpeed]=s.demoSpeedMultiplier.takeIf{it in listOf(1,2,5)}?:1;p[K.language]=s.appLanguage.name
    }

    suspend fun setMockEnabled(enabled:Boolean)=context.store.edit{it[K.mock]=enabled}
}
