package com.yokuli.anchorwatch.domain.tide

import java.time.Instant

enum class TideStationType { DAILY_PREDICTION, SECONDARY_PORT }
enum class TideExtremeType { HIGH, LOW }
enum class TideCorrectionStatus { AVAILABLE, INTERVAL_OUTSIDE_GUIDANCE, NO_STATION, DATA_MISSING, OUTSIDE_DATA_RANGE, OFFLINE_NO_CACHE, PARSE_ERROR }
enum class TideInterpolationQuality { RECOMMENDED_5_TO_7_HOURS, OUTSIDE_RECOMMENDED_INTERVAL }

data class TideStation(
    val id:String,
    val name:String,
    val latitude:Double,
    val longitude:Double,
    val type:TideStationType,
    val referenceStationId:String?=null,
    val csvName:String?=null,
    val zoneId:String="Pacific/Auckland",
    val highWaterOffsetMinutes:Int=0,
    val lowWaterOffsetMinutes:Int=0,
    val meanSeaLevelMeters:Double?=null,
    val referenceMeanSeaLevelMeters:Double?=null,
    val rangeRatio:Double=1.0,
)

data class TideExtreme(
    val instantUtc:Instant,
    val heightMetersAboveChartDatum:Double,
    val type:TideExtremeType,
)

data class TideInterpolationResult(
    val heightMeters:Double,
    val method:String,
    val previousExtreme:TideExtreme,
    val nextExtreme:TideExtreme,
    val intervalMinutes:Long,
    val quality:TideInterpolationQuality,
)

data class TideCorrectionResult(
    val status:TideCorrectionStatus,
    val tideHeightMetersAboveChartDatum:Double?=null,
    val stationId:String?=null,
    val stationName:String?=null,
    val stationDistanceMeters:Double?=null,
    val predictionYear:Int?=null,
    val method:String?=null,
    val sourceUpdatedAt:Long?=null,
)
