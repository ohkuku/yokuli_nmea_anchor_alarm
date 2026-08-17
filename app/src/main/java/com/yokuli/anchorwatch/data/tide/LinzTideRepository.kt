package com.yokuli.anchorwatch.data.tide

import com.yokuli.anchorwatch.data.database.TidePredictionCacheDao
import com.yokuli.anchorwatch.data.database.TidePredictionCacheEntity
import com.yokuli.anchorwatch.domain.tide.TideCorrectionResult
import com.yokuli.anchorwatch.domain.tide.TideCorrectionStatus
import com.yokuli.anchorwatch.domain.tide.TideExtreme
import com.yokuli.anchorwatch.domain.tide.TideStation
import com.yokuli.anchorwatch.domain.tide.TideStationType
import com.yokuli.anchorwatch.domain.tide.TideInterpolationQuality
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TideRuntimeDiagnosticsSnapshot(val corrections:Long=0,val lastDurationMillis:Long=0,val maxDurationMillis:Long=0)
object TideRuntimeDiagnostics{
    private val _state=MutableStateFlow(TideRuntimeDiagnosticsSnapshot());val state=_state.asStateFlow()
    internal fun completed(durationMillis:Long){_state.value=_state.value.copy(corrections=_state.value.corrections+1,lastDurationMillis=durationMillis,maxDurationMillis=maxOf(_state.value.maxDurationMillis,durationMillis))}
}

@Singleton
class LinzTideRepository @Inject constructor(
    private val cache:TidePredictionCacheDao,
    private val downloader:LinzTideDownloader,
){
    private val parsed=java.util.concurrent.ConcurrentHashMap<Pair<String,Int>,List<TideExtreme>>()
    private val catalogMutex=Mutex()
    @Volatile private var catalogLoaded=false

    fun nearestStation(latitude:Double,longitude:Double)=TideStationCatalog.nearest(latitude,longitude)
    fun station(id:String?)=TideStationCatalog.byId(id)

    suspend fun refreshStationCatalog():Boolean=catalogMutex.withLock{
        if(catalogLoaded)return@withLock true
        val cached=cache.get(SECONDARY_CATALOG_CACHE_ID,SECONDARY_CATALOG_YEAR)
        if(cached!=null&&TideStationCatalog.installOfficialCsv(cached.csv)>0)catalogLoaded=true
        if(!catalogLoaded){
            runCatching{
                val (url,csv)=downloader.downloadSecondaryPorts()
                require(TideStationCatalog.installOfficialCsv(csv)>0)
                cache.upsert(TidePredictionCacheEntity(SECONDARY_CATALOG_CACHE_ID,SECONDARY_CATALOG_YEAR,System.currentTimeMillis(),url,csv))
                catalogLoaded=true
            }
        }
        catalogLoaded
    }

    suspend fun ensure(station:TideStation,instant:Instant):Boolean{
        val reference=TideStationCatalog.reference(station)
        val year=instant.atZone(ZoneId.of(reference.zoneId)).year
        val years=buildSet{add(year);if(instant.atZone(ZoneId.of(reference.zoneId)).dayOfYear<=2)add(year-1);if(instant.atZone(ZoneId.of(reference.zoneId)).dayOfYear>=364)add(year+1)}
        return years.all{ensureYear(reference,it)}
    }

    suspend fun ensureYear(station:TideStation,year:Int):Boolean{
        val reference=TideStationCatalog.reference(station)
        cache.get(reference.id,year)?.let{return TidePredictionCsvParser.parse(it.csv,reference.zoneId).isNotEmpty()}
        return runCatching{
            val (url,csv)=downloader.download(reference,year)
            val values=TidePredictionCsvParser.parse(csv,reference.zoneId)
            require(values.isNotEmpty()){ "LINZ tide CSV contained no predictions" }
            cache.upsert(TidePredictionCacheEntity(reference.id,year,System.currentTimeMillis(),url,csv))
            parsed[reference.id to year]=values
            true
        }.getOrDefault(false)
    }

    suspend fun correctionAt(station:TideStation,stationDistanceMeters:Double?,instant:Instant):TideCorrectionResult{
        val started=System.nanoTime()
        return try{correctionAtInternal(station,stationDistanceMeters,instant)}finally{TideRuntimeDiagnostics.completed((System.nanoTime()-started)/1_000_000L)}
    }

    private suspend fun correctionAtInternal(station:TideStation,stationDistanceMeters:Double?,instant:Instant):TideCorrectionResult{
        val reference=TideStationCatalog.reference(station)
        val localYear=instant.atZone(ZoneId.of(reference.zoneId)).year
        val rows=(-1..1).mapNotNull{offset->cache.get(reference.id,localYear+offset)}
        if(rows.isEmpty())return unavailable(TideCorrectionStatus.OFFLINE_NO_CACHE,station,stationDistanceMeters,localYear)
        val extremes=rows.flatMap{row->parsed.getOrPut(row.stationId to row.year){TidePredictionCsvParser.parse(row.csv,reference.zoneId)}}.sortedBy{it.instantUtc}
        if(extremes.size<2)return unavailable(TideCorrectionStatus.PARSE_ERROR,station,stationDistanceMeters,localYear)
        val adjusted=if(station.type==TideStationType.SECONDARY_PORT)SecondaryPortCorrection.apply(extremes,station)else extremes
        val nextIndex=adjusted.indexOfFirst{it.instantUtc>=instant}
        if(nextIndex<=0)return unavailable(TideCorrectionStatus.OUTSIDE_DATA_RANGE,station,stationDistanceMeters,localYear)
        val interpolation=TideHeightInterpolator.heightAt(instant,adjusted[nextIndex-1],adjusted[nextIndex])?:return unavailable(TideCorrectionStatus.OUTSIDE_DATA_RANGE,station,stationDistanceMeters,localYear)
        val sourceUpdated=rows.maxOfOrNull{it.downloadedAt}
        val status=if(interpolation.quality==TideInterpolationQuality.RECOMMENDED_5_TO_7_HOURS)TideCorrectionStatus.AVAILABLE else TideCorrectionStatus.INTERVAL_OUTSIDE_GUIDANCE
        return TideCorrectionResult(status,interpolation.heightMeters,station.id,station.name,stationDistanceMeters,localYear,buildString{append(TideHeightInterpolator.METHOD);append("+");append(interpolation.quality.name);if(station.type==TideStationType.SECONDARY_PORT)append("+LINZ_SECONDARY_PORT_MEAN_OFFSETS")},sourceUpdated)
    }

    suspend fun clearCache(){cache.clear();parsed.clear()}

    private fun unavailable(status:TideCorrectionStatus,station:TideStation,distance:Double?,year:Int)=TideCorrectionResult(status,stationId=station.id,stationName=station.name,stationDistanceMeters=distance,predictionYear=year)

    companion object{
        const val SECONDARY_CATALOG_CACHE_ID="__linz_secondary_ports__"
        const val SECONDARY_CATALOG_YEAR=2026
    }
}
