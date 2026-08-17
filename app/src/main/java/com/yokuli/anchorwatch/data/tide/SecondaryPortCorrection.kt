package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.domain.tide.TideExtreme
import com.yokuli.anchorwatch.domain.tide.TideExtremeType
import com.yokuli.anchorwatch.domain.tide.TideStation

object SecondaryPortCorrection{
    fun apply(reference:List<TideExtreme>,secondary:TideStation):List<TideExtreme>{
        val secondaryMsl=secondary.meanSeaLevelMeters?:return emptyList()
        val referenceMsl=secondary.referenceMeanSeaLevelMeters?:return emptyList()
        return reference.map{extreme->
            val offset=if(extreme.type==TideExtremeType.HIGH)secondary.highWaterOffsetMinutes else secondary.lowWaterOffsetMinutes
            extreme.copy(
                instantUtc=extreme.instantUtc.plusSeconds(offset*60L),
                heightMetersAboveChartDatum=secondaryMsl+(extreme.heightMetersAboveChartDatum-referenceMsl)*secondary.rangeRatio,
            )
        }.sortedBy{it.instantUtc}
    }
}
