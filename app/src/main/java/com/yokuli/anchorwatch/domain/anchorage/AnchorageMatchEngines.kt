package com.yokuli.anchorwatch.domain.anchorage

import kotlin.math.max
import kotlin.math.min

enum class AnchorageSpotMatch { LIKELY_SAME, POSSIBLY_SAME, DISTINCT }
data class AnchorageSpotMatchCandidate(val latitude:Double,val longitude:Double,val uncertaintyMeters:Double?,val preferredAlarmRadiusMeters:Double?)
data class AnchorageSpotMatchResult(val match:AnchorageSpotMatch,val distanceMeters:Double,val effectiveThresholdMeters:Double,val uncertaintyOverlaps:Boolean)

object AnchorageSpotMatchEngine {
    fun evaluate(candidate:AnchorageSpotMatchCandidate,spot:AnchorageSpotMatchCandidate,samePlace:Boolean):AnchorageSpotMatchResult{
        val distance=AnchorageGeometryOps.distance(AnchorageGeoPoint(candidate.latitude,candidate.longitude),AnchorageGeoPoint(spot.latitude,spot.longitude))
        val firstUncertainty=candidate.uncertaintyMeters.validNonNegative()
        val secondUncertainty=spot.uncertaintyMeters.validNonNegative()
        val preferred=listOfNotNull(candidate.preferredAlarmRadiusMeters.validPositive(),spot.preferredAlarmRadiusMeters.validPositive()).maxOrNull()
        val threshold=max(20.0,max(firstUncertainty?:0.0,max(secondUncertainty?:0.0,min((preferred?:0.0)*0.5,75.0))))
        val overlaps=distance<=(firstUncertainty?:0.0)+(secondUncertainty?:0.0)
        val result=when{
            !samePlace->AnchorageSpotMatch.DISTINCT
            overlaps||distance<=threshold->AnchorageSpotMatch.LIKELY_SAME
            distance<=threshold*1.75->AnchorageSpotMatch.POSSIBLY_SAME
            else->AnchorageSpotMatch.DISTINCT
        }
        return AnchorageSpotMatchResult(result,distance,threshold,overlaps)
    }
    private fun Double?.validNonNegative()=this?.takeIf{it.isFinite()&&it>=0}
    private fun Double?.validPositive()=this?.takeIf{it.isFinite()&&it>0}
}

data class AnchoragePlaceMatchCandidate(val id:Long,val name:String,val center:AnchorageGeoPoint,val geometry:AnchorageGeometry?,val primaryRegionId:Long?)
data class AnchoragePlaceMatchResult(val place:AnchoragePlaceMatchCandidate,val score:Double,val contains:Boolean,val distanceMeters:Double)
object AnchoragePlaceMatchEngine {
    fun rank(point:AnchorageGeoPoint,regionId:Long?,proposedName:String?,places:List<AnchoragePlaceMatchCandidate>):List<AnchoragePlaceMatchResult> = places.map{place->
        val contains=place.geometry?.let{AnchorageGeometryOps.contains(it,point)}?:false
        val distance=AnchorageGeometryOps.distance(point,place.center)
        val regionScore=if(regionId!=null&&place.primaryRegionId==regionId)35.0 else 0.0
        val nameScore=if(!proposedName.isNullOrBlank())similarity(proposedName,place.name)*40 else 0.0
        AnchoragePlaceMatchResult(place,(if(contains)100.0 else 0.0)+regionScore+nameScore-distance.coerceAtMost(2_000.0)/50.0,contains,distance)
    }.sortedByDescending{it.score}
    private fun similarity(a:String,b:String):Double{val first=a.lowercase().trim().split(Regex("\\s+")).toSet();val second=b.lowercase().trim().split(Regex("\\s+")).toSet();return if(first.isEmpty()||second.isEmpty())0.0 else first.intersect(second).size.toDouble()/first.union(second).size}
}

object AnchorageRegionCandidateRanker {
    fun score(value:AnchorageRegionCandidate):Double=(if(value.containsPoint)100.0 else 0.0)+when(value.featureType){AnchorageRegionFeatureType.BAY,AnchorageRegionFeatureType.COVE,AnchorageRegionFeatureType.INLET->40.0;AnchorageRegionFeatureType.HARBOUR->30.0;AnchorageRegionFeatureType.ISLAND,AnchorageRegionFeatureType.GULF,AnchorageRegionFeatureType.MARINE_REGION->15.0;else->0.0}+(if(value.official)10.0 else 0.0)-value.distanceMeters.coerceAtMost(50_000.0)/1_000.0
    fun rank(values:List<AnchorageRegionCandidate>)=values.distinctBy{listOf(it.provider,it.externalId?:"",it.displayName,it.featureType.name).joinToString("|")}.sortedByDescending(::score)
}

class AnchorageRegionResolver(private val providers:List<AnchorageRegionProvider>){
    suspend fun resolve(latitude:Double,longitude:Double,radiusMeters:Double=15_000.0):List<AnchorageRegionCandidate>{
        require(latitude in -90.0..90.0&&longitude in -180.0..180.0&&radiusMeters>0)
        val values=mutableListOf<AnchorageRegionCandidate>()
        providers.forEach{provider->provider.resolveCandidates(latitude,longitude,radiusMeters).getOrNull()?.let(values::addAll)}
        return AnchorageRegionCandidateRanker.rank(values)
    }
}
