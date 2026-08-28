package com.yokuli.anchorwatch.data.trip

import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripEventEntity
import com.yokuli.anchorwatch.data.database.TripSampleEntity
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.data.database.TripWaypointEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TripTrackPoint(
    val tripId:Long,
    val recordingSequence:Long,
    val timestamp:Long,
    val latitude:Double?,
    val longitude:Double?,
    /** Non-null only for an already segmented, display-only compacted path. */
    val compactedSegmentKey:Long?=null,
){
    val stableKey:String get()="$tripId:$recordingSequence"
    val hasPosition:Boolean get()=latitude?.isFinite()==true&&longitude?.isFinite()==true&&latitude in -90.0..90.0&&longitude in -180.0..180.0
}

data class TripTrackSegment(val points:List<TripTrackPoint>)

enum class TripMapDestinationType{LIVE,HISTORY}
data class TripMapDestination(val type:TripMapDestinationType,val tripId:Long,val selectedWaypointId:Long?=null)
data class TripMapData(
    val session:TripSessionEntity?,
    val segments:List<TripTrackSegment>,
    val waypoints:List<TripWaypointEntity>,
    val events:List<TripEventEntity>,
)

data class TripTrackSnapshot(
    val tripId:Long?=null,
    val persistedSegments:List<TripTrackSegment> = emptyList(),
    val liveTail:List<TripTrackPoint> = emptyList(),
    val hydrated:Boolean=false,
    /** Changes only when the durable/background geometry changes. Live-tail
     * appends therefore never invalidate the entire historical polyline. */
    val historicalRevision:Long=0,
){
    fun rendered(pointBudget:Int):List<TripTrackSegment>{
        if(tripId==null)return emptyList()
        val durable=persistedSegments.flatMap{segment->segment.points}
        val persistedKeys=durable.asSequence().map{it.stableKey}.toHashSet()
        val liveSegments=TripTrackRenderPolicy.segment(liveTail.filterNot{it.stableKey in persistedKeys})
        val combined=persistedSegments.map{TripTrackSegment(it.points.toList())}.toMutableList()
        liveSegments.forEach{segment->
            val last=combined.lastOrNull()?.points?.lastOrNull();val first=segment.points.firstOrNull()
            if(last!=null&&first!=null&&TripTrackRenderPolicy.segment(listOf(last,first)).size==1){val merged=combined.removeAt(combined.lastIndex).points+segment.points;combined+=TripTrackSegment(merged)}
            else combined+=segment
        }
        return TripTrackRenderPolicy.withBudget(combined,pointBudget)
    }
}

/** Display-only geometry. Original samples remain untouched in Room. */
object TripTrackRenderPolicy{
    const val CARD_PREVIEW_BUDGET=256
    const val OVERVIEW_BUDGET=512
    const val LIVE_DETAIL_BUDGET=2_500
    const val HISTORY_DETAIL_BUDGET=5_000
    const val RECENT_HIGH_RESOLUTION_POINTS=384
    const val GAP_MILLIS=15_000L
    const val ABSOLUTE_JUMP_METERS=1_500.0

    fun render(values:List<TripTrackPoint>,budget:Int):List<TripTrackSegment>{
        if(budget<=0||values.isEmpty())return emptyList()
        val deduplicated=LinkedHashMap<String,TripTrackPoint>(values.size)
        values.forEach{deduplicated[it.stableKey]=it}
        return withBudget(segment(deduplicated.values.toList()),budget)
    }

    /**
     * Bounds the repository's in-memory history without erasing discontinuities.
     * Null separator points are display metadata only; persisted Room samples are
     * never rewritten. Counting separators in [budget] also prevents a very
     * fragmented recording from growing the cache without bound.
     */
    internal fun compact(values:List<TripTrackPoint>,budget:Int):List<TripTrackPoint>{
        if(budget<=0||values.isEmpty())return emptyList()
        if(values.size<=budget)return values
        val allSegments=segment(values)
        if(allSegments.isEmpty())return emptyList()
        val maxSegments=((budget+1)/2).coerceAtLeast(1)
        val selectedSegments=allSegments.takeLast(maxSegments)
        val separatorCount=(selectedSegments.size-1).coerceAtLeast(0)
        val pointBudget=(budget-separatorCount).coerceAtLeast(1)
        val sampled=withBudget(selectedSegments,pointBudget).map{trackSegment->
            val key=trackSegment.points.first().recordingSequence
            TripTrackSegment(trackSegment.points.map{it.copy(compactedSegmentKey=key)})
        }
        return buildList{
            sampled.forEachIndexed{index,trackSegment->
                if(index>0){
                    val first=trackSegment.points.first()
                    add(
                        TripTrackPoint(
                            tripId=first.tripId,
                            recordingSequence=Long.MIN_VALUE+index,
                            timestamp=first.timestamp,
                            latitude=null,
                            longitude=null,
                            compactedSegmentKey=null,
                        ),
                    )
                }
                addAll(trackSegment.points)
            }
        }
    }

