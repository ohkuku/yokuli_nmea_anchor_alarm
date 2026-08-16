package com.yokuli.anchorwatch.location

import com.yokuli.anchorwatch.domain.model.GpsDataSource

/** System GPS is not independent while Android fused location is in mock mode. */
object GpsSourceSafety {
    fun blocksSystemGps(mockEnabledSetting: Boolean, mockState: MockGpsState): Boolean =
        mockEnabledSetting || mockState == MockGpsState.STARTING || mockState == MockGpsState.ACTIVE

    /** Pause changes alarm activity, never ownership of the session source. */
    fun allowsSessionSource(hasOpenSession:Boolean,lockedSource:GpsDataSource?,requestedSource:GpsDataSource):Boolean =
        !hasOpenSession || lockedSource == requestedSource
}
