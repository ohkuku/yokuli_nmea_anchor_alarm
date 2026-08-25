package com.yokuli.anchorwatch.domain.anchorage

/**
 * One authoritative product state for saved-anchorage discovery and guidance.
 * IDs are durable Room Place/Spot IDs; map clusters are presentation only and
 * must never be persisted here.
 */
sealed interface AnchorageExperienceState {
    data object Browsing : AnchorageExperienceState
    data class Nearby(val episodeId: Long, val placeIds: Set<Long>) : AnchorageExperienceState {
        init { require(episodeId > 0); require(placeIds.isNotEmpty()) }
    }
    data class Approaching(
        val episodeId: Long,
        val placeId: Long,
        val spotId: Long,
        val startedAt: Long,
    ) : AnchorageExperienceState {
        init { require(episodeId > 0); require(placeId > 0); require(spotId > 0) }
    }
    data class Arrived(val episodeId: Long, val placeId: Long, val spotId: Long) : AnchorageExperienceState {
        init { require(episodeId > 0); require(placeId > 0); require(spotId > 0) }
    }
    data class Anchored(val placeId: Long?, val spotId: Long?, val anchorSessionId: Long) : AnchorageExperienceState {
        init {
            require(anchorSessionId > 0)
            require(placeId != null || spotId == null) { "A linked Spot requires its Place" }
        }
    }
    data class DepartureCooldown(val suppressedPlaceIds: Set<Long>) : AnchorageExperienceState {
        init { require(suppressedPlaceIds.isNotEmpty()) }
    }
}

sealed interface AnchorageExperienceEvent {
    data class NearbyDetected(val episodeId: Long, val placeIds: Set<Long>) : AnchorageExperienceEvent
    data object NearbyCleared : AnchorageExperienceEvent
    data class StartApproach(
        val episodeId: Long,
        val placeId: Long,
        val spotId: Long,
        val startedAt: Long,
    ) : AnchorageExperienceEvent
    data object TargetAreaEntered : AnchorageExperienceEvent
    data class CancelApproach(val placesInsideRearmZone: Set<Long>) : AnchorageExperienceEvent
    data class AnchorStarted(val anchorSessionId: Long, val placeId: Long?, val spotId: Long?) : AnchorageExperienceEvent
    data class AnchorLifted(val placesInsideRearmZone: Set<Long>) : AnchorageExperienceEvent
    data class RearmZoneExited(val placeIds: Set<Long>) : AnchorageExperienceEvent
}

