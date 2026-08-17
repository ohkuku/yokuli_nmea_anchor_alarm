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
    suspend fun ensureConnected(profile:ConnectionProfile)=navigation.acquireBackgroundConnection(profile)
    fun releaseIfUnowned(){if(!resources.snapshot().needsNmeaTransport)navigation.releaseBackgroundConnection()}
}
