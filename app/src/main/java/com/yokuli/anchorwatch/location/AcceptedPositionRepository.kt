package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.FixTrust
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.HeadingQuality
import com.yokuli.anchorwatch.domain.model.HeadingSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionHealth
import com.yokuli.anchorwatch.domain.vessel.VesselSourcePreference
import com.yokuli.anchorwatch.data.vessel.VesselSettingsRepository
import com.yokuli.anchorwatch.data.vessel.VesselDataHub
import com.yokuli.anchorwatch.location.vessel.VesselMountCalibrationRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    /** Transport generation that produced [acceptedFix]. Only meaningful for NMEA. */
    val acceptedConnectionGeneration: Long? = null,
    val integrityLastDurationMicros: Long = 0,
    val integrityMaxDurationMicros: Long = 0,
    val headingEvidence:AnchorHeadingEvidence=AnchorHeadingEvidence(reason="NOT_EVALUATED"),
)

data class AcceptedPositionEvent(
    val source: GpsDataSource,
    val accepted: IntegrityAcceptedFix,
    val headingEvidence:AnchorHeadingEvidence=AnchorHeadingEvidence(reason="NOT_REPORTED"),
    /** Present only for NMEA. Consumers must reject an event after reconnect. */
    val connectionGeneration:Long?=null,
)

/**
 * The process-wide navigation truth. Raw provider fixes may be inspected in
 * diagnostics, but every safety/output consumer must observe this repository.
 */
