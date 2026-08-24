package com.yokuli.anchorwatch.data.vessel

import com.yokuli.anchorwatch.domain.vessel.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class VesselSourceRegistry @Inject constructor(){
    private val values=linkedMapOf<VesselMetricId,LinkedHashMap<String,VesselSourceCandidate<*>>>()
    private val _snapshot=MutableStateFlow<Map<VesselMetricId,List<VesselSourceCandidate<*>>>>(emptyMap())
    val snapshot=_snapshot.asStateFlow()

    @Synchronized fun publish(candidate:VesselSourceCandidate<*>){
        val sources=values.getOrPut(candidate.metric){linkedMapOf()};sources[candidate.source.id]=candidate
        while(sources.size>MAX_SOURCES_PER_METRIC)sources.remove(sources.minByOrNull{it.value.receivedElapsedRealtime}?.key)
        publishSnapshot()
    }
    @Synchronized fun publishAll(candidates:Iterable<VesselSourceCandidate<*>>){candidates.forEach{candidate->val sources=values.getOrPut(candidate.metric){linkedMapOf()};sources[candidate.source.id]=candidate;while(sources.size>MAX_SOURCES_PER_METRIC)sources.remove(sources.minByOrNull{it.value.receivedElapsedRealtime}?.key)};publishSnapshot()}
    @Suppress("UNCHECKED_CAST") @Synchronized fun <T> candidates(metric:VesselMetricId):List<VesselSourceCandidate<T>> = values[metric]?.values?.map{it as VesselSourceCandidate<T>}?:emptyList()
    @Synchronized fun clearTransportGeneration(profileId:String,generation:Long){values.values.forEach{sources->sources.entries.removeAll{entry->entry.value.source.transportProfileId==profileId&&entry.value.source.connectionGeneration!=generation}};publishSnapshot()}
    @Synchronized fun clearNmea(){values.values.forEach{sources->sources.entries.removeAll{it.value.source.sourceType==VesselSourceType.NMEA_INPUT}};publishSnapshot()}
    @Synchronized fun clearPhone(){values.values.forEach{sources->sources.entries.removeAll{it.value.source.sourceType==VesselSourceType.PHONE_SENSOR}};publishSnapshot()}
    @Synchronized fun removeSources(sourceIds:Set<String>){if(sourceIds.isEmpty())return;values.values.forEach{sources->sources.entries.removeAll{it.value.source.id in sourceIds}};publishSnapshot()}
    private fun publishSnapshot(){_snapshot.value=values.mapValues{(_,sources)->sources.values.sortedByDescending{it.receivedElapsedRealtime}}}
    private companion object{const val MAX_SOURCES_PER_METRIC=16}
}
