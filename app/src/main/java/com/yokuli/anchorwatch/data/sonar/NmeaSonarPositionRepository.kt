package com.yokuli.anchorwatch.data.sonar

import com.yokuli.anchorwatch.data.NavigationRepository
import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.NmeaConnectionState
import com.yokuli.anchorwatch.domain.model.PositionProvider
import com.yokuli.anchorwatch.location.PositionIntegrityFilter
import com.yokuli.anchorwatch.location.PositionIntegrityResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch

data class NmeaSonarPositionState(
    val acceptedFix: NavigationFix? = null,
    val trust: FixTrust? = null,
    val disposition: String = "WAITING_FOR_NMEA_POSITION",
    val reason: String? = null,
)

/**
 * Independent position-integrity stream for personal sonar mapping.
 *
 * It intentionally does not observe the anchor-watch source selector. Real
 * soundings and positions must originate from the same connected NMEA stream;
 * System GNSS is never meaningful for this data product.
 */
@Singleton
class NmeaSonarPositionRepository @Inject constructor(
    private val navigation: NavigationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val filter = PositionIntegrityFilter()
    private val _state = MutableStateFlow(NmeaSonarPositionState())
    val state = _state.asStateFlow()
    private val _acceptedPositions=MutableSharedFlow<NavigationFix>(extraBufferCapacity=32,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val acceptedPositions=_acceptedPositions.asSharedFlow()
    private val _connectionGeneration=MutableStateFlow(navigation.transportDiagnostics.value.connectionGeneration)
    val connectionGeneration=_connectionGeneration.asStateFlow()

    init {
        scope.launch {
            navigation.connectionState.collect { connection ->
                val live=connection in LIVE_CONNECTION_STATES
                if(!live||connection==NmeaConnectionState.RECONNECTING)reset("NMEA_NOT_CONNECTED")
            }
        }
        // The transport owns the generation identity. Following it directly
        // avoids guessing from transient ERROR/RECONNECTING states (which can
        // otherwise clear a held sounding twice for one reconnect), and makes
        // it impossible to carry depth/position pairing across a new socket.
        scope.launch {
            navigation.transportDiagnostics
                .map { it.connectionGeneration }
                .distinctUntilChanged()
                .drop(1)
                .collect { generation ->
                    _connectionGeneration.value=generation
                    reset("NMEA_CONNECTION_GENERATION_CHANGED")
                }
        }
        scope.launch {
            navigation.fix.filterNotNull().collect(::submit)
        }
    }

    @Synchronized
    private fun submit(rawFix: NavigationFix) {
        val connectionStarted = navigation.connectionStartedElapsed.value ?: return
        if (navigation.connectionState.value !in LIVE_CONNECTION_STATES ||
            rawFix.positionProvider != PositionProvider.NMEA ||
            rawFix.receivedElapsedRealtime < connectionStarted
        ) return
        when (val result = filter.evaluate(rawFix)) {
            is PositionIntegrityResult.Accepted -> result.fixes.forEach { accepted ->
                _state.value = NmeaSonarPositionState(
                    acceptedFix = accepted.fix,
                    trust = accepted.trust,
                    disposition = "ACCEPTED_NMEA_POSITION",
                    reason = accepted.reason,
                )
                _acceptedPositions.tryEmit(accepted.fix)
            }
            is PositionIntegrityResult.Quarantined -> _state.value = _state.value.copy(
                disposition = "QUARANTINED_NMEA_POSITION",
                reason = result.reason,
            )
            is PositionIntegrityResult.Rejected -> _state.value = _state.value.copy(
                disposition = "REJECTED_NMEA_POSITION",
                reason = result.reason,
            )
        }
    }

    @Synchronized
    private fun reset(reason: String) {
        filter.reset()
        _state.value = NmeaSonarPositionState(reason = reason)
    }

    fun hasFreshPosition(nowElapsed: Long, maxAgeMillis: Long = 2_000L): Boolean {
        if (navigation.connectionState.value != NmeaConnectionState.CONNECTED) return false
        return state.value.acceptedFix?.let { fix ->
            fix.positionProvider == PositionProvider.NMEA &&
                nowElapsed - fix.receivedElapsedRealtime in 0..maxAgeMillis
        } == true
    }

    private companion object {
        val LIVE_CONNECTION_STATES = setOf(
            NmeaConnectionState.CONNECTING,
            NmeaConnectionState.CONNECTED,
            NmeaConnectionState.CONNECTED_NO_DATA,
            NmeaConnectionState.CONNECTED_NO_FIX,
            NmeaConnectionState.STALE,
            NmeaConnectionState.RECONNECTING,
        )
    }
}

enum class SonarPositionPairingDecision {
    ALLOWED,
    POSITION_MISSING,
    WRONG_POSITION_PROVIDER,
    POSITION_STALE,
}

object SonarPositionPairingPolicy {
    fun evaluate(
        demo: Boolean,
        fix: NavigationFix?,
        observationElapsedRealtime: Long,
        maxAgeMillis: Long = 2_000L,
    ): SonarPositionPairingDecision = when {
        fix == null -> SonarPositionPairingDecision.POSITION_MISSING
        demo && fix.positionProvider != PositionProvider.DEMO -> SonarPositionPairingDecision.WRONG_POSITION_PROVIDER
        !demo && fix.positionProvider != PositionProvider.NMEA -> SonarPositionPairingDecision.WRONG_POSITION_PROVIDER
        kotlin.math.abs(observationElapsedRealtime - fix.receivedElapsedRealtime) > maxAgeMillis -> SonarPositionPairingDecision.POSITION_STALE
        else -> SonarPositionPairingDecision.ALLOWED
    }
}
