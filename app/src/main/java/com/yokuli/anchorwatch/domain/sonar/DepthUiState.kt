package com.yokuli.anchorwatch.domain.sonar

import com.yokuli.anchorwatch.data.linz.LinzDepthReference

data class DepthUiState(
    val liveDepthMeters:Double?=null,
    val liveDepthReference:DepthReference?=null,
    val liveDepthAgeMillis:Long?=null,
    val correctedDepthMeters:Double?=null,
    val linz:LinzDepthReference=LinzDepthReference(),
    val personalMapDepthMeters:Double?=null,
    val personalMapMeasured:Boolean?=null,
    val personalMapSamples:Int?=null,
    val personalMapUncertaintyMeters:Double?=null,
    val personalSurveyName:String?=null,
    val personalSurveyStartedAt:Long?=null,
)
