package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.GpsDataSource

/** System GPS is not independent while Android fused location is in mock mode. */
object GpsSourceSafety {
    fun blocksSystemGps(mockEnabledSetting: Boolean, mockState: MockGpsState): Boolean =
        mockEnabledSetting || mockState == MockGpsState.STARTING || mockState == MockGpsState.ACTIVE

    /** Every state that blocks an independent System-GNSS handover must expose
     * an immediate Stop action. STALE is deliberately excluded because its
     * transition has already disabled Android mock mode and restored GNSS. */
    fun requiresStopAction(mockEnabledSetting:Boolean,mockState:MockGpsState):Boolean=
        blocksSystemGps(mockEnabledSetting,mockState)

    /**
     * A running watch never changes source from a settings tap. A paused live
     * session may perform a verified System/NMEA handover while retaining its
     * centre, range, track and identity. Demo remains isolated for the entire
     * session because its coordinates are synthetic.
     */
    fun allowsSessionSource(
        hasOpenSession:Boolean,
        sessionPaused:Boolean,
        lockedSource:GpsDataSource?,
        requestedSource:GpsDataSource,
    ):Boolean = when {
        !hasOpenSession || lockedSource==requestedSource -> true
        !sessionPaused -> false
        lockedSource==GpsDataSource.DEMO || requestedSource==GpsDataSource.DEMO -> false
        else -> true
    }
}
