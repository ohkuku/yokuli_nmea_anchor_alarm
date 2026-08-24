package com.yokuli.anchorwatch.data.anchorage.gis

import com.yokuli.anchorwatch.domain.anchorage.AnchorageRegionCandidate
import com.yokuli.anchorwatch.domain.anchorage.AnchorageRegionCandidateRanker
import com.yokuli.anchorwatch.domain.anchorage.AnchorageRegionFeatureType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class AnchorageRegionCandidateService @Inject constructor(private val cached:CachedRegionProvider,private val user:UserRegionProvider,private val linz:LinzGazetteerProvider){
    suspend fun resolve(latitude:Double,longitude:Double,radiusMeters:Double=15_000.0,forceRefresh:Boolean=false):List<AnchorageRegionCandidate>{
        val local=listOf(user,cached).flatMap{it.resolveCandidates(latitude,longitude,radiusMeters).getOrDefault(emptyList())}
        val sufficient=local.any{it.containsPoint&&it.featureType in SPECIFIC_MARINE_FEATURES&&(it.provider=="USER"||it.official)}
        val online=if(forceRefresh||!sufficient)linz.resolveCandidates(latitude,longitude,radiusMeters).getOrDefault(emptyList()) else emptyList()
        return AnchorageRegionCandidateRanker.rank(local+online)
    }
    companion object{private val SPECIFIC_MARINE_FEATURES=setOf(AnchorageRegionFeatureType.BAY,AnchorageRegionFeatureType.COVE,AnchorageRegionFeatureType.INLET,AnchorageRegionFeatureType.HARBOUR)}
}
