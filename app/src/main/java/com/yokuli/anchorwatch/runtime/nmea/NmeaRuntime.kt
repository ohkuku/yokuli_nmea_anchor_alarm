package com.yokuli.anchorwatch.runtime.nmea

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.runtime.RuntimeResourceManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one background-transport gateway shared by Anchor, Sonar and Proxy.
 * NavigationRepository remains the parser/state store; this class prevents
 * feature runtimes from independently deciding when the upstream may close.
 */
@Singleton
class NmeaRuntime @Inject constructor(
    private val navigation:NavigationRepository,
    private val resources:RuntimeResourceManager,
){
    val connectionState get()=navigation.connectionState
    /**
     * Claim an already-live user connection before considering a new transport.
     * Restoring a foreground service must not bounce, replace, or synchronously
     * re-enter the socket that the Data page is already using.
     */
    suspend fun ensureConnected(profile:ConnectionProfile){
        if(!navigation.claimBackgroundConnectionIfConnected()){
            navigation.acquireBackgroundConnection(profile)
        }
    }
    fun releaseIfUnowned(){if(!resources.snapshot().needsNmeaTransport)navigation.releaseBackgroundConnection()}
}
