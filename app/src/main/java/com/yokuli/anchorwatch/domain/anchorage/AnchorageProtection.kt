package com.yokuli.anchorwatch.domain.anchorage

data class AnchorageProtectionObservation(val medium:AnchorageProtectionMedium,val sector:AnchorageCompassSector,val rating:AnchorageProtectionRating,val source:AnchorageInformationSource)

object AnchorageConditionFitEngine {
    fun evaluate(input:AnchorageForecastInput,observations:List<AnchorageProtectionObservation>):AnchorageConditionFit{
        val wind=input.windDirectionDegrees?.takeIf(Double::isFinite)?.let(::sector)?.let{target->observations.firstOrNull{it.medium==AnchorageProtectionMedium.WIND&&it.sector==target}}
        val swell=input.swellDirectionDegrees?.takeIf(Double::isFinite)?.let(::sector)?.let{target->observations.firstOrNull{it.medium==AnchorageProtectionMedium.SWELL&&it.sector==target}}
        val requested=listOfNotNull(input.windDirectionDegrees,input.swellDirectionDegrees).size
        val covered=listOfNotNull(wind,swell).size
        val messages=buildList{
            wind?.let{add("${it.sector} wind protection: ${it.rating}")}
            swell?.let{add("${it.sector} swell protection: ${it.rating}")}
            if(input.windDirectionDegrees!=null&&wind==null)add("No personal wind-protection record for ${sector(input.windDirectionDegrees)}")
            if(input.swellDirectionDegrees!=null&&swell==null)add("No personal swell-protection record for ${sector(input.swellDirectionDegrees)}")
        }
        return AnchorageConditionFit(wind?.rating?:AnchorageProtectionRating.UNKNOWN,swell?.rating?:AnchorageProtectionRating.UNKNOWN,messages,if(requested==0)0.0 else covered.toDouble()/requested)
    }
    fun sector(degrees:Double):AnchorageCompassSector=AnchorageCompassSector.entries[((((degrees%360)+360)%360+22.5)/45.0).toInt()%8]
}

object PersonalAnchorageSummaryEngine {
    const val VERSION="1"
    fun summarize(visits:List<AnchorageVisitObservation>):PersonalAnchorageSummary{
        fun valid(value:Double?)=value?.takeIf{it.isFinite()}
        val depths=visits.flatMap{listOfNotNull(valid(it.depthMeters),valid(it.minDepthMeters),valid(it.maxDepthMeters))}
        val rode=visits.mapNotNull{valid(it.rodeLengthMeters)}
        val motion=visits.mapNotNull{valid(it.typicalMotionScore)}.sorted()
        val p95=visits.mapNotNull{valid(it.p95MotionScore)}.maxOrNull()
        val roll=visits.mapNotNull{valid(it.dominantRollPeriodSeconds)}.sorted().median()
        val windGroups=visits.filter{valid(it.maxWindKnots)!=null&&valid(it.windDirectionDegrees)!=null}.groupBy{AnchorageConditionFitEngine.sector(it.windDirectionDegrees!!)}.map{(sector,group)->AnchorageWindExperience(sector,group.size,group.minOf{it.maxWindKnots!!},group.maxOf{it.maxWindKnots!!},group.mapNotNull{it.typicalMotionScore}.sorted().median())}.sortedBy{it.sector.ordinal}
        val coverage=AnchorageSummaryCoverage(visits.size,visits.count{listOf(it.depthMeters,it.minDepthMeters,it.maxDepthMeters).any{value->valid(value)!=null}},visits.count{valid(it.typicalMotionScore)!=null||valid(it.p95MotionScore)!=null},visits.count{valid(it.maxWindKnots)!=null})
        return PersonalAnchorageSummary(visits.size,visits.sumOf{visit->visit.endedAt?.let{(it-visit.startedAt).coerceAtLeast(0)}?:0},depths.minOrNull(),depths.maxOrNull(),rode.minOrNull(),rode.maxOrNull(),visits.mapNotNull{valid(it.maxExcursionMeters)}.maxOrNull(),visits.sumOf{it.alarmCount.coerceAtLeast(0)},motion.median(),p95,roll,windGroups,coverage)
    }
    private fun List<Double>.median():Double?=takeIf{it.isNotEmpty()}?.let{if(it.size%2==1)it[it.size/2] else (it[it.size/2-1]+it[it.size/2])/2}
}
