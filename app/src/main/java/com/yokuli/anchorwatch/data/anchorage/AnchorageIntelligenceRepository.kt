package com.yokuli.anchorwatch.data.anchorage

import com.google.gson.Gson
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceSummaryEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageVisitObservation
import com.yokuli.anchorwatch.domain.anchorage.PersonalAnchorageSummary
import com.yokuli.anchorwatch.domain.anchorage.PersonalAnchorageSummaryEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnchorageIntelligenceRepository @Inject constructor(private val database:AppDatabase){
    private val gson=Gson()
    suspend fun rebuild(placeId:Long):PersonalAnchorageSummary{
        val summary=PersonalAnchorageSummaryEngine.summarize(database.anchorageVisitDao().forPlaceNow(placeId).map{visit->AnchorageVisitObservation(visit.startedAt,visit.endedAt,visit.waterDepthMeters,visit.minDepthMeters,visit.maxDepthMeters,visit.rodeLengthMeters,visit.maxExcursionMeters,visit.alarmCount,visit.typicalMotionScore,visit.p95MotionScore,visit.dominantRollPeriodSeconds,visit.maxWindKnots)})
        database.anchorageMetadataDao().upsertSummary(AnchoragePlaceSummaryEntity(placeId,System.currentTimeMillis(),PersonalAnchorageSummaryEngine.VERSION,gson.toJson(summary)))
        return summary
    }
    suspend fun rebuildAll(){database.anchoragePlaceDao().allNow().forEach{rebuild(it.id)}}
    fun decode(value:AnchoragePlaceSummaryEntity?):PersonalAnchorageSummary?=value?.takeIf{it.engineVersion==PersonalAnchorageSummaryEngine.VERSION}?.let{runCatching{gson.fromJson(it.json,PersonalAnchorageSummary::class.java)}.getOrNull()}
}
