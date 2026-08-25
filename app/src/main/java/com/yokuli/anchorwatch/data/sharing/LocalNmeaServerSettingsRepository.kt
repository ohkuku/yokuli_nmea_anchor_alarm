package com.yokuli.anchorwatch.data.sharing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.localNmeaServerSettingsStore by preferencesDataStore("local_nmea_server_settings")

/**
 * Configuration for the NMEA service hosted by this phone.
 *
 * This is deliberately not an output destination. Boat-network injection is a
 * client/write-side feature; this product owns a listening socket and accepts
 * downstream dashboard clients. Its live lease is process-local so a restored
 * backup or process restart can never silently reopen a network listener.
 */
data class LocalNmeaServerSettings(
    val port:Int=10111,
    val includePressure:Boolean=true,
    val includeDerivedWind:Boolean=true,
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
        val configured=booleanPreferencesKey("configured")
    }

    private val requested=MutableStateFlow(false)
    private val persisted=context.localNmeaServerSettingsStore.data.map{preferences->
        LocalNmeaServerSettings(
            port=(preferences[K.port]?:10111).takeIf{it in 1024..65535}?:10111,
            includePressure=preferences[K.includePressure]?:true,
            includeDerivedWind=preferences[K.includeDerivedWind]?:true,
            configured=preferences[K.configured]?:true,
            serverRequested=false,
        )
    }

    val settings=combine(persisted,requested){saved,running->saved.copy(serverRequested=running)}

    /** Saving a port never starts or stops the server. */
    suspend fun saveConfiguration(value:LocalNmeaServerSettings){
        require(value.port in 1024..65535){"Local NMEA server port must be between 1024 and 65535."}
        context.localNmeaServerSettingsStore.edit{preferences->
            preferences[K.port]=value.port
            preferences[K.includePressure]=value.includePressure
            preferences[K.includeDerivedWind]=value.includeDerivedWind
            preferences[K.configured]=true
        }
    }

    fun requestStart(){requested.value=true}
    fun requestStop(){requested.value=false}
    fun resetRuntimeLease(){requested.value=false}
}
