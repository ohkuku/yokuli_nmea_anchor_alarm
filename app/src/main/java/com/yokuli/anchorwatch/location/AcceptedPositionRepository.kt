package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionHealth
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AcceptedPositionState(
    val selectedSource: GpsDataSource = GpsDataSource.SYSTEM,
    val lockedSessionId: Long? = null,
    val rawFix: NavigationFix? = null,
    val acceptedFix: NavigationFix? = null,
    val trust: FixTrust? = null,
    val health: PositionHealth = PositionHealth.GPS_LOST,
    val disposition: String = "WAITING",
    val reason: String? = null,
    val lastAcceptedElapsedRealtime: Long? = null,
)

data class AcceptedPositionEvent(
    val source: GpsDataSource,
    val accepted: IntegrityAcceptedFix,
)

/**
 * The process-wide navigation truth. Raw provider fixes may be inspected in
 * diagnostics, but every safety/output consumer must observe this repository.
 */
@Singleton
class AcceptedPositionRepository @Inject constructor(
    private val phoneHeading: PhoneHeadingRepository,
    private val phoneMotion: PhoneMotionRepository,
) {
    private val filter = PositionIntegrityFilter()
    private val _state = MutableStateFlow(AcceptedPositionState())
    val state = _state.asStateFlow()
    private val _accepted = MutableSharedFlow<AcceptedPositionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val accepted = _accepted.asSharedFlow()
    private var phoneHeadingEvidenceEnabled = false
    private var lastSubmissionKey: Triple<GpsDataSource, Long, String>? = null

    @Synchronized
    fun selectSource(source: GpsDataSource): Boolean {
        val current = _state.value
        if (current.lockedSessionId != null && current.selectedSource != source) return false
        changeSourceIfNeeded(source)
        return true
    }

    @Synchronized
    fun lockSource(sessionId: Long, source: GpsDataSource) {
        val current = _state.value
        if (current.lockedSessionId == sessionId && current.selectedSource == source) return
        filter.reset()
        lastSubmissionKey = null
        _state.value = current.copy(
            selectedSource = source,
            lockedSessionId = sessionId,
            rawFix = null,
            acceptedFix = null,
            trust = null,
            health = PositionHealth.GPS_LOST,
            disposition = "WAITING",
            reason = null,
            lastAcceptedElapsedRealtime = null,
        )
    }

    @Synchronized
    fun unlockSource(sessionId: Long?) {
        val current = _state.value
        if (current.lockedSessionId == null || (sessionId != null && current.lockedSessionId != sessionId)) return
        _state.value = current.copy(lockedSessionId = null)
    }

    @Synchronized
    fun setPhoneHeadingEvidenceEnabled(enabled: Boolean) {
        phoneHeadingEvidenceEnabled = enabled
    }

    @Synchronized
    fun seed(source: GpsDataSource, fix: NavigationFix, sessionId: Long? = null) {
        if (sessionId != null) lockSource(sessionId, source) else changeSourceIfNeeded(source)
        filter.seed(fix)
        lastSubmissionKey = Triple(source, fix.receivedElapsedRealtime, fix.sourceSentence)
        _state.value = _state.value.copy(
            rawFix = fix,
            acceptedFix = fix,
            trust = FixTrust.DEGRADED,
            health = PositionHealth.GPS_DEGRADED,
            disposition = "SEEDED_FROM_PERSISTED_ACCEPTED_FIX",
            reason = "AWAITING_FRESH_CONFIRMATION",
            lastAcceptedElapsedRealtime = fix.receivedElapsedRealtime,
        )
    }

    @Synchronized
    fun submit(source: GpsDataSource, rawFix: NavigationFix) {
        if (_state.value.selectedSource != source) return
        val key = Triple(source, rawFix.receivedElapsedRealtime, rawFix.sourceSentence)
        if (lastSubmissionKey == key) return
        lastSubmissionKey = key
        phoneHeading.setPosition(rawFix.latitude, rawFix.longitude, rawFix.altitudeMeters, rawFix.timestampUtcMillis)
        val phone = phoneHeading.sample.value
        // Phone orientation is optional estimator evidence for either selected
        // position source. A physical NMEA heading always wins when present.
        val usePhone = phoneHeadingEvidenceEnabled && rawFix.headingTrueDegrees == null
        val fix = if (usePhone) rawFix.copy(
            headingTrueDegrees = phone.trueHeadingDegrees,
            headingSource = if (phone.trueHeadingDegrees != null) HeadingSource.PHONE else HeadingSource.NONE,
            headingQuality = phone.quality,
            headingEpoch = phone.epoch,
            headingSampleSequence = phone.sequence,
        ) else rawFix.copy(
            headingSource = rawFix.headingSource.takeIf { rawFix.headingTrueDegrees != null } ?: HeadingSource.NONE,
            headingQuality = rawFix.headingQuality.takeIf { rawFix.headingTrueDegrees != null } ?: HeadingQuality.UNAVAILABLE,
        )
        when (val result = filter.evaluate(fix, phoneMotion.state.value.takeIf { source == GpsDataSource.SYSTEM })) {
            is PositionIntegrityResult.Accepted -> {
                result.fixes.forEach { accepted ->
                    _state.value = _state.value.copy(
                        rawFix = rawFix,
                        acceptedFix = accepted.fix,
                        trust = accepted.trust,
                        health = if (accepted.trust == FixTrust.TRUSTED) PositionHealth.GPS_OK else PositionHealth.GPS_DEGRADED,
                        disposition = "ACCEPTED",
                        reason = accepted.reason,
                        lastAcceptedElapsedRealtime = accepted.fix.receivedElapsedRealtime,
                    )
                    _accepted.tryEmit(AcceptedPositionEvent(source, accepted))
                }
            }
            is PositionIntegrityResult.Quarantined -> {
                _state.value = _state.value.copy(
                    rawFix = rawFix,
                    health = PositionHealth.GPS_DEGRADED,
                    disposition = "QUARANTINED",
                    reason = result.reason,
                )
            }
            is PositionIntegrityResult.Rejected -> {
                _state.value = _state.value.copy(
                    rawFix = rawFix,
                    health = if (_state.value.acceptedFix == null) PositionHealth.GPS_LOST else PositionHealth.GPS_DEGRADED,
                    disposition = "REJECTED",
                    reason = result.reason,
                )
            }
        }
    }

    @Synchronized
    private fun changeSourceIfNeeded(source: GpsDataSource) {
        if (_state.value.selectedSource == source) return
        filter.reset()
        lastSubmissionKey = null
        _state.value = AcceptedPositionState(selectedSource = source)
    }
}