object AnchorageExperienceReducer {
    fun reduce(state: AnchorageExperienceState, event: AnchorageExperienceEvent): AnchorageExperienceState = when (event) {
        is AnchorageExperienceEvent.AnchorStarted -> AnchorageExperienceState.Anchored(
            event.placeId,
            event.spotId,
            event.anchorSessionId,
        )

        is AnchorageExperienceEvent.AnchorLifted -> when {
            state !is AnchorageExperienceState.Anchored -> state
            event.placesInsideRearmZone.isEmpty() -> AnchorageExperienceState.Browsing
            else -> AnchorageExperienceState.DepartureCooldown(event.placesInsideRearmZone)
        }

        is AnchorageExperienceEvent.StartApproach -> when (state) {
            is AnchorageExperienceState.Anchored -> state
            else -> AnchorageExperienceState.Approaching(
                event.episodeId,
                event.placeId,
                event.spotId,
                event.startedAt,
            )
        }

        AnchorageExperienceEvent.TargetAreaEntered -> when (state) {
            is AnchorageExperienceState.Approaching -> AnchorageExperienceState.Arrived(
                state.episodeId,
                state.placeId,
                state.spotId,
            )
            else -> state
        }

        is AnchorageExperienceEvent.CancelApproach -> when (state) {
            is AnchorageExperienceState.Approaching,
            is AnchorageExperienceState.Arrived,
            -> if (event.placesInsideRearmZone.isEmpty()) AnchorageExperienceState.Browsing
            else AnchorageExperienceState.DepartureCooldown(event.placesInsideRearmZone)
            else -> state
        }

        is AnchorageExperienceEvent.NearbyDetected -> when (state) {
            is AnchorageExperienceState.Browsing -> event.placeIds.takeIf { it.isNotEmpty() }
                ?.let { AnchorageExperienceState.Nearby(event.episodeId, it) }
                ?: state
            is AnchorageExperienceState.Nearby -> {
                val sameEpisode = state.episodeId == event.episodeId
                val ids = if (sameEpisode) state.placeIds + event.placeIds else event.placeIds
                ids.takeIf(Set<Long>::isNotEmpty)?.let { AnchorageExperienceState.Nearby(event.episodeId, it) }
                    ?: AnchorageExperienceState.Browsing
            }
            is AnchorageExperienceState.DepartureCooldown -> {
                val eligible = event.placeIds - state.suppressedPlaceIds
                if (eligible.isEmpty()) state else AnchorageExperienceState.Nearby(event.episodeId, eligible)
            }
            // A target cannot also be Nearby. An active watch also suppresses discovery.
            is AnchorageExperienceState.Approaching,
            is AnchorageExperienceState.Arrived,
            is AnchorageExperienceState.Anchored,
            -> state
        }

        AnchorageExperienceEvent.NearbyCleared -> when (state) {
            is AnchorageExperienceState.Nearby -> AnchorageExperienceState.DepartureCooldown(state.placeIds)
            else -> state
        }

        is AnchorageExperienceEvent.RearmZoneExited -> when (state) {
            is AnchorageExperienceState.Nearby -> {
                val remaining = state.placeIds - event.placeIds
                if (remaining.isEmpty()) AnchorageExperienceState.Browsing
                else AnchorageExperienceState.Nearby(state.episodeId, remaining)
            }
            is AnchorageExperienceState.DepartureCooldown -> {
                val remaining = state.suppressedPlaceIds - event.placeIds
                if (remaining.isEmpty()) AnchorageExperienceState.Browsing
                else AnchorageExperienceState.DepartureCooldown(remaining)
            }
            else -> state
        }
    }
}

data class AnchorageSpotApproachTarget(
    val placeId: Long,
    val spotId: Long,
    val placeName: String,
    val spotName: String,
    val latitude: Double,
    val longitude: Double,
    val areaRadiusMeters: Double,
    val coordinateEstimated: Boolean,
    val alarmRadiusMeters: Double?,
    val waterDepthMeters: Double?,
    val rodeMeters: Double?,
) {
    init {
        require(placeId > 0 && spotId > 0)
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(areaRadiusMeters.isFinite() && areaRadiusMeters >= 0.0)
    }
}

data class AnchorageSpotApproachState(
    val target: AnchorageSpotApproachTarget? = null,
    val distanceToCentreMeters: Double? = null,
    val distanceToAreaMeters: Double? = null,
    val targetBearingTrueDegrees: Double? = null,
    val relativeBearingDegrees: Double? = null,
    val directionReference: ApproachDirectionReference = ApproachDirectionReference.NORTH_UP,
    val phase: ApproachPhase = ApproachPhase.IDLE,
    val positionAvailable: Boolean = false,
)

