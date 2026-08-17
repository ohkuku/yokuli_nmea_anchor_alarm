package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.domain.tide.TideStation
import com.yokuli.anchorwatch.domain.tide.TideStationType
import java.text.Normalizer
import java.util.Locale

/** Parser for the official LINZ NZNA secondary-port CSV (2026–27 column layout). */
object SecondaryPortCsvParser {
    fun parse(csv:String):List<TideStation>{
        val result=linkedMapOf<String,TideStation>()
        var reference:TideStation?=null
        csv.lineSequence().forEach{rawLine->
            val fields=parseRow(rawLine.removePrefix("\uFEFF"))
            val number=fields.getOrNull(0)?.trim().orEmpty()
            val name=fields.getOrNull(1)?.trim().orEmpty()
            if(number.isBlank()||name.isBlank())return@forEach
            val latitude=coordinate(fields.getOrNull(2),fields.getOrNull(3),negative=true)?:return@forEach
            val longitude=coordinate(fields.getOrNull(4),fields.getOrNull(5),negative=false)?:return@forEach
            val meanHigh=fields.getOrNull(6)?.trim().orEmpty()
            val msl=fields.getOrNull(15)?.trim()?.toDoubleOrNull()
            if(meanHigh.equals("hhmm",ignoreCase=true)){
                val id=slug(name)
                reference=TideStation(id,name,latitude,longitude,TideStationType.DAILY_PREDICTION,csvName=name,meanSeaLevelMeters=msl,referenceMeanSeaLevelMeters=msl)
                result[id]=requireNotNull(reference)
                return@forEach
            }
            val standard=reference?:return@forEach
            val highOffset=parseOffsetMinutes(meanHigh)?:return@forEach
            val lowOffset=parseOffsetMinutes(fields.getOrNull(8)?.trim().orEmpty())?:return@forEach
            val ratio=fields.getOrNull(16)?.trim()?.toDoubleOrNull()?:return@forEach
            if(msl==null)return@forEach
            val id=slug(name)
            result[id]=TideStation(
                id=id,name=name,latitude=latitude,longitude=longitude,type=TideStationType.SECONDARY_PORT,
                referenceStationId=standard.id,highWaterOffsetMinutes=highOffset,lowWaterOffsetMinutes=lowOffset,
                meanSeaLevelMeters=msl,referenceMeanSeaLevelMeters=standard.meanSeaLevelMeters,rangeRatio=ratio,
            )
        }
        return result.values.toList()
    }

    internal fun parseOffsetMinutes(value:String):Int?{
        val compact=value.trim().replace(":","")
        if(!compact.matches(Regex("[+-]?\\d{4}")))return null
        val sign=if(compact.startsWith("-"))-1 else 1
        val digits=compact.removePrefix("+").removePrefix("-")
        val hours=digits.take(2).toInt();val minutes=digits.takeLast(2).toInt()
        if(minutes !in 0..59)return null
        return sign*(hours*60+minutes)
    }

    private fun coordinate(degrees:String?,minutes:String?,negative:Boolean):Double?{
        val value=(degrees?.trim()?.toDoubleOrNull()?:return null)+(minutes?.trim()?.toDoubleOrNull()?:return null)/60.0
        return if(negative)-value else value
    }

    private fun slug(value:String):String=Normalizer.normalize(value,Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"),"-").trim('-')

    private fun parseRow(line:String):List<String>{
        val result=mutableListOf<String>();val field=StringBuilder();var quoted=false;var index=0
        while(index<line.length){
            val character=line[index]
            when{
                character=='"'&&quoted&&index+1<line.length&&line[index+1]=='"'->{field.append('"');index++}
                character=='"'->quoted=!quoted
                character==','&&!quoted->{result+=field.toString();field.clear()}
                else->field.append(character)
            }
            index++
        }
        result+=field.toString();return result
    }
}
