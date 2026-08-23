package com.yokuli.anchorwatch.domain.report

import com.yokuli.anchorwatch.data.database.AnchorDao
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import javax.inject.Inject
import kotlin.math.atan2

data class AnchorReport(
    val session:AnchorSessionEntity,
    val durationMillis:Long,
    val pointCount:Long,
    val positionCoveragePercent:Double,
    val circularCoveragePercent:Double,
    val maximumDistanceMeters:Double,
    val timeNearAlarmPercent:Double,
    val gpsGapCount:Int,
    val nmeaDisconnectCount:Int,
    val depthUnavailableCount:Int,
    val windUnavailableCount:Int,
    val minimumDepthMeters:Double?,
    val depthCoveragePercent:Double,
    val maximumWindKnots:Double?,
    val windCoveragePercent:Double,
    val meanAbsoluteHeelDegrees:Double?,
    val p95AbsoluteHeelDegrees:Double?,
    val maximumHeelDegrees:Double?,
    val meanMotionScore:Double?,
    val p95MotionScore:Double?,
    val maximumMotionScore:Double?,
    val medianRollPeriodSeconds:Double?,
    val pressureStartHpa:Double?,
    val pressureEndHpa:Double?,
    val pressureChangeHpa:Double?,
    val centreChangeCount:Int,
    val eventCount:Int,
    val quality:ReportQuality,
    val findings:List<TripFinding>,
    val reportEngineVersion:String="ANCHOR_REPORT_V2",
)