@Singleton
class AcceptedPositionRepository @Inject constructor(
    private val phoneHeading: PhoneHeadingRepository,
    private val phoneMotion: PhoneMotionRepository,
    private val vesselDataHub:VesselDataHub,
    vesselSettings:VesselSettingsRepository,
    mountCalibration:VesselMountCalibrationRepository,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val filter = PositionIntegrityFilter()
    private val _state = MutableStateFlow(AcceptedPositionState())
    val state = _state.asStateFlow()
    private val _accepted = MutableSharedFlow<AcceptedPositionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val accepted = _accepted.asSharedFlow()
    @Volatile private var headingPreference=VesselSourcePreference.AUTO
    @Volatile private var headingSourceExplicitlyPinned=false
    @Volatile private var phoneHeadingAlignment=PhoneVesselHeadingAlignment()
    private var lastSubmissionKey: List<Any?>? = null

    init{
        scope.launch{vesselSettings.settings.collect{headingPreference=it.headingPreference;headingSourceExplicitlyPinned=!it.boatHeadingSourceId.isNullOrBlank()}}
        scope.launch{mountCalibration.calibration.collect{phoneHeadingAlignment=PhoneVesselHeadingAlignment(it.headingAligned,it.headingAlignmentOffsetDegrees)}}
    }

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
            acceptedConnectionGeneration = null,
        )
    }

    @Synchronized
    fun unlockSource(sessionId: Long?) {
        val current = _state.value
        if (current.lockedSessionId == null || (sessionId != null && current.lockedSessionId != sessionId)) return
        _state.value = current.copy(lockedSessionId = null)
    }

    @Synchronized
    fun seed(source: GpsDataSource, fix: NavigationFix, sessionId: Long? = null) {
        if (sessionId != null) lockSource(sessionId, source) else changeSourceIfNeeded(source)
        filter.seed(fix)
        lastSubmissionKey = listOf(source, fix.receivedElapsedRealtime, fix.latitude, fix.longitude, fix.sourceSentence, null)
        _state.value = _state.value.copy(
            rawFix = fix,
            acceptedFix = fix,
            trust = FixTrust.DEGRADED,
            health = PositionHealth.GPS_DEGRADED,
            disposition = "SEEDED_FROM_PERSISTED_ACCEPTED_FIX",
            reason = "AWAITING_FRESH_CONFIRMATION",
            lastAcceptedElapsedRealtime = fix.receivedElapsedRealtime,
            acceptedConnectionGeneration = null,
        )
    }

    @Synchronized
    fun submit(
        source: GpsDataSource,
        rawFix: NavigationFix,
        connectionGeneration: Long? = null,
        emitAcceptedEvents:Boolean = true,
    ):List<AcceptedPositionEvent> {
        if (_state.value.selectedSource != source) return emptyList()
        // Multiple sentences can be parsed in the same monotonic millisecond.
        // Coordinates are part of the identity so a genuinely newer position
        // is never mistaken for the queued/direct delivery of the same fix.
        val key = listOf(source, rawFix.receivedElapsedRealtime, rawFix.latitude, rawFix.longitude, rawFix.sourceSentence, connectionGeneration)
        if (lastSubmissionKey == key) return emptyList()
        lastSubmissionKey = key
        phoneHeading.setPosition(rawFix.latitude, rawFix.longitude, rawFix.altitudeMeters, rawFix.timestampUtcMillis)
        val phone = phoneHeading.sample.value
        val now = rawFix.receivedElapsedRealtime
        // Held NMEA fields remain visible in diagnostics and VesselDataHub, but
        // safety/estimator input must not treat an old component as a fresh one
        // merely because a newer position-only sentence arrived.
        val safetyFix = if (rawFix.positionProvider == com.yokuli.anchorwatch.domain.model.PositionProvider.NMEA) rawFix.copy(
            sogKnots=rawFix.sogKnots.takeIf{rawFix.sogReceivedElapsedRealtime.isFreshAt(now)},
            cogTrueDegrees=rawFix.cogTrueDegrees.takeIf{rawFix.cogReceivedElapsedRealtime.isFreshAt(now)},
            headingTrueDegrees=rawFix.headingTrueDegrees.takeIf{rawFix.headingReceivedElapsedRealtime.isFreshAt(now)},
            headingMagneticDegrees=rawFix.headingMagneticDegrees.takeIf{rawFix.headingMagneticReceivedElapsedRealtime.isFreshAt(now)},
        ) else rawFix
        val vesselSnapshot=vesselDataHub.snapshot.value
        val evidence=AnchorHeadingEvidenceRouter.routeSelected(headingPreference,vesselSnapshot.headingTrueDegrees,vesselSnapshot.conflicts[com.yokuli.anchorwatch.domain.vessel.VesselMetricId.HEADING_TRUE]?:vesselSnapshot.headingTrueDegrees.conflict,headingSourceExplicitlyPinned,phone,phoneHeadingAlignment)
        val fix=safetyFix.copy(
            headingTrueDegrees=evidence.trueDegrees,
            headingReceivedElapsedRealtime=when(evidence.source){HeadingSource.PHONE->phone.receivedElapsedRealtime;HeadingSource.NMEA_PHYSICAL->safetyFix.headingReceivedElapsedRealtime;else->null},
            headingSource=evidence.source,headingQuality=evidence.quality,headingEpoch=evidence.epoch,headingSampleSequence=evidence.sequence,
        )
        val integrityStarted = System.nanoTime()
        val result = filter.evaluate(fix, phoneMotion.state.value.takeIf { source == GpsDataSource.SYSTEM })
        val integrityMicros = ((System.nanoTime() - integrityStarted) / 1_000L).coerceAtLeast(0L)
        val integrityMaxMicros = maxOf(_state.value.integrityMaxDurationMicros, integrityMicros)
        return when (result) {
            is PositionIntegrityResult.Accepted -> {
                result.fixes.map { accepted ->
                    _state.value = _state.value.copy(
                        rawFix = rawFix,
                        acceptedFix = accepted.fix,
                        trust = accepted.trust,
                        health = if (accepted.trust == FixTrust.TRUSTED) PositionHealth.GPS_OK else PositionHealth.GPS_DEGRADED,
                        disposition = "ACCEPTED",
                        reason = accepted.reason,
                        lastAcceptedElapsedRealtime = accepted.fix.receivedElapsedRealtime,
                        acceptedConnectionGeneration = connectionGeneration.takeIf { source == GpsDataSource.NMEA },
                        integrityLastDurationMicros = integrityMicros,
                        integrityMaxDurationMicros = integrityMaxMicros,
                        headingEvidence = evidence,
                    )
                    AcceptedPositionEvent(source,accepted,evidence,connectionGeneration.takeIf{source==GpsDataSource.NMEA}).also{event->
                        if(emitAcceptedEvents)_accepted.tryEmit(event)
                    }
                }
            }
            is PositionIntegrityResult.Quarantined -> {
                _state.value = _state.value.copy(
                    rawFix = rawFix,
                    health = PositionHealth.GPS_DEGRADED,
                    disposition = "QUARANTINED",
                    reason = result.reason,
                    integrityLastDurationMicros = integrityMicros,
                    integrityMaxDurationMicros = integrityMaxMicros,
                    headingEvidence = evidence,
                )
                emptyList()
            }
            is PositionIntegrityResult.Rejected -> {
                _state.value = _state.value.copy(
                    rawFix = rawFix,
                    health = if (_state.value.acceptedFix == null) PositionHealth.GPS_LOST else PositionHealth.GPS_DEGRADED,
                    disposition = "REJECTED",
                    reason = result.reason,
                    integrityLastDurationMicros = integrityMicros,
                    integrityMaxDurationMicros = integrityMaxMicros,
                    headingEvidence = evidence,
                )
                emptyList()
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

    private fun Long?.isFreshAt(now:Long,maxAgeMillis:Long=10_000L)=this?.let{now-it in 0L..maxAgeMillis}==true
}
