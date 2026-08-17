package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.tide.TideStation
import com.yokuli.anchorwatch.domain.tide.TideStationType

/** Lightweight, traceable catalog of frequently used LINZ daily-prediction ports. */
object TideStationCatalog{
    const val SOURCE="https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view"
    private val bundledStations=listOf(
        TideStation("auckland","Auckland",-36.8500,174.7667,TideStationType.DAILY_PREDICTION,csvName="Auckland"),
        TideStation("onehunga","Onehunga",-36.9333,174.7833,TideStationType.DAILY_PREDICTION,csvName="Onehunga"),
        TideStation("marsden-point","Marsden Point",-35.8333,174.5000,TideStationType.DAILY_PREDICTION,csvName="Marsden Point"),
        TideStation("port-charles","Port Charles",-36.5167,175.4667,TideStationType.SECONDARY_PORT,referenceStationId="marsden-point",highWaterOffsetMinutes=-39,lowWaterOffsetMinutes=-35,meanSeaLevelMeters=1.30,referenceMeanSeaLevelMeters=1.63,rangeRatio=.95),
        TideStation("opua","Opua",-35.3167,174.1167,TideStationType.DAILY_PREDICTION,csvName="Opua"),
        TideStation("whangarei","Whangārei",-35.7333,174.3333,TideStationType.DAILY_PREDICTION,csvName="Whangarei"),
        TideStation("tauranga","Tauranga",-37.6500,176.1833,TideStationType.DAILY_PREDICTION,csvName="Tauranga"),
        TideStation("whitianga","Whitianga",-36.8333,175.7000,TideStationType.DAILY_PREDICTION,csvName="Whitianga"),
        TideStation("gisborne","Gisborne",-38.6667,178.0167,TideStationType.DAILY_PREDICTION,csvName="Gisborne"),
        TideStation("napier","Napier",-39.4833,176.9167,TideStationType.DAILY_PREDICTION,csvName="Napier"),
        TideStation("port-taranaki","Port Taranaki",-39.0667,174.0333,TideStationType.DAILY_PREDICTION,csvName="Port Taranaki"),
        TideStation("wellington","Wellington",-41.2833,174.7833,TideStationType.DAILY_PREDICTION,csvName="Wellington"),
        TideStation("picton","Picton",-41.2833,174.0000,TideStationType.DAILY_PREDICTION,csvName="Picton"),
        TideStation("nelson","Nelson",-41.2667,173.2833,TideStationType.DAILY_PREDICTION,csvName="Nelson"),
        TideStation("westport","Westport",-41.7500,171.6000,TideStationType.DAILY_PREDICTION,csvName="Westport"),
        TideStation("lyttelton","Lyttelton",-43.6000,172.7167,TideStationType.DAILY_PREDICTION,csvName="Lyttelton"),
        TideStation("akaroa","Akaroa",-43.8000,172.9667,TideStationType.DAILY_PREDICTION,csvName="Akaroa"),
        TideStation("timaru","Timaru",-44.4000,171.2500,TideStationType.DAILY_PREDICTION,csvName="Timaru"),
        TideStation("dunedin","Dunedin",-45.8833,170.5000,TideStationType.DAILY_PREDICTION,csvName="Dunedin"),
        TideStation("bluff","Bluff",-46.6000,168.3500,TideStationType.DAILY_PREDICTION,csvName="Bluff"),
    )
    @Volatile private var officialStations:List<TideStation> = bundledStations
    val stations get()=officialStations
    @Synchronized fun installOfficialCsv(csv:String):Int{
        val parsed=SecondaryPortCsvParser.parse(csv)
        if(parsed.isEmpty())return 0
        val merged=linkedMapOf<String,TideStation>()
        bundledStations.forEach{merged[it.id]=it}
        parsed.forEach{merged[it.id]=it}
        officialStations=merged.values.toList()
        return parsed.size
    }
    fun byId(id:String?)=officialStations.firstOrNull{it.id==id}
    fun nearest(latitude:Double,longitude:Double):Pair<TideStation,Double>?=officialStations.map{it to AnchorGeometry.distanceMeters(latitude,longitude,it.latitude,it.longitude)}.minByOrNull{it.second}
    fun reference(station:TideStation)=station.referenceStationId?.let(::byId)?:station
}