class AnchorReportEngine @Inject constructor(private val anchorDao:AnchorDao,private val tripDao:TripDao){
    suspend fun generate(sessionId:Long):AnchorReport?{
        val session=anchorDao.session(sessionId)?:return null
        var timestamp=Long.MIN_VALUE;var rowId=Long.MIN_VALUE;var count=0L;var valid=0L;var near=0L;var maxDistance=0.0;var gaps=0;var lastTime:Long?=null;val bearingBins=BooleanArray(360)
        while(true){val page=anchorDao.pointsPage(sessionId,timestamp,rowId,1_000);if(page.isEmpty())break;page.forEach{point->count++;if(point.fixTrust!="REJECTED"){valid++;maxDistance=maxOf(maxDistance,point.distanceFromAnchor);if(point.distanceFromAnchor>=session.alarmRadiusMeters*.8)near++;if(point.distanceFromAnchor>=3){val north=(point.latitude-session.anchorLatitude)*110_540.0;val east=(point.longitude-session.anchorLongitude)*111_320.0*kotlin.math.cos(Math.toRadians(session.anchorLatitude));val bearing=(Math.toDegrees(atan2(east,north))+360)%360;bearingBins[bearing.toInt().coerceIn(0,359)]=true}};lastTime?.let{if(point.timestamp-it>15_000)gaps++};lastTime=point.timestamp};val last=page.last();timestamp=last.timestamp;rowId=last.id}
        val depth=StreamingStatistics();val wind=StreamingStatistics();val absoluteHeel=StreamingStatistics();val motion=StreamingStatistics();val rollPeriod=StreamingStatistics();var telemetryCount=0L;var depthCount=0L;var windCount=0L;var pressureStart:Double?=null;var pressureEnd:Double?=null;timestamp=Long.MIN_VALUE;rowId=Long.MIN_VALUE
        while(true){val page=tripDao.anchorTelemetryPage(sessionId,timestamp,rowId,1_000);if(page.isEmpty())break;page.forEach{sample->telemetryCount++;sample.depthMeters?.takeIf{(sample.depthAgeMillis?:Long.MAX_VALUE)<=60_000}?.let{depth.add(it);depthCount++};sample.trueWindSpeedKnots?.takeIf{(sample.windAgeMillis?:Long.MAX_VALUE)<=60_000}?.let{wind.add(it);windCount++};sample.heelDegrees?.let{kotlin.math.abs(it)}?.let(absoluteHeel::add);sample.motionScore?.let(motion::add);sample.rollPeriodSeconds?.takeIf{it>0}?.let(rollPeriod::add);sample.pressureHpa?.let{if(pressureStart==null)pressureStart=it;pressureEnd=it}};val last=page.last();timestamp=last.timestamp;rowId=last.id}
        var centreChanges=0;var nmeaDisconnects=0;var depthUnavailable=0;var windUnavailable=0;var eventCount=0;timestamp=Long.MIN_VALUE;rowId=Long.MIN_VALUE
        while(true){val page=anchorDao.eventsPage(sessionId,timestamp,rowId,1_000);if(page.isEmpty())break;page.forEach{event->eventCount++;when(event.type){"ANCHOR_CENTRE_RECALCULATED_APPLIED","ANCHOR_CENTER_ACCEPTED_BY_USER"->centreChanges++;"NMEA_CONNECTION_LOST"->nmeaDisconnects++;"DEPTH_DATA_LOST"->depthUnavailable++;"WIND_DATA_LOST"->windUnavailable++}};val last=page.last();timestamp=last.timestamp;rowId=last.id}
        val coverage=if(count==0L)0.0 else valid*100.0/count;val circular=circularCoveragePercent(bearingBins);val nearPercent=if(valid==0L)0.0 else near*100.0/valid;val quality=when{coverage>=90&&gaps==0->ReportQuality.GOOD;coverage>=60->ReportQuality.PARTIAL;else->ReportQuality.LIMITED};val findings=buildList{if(gaps>0)add(TripFinding("WARNING","GPS gaps recorded","$gaps gaps longer than 15 seconds were observed."));if(nmeaDisconnects>0)add(TripFinding("ATTENTION","NMEA interruptions recorded","NMEA became unavailable $nmeaDisconnects times during this watch."));if(depthUnavailable+windUnavailable>0)add(TripFinding("ATTENTION","Condition data became unavailable","Depth became unavailable $depthUnavailable times and wind became unavailable $windUnavailable times."));if(nearPercent>=10)add(TripFinding("ATTENTION","Frequent approach to alarm boundary","${nearPercent.toInt()}% of usable positions were beyond 80% of the configured alarm radius."));if(session.alarmCount+session.depthAlarmCount+session.windAlarmCount>0)add(TripFinding("REVIEW","Safety alarms occurred","Review the event timeline; an alarm alone does not prove anchor drag."));if(session.centerStatus!="RESOLVED")add(TripFinding("LIMITATION","Anchor centre remained unresolved","Track metrics use the protected temporary geometry and must not be read as a precise anchor position."))}
        return AnchorReport(session,((session.endedAt?:System.currentTimeMillis())-session.startedAt).coerceAtLeast(0),count,coverage,circular,maxDistance,nearPercent,gaps,nmeaDisconnects,depthUnavailable,windUnavailable,depth.minimum?:session.minObservedDepthMeters,percent(depthCount,telemetryCount),wind.maximum?:session.maxObservedWindKnots,percent(windCount,telemetryCount),absoluteHeel.mean(),absoluteHeel.quantile(.95),absoluteHeel.maximum,motion.mean(),motion.quantile(.95),motion.maximum,rollPeriod.quantile(.5),pressureStart,pressureEnd,if(pressureStart!=null&&pressureEnd!=null)pressureEnd!!-pressureStart!! else null,centreChanges,eventCount,quality,findings)
    }
    private fun circularCoveragePercent(bins:BooleanArray):Double{
        if(bins.none{it})return 0.0
        if(bins.all{it})return 100.0
        var longest=0;var run=0
        repeat(bins.size*2){index->if(!bins[index%bins.size]){run++;longest=maxOf(longest,run.coerceAtMost(bins.size))}else run=0}
        return (bins.size-longest.coerceAtMost(bins.size))*100.0/bins.size
    }
    private fun percent(value:Long,total:Long)=if(total==0L)0.0 else value*100.0/total
}
