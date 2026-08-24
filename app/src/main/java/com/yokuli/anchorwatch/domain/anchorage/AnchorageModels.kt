package com.yokuli.anchorwatch.domain.anchorage

enum class AnchorageRegionSource { USER, LINZ_GAZETTEER, IMPORTED }
enum class AnchorageRegionFeatureType { COUNTRY, MARINE_REGION, GULF, ISLAND, HARBOUR, BAY, COVE, INLET, SOUND, PASSAGE, COAST, ADMIN_REGION, CUSTOM, UNKNOWN }
enum class AnchorageGeometryType { POINT, CIRCLE, POLYGON, MULTI_POLYGON }
enum class AnchoragePlaceType { BAY, COVE, HARBOUR, INLET, ROADSTEAD, ANCHORAGE_AREA, CUSTOM, UNKNOWN }
enum class AnchorageVerificationStatus { PLANNED, VISITED, VERIFIED_BY_SESSION }
enum class AnchoragePlanningStatus { NONE, WANT_TO_VISIT, PLANNED, COMMON, BACKUP, AVOID, ARCHIVED }
enum class AnchorageSpotType { ANCHOR_SPOT, PLANNED_REFERENCE }
enum class AnchorageVisitKind { SESSION, LEGACY_SUMMARY, MANUAL }

enum class AnchorageProtectionMedium { WIND, SWELL }
enum class AnchorageCompassSector { N, NE, E, SE, S, SW, W, NW }
enum class AnchorageProtectionRating { UNKNOWN, EXPOSED, PARTIAL, GOOD }
enum class AnchorageInformationSource { USER, OBSERVED, GIS_ASSISTED, IMPORTED }
enum class AnchorageFacilityType { DINGHY_LANDING, SHORE_ACCESS, WATER, TOILET, RUBBISH, GROCERIES, FUEL, REPAIR, MOBILE_SIGNAL, INTERNET, DOG_ACCESS, HAZARD, RESTRICTION, OTHER }
enum class AnchorageFacilityAvailability { UNKNOWN, NO, LIMITED, YES }
enum class AnchoragePreference { UNKNOWN, FAVORITE, NEUTRAL, AVOID }

data class AnchorageSaveDraft(
    val sessionId:Long?,
    val proposedLatitude:Double,
    val proposedLongitude:Double,
    val coordinateSource:String,
    val uncertaintyMeters:Double?,
    val depthMeters:Double?,
    val rodeMeters:Double?,
    val alarmRadiusMeters:Double?,
    val seabedType:String?,
) {
    init {
        require(proposedLatitude.isFinite() && proposedLatitude in -90.0..90.0)
        require(proposedLongitude.isFinite() && proposedLongitude in -180.0..180.0)
        require(uncertaintyMeters == null || uncertaintyMeters.isFinite() && uncertaintyMeters >= 0.0)
    }
}

data class AnchorageViewport(val south:Double,val west:Double,val north:Double,val east:Double){
    init { require(listOf(south,west,north,east).all(Double::isFinite));require(south<=north);require(south>=-90&&north<=90);require(west>=-180&&west<=180&&east>=-180&&east<=180) }
    val crossesAntiMeridian:Boolean get()=west>east
    fun queryWindows():List<AnchorageViewport> = if(!crossesAntiMeridian) listOf(this) else listOf(copy(east=180.0),copy(west=-180.0))
}

data class AnchorageRegionParentHint(val displayName:String,val featureType:AnchorageRegionFeatureType,val externalId:String?=null)
data class AnchorageRegionCandidate(
    val provider:String,
    val externalId:String?,
    val displayName:String,
    val officialName:String?,
    val featureType:AnchorageRegionFeatureType,
    val geometry:AnchorageGeometry?,
    val centerLatitude:Double,
    val centerLongitude:Double,
    val containsPoint:Boolean,
    val distanceMeters:Double,
    val official:Boolean,
    val parentHints:List<AnchorageRegionParentHint> = emptyList(),
    val sourceUpdatedAt:Long? = null,
)

interface AnchorageRegionProvider {
    val providerId:String
    suspend fun resolveCandidates(latitude:Double,longitude:Double,radiusMeters:Double):Result<List<AnchorageRegionCandidate>>
}

data class AnchorageForecastInput(
    val windDirectionDegrees:Double?,
    val windSpeedKnots:Double?,
    val gustKnots:Double?,
    val swellDirectionDegrees:Double?,
    val swellHeightMeters:Double?,
)

data class AnchorageConditionFit(
    val windFit:AnchorageProtectionRating,
    val swellFit:AnchorageProtectionRating,
    val messages:List<String>,
    val sourceCoverage:Double,
)

data class AnchorageWindExperience(val sector:AnchorageCompassSector,val visitCount:Int,val minWindKnots:Double?,val maxWindKnots:Double?,val medianMotionScore:Double?)
data class AnchorageSummaryCoverage(val visits:Int,val visitsWithDepth:Int,val visitsWithMotion:Int,val visitsWithWind:Int){
    val lowSample:Boolean get()=visits<3
}
data class PersonalAnchorageSummary(
    val visitCount:Int,
    val totalDurationMillis:Long,
    val observedDepthMinMeters:Double?,
    val observedDepthMaxMeters:Double?,
    val observedRodeMinMeters:Double?,
    val observedRodeMaxMeters:Double?,
    val maxExcursionMeters:Double?,
    val alarmCount:Int,
    val medianMotionScore:Double?,
    val p95MotionScore:Double?,
    val dominantRollPeriodSeconds:Double?,
    val windObservationGroups:List<AnchorageWindExperience>,
    val coverage:AnchorageSummaryCoverage,
)

data class AnchorageVisitObservation(
    val startedAt:Long,
    val endedAt:Long?,
    val depthMeters:Double?,
    val minDepthMeters:Double?,
    val maxDepthMeters:Double?,
    val rodeLengthMeters:Double?,
    val maxExcursionMeters:Double?,
    val alarmCount:Int,
    val typicalMotionScore:Double?,
    val p95MotionScore:Double?,
    val dominantRollPeriodSeconds:Double?,
    val maxWindKnots:Double?,
    val windDirectionDegrees:Double?=null,
)
