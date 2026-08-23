package com.yokuli.anchorwatch.data.trip

import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripSampleEntity
import javax.inject.Inject
import javax.inject.Singleton

data class TripReplayPoint(
    val timestamp:Long,
    val latitude:Double?,
    val longitude:Double?,
    val sogKnots:Double?,
    val cogDegrees:Double?,
    val headingDegrees:Double?,
    val depthMeters:Double?,
    val boatSpeedKnots:Double?,
    val trueWindKnots:Double?,
    val apparentWindKnots:Double?,
    val heelDegrees:Double?,
    val motionScore:Double?,
){val windKnots:Double? get()=trueWindKnots?:apparentWindKnots}
data class TripReplayMarker(val timestamp:Long,val pointIndex:Int,val type:String,val title:String)
data class TripReplayData(val points:List<TripReplayPoint>,val markers:List<TripReplayMarker>)
enum class TripReplayColorMode { SOG, BSP, HEEL, TWS, AWS, MOTION, DEPTH }

object TripReplayPolicy{
    fun nearestIndex(timestamps:List<Long>,target:Long):Int?{
        if(timestamps.isEmpty())return null
        val found=timestamps.binarySearch(target);if(found>=0)return found
        val insertion=-found-1;return when{insertion<=0->0;insertion>=timestamps.size->timestamps.lastIndex;target-timestamps[insertion-1]<=timestamps[insertion]-target->insertion-1;else->insertion}
    }
    fun colorBucket(point:TripReplayPoint,mode:TripReplayColorMode):Int=when(mode){
        TripReplayColorMode.SOG->speedBucket(point.sogKnots)
        TripReplayColorMode.BSP->speedBucket(point.boatSpeedKnots)
        TripReplayColorMode.HEEL->when{point.heelDegrees==null->-1;kotlin.math.abs(point.heelDegrees)<10->0;kotlin.math.abs(point.heelDegrees)<20->1;else->2}
        TripReplayColorMode.TWS->windBucket(point.trueWindKnots)
        TripReplayColorMode.AWS->windBucket(point.apparentWindKnots)
        TripReplayColorMode.MOTION->when{point.motionScore==null->-1;point.motionScore<35->0;point.motionScore<65->1;else->2}
        TripReplayColorMode.DEPTH->when{point.depthMeters==null->-1;point.depthMeters<3->2;point.depthMeters<8->1;else->0}
    }
    private fun speedBucket(value:Double?)=when{value==null->-1;value<2->0;value<5->1;else->2}
    private fun windBucket(value:Double?)=when{value==null->-1;value<15->0;value<25->1;else->2}
}

/** Builds one lightweight in-memory series when Replay opens; slider movement never queries Room. */
@Singleton
class TripReplayLoader @Inject constructor(private val dao:TripDao){
    suspend fun load(tripId:Long):TripReplayData{
        var afterTime=Long.MIN_VALUE;var afterId=Long.MIN_VALUE;var spacing=1_000L;var lastKept=Long.MIN_VALUE
        val points=mutableListOf<TripReplayPoint>()
        while(true){
            val page=dao.samplesPage(tripId,afterTime,afterId,PAGE);if(page.isEmpty())break
            page.forEach{sample->
                if(lastKept==Long.MIN_VALUE||sample.timestamp-lastKept>=spacing){points+=sample.replayPoint();lastKept=sample.timestamp}
                if(points.size>MAX_POINTS){val reduced=points.filterIndexed{index,_->index%2==0}.toMutableList();points.clear();points+=reduced;spacing*=2;lastKept=points.lastOrNull()?.timestamp?:Long.MIN_VALUE}
            }
            val last=page.last();afterTime=last.timestamp;afterId=last.id
        }
        val rawMarkers=buildList{
            dao.events(tripId).filter{it.type in REPLAY_EVENT_TYPES}.forEach{add(Triple(it.timestamp,it.type,it.type.replace('_',' ').lowercase().replaceFirstChar{char->char.titlecase()}))}
            dao.waypoints(tripId).forEach{add(Triple(it.timestamp,"WAYPOINT",it.name))}
        }
        val timestamps=points.map{it.timestamp}
        val markers=rawMarkers.sortedBy{it.first}.mapNotNull{(timestamp,type,title)->TripReplayPolicy.nearestIndex(timestamps,timestamp)?.let{TripReplayMarker(timestamp,it,type,title)}}
        return TripReplayData(points,markers)
    }

    private fun TripSampleEntity.replayPoint()=TripReplayPoint(timestamp,latitude,longitude,sogKnots,cogTrueDegrees,headingTrueDegrees,depthMeters,speedThroughWaterKnots,trueWindSpeedKnots,apparentWindSpeedKnots,heelDegrees,motionScore)
    private companion object{
        const val PAGE=1_000;const val MAX_POINTS=50_000
        val REPLAY_EVENT_TYPES=setOf("USER_WAYPOINT","IMPACT_CANDIDATE","HIGH_MOTION","POSITION_GAP_STARTED","POSITION_GAP_ENDED","NMEA_DATA_GAP","NMEA_DATA_RESTORED","POSITION_SOURCE_CHANGED","HEADING_SOURCE_CHANGED")
    }
}
