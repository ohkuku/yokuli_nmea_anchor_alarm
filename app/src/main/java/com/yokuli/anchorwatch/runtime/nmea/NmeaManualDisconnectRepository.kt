package com.yokuli.anchorwatch.runtime.nmea

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nmeaManualDisconnectStore by preferencesDataStore("nmea_manual_disconnect")

data class NmeaManualDisconnectState(
    val suppressed:Boolean=false,
    val disconnectedAtUtcMillis:Long?=null,
)

/**
 * A user's Disconnect is a durable safety decision, not a transient socket
 * state. Background feature restoration may not clear this latch; only an
 * explicit Connect/Reconnect action may do so.
 */
@Singleton
class NmeaManualDisconnectRepository @Inject constructor(
    @ApplicationContext private val context:Context,
){
    private object Keys{
        val suppressed=booleanPreferencesKey("suppressed")
        val disconnectedAt=longPreferencesKey("disconnected_at_utc_millis")
    }
    val state:Flow<NmeaManualDisconnectState> = context.nmeaManualDisconnectStore.data.map{values->
        NmeaManualDisconnectState(
            suppressed=values[Keys.suppressed]?:false,
            disconnectedAtUtcMillis=values[Keys.disconnectedAt],
        )
    }
    suspend fun current()=state.first()
    suspend fun suppress(nowUtcMillis:Long=System.currentTimeMillis())=context.nmeaManualDisconnectStore.edit{values->
        values[Keys.suppressed]=true
        values[Keys.disconnectedAt]=nowUtcMillis
    }
    suspend fun clear()=context.nmeaManualDisconnectStore.edit{values->
        values[Keys.suppressed]=false
        values.remove(Keys.disconnectedAt)
    }
}
