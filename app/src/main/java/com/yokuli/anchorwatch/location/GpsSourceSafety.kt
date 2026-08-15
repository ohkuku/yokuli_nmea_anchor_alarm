package com.yokuli.anchorwatch.location

/** System GPS is not independent while Android fused location is in mock mode. */
object GpsSourceSafety {
    fun blocksSystemGps(mockEnabledSetting: Boolean, mockState: MockGpsState): Boolean =
        mockEnabledSetting || mockState == MockGpsState.STARTING || mockState == MockGpsState.ACTIVE
}