    fun withBudget(raw:List<TripTrackSegment>,budget:Int):List<TripTrackSegment>{
        if(budget<=0)return emptyList()
        val nonEmpty=raw.filter{it.points.isNotEmpty()}
        if(nonEmpty.sumOf{it.points.size}<=budget)return nonEmpty
        if(nonEmpty.size>=budget)return nonEmpty.takeLast(budget).map{TripTrackSegment(listOf(it.points.last()))}
        val last=nonEmpty.last();val recentCount=minOf(RECENT_HIGH_RESOLUTION_POINTS,budget/2,last.points.size);val recent=last.points.takeLast(recentCount)
        val older=nonEmpty.mapIndexed{index,segment->if(index==nonEmpty.lastIndex)segment.points.dropLast(recentCount)else segment.points}.filter{it.isNotEmpty()}
        val slots=(budget-recent.size).coerceAtLeast(0)
        if(slots==0)return listOf(TripTrackSegment(recent))
        val chosen=if(older.size>slots)older.takeLast(slots)else older
        val allocations=MutableList(chosen.size){1};var extra=slots-chosen.size
        val total=chosen.sumOf{it.size}.coerceAtLeast(1)
        chosen.indices.forEach{index->if(extra>0){val addition=minOf(extra,(slots-chosen.size)*chosen[index].size/total);allocations[index]+=addition;extra-=addition}}
        var cursor=chosen.lastIndex;while(extra>0&&chosen.isNotEmpty()){allocations[cursor]++;extra--;cursor=if(cursor==0)chosen.lastIndex else cursor-1}
        val output=chosen.mapIndexed{index,points->TripTrackSegment(sampleEvenly(points,allocations[index].coerceAtMost(points.size)))}.toMutableList()
        if(recent.isNotEmpty()){
            val belongsToLast=chosen.isNotEmpty()&&older.lastOrNull()===chosen.lastOrNull()
            if(belongsToLast){val previous=output.removeAt(output.lastIndex).points;output+=TripTrackSegment(previous+recent)}else output+=TripTrackSegment(recent)
        }
        return output
    }

    fun segment(values:List<TripTrackPoint>):List<TripTrackSegment>{
        val output=mutableListOf<TripTrackSegment>();var current=mutableListOf<TripTrackPoint>();var previous:TripTrackPoint?=null
        fun flush(){if(current.isNotEmpty())output+=TripTrackSegment(current.toList());current=mutableListOf()}
        values.forEach{point->
            if(!point.hasPosition){flush();previous=null;return@forEach}
            val prior=previous
            val alreadySegmented=prior?.compactedSegmentKey!=null&&prior.compactedSegmentKey==point.compactedSegmentKey
            val discontinuity=prior!=null&&!alreadySegmented&&(
                point.timestamp-prior.timestamp !in 0L..GAP_MILLIS||
                    AnchorGeometry.distanceMeters(requireNotNull(prior.latitude),requireNotNull(prior.longitude),requireNotNull(point.latitude),requireNotNull(point.longitude))>
                    maxOf(ABSOLUTE_JUMP_METERS,(point.timestamp-prior.timestamp).coerceAtLeast(1L)/1_000.0*50.0)
                )
            if(discontinuity)flush()
            current+=point;previous=point
        }
        flush();return output
    }

    private fun sampleEvenly(values:List<TripTrackPoint>,budget:Int):List<TripTrackPoint>{
        if(budget<=0||values.isEmpty())return emptyList()
        if(values.size<=budget)return values
        if(budget==1)return listOf(values.last())
        val selected=LinkedHashMap<String,TripTrackPoint>(budget)
        for(index in 0 until budget){
            val sourceIndex=((index.toDouble()*(values.lastIndex))/(budget-1)).toInt().coerceIn(0,values.lastIndex)
            selected[values[sourceIndex].stableKey]=values[sourceIndex]
        }
        return selected.values.toList()
    }
}

/** One process-wide bridge between TripRuntime batching and every live map.
 * It owns only bounded display geometry; Room remains the source of truth. */
@Singleton
class TripTrackRepository @Inject constructor(private val dao:TripDao){
    private val mutex=Mutex()
    private val _snapshot=MutableStateFlow(TripTrackSnapshot())
    val snapshot=_snapshot.asStateFlow()
    private var persisted=mutableListOf<TripTrackPoint>()
    private var persistedRendered:List<TripTrackSegment> = emptyList()
    private val tail=LinkedHashMap<String,TripTrackPoint>()
    private var revision=0L

