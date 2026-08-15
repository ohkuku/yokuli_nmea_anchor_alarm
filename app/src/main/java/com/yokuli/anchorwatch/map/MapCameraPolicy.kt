package com.yokuli.anchorwatch.map

import com.yokuli.anchorwatch.domain.model.GpsDataSource

internal enum class FollowCameraMove {
    CENTER_WITH_DEFAULT_ZOOM,
    CENTER_PRESERVING_ZOOM,
}

/** Keeps the first usable fix from inheriting Google Maps' temporary world-scale zoom. */
internal object MapCameraPolicy {
    const val DEFAULT_FOLLOW_ZOOM = 16f

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
