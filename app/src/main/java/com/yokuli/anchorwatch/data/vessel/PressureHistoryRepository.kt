package com.yokuli.anchorwatch.data.vessel

import com.yokuli.anchorwatch.data.database.PressureHistoryDao
import com.yokuli.anchorwatch.data.database.PressureHistoryEntity
import com.yokuli.anchorwatch.domain.vessel.PressureTrend
import com.yokuli.anchorwatch.domain.vessel.PressureTrendEstimator
import com.yokuli.anchorwatch.runtime.WallClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PressureHistoryPolicy{
    const val BUCKET_MILLIS=60_000L
    const val TREND_RETENTION_MILLIS=6*60*60_000L
    const val DATABASE_RETENTION_MILLIS=30L*24*60*60_000L
    fun bucket(utcMillis:Long)=utcMillis/BUCKET_MILLIS
    fun validPressure(value:Double)=value.isFinite()&&value in 800.0..1_200.0
}

/** Bounded restart-safe pressure observations. Exact raw NMEA is not stored;
 * only one numeric pressure measurement per physical source and UTC minute. */
@Singleton
class PressureHistoryRepository @Inject constructor(
    private val dao:PressureHistoryDao,
    private val wallClock:WallClock,
){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    /** Room upserts for one minute must keep observation order. Launching one
     * coroutine per sample allowed an older pressure value to complete after a
     * newer one and overwrite it. A single unbounded actor keeps record()
     * non-blocking while preserving the caller's order. */
    private val databaseWrites=Channel<PressureHistoryEntity>(Channel.UNLIMITED)
    private val estimators=linkedMapOf<String,PressureTrendEstimator>()
    private val pending=mutableListOf<PressureHistoryEntity>()
    private var loaded=false
    private val _historyLoaded=MutableStateFlow(false)
    val historyLoaded=_historyLoaded.asStateFlow()

    init{
        scope.launch{for(value in databaseWrites)dao.upsert(value)}
        scope.launch{
            val now=wallClock.currentTimeMillis()
            val rows=dao.since(now-PressureHistoryPolicy.TREND_RETENTION_MILLIS-PressureHistoryPolicy.BUCKET_MILLIS)
            synchronized(this@PressureHistoryRepository){
                (rows+pending).sortedBy{it.sampledAtUtcMillis}.forEach(::addToEstimator)
                pending.clear();loaded=true;_historyLoaded.value=true
            }
            dao.prune(now-PressureHistoryPolicy.DATABASE_RETENTION_MILLIS)
        }
    }

    fun record(sourceStableKey:String,sourceDisplayName:String,pressureHpa:Double,observedAtUtcMillis:Long=wallClock.currentTimeMillis()){
        val key=sourceStableKey.trim();if(key.isEmpty()||!PressureHistoryPolicy.validPressure(pressureHpa))return
        val value=PressureHistoryEntity(key,PressureHistoryPolicy.bucket(observedAtUtcMillis),observedAtUtcMillis,pressureHpa,sourceDisplayName.ifBlank{key})
        synchronized(this){if(loaded)addToEstimator(value)else{pending+=value;if(pending.size>1_000)pending.removeAt(0)}}
        databaseWrites.trySend(value)
    }

    @Synchronized fun trend(sourceStableKey:String,windowMillis:Long,nowUtcMillis:Long=wallClock.currentTimeMillis()):PressureTrend?{
        if(!loaded)return null
        return estimators[sourceStableKey]?.trend(nowUtcMillis,windowMillis)
    }

    private fun addToEstimator(value:PressureHistoryEntity){
        estimators.getOrPut(value.sourceStableKey){PressureTrendEstimator()}.add(value.sampledAtUtcMillis,value.pressureHpa)
    }
}