    suspend fun begin(tripId:Long){
        mutex.withLock{
            if(_snapshot.value.tripId==tripId&&_snapshot.value.hydrated)return
            persisted=mutableListOf();persistedRendered=emptyList();tail.clear();_snapshot.value=TripTrackSnapshot(tripId=tripId)
        }
        val loaded=loadCanonical(tripId,MAX_PERSISTED_RENDER_POINTS)
        mutex.withLock{
            if(_snapshot.value.tripId!=tripId)return
            persisted=loaded.toMutableList();persistedRendered=TripTrackRenderPolicy.render(persisted,MAX_PERSISTED_RENDER_POINTS);revision++
            publish(hydrated=true)
        }
    }

    suspend fun clear(){mutex.withLock{persisted.clear();persistedRendered=emptyList();tail.clear();revision++;_snapshot.value=TripTrackSnapshot(historicalRevision=revision)}}

    suspend fun appendLive(sample:TripSampleEntity)=mutex.withLock{
        val point=sample.trackPoint()
        if(_snapshot.value.tripId!=sample.tripId){persisted.clear();persistedRendered=emptyList();tail.clear();_snapshot.value=TripTrackSnapshot(tripId=sample.tripId,hydrated=true,historicalRevision=revision)}
        tail[point.stableKey]=point
        while(tail.size>MAX_LIVE_TAIL_POINTS)tail.remove(tail.keys.first())
        publish(_snapshot.value.hydrated)
    }

    suspend fun markPersisted(values:List<TripSampleEntity>)=mutex.withLock{
        if(values.isEmpty())return@withLock
        val tripId=values.first().tripId
        if(_snapshot.value.tripId!=tripId){persisted.clear();persistedRendered=emptyList();tail.clear();_snapshot.value=TripTrackSnapshot(tripId=tripId,hydrated=true,historicalRevision=revision)}
        values.forEach{sample->val point=sample.trackPoint();tail.remove(point.stableKey);persisted+=point}
        persisted=bounded(persisted).toMutableList();persistedRendered=TripTrackRenderPolicy.render(persisted,MAX_PERSISTED_RENDER_POINTS);revision++;publish(true)
    }

    suspend fun loadRendered(tripId:Long,pointBudget:Int):List<TripTrackSegment>{
        val current=snapshot.value
        if(current.tripId==tripId&&current.hydrated)return current.rendered(pointBudget)
        return TripTrackRenderPolicy.render(loadCanonical(tripId,MAX_PERSISTED_RENDER_POINTS),pointBudget)
    }

    suspend fun loadMapData(tripId:Long,pointBudget:Int)=TripMapData(
        session=dao.session(tripId),segments=loadRendered(tripId,pointBudget),
        waypoints=withContext(Dispatchers.IO){dao.waypoints(tripId)},
        events=withContext(Dispatchers.IO){dao.events(tripId)},
    )

    private suspend fun loadCanonical(tripId:Long,limit:Int)=withContext(Dispatchers.IO){
        var afterTimestamp=Long.MIN_VALUE;var afterId=Long.MIN_VALUE;val accumulator=mutableListOf<TripTrackPoint>()
        while(true){
            val page=dao.samplesPage(tripId,afterTimestamp,afterId,PAGE_SIZE)
            if(page.isEmpty())break
            page.forEach{accumulator+=it.trackPoint()}
            if(accumulator.size>limit*2){val reduced=bounded(accumulator,limit);accumulator.clear();accumulator+=reduced}
            val last=page.last();afterTimestamp=last.timestamp;afterId=last.id
        }
        bounded(accumulator,limit)
    }

    private fun publish(hydrated:Boolean){
        _snapshot.value=TripTrackSnapshot(
            tripId=_snapshot.value.tripId,
            persistedSegments=persistedRendered,
            liveTail=tail.values.toList(),hydrated=hydrated,historicalRevision=revision,
        )
    }

    private fun bounded(values:List<TripTrackPoint>,limit:Int=MAX_PERSISTED_RENDER_POINTS):List<TripTrackPoint>{
        return TripTrackRenderPolicy.compact(values,limit)
    }

    private fun TripSampleEntity.trackPoint()=TripTrackPoint(tripId,recordingSequence,timestamp,latitude,longitude)

    companion object{
        const val MAX_LIVE_TAIL_POINTS=240
        const val MAX_PERSISTED_RENDER_POINTS=5_000
        const val PAGE_SIZE=1_000
    }
}