object AnchorageSpotApproachEngine {
    fun evaluate(
        targets: List<AnchorageSpotApproachTarget>,
        selectedSpotId: Long?,
        positionLatitude: Double?,
        positionLongitude: Double?,
        direction: ((Double) -> ApproachDirection)? = null,
    ): AnchorageSpotApproachState {
        val target = targets.firstOrNull { it.spotId == selectedSpotId }
            ?: return AnchorageSpotApproachState(positionAvailable = positionLatitude != null && positionLongitude != null)
        if (positionLatitude == null || positionLongitude == null) return AnchorageSpotApproachState(
            target = target,
            phase = ApproachPhase.APPROACHING,
            positionAvailable = false,
        )
        val centreDistance = com.yokuli.anchorwatch.domain.anchor.AnchorGeometry.distanceMeters(
            positionLatitude,
            positionLongitude,
            target.latitude,
            target.longitude,
        )
        val areaDistance = (centreDistance - target.areaRadiusMeters).coerceAtLeast(0.0)
        val bearing = com.yokuli.anchorwatch.domain.anchor.AnchorGeometry.bearingDegrees(
            positionLatitude,
            positionLongitude,
            target.latitude,
            target.longitude,
        )
        val resolved = direction?.invoke(bearing)
        val phase = when {
            areaDistance == 0.0 -> ApproachPhase.INSIDE_AREA
            areaDistance <= AnchorageApproachEngine.NEAR_DISTANCE_METERS -> ApproachPhase.NEAR
            else -> ApproachPhase.APPROACHING
        }
        return AnchorageSpotApproachState(
            target = target,
            distanceToCentreMeters = centreDistance,
            distanceToAreaMeters = areaDistance,
            targetBearingTrueDegrees = bearing,
            relativeBearingDegrees = resolved?.relativeBearingDegrees ?: ApproachDirectionPolicy.signedAngle(bearing),
            directionReference = resolved?.reference ?: ApproachDirectionReference.NORTH_UP,
            phase = phase,
            positionAvailable = true,
        )
    }
}

data class AnchorageExperienceSnapshot(
    val kind: String,
    val episodeId: Long? = null,
    val placeId: Long? = null,
    val spotId: Long? = null,
    val startedAt: Long? = null,
    val anchorSessionId: Long? = null,
    val placeIds: Set<Long> = emptySet(),
)

object AnchorageExperienceSnapshotCodec {
    fun encode(state: AnchorageExperienceState) = when (state) {
        AnchorageExperienceState.Browsing -> AnchorageExperienceSnapshot("BROWSING")
        is AnchorageExperienceState.Nearby -> AnchorageExperienceSnapshot("NEARBY", state.episodeId, placeIds = state.placeIds)
        is AnchorageExperienceState.Approaching -> AnchorageExperienceSnapshot("APPROACHING", state.episodeId, state.placeId, state.spotId, state.startedAt)
        is AnchorageExperienceState.Arrived -> AnchorageExperienceSnapshot("ARRIVED", state.episodeId, state.placeId, state.spotId)
        is AnchorageExperienceState.Anchored -> AnchorageExperienceSnapshot("ANCHORED", placeId = state.placeId, spotId = state.spotId, anchorSessionId = state.anchorSessionId)
        is AnchorageExperienceState.DepartureCooldown -> AnchorageExperienceSnapshot("DEPARTURE_COOLDOWN", placeIds = state.suppressedPlaceIds)
    }

    fun decode(value: AnchorageExperienceSnapshot): AnchorageExperienceState = runCatching {
        when (value.kind) {
            "NEARBY" -> AnchorageExperienceState.Nearby(requireNotNull(value.episodeId), value.placeIds)
            "APPROACHING" -> AnchorageExperienceState.Approaching(requireNotNull(value.episodeId), requireNotNull(value.placeId), requireNotNull(value.spotId), requireNotNull(value.startedAt))
            "ARRIVED" -> AnchorageExperienceState.Arrived(requireNotNull(value.episodeId), requireNotNull(value.placeId), requireNotNull(value.spotId))
            "ANCHORED" -> AnchorageExperienceState.Anchored(value.placeId, value.spotId, requireNotNull(value.anchorSessionId))
            "DEPARTURE_COOLDOWN" -> AnchorageExperienceState.DepartureCooldown(value.placeIds)
            else -> AnchorageExperienceState.Browsing
        }
    }.getOrDefault(AnchorageExperienceState.Browsing)
}
