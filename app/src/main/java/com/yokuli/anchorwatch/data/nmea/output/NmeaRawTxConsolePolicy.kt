package com.yokuli.anchorwatch.data.nmea.output

object NmeaRawTxConsolePolicy{
    private val streamPattern=Regex("\\[([^]]+)]")
    fun stream(line:String)=streamPattern.find(line)?.groupValues?.getOrNull(1)?.uppercase()
    fun sentenceType(line:String):String?{
        val body=line.substringAfter('$',"").substringBefore('*').substringBefore(',').trim()
        return body.takeIf{it.length>=3}?.takeLast(3)?.uppercase()
    }
    fun afterClearMarker(lines:List<String>,marker:String?):List<String>{
        if(marker==null)return lines
        val index=lines.indexOfLast{it==marker}
        return if(index>=0)lines.drop(index+1) else lines
    }
    fun filter(lines:List<String>,streamFilter:String,typeFilter:String):List<String>{
        val streamQuery=streamFilter.trim().uppercase();val typeQuery=typeFilter.trim().uppercase()
        return lines.filter{line->(streamQuery.isEmpty()||stream(line)?.contains(streamQuery)==true)&&(typeQuery.isEmpty()||sentenceType(line)?.contains(typeQuery)==true)}
    }
}
