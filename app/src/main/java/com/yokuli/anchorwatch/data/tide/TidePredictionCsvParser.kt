package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.domain.tide.TideExtreme
import com.yokuli.anchorwatch.domain.tide.TideExtremeType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object TidePredictionCsvParser{
    fun parse(csv:String,zoneId:String="Pacific/Auckland"):List<TideExtreme>{
        val raw=mutableListOf<Pair<java.time.Instant,Double>>()
        csv.lineSequence().forEach{line->
            val fields=line.removePrefix("\uFEFF").split(',').map(String::trim)
            val day=fields.getOrNull(0)?.toIntOrNull()?:return@forEach
            val month=fields.getOrNull(2)?.toIntOrNull()?:return@forEach
            val year=fields.getOrNull(3)?.toIntOrNull()?:return@forEach
            val date=runCatching{LocalDate.of(year,month,day)}.getOrNull()?:return@forEach
            var index=4
            while(index+1<fields.size){
                val time=runCatching{LocalTime.parse(fields[index])}.getOrNull()
                val height=fields[index+1].toDoubleOrNull()
                if(time!=null&&height!=null)raw+=date.atTime(time).atZone(ZoneId.of(zoneId)).toInstant() to height
                index+=2
            }
        }
        val sorted=raw.distinctBy{it.first}.sortedBy{it.first}
        if(sorted.size<2)return emptyList()
        val firstType=if(sorted[0].second>=sorted[1].second)TideExtremeType.HIGH else TideExtremeType.LOW
        return sorted.mapIndexed{index,(instant,height)->TideExtreme(instant,height,if(index%2==0)firstType else if(firstType==TideExtremeType.HIGH)TideExtremeType.LOW else TideExtremeType.HIGH)}
    }
}
