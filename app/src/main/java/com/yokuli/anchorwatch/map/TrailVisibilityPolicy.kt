package com.yokuli.anchorwatch.map

import com.yokuli.anchorwatch.data.database.TrackPointEntity

/** Keeps a useful overnight breadcrumb without handing an unbounded point list to Google Maps. */
object TrailVisibilityPolicy {
    const val VISIBLE_WINDOW_MILLIS = 24L * 60L * 60L * 1_000L
    const val MAX_RENDER_POINTS = 4_800

    fun visiblePoints(points: List<TrackPointEntity>): List<TrackPointEntity> {
        if (points.size <= 2) return points
        val cutoff = points.last().timestamp - VISIBLE_WINDOW_MILLIS
        val firstVisible = points.binarySearch { it.timestamp.compareTo(cutoff) }
            .let { if (it >= 0) it else (-it - 1).coerceAtMost(points.lastIndex) }
            .coerceAtMost((points.lastIndex - 1).coerceAtLeast(0))
        return downsample(points.subList(firstVisible, points.size), MAX_RENDER_POINTS)
    }

    private fun downsample(points: List<TrackPointEntity>, maximum: Int): List<TrackPointEntity> {
        if (points.size <= maximum) return points
        val last = points.lastIndex
        return (0 until maximum).map { outputIndex ->
            points[(outputIndex.toLong() * last / (maximum - 1)).toInt()]
        }
    }
}
