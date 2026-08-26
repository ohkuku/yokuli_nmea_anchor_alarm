package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Waits on the complete NMEA-position readiness tuple. NavigationRepository
 * publishes a parsed fix before it promotes transport state to CONNECTED, so a
 * consumer that observes only the fix flow can miss the ready transition and
 * wait forever for a duplicate position sentence. */
object NmeaPositionAwaiter {
    suspend fun awaitUsable(
        connectionState: StateFlow<NmeaConnectionState>,
        fix: StateFlow<NavigationFix?>,
        connectionStartedElapsedRealtime: StateFlow<Long?>,
        timeoutMillis: Long,
        maximumAgeMillis: Long,
        nowElapsedRealtime: () -> Long,
    ): NavigationFix? {
        fun usable(state: NmeaConnectionState, candidate: NavigationFix?, started: Long?) =
            NmeaSourceSelectionPolicy.isUsablePosition(
                state,
                candidate,
                started,
                nowElapsedRealtime(),
                maximumAgeMillis,
            )

        val current = fix.value
        if (usable(connectionState.value, current, connectionStartedElapsedRealtime.value)) return current

        return withTimeoutOrNull(timeoutMillis) {
            combine(connectionState, fix, connectionStartedElapsedRealtime) { state, candidate, started ->
                Triple(state, candidate, started)
            }.first { (state, candidate, started) -> usable(state, candidate, started) }.second
        }
    }
}
