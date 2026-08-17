package com.yokuli.anchorwatch.map

import com.yokuli.anchorwatch.domain.model.GpsDataSource

internal enum class FollowCameraMove {
    CENTER_WITH_DEFAULT_ZOOM,
    CENTER_PRESERVING_ZOOM,
}

internal data class MapGesturePolicy(
    val scrollEnabled: Boolean,
    val zoomEnabled: Boolean,
    val rotationEnabled: Boolean,
    val tiltEnabled: Boolean,
)

/** Keeps the first usable fix from inheriting Google Maps' temporary world-scale zoom. */
internal object MapCameraPolicy {
    const val DEFAULT_FOLLOW_ZOOM = 16f
    const val LOCKED_GESTURE_RETURN_DELAY_MILLIS = 1_200L

    /** Follow mode still permits panning and zooming; heading/tilt stay stable for an anchor watch. */
    fun gestures(mapLockedToBoat: Boolean) = MapGesturePolicy(
        scrollEnabled = true,
        zoomEnabled = true,
        rotationEnabled = !mapLockedToBoat,
        tiltEnabled = !mapLockedToBoat,
    )

    fun nextMove(
        hasCenteredOnFix: Boolean,
        previousSource: GpsDataSource?,
        currentSource: GpsDataSource,
    ): FollowCameraMove = if (!hasCenteredOnFix || previousSource != currentSource) {
        FollowCameraMove.CENTER_WITH_DEFAULT_ZOOM
    } else {
        FollowCameraMove.CENTER_PRESERVING_ZOOM
    }
}
