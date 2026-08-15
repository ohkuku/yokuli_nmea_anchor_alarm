package com.yokuli.anchorwatch.data.preferences
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yokuli.anchorwatch.data.nmea.*
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import kotlinx.coroutines.flow.*
private val Context.store by preferencesDataStore("settings")
data class AppSettings(val profile:ConnectionProfile=ConnectionProfile(),val alarmPersistenceSeconds:Int=8,val gpsLossSeconds:Int=15,val hdopLimit:Double=5.0,val safetyMargin:Double=10.0,val keepWifiAwake:Boolean=true,val mapType:Int=1,val gpsDataSource:GpsDataSource=GpsDataSource.SYSTEM,val mockEnabled:Boolean=false,val enhancedMock:Boolean=true,val mockHz:Int=1)
class SettingsRepository(private val context:Context){
 private object K{val name=stringPreferencesKey("name");val host=stringPreferencesKey("host");val port=intPreferencesKey("port");val protocol=stringPreferencesKey("protocol");val checksum=booleanPreferencesKey("checksum");val auto=booleanPreferencesKey("auto");val map=intPreferencesKey("map");val wifi=booleanPreferencesKey("wifi");val gpsSource=stringPreferencesKey("gps_source");val mock=booleanPreferencesKey("mock");val enhanced=booleanPreferencesKey("mock_enhanced");val hz=intPreferencesKey("mock_hz");val gpsLoss=intPreferencesKey("gps_loss")}
 val settings=context.store.data.map{p->AppSettings(profile=ConnectionProfile(name=p[K.name]?:"Boat",host=p[K.host]?:"192.168.1.100",port=p[K.port]?:10110,protocol=runCatching{Protocol.valueOf(p[K.protocol]?:"TCP")}.getOrDefault(Protocol.TCP),requireChecksum=p[K.checksum]?:true,autoReconnect=p[K.auto]?:true),mapType=(p[K.map]?:1).takeIf{it in 1..2}?:1,keepWifiAwake=p[K.wifi]?:true,gpsDataSource=p[K.gpsSource]?.let{runCatching{GpsDataSource.valueOf(it)}.getOrNull()}?:if(p[K.host]!=null)GpsDataSource.NMEA else GpsDataSource.SYSTEM,mockEnabled=p[K.mock]?:false,enhancedMock=p[K.enhanced]?:true,mockHz=p[K.hz]?:1,gpsLossSeconds=p[K.gpsLoss]?:15)}
 suspend fun save(s:AppSettings)=context.store.edit{p->p[K.name]=s.profile.name;p[K.host]=s.profile.host;p[K.port]=s.profile.port;p[K.protocol]=s.profile.protocol.name;p[K.checksum]=s.profile.requireChecksum;p[K.auto]=s.profile.autoReconnect;p[K.map]=s.mapType;p[K.wifi]=s.keepWifiAwake;p[K.gpsSource]=s.gpsDataSource.name;p[K.mock]=s.mockEnabled;p[K.enhanced]=s.enhancedMock;p[K.hz]=s.mockHz;p[K.gpsLoss]=s.gpsLossSeconds}
 suspend fun setMockEnabled(enabled:Boolean)=context.store.edit{it[K.mock]=enabled}
}
