package com.yokuli.anchorwatch.data.sharing

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

private val Context.localNmeaServerSettingsStore by preferencesDataStore("local_nmea_server_settings")

/**
 * Configuration for the NMEA service hosted by this phone.
 *
 * This is deliberately not an output destination. Boat-network injection is a
 * client/write-side feature; this product owns a listening socket and accepts
 * downstream dashboard clients. Its explicit live lease survives process
 * reclamation during the same boot so a foreground listener does not vanish
 * when an OEM kills and recreates the process after screen-off. A reboot is a
 * new safety boundary and always requires another explicit Start.
 */
data class LocalNmeaServerSettings(
    val port:Int=10111,
    val includePressure:Boolean=true,
    val includeDerivedWind:Boolean=false,
    val configured:Boolean=true,
    val serverRequested:Boolean=false,
)

@Singleton
class LocalNmeaServerSettingsRepository @Inject constructor(
    @ApplicationContext private val context:Context,
){
    private object K{
        val port=intPreferencesKey("listen_port")
        val includePressure=booleanPreferencesKey("include_phone_pressure")
        val includeDerivedWind=booleanPreferencesKey("include_app_derived_wind")
        val includeDerivedWindOptIn=booleanPreferencesKey("include_app_derived_wind_opt_in_v2")
        val configured=booleanPreferencesKey("configured")
        val runRequested=booleanPreferencesKey("run_requested_same_boot")
        val runBootCount=intPreferencesKey("run_requested_boot_count")
    }

    private fun bootCount()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
    private val persisted=context.localNmeaServerSettingsStore.data.map{preferences->
        val requested=LocalNmeaServerLeasePolicy.restore(
            requested=preferences[K.runRequested]?:false,
            requestedBootCount=preferences[K.runBootCount]?:Int.MIN_VALUE,
            currentBootCount=bootCount(),
        )
        LocalNmeaServerSettings(
            port=(preferences[K.port]?:10111).takeIf{it in 1024..65535}?:10111,
            includePressure=preferences[K.includePressure]?:true,
            includeDerivedWind=(preferences[K.includeDerivedWindOptIn]?:false)&&(preferences[K.includeDerivedWind]?:false),
            configured=preferences[K.configured]?:true,
            serverRequested=requested,
        )
    }

    val settings=persisted

    /** Saving a port never starts or stops the server. */
    suspend fun saveConfiguration(value:LocalNmeaServerSettings){
        require(value.port in 1024..65535){"Local NMEA server port must be between 1024 and 65535."}
        context.localNmeaServerSettingsStore.edit{preferences->
            preferences[K.port]=value.port
            preferences[K.includePressure]=value.includePressure
            preferences[K.includeDerivedWind]=value.includeDerivedWind
            preferences[K.includeDerivedWindOptIn]=value.includeDerivedWind
            preferences[K.configured]=true
        }
    }

    suspend fun requestStart(){context.localNmeaServerSettingsStore.edit{preferences->preferences[K.runRequested]=true;preferences[K.runBootCount]=bootCount()}}
    suspend fun requestStop(){context.localNmeaServerSettingsStore.edit{preferences->preferences[K.runRequested]=false;preferences.remove(K.runBootCount)}}
    suspend fun resetRuntimeLease(){requestStop()}
}

object LocalNmeaServerLeasePolicy{
    fun restore(requested:Boolean,requestedBootCount:Int,currentBootCount:Int)=
        requested&&currentBootCount>=0&&requestedBootCount==currentBootCount
}
