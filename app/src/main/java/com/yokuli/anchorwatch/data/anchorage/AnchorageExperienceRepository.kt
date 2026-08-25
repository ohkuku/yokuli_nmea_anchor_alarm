package com.yokuli.anchorwatch.data.anchorage

import android.app.Application
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceEvent
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceReducer
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceSnapshot
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceSnapshotCodec
import com.yokuli.anchorwatch.domain.anchorage.AnchorageExperienceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Small safety state persisted independently from map/UI state. */
@Singleton
class AnchorageExperienceRepository @Inject constructor(app: Application) {
    private val preferences = app.getSharedPreferences("anchorage_experience_v1", 0)
    private val mutable = MutableStateFlow(restore())
    val state = mutable.asStateFlow()

    @Synchronized
    fun dispatch(event: AnchorageExperienceEvent): AnchorageExperienceState {
        val updated = AnchorageExperienceReducer.reduce(mutable.value, event)
        if (updated != mutable.value) {
            mutable.value = updated
            persist(updated)
        }
        return updated
    }

    @Synchronized
    fun resetIfTargetMissing(validPlaceIds: Set<Long>, validSpotIds: Set<Long>) {
        // Room flows may emit an empty bootstrap before the restored database
        // snapshot arrives. Do not destroy a process-restored approach on that
        // transient emission; the first non-empty canonical target set validates it.
        if(validSpotIds.isEmpty())return
        val current = mutable.value
        val invalid = when (current) {
            is AnchorageExperienceState.Approaching -> current.placeId !in validPlaceIds || current.spotId !in validSpotIds
            is AnchorageExperienceState.Arrived -> current.placeId !in validPlaceIds || current.spotId !in validSpotIds
            // The Anchor session is the authority while armed. Deleting a
            // library record must not change or stop the safety session.
            is AnchorageExperienceState.Anchored -> false
            else -> false
        }
        if (invalid) {
            mutable.value = AnchorageExperienceState.Browsing
            persist(mutable.value)
        }
    }

    fun nextEpisodeId(now: Long = System.currentTimeMillis()): Long = now.coerceAtLeast(1L)

    private fun persist(state: AnchorageExperienceState) {
        val value = AnchorageExperienceSnapshotCodec.encode(state)
        preferences.edit()
            .putString("kind", value.kind)
            .putLong("episode", value.episodeId ?: 0L)
            .putLong("place", value.placeId ?: 0L)
            .putLong("spot", value.spotId ?: 0L)
            .putLong("started", value.startedAt ?: 0L)
            .putLong("session", value.anchorSessionId ?: 0L)
            .putString("places", value.placeIds.sorted().joinToString(","))
            .apply()
    }

    private fun restore(): AnchorageExperienceState {
        val kind = preferences.getString("kind", null) ?: return AnchorageExperienceState.Browsing
        fun positive(key: String) = preferences.getLong(key, 0L).takeIf { it > 0L }
        val placeIds = preferences.getString("places", "").orEmpty().split(',')
            .mapNotNull(String::toLongOrNull)
            .filterTo(linkedSetOf()) { it > 0L }
        return AnchorageExperienceSnapshotCodec.decode(
            AnchorageExperienceSnapshot(
                kind = kind,
                episodeId = positive("episode"),
                placeId = positive("place"),
                spotId = positive("spot"),
                startedAt = positive("started"),
                anchorSessionId = positive("session"),
                placeIds = placeIds,
            ),
        )
    }
}
