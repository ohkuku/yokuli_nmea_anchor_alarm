package com.yokuli.anchorwatch.data.linz

import android.os.SystemClock
import com.yokuli.anchorwatch.data.database.LinzDepthCacheDao
import com.yokuli.anchorwatch.data.database.LinzDepthCacheEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.sonar.SonarGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

class LinzQueryThrottle(private val movementMeters:Double=30.0,private val intervalMillis:Long=60_000L){
    private var latitude:Double?=null;private var longitude:Double?=null;private var atElapsed:Long?=null
    fun shouldQuery(nextLatitude:Double,nextLongitude:Double,nowElapsed:Long):Boolean{val lat=latitude;val lon=longitude;val at=atElapsed;return lat==null||lon==null||at==null||nowElapsed-at>=intervalMillis||AnchorGeometry.distanceMeters(lat,lon,nextLatitude,nextLongitude)>movementMeters}
    fun record(latitude:Double,longitude:Double,nowElapsed:Long){this.latitude=latitude;this.longitude=longitude;atElapsed=nowElapsed}
}

@Singleton
class LinzDepthReferenceRepository @Inject constructor(private val client:LinzWfsClient,private val cache:LinzDepthCacheDao){
    private val mutex=Mutex();private val throttle=LinzQueryThrottle();private val _state=MutableStateFlow(LinzDepthReference(status=if(client.configured)LinzDepthStatus.IDLE else LinzDepthStatus.NOT_CONFIGURED));val state=_state.asStateFlow()
    private val _diagnostics=MutableStateFlow(LinzDepthDiagnostics(layerIds=client.allLayerIds));val diagnostics=_diagnostics.asStateFlow()

    suspend fun refresh(latitude:Double,longitude:Double,nowWall:Long=System.currentTimeMillis(),nowElapsed:Long=SystemClock.elapsedRealtime())=mutex.withLock{
        if(!client.configured){_state.value=LinzDepthReference(latitude,longitude,nowWall,status=LinzDepthStatus.NOT_CONFIGURED);return@withLock}
        if(!throttle.shouldQuery(latitude,longitude,nowElapsed))return@withLock;throttle.record(latitude,longitude,nowElapsed);val key=cellKey(latitude,longitude);val cached=withContext(Dispatchers.IO){cache.get(key)}
        if(cached!=null&&nowWall-cached.queriedAt<=CACHE_TTL_MILLIS){_state.value=cached.model(cached=true);_diagnostics.value=_diagnostics.value.copy(cacheHits=_diagnostics.value.cacheHits+1,lastQueryAt=nowWall,lastQueryLatitude=latitude,lastQueryLongitude=longitude,message="Fresh spatial cache hit");return@withLock}
        _diagnostics.value=_diagnostics.value.copy(cacheMisses=_diagnostics.value.cacheMisses+1,lastQueryAt=nowWall,lastQueryLatitude=latitude,lastQueryLongitude=longitude,message="Querying LINZ vector depth");_state.value=LinzDepthReference(latitude,longitude,nowWall,status=LinzDepthStatus.LOADING)
        try{
            val result=client.query(latitude,longitude);val selected=LinzHydroSelector.select(latitude,longitude,nowWall,result.soundings,result.areas,result.contours,client.allLayerIds)
            withContext(Dispatchers.IO){cache.upsert(selected.entity(key));cache.prune(nowWall-CACHE_TTL_MILLIS*2)};_state.value=selected
            _diagnostics.value=_diagnostics.value.copy(requests=_diagnostics.value.requests+result.requestCount,lastHttpCode=result.lastHttpCode,message=if(result.errors.isEmpty())"LINZ vector depth available" else "LINZ partial result · ${result.errors.joinToString()}")
        }catch(error:Throwable){
            if(error is CancellationException)throw error
            if(cached!=null){_state.value=cached.model(cached=true).copy(status=LinzDepthStatus.OFFLINE);_diagnostics.value=_diagnostics.value.copy(requests=_diagnostics.value.requests+3,message="Offline · cached LINZ depth")}
            else{_state.value=LinzDepthReference(latitude,longitude,nowWall,status=if(error is java.io.IOException)LinzDepthStatus.OFFLINE else LinzDepthStatus.ERROR);_diagnostics.value=_diagnostics.value.copy(requests=_diagnostics.value.requests+3,message=if(error is java.io.IOException)"LINZ unavailable offline" else "LINZ vector query failed")}
        }
    }

    private fun cellKey(latitude:Double,longitude:Double):String{val projected=SonarGrid.project(latitude,longitude);return "${floor(projected.first/100.0).toLong()}:${floor(projected.second/100.0).toLong()}"}
    private fun LinzDepthReference.entity(key:String)=LinzDepthCacheEntity(key,queriedLatitude,queriedLongitude,queriedAt,depthAreaMinMeters,depthAreaMaxMeters,nearestSoundingDepthMeters,nearestSoundingDistanceMeters,nearestSoundingLatitude,nearestSoundingLongitude,nearestContourDepthMeters,nearestContourDistanceMeters,sourceLayers.joinToString("|"),status.name)
    private fun LinzDepthCacheEntity.model(cached:Boolean)=LinzDepthReference(queriedLatitude,queriedLongitude,queriedAt,depthAreaMinMeters,depthAreaMaxMeters,nearestSoundingDepthMeters,nearestSoundingDistanceMeters,nearestSoundingLatitude,nearestSoundingLongitude,nearestContourDepthMeters,nearestContourDistanceMeters,sourceLayers.split('|').filter{it.isNotBlank()},runCatching{LinzDepthStatus.valueOf(status)}.getOrDefault(LinzDepthStatus.NO_DATA),cached)
    companion object{const val CACHE_TTL_MILLIS=24*60*60*1000L}
}
