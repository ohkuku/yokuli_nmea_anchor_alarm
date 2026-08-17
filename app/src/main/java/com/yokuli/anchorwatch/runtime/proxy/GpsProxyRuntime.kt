package com.yokuli.anchorwatch.runtime.proxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.location.GlobalMockLocationManager
import com.yokuli.anchorwatch.location.MockGpsPolicy
import com.yokuli.anchorwatch.location.MockGpsState
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.nmea.NmeaRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProxyRuntimeResult(
    val title:String?=null,
    val message:String?=null,
    val high:Boolean=false,
    val eventType:String?=null,
    val eventDetail:String="",
)

/** Owns global mock-location preflight, publication and stale-source recovery. */
@Singleton
class GpsProxyRuntime @Inject constructor(
    @ApplicationContext private val context:Context,
    private val manager:GlobalMockLocationManager,
    private val settings:SettingsRepository,
    private val resources:RuntimeResourceManager,
    private val nmeaRuntime:NmeaRuntime,
    private val clock:MonotonicClock,
){
    private val mutex=Mutex()
    private var policy:MockGpsPolicy?=null
    val status get()=manager.status

    suspend fun start(ensureLocationForeground:()->Boolean):ProxyRuntimeResult=mutex.withLock{
        val current=settings.settings.first()
        if(current.gpsDataSource!=GpsDataSource.NMEA){
            settings.setMockEnabled(false);policy=null;resources.release(RuntimeOwner.GPS_PROXY)
            manager.stop("Select NMEA GPS before enabling the global proxy.")
            return@withLock ProxyRuntimeResult("Android GPS proxy not active","Select NMEA GPS before enabling the global proxy.",true)
        }
        nmeaRuntime.ensureConnected(current.profile)
        resources.set(RuntimeOwner.GPS_PROXY,RuntimeRequirement(needsNmeaTransport=true,needsWakeLock=true,needsWifiLock=current.keepWifiAwake))
        policy=MockGpsPolicy(current.gpsLossSeconds*1_000L,current.mockHz).apply{start(clock.elapsedRealtime())}
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            return@withLock fail("GPS proxy was not enabled. Fine location permission is required; Android GPS is using its normal source.","Grant Fine location permission before enabling GPS proxy.")
        }
        if(!ensureLocationForeground()){
            return@withLock fail("GPS proxy was not enabled. Android did not allow a location foreground service; Android GPS is using its normal source.","Open the app and grant location permission before enabling GPS proxy.")
        }
        val result=manager.start(current.enhancedMock)
        val enabled=result.state==MockGpsState.ACTIVE
        settings.setMockEnabled(enabled)
        if(!enabled)policy=null
        if(enabled)ProxyRuntimeResult(eventType="GPS_PROXY_STARTED",eventDetail=result.message)
        else ProxyRuntimeResult("Android GPS proxy not active",result.message,true)
    }

    suspend fun stop(message:String):ProxyRuntimeResult=mutex.withLock{
        manager.stop(message);settings.setMockEnabled(false);policy=null;resources.release(RuntimeOwner.GPS_PROXY);releaseNmeaIfUnowned()
        ProxyRuntimeResult("Android GPS restored",message,false)
    }

    suspend fun restoreIfRequested(ensureLocationForeground:()->Boolean):ProxyRuntimeResult?{
        val current=settings.settings.first()
        return when{
            current.mockEnabled&&current.gpsDataSource==GpsDataSource.NMEA->start(ensureLocationForeground)
            current.mockEnabled->{settings.setMockEnabled(false);manager.stop("A non-NMEA App GPS source is selected — global NMEA proxy disabled.");null}
            else->null
        }
    }

    suspend fun onAcceptedNmeaFix(fix:NavigationFix):ProxyRuntimeResult?=mutex.withLock{
        if(manager.status.value.state!=MockGpsState.ACTIVE)return@withLock null
        val now=clock.elapsedRealtime()
        if(policy?.onValidFix(now)!=true)return@withLock null
        val result=manager.publish(fix)
        if(result.isSuccess)return@withLock null
        val detail=result.exceptionOrNull()?.message.orEmpty()
        manager.stop("NMEA injection failed — Android GPS restored.");settings.setMockEnabled(false);policy=null;resources.release(RuntimeOwner.GPS_PROXY);releaseNmeaIfUnowned()
        ProxyRuntimeResult("Android GPS restored","NMEA injection failed — Android GPS restored.",true,"MOCK_GPS_FAILED",detail)
    }

    suspend fun watchdog(nowElapsed:Long):ProxyRuntimeResult?=mutex.withLock{
        if(manager.status.value.state!=MockGpsState.ACTIVE||policy?.isStale(nowElapsed)!=true)return@withLock null
        manager.stale();policy=null;settings.setMockEnabled(false);resources.release(RuntimeOwner.GPS_PROXY);releaseNmeaIfUnowned()
        ProxyRuntimeResult("NMEA GPS lost","Android GPS restored to its normal source.",true,"GPS_PROXY_STALE")
    }

    suspend fun shutdown(){
        if(manager.status.value.state==MockGpsState.ACTIVE||manager.status.value.state==MockGpsState.STARTING)manager.stop()
        resources.release(RuntimeOwner.GPS_PROXY)
    }

    private suspend fun fail(managerMessage:String,userMessage:String):ProxyRuntimeResult{
        settings.setMockEnabled(false);policy=null;resources.release(RuntimeOwner.GPS_PROXY);releaseNmeaIfUnowned();manager.stop(managerMessage)
        return ProxyRuntimeResult("Android GPS proxy not active",userMessage,true)
    }
    private fun releaseNmeaIfUnowned()=nmeaRuntime.releaseIfUnowned()
}
