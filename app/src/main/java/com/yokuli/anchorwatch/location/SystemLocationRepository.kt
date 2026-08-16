package com.yokuli.anchorwatch.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val guard = Any()
    private val _fix = MutableStateFlow<NavigationFix?>(null)
    val fix = _fix.asStateFlow()
    private val _recentFixes = MutableStateFlow<List<NavigationFix>>(emptyList())
    val recentFixes = _recentFixes.asStateFlow()
    private var appEnabled = false
    private var backgroundEnabled = false
    private var running = false
    private val listener = LocationListener { publish(it) }

    private fun publish(location: Location) {
        // A switch away from the NMEA proxy must be backed by a real system
        // position, never by the app's own mock location fed back to itself.
        if (LocationCompat.isMock(location)) return
        val received = location.elapsedRealtimeNanos.takeIf { it > 0 }?.div(1_000_000) ?: SystemClock.elapsedRealtime()
        val value = NavigationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            timestampUtcMillis = location.time,
            receivedElapsedRealtime = received,
            sogKnots = location.speed.takeIf { location.hasSpeed() }?.times(1.943844),
            cogTrueDegrees = location.bearing.takeIf { location.hasBearing() }?.toDouble(),
            // Android bearing is course over ground. It is not bow heading,
            // especially at anchor where tiny GPS motion makes it unstable.
            headingTrueDegrees = null,
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
            horizontalAccuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }?.toDouble(),
            positionProvider = if (location.provider == LocationManager.GPS_PROVIDER) {
                PositionProvider.ANDROID_GNSS
            } else {
                PositionProvider.ANDROID_NETWORK
            },
            isMockLocation = LocationCompat.isMock(location),
            hdop = null,
            sourceSentence = "SYSTEM_GPS:${location.provider}",
            valid = location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0,
        )
        _fix.value = value
        if (value.valid) appendRecent(value)
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun setAppEnabled(enabled: Boolean) = synchronized(guard) { appEnabled = enabled; reconcileLocked() }
    fun setBackgroundEnabled(enabled: Boolean) = synchronized(guard) { backgroundEnabled = enabled; reconcileLocked() }
    fun refreshPermission() = synchronized(guard) { reconcileLocked() }

    @SuppressLint("MissingPermission")
    private fun reconcileLocked() {
        val shouldRun = (appEnabled || backgroundEnabled) && hasPermission()
        if (shouldRun && !running) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            providers.mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)?.let(::publish)
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                    provider
                }.getOrNull()
            }.also { running = it.isNotEmpty() }
        } else if (!shouldRun && running) {
            locationManager.removeUpdates(listener)
            running = false
            if (!appEnabled && !backgroundEnabled) _fix.value = null
        }
    }

    private fun appendRecent(fix: NavigationFix) {
        val cutoff = fix.receivedElapsedRealtime - 10 * 60_000L
        _recentFixes.value = (_recentFixes.value + fix).filter { it.receivedElapsedRealtime >= cutoff }.takeLast(1_200)
    }
}
