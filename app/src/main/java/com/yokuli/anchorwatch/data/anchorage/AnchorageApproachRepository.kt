package com.yokuli.anchorwatch.data.anchorage

import com.yokuli.anchorwatch.data.database.AnchorageDao
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterer
import com.yokuli.anchorwatch.domain.anchorage.SavedAnchorageReference
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Approach geometry is deliberately sourced only from explicitly saved anchorages.
 * AnchorSession history and sourceSessionId provenance never enter this stream.
 */
@Singleton
class AnchorageApproachRepository @Inject constructor(
    dao: AnchorageDao,
) {
    val clusters = dao.anchorages()
        .map { saved -> AnchorageClusterer.cluster(saved.map(SavedAnchorageEntity::toApproachReference)) }
        .distinctUntilChanged()
}

private fun SavedAnchorageEntity.toApproachReference() = SavedAnchorageReference(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    preferredAlarmRadiusMeters = preferredAlarmRadiusMeters,
    typicalWaterDepthMeters = typicalWaterDepthMeters,
    typicalRodeLengthMeters = typicalRodeLengthMeters,
    seabedType = seabedType,
    rating = rating,
    notes = notes,
    sourceSessionId = sourceSessionId,
    updatedAt = updatedAt,
    lastVisitedAt = lastVisitedAt,
)
