package com.yokuli.anchorwatch.domain.report

import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripSampleEntity
import com.yokuli.anchorwatch.data.database.TripSessionEntity
import com.yokuli.anchorwatch.domain.trip.TripSampleFreshness
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ReportQuality { GOOD, PARTIAL, LIMITED }
enum class TripTrueWindReference { EXTERNAL, WATER, GROUND }
internal object TripTrueWindReferenceClassifier{
    fun from(source:String?):TripTrueWindReference?{
        val normalized=source?.uppercase()?:return null
        return when{
            "GROUND" in normalized->TripTrueWindReference.GROUND
            "WATER" in normalized->TripTrueWindReference.WATER
            "EXTERNAL" in normalized||normalized=="BOAT_NMEA"->TripTrueWindReference.EXTERNAL
            else->null
        }
    }
}
data class TripFinding(val severity:String,val title:String,val detail:String)
data class TripSourceTimelineEntry(val timestamp:Long,val type:String,val detailJson:String)
data class HeelDistribution(val zeroToTenPercent:Double=0.0,val tenToTwentyPercent:Double=0.0,val twentyToThirtyPercent:Double=0.0,val overThirtyPercent:Double=0.0)
data class WindHeelBand(val label:String,val sampleCount:Long,val medianAbsHeelDegrees:Double?,val p95AbsHeelDegrees:Double?)
data class SpeedHeelBand(val label:String,val sampleCount:Long,val averageSogKnots:Double?,val maximumSogKnots:Double?,val averageBoatSpeedKnots:Double?,val maximumBoatSpeedKnots:Double?)
data class TripReport(
    val session:TripSessionEntity,
    val durationMillis:Long,
    val distanceMeters:Double,
    val movingMillis:Long,
    val recordedSampleCount:Long,
    val startLatitude:Double?,val startLongitude:Double?,val endLatitude:Double?,val endLongitude:Double?,
    val averageSogKnots:Double?,val medianSogKnots:Double?,val p95SogKnots:Double?,val maxSogKnots:Double?,val maxSogTimestamp:Long?,
    val averageAbsHeelDegrees:Double?,val rmsHeelDegrees:Double?,val p95AbsHeelDegrees:Double?,val maxPortHeelDegrees:Double?,val maxStarboardHeelDegrees:Double?,val heelDistribution:HeelDistribution,
    val meanPitchDegrees:Double?,val p95AbsPitchDegrees:Double?,val maxBowUpDegrees:Double?,val maxBowDownDegrees:Double?,
    val motionMean:Double?,val motionMedian:Double?,val motionP95:Double?,val motionMaximum:Double?,val rollRateRmsDegreesPerSecond:Double?,val pitchRateRmsDegreesPerSecond:Double?,val dominantRollPeriodSeconds:Double?,
    val minDepthMeters:Double?,val medianDepthMeters:Double?,val p05DepthMeters:Double?,val minUkcMeters:Double?,val p05UkcMeters:Double?,
    val apparentWindMeanKnots:Double?,val apparentWindP95Knots:Double?,val apparentWindMaximumKnots:Double?,val trueWindMeanKnots:Double?,val trueWindP95Knots:Double?,val maximumTrueWindKnots:Double?,val meanTrueWindDirectionDegrees:Double?,val windHeelBands:List<WindHeelBand>,
    val pressureStartHpa:Double?,val pressureEndHpa:Double?,val pressureMinimumHpa:Double?,val pressureMaximumHpa:Double?,val pressureChangeHpa:Double?,
    val headingCogMedianDegrees:Double?,val headingCogP95AbsoluteDegrees:Double?,val headingCogBias:String?,
    val estimatedSetDegrees:Double?,val estimatedDriftKnots:Double?,val setDriftSampleCount:Long,
    val positionCoveragePercent:Double,val depthCoveragePercent:Double,val attitudeCoveragePercent:Double,val windCoveragePercent:Double,
    val impactCandidateCount:Int,val positionGapCount:Int,val nmeaGapCount:Int,val depthGapCount:Int,val windGapCount:Int,val phoneMotionGapCount:Int,val highMotionCount:Int,
    val positionSourceChangeCount:Int,val headingSourceChangeCount:Int,val eventCount:Int,val waypointCount:Int,val sustainedHeelMillis:Long,
    val quality:ReportQuality,val findings:List<TripFinding>,val motionAlgorithmVersion:String,val reportEngineVersion:String=ENGINE_VERSION,
    val averageBoatSpeedKnots:Double?=null,val p95BoatSpeedKnots:Double?=null,val maxBoatSpeedKnots:Double?=null,
    val fastest500mAverageKnots:Double?=null,val fastest500mStartedAt:Long?=null,val fastest500mEndedAt:Long?=null,
    val sailingUsableSampleCount:Long=0,val portTackMillis:Long=0,val starboardTackMillis:Long=0,
    val pointOfSailMillis:Map<PointOfSail,Long> = emptyMap(),val tackCount:Int=0,val gybeCount:Int=0,
    val legs:List<TripLegSummary> = emptyList(),
    val trueWindCoveragePercent:Double=0.0,
    val apparentWindCoveragePercent:Double=0.0,
    val externalTrueWindCoveragePercent:Double=0.0,
    val derivedWaterTrueWindCoveragePercent:Double=0.0,
    val derivedGroundTrueWindCoveragePercent:Double=0.0,
    val sourceTimeline:List<TripSourceTimelineEntry> = emptyList(),
    val maximumSogWithAttitudeKnots:Double?=null,
    val heelAtMaximumSogDegrees:Double?=null,
    val maximumSogWithAttitudeTimestamp:Long?=null,
    val speedHeelBands:List<SpeedHeelBand> = emptyList(),
    val attitudeArtifactFilteredCount:Long=0,
){companion object{const val ENGINE_VERSION="TRIP_REPORT_V4"}}

class TripReportEngine @Inject constructor(private val dao:TripDao){
    suspend fun generate(sessionId:Long):TripReport?{
        val session=dao.session(sessionId)?:return null
        val waypoints=dao.waypoints(sessionId).sortedBy{it.timestamp}
        val legAnalytics=TripLegAccumulator(session.startedAt,waypoints.map{TripLegBoundary(it.timestamp,it.name)})
        var afterTimestamp=Long.MIN_VALUE;var afterId=Long.MIN_VALUE
        var count=0L;var positionCount=0L;var depthCount=0L;var attitudeCount=0L;var windCount=0L;var trueWindCount=0L;var apparentWindCount=0L
        var externalTrueWindCount=0L;var derivedWaterTrueWindCount=0L;var derivedGroundTrueWindCount=0L
        var distance=0.0;var previousPosition:TripSampleEntity?=null
        var startLatitude:Double?=null;var startLongitude:Double?=null;var endLatitude:Double?=null;var endLongitude:Double?=null
        val movingSog=StreamingStatistics();val boatSpeed=StreamingStatistics();var maxSogTimestamp:Long?=null
        val distanceWindow=ArrayDeque<Pair<TripSampleEntity,Double>>();var cumulativeDistance=0.0;var fastest500m:Double?=null;var fastest500mStart:Long?=null;var fastest500mEnd:Long?=null
        val heel=StreamingStatistics();val absoluteHeel=StreamingStatistics();val heelBins=LongArray(4)
        val pitch=StreamingStatistics();val absolutePitch=StreamingStatistics()
        val motion=StreamingStatistics();val rollRate=StreamingStatistics();val pitchRate=StreamingStatistics();val rollPeriod=StreamingStatistics()
        val depth=StreamingStatistics();val ukc=StreamingStatistics();val apparentWind=StreamingStatistics();val trueWind=StreamingStatistics();val trueWindDirection=StreamingCircularMean()
        val pressure=StreamingStatistics();var pressureStart:Double?=null;var pressureEnd:Double?=null
        val headingCog=StreamingStatistics();val headingCogAbs=StreamingStatistics()
        val windHeel=List(5){StreamingStatistics(seed=0x59100+it)}
        val speedByHeel=List(5){StreamingStatistics(seed=0x5A100+it)}
        val boatSpeedByHeel=List(5){StreamingStatistics(seed=0x5B100+it)}
        var maximumSogWithAttitude:Double?=null;var heelAtMaximumSog:Double?=null;var maximumSogWithAttitudeAt:Long?=null
        var attitudeArtifactFilteredCount=0L
        var setNorth=0.0;var setEast=0.0;var setCount=0L
        var previousHighHeelAt:Long?=null;var sustainedHeelMillis=0L
        val sourceTimeline=mutableListOf<TripSourceTimelineEntry>()
        val sailing=SailingAnalyticsAccumulator()
        data class AttitudeFrame(val sample:TripSampleEntity,val legPoint:TripLegPoint,val usable:Boolean,val apparentWindUsable:Boolean)
        val attitudeWindow=ArrayDeque<AttitudeFrame>()
        var firstAttitudeFrameEmitted=false
        val processAttitudeFrame:(AttitudeFrame,Boolean,Boolean)->Unit={frame,accepted,artifact->
            val sample=frame.sample
            if(artifact)attitudeArtifactFilteredCount++
            legAnalytics.add(frame.legPoint.copy(heelDegrees=sample.heelDegrees.takeIf{accepted}))
            if(accepted){
                sample.heelDegrees?.let{value->
                    attitudeCount++;heel.add(value);val magnitude=abs(value);absoluteHeel.add(magnitude)
                    heelBins[when{magnitude<10->0;magnitude<20->1;magnitude<30->2;else->3}]++
                    if(magnitude>=SUSTAINED_HEEL_DEGREES){previousHighHeelAt?.let{old->if(sample.timestamp-old in 1..MAX_ATTITUDE_SEGMENT_GAP_MILLIS)sustainedHeelMillis+=sample.timestamp-old};previousHighHeelAt=sample.timestamp}else previousHighHeelAt=null
                    sample.sogKnots?.takeIf{(sample.sogAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&it>=0.0}?.let{sog->
                        val band=heelSpeedBand(magnitude);speedByHeel[band].add(sog)
                        if(maximumSogWithAttitude==null||sog>maximumSogWithAttitude!!){maximumSogWithAttitude=sog;heelAtMaximumSog=value;maximumSogWithAttitudeAt=sample.timestamp}
                    }
                    sample.speedThroughWaterKnots?.takeIf{(sample.stwAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&it>=0.0}?.let{boatSpeedByHeel[heelSpeedBand(magnitude)].add(it)}
                    if(frame.apparentWindUsable&&sample.apparentWindSpeedKnots!=null)windHeel[windBand(sample.apparentWindSpeedKnots)].add(magnitude)
                }
                sample.pitchDegrees?.let{pitch.add(it);absolutePitch.add(abs(it))}
                sample.motionScore?.let(motion::add)
                sample.rollRateDegPerSec?.let(rollRate::add)
                sample.pitchRateDegPerSec?.let(pitchRate::add)
                sample.rollPeriodSeconds?.takeIf{it>0}?.let(rollPeriod::add)
            }else previousHighHeelAt=null
        }
        fun filterPoint(frame:AttitudeFrame)=TripAttitudeFilterPoint(
            timestamp=frame.sample.timestamp,heelDegrees=frame.sample.heelDegrees,pitchDegrees=frame.sample.pitchDegrees,
            rollRateDegreesPerSecond=frame.sample.rollRateDegPerSec,pitchRateDegreesPerSecond=frame.sample.pitchRateDegPerSec,
            cogDegrees=frame.sample.cogTrueDegrees,cogFresh=(frame.sample.cogAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS,usable=frame.usable,
        )
        fun addAttitudeFrame(frame:AttitudeFrame){
            attitudeWindow.addLast(frame)
            if(!firstAttitudeFrameEmitted&&attitudeWindow.size==2){
                val first=attitudeWindow.first();processAttitudeFrame(first,first.usable,false);firstAttitudeFrameEmitted=true
            }
            if(attitudeWindow.size>=3){
                val previous=attitudeWindow.elementAt(0);val current=attitudeWindow.elementAt(1);val next=attitudeWindow.elementAt(2)
                val artifact=TripAttitudeArtifactPolicy.isShortHandlingArtifact(filterPoint(previous),filterPoint(current),filterPoint(next))
                processAttitudeFrame(current,current.usable&&!artifact,artifact);attitudeWindow.removeFirst()
            }
        }

        while(true){
            val page=dao.samplesPage(sessionId,afterTimestamp,afterId,PAGE_SIZE)
            if(page.isEmpty())break
            page.forEach{sample->
                count++
                val positionUsable=sample.latitude!=null&&sample.longitude!=null&&(sample.positionAgeMillis?:Long.MAX_VALUE)<=POSITION_USABLE_MILLIS
                if(positionUsable){
                    positionCount++
                    if(startLatitude==null){startLatitude=sample.latitude;startLongitude=sample.longitude}
                    endLatitude=sample.latitude;endLongitude=sample.longitude
                    previousPosition?.takeIf{old->sample.timestamp-old.timestamp in 1..MAX_DISTANCE_SEGMENT_GAP_MILLIS}?.let{old->
                        val segment=AnchorGeometry.distanceMeters(old.latitude!!,old.longitude!!,sample.latitude!!,sample.longitude!!)
                        if(segment<=MAX_CONTINUOUS_SEGMENT_METERS){distance+=segment;cumulativeDistance+=segment}
                    }
                    distanceWindow.addLast(sample to cumulativeDistance)
                    val start=distanceWindow.firstOrNull{(_,at)->cumulativeDistance-at>=500.0}
                    if(start!=null){val elapsed=sample.timestamp-start.first.timestamp;if(elapsed>0){val speed=(cumulativeDistance-start.second)/(elapsed/1000.0)*1.943844; if(fastest500m==null||speed>fastest500m!!){fastest500m=speed;fastest500mStart=start.first.timestamp;fastest500mEnd=sample.timestamp}}}
                    while(distanceWindow.size>2&&cumulativeDistance-distanceWindow.first().second>650.0)distanceWindow.removeFirst()
                    previousPosition=sample
                }else{previousPosition=null;distanceWindow.clear()}
                val attitudeUsable=TripSampleFreshness.attitudeUsable(sample.attitudeAgeMillis,sample.attitudeQuality,sample.attitudeMountSuspect)
                val trueWindSpeedUsable=TripSampleFreshness.trueWindAvailable(sample.trueWindSpeedKnots,sample.trueWindSpeedAgeMillis)
                val apparentWindSpeedUsable=TripSampleFreshness.apparentWindAvailable(sample.apparentWindSpeedKnots,sample.apparentWindSpeedAgeMillis)
                addAttitudeFrame(AttitudeFrame(sample,TripLegPoint(sample.timestamp,sample.latitude.takeIf{positionUsable},sample.longitude.takeIf{positionUsable},sample.sogKnots.takeIf{positionUsable&&(sample.sogAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS},sample.speedThroughWaterKnots?.takeIf{(sample.stwAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS},sample.trueWindSpeedKnots?.takeIf{trueWindSpeedUsable},null),attitudeUsable,apparentWindSpeedUsable))

                sample.sogKnots?.takeIf{positionUsable&&(sample.sogAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&it>=MOVING_SOG_KNOTS}?.let{value->
                    val oldMax=movingSog.maximum;movingSog.add(value);if(oldMax==null||value>oldMax)maxSogTimestamp=sample.timestamp
                }
                sample.speedThroughWaterKnots?.takeIf{(sample.stwAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&it>=0.0}?.let(boatSpeed::add)
                sample.depthMeters?.takeIf{(sample.depthAgeMillis?:Long.MAX_VALUE)<=DEPTH_USABLE_MILLIS}?.let{depth.add(it);depthCount++}
                sample.ukcMeters?.takeIf{(sample.depthAgeMillis?:Long.MAX_VALUE)<=DEPTH_USABLE_MILLIS}?.let(ukc::add)
                val trueWindDirectionUsable=(sample.trueWindDirectionAgeMillis?:Long.MAX_VALUE)<=WIND_USABLE_MILLIS
                val trueWindAngleUsable=(sample.trueWindAngleAgeMillis?:Long.MAX_VALUE)<=WIND_USABLE_MILLIS
                if(trueWindSpeedUsable&&sample.trueWindSpeedKnots!=null){
                    trueWindCount++
                    when(TripTrueWindReferenceClassifier.from(sample.trueWindProvenance?:sample.windSource)){
                        TripTrueWindReference.EXTERNAL->externalTrueWindCount++
                        TripTrueWindReference.WATER->derivedWaterTrueWindCount++
                        TripTrueWindReference.GROUND->derivedGroundTrueWindCount++
                        null->Unit
                    }
                }
                if(apparentWindSpeedUsable&&sample.apparentWindSpeedKnots!=null)apparentWindCount++
                if((trueWindSpeedUsable&&sample.trueWindSpeedKnots!=null)||(apparentWindSpeedUsable&&sample.apparentWindSpeedKnots!=null))windCount++
                sample.apparentWindSpeedKnots?.takeIf{apparentWindSpeedUsable}?.let(apparentWind::add)
                sample.trueWindSpeedKnots?.takeIf{trueWindSpeedUsable}?.let(trueWind::add)
                sample.trueWindDirectionDegrees?.takeIf{trueWindDirectionUsable}?.let(trueWindDirection::add)
                // Sailing point-of-sail analytics are water-relative only.
                sailing.add(sample.timestamp,sample.trueWindAngleDegrees?.takeIf{trueWindAngleUsable},sample.speedThroughWaterKnots?.takeIf{(sample.stwAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS})
                sample.pressureHpa?.takeIf{(sample.pressureAgeMillis?:Long.MAX_VALUE)<=PRESSURE_USABLE_MILLIS}?.let{value->if(pressureStart==null)pressureStart=value;pressureEnd=value;pressure.add(value)}

                val navigationFresh=(sample.sogAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&(sample.cogAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&(sample.headingAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS
                if(navigationFresh&&(sample.sogKnots?:0.0)>=HEADING_COG_MIN_SOG&&sample.headingTrueDegrees!=null&&sample.cogTrueDegrees!=null){
                    val difference=shortestSigned(sample.cogTrueDegrees-sample.headingTrueDegrees);headingCog.add(difference);headingCogAbs.add(abs(difference))
                }
                if(navigationFresh&&(sample.stwAgeMillis?:Long.MAX_VALUE)<=NAVIGATION_ANALYTICS_FRESH_MILLIS&&sample.speedThroughWaterKnots!=null&&sample.headingTrueDegrees!=null&&sample.sogKnots!=null&&sample.cogTrueDegrees!=null){
                    val ground=Math.toRadians(sample.cogTrueDegrees);val water=Math.toRadians(sample.headingTrueDegrees)
                    setEast+=sample.sogKnots*sin(ground)-sample.speedThroughWaterKnots*sin(water)
                    setNorth+=sample.sogKnots*cos(ground)-sample.speedThroughWaterKnots*cos(water)
                    setCount++
                }
            }
            val last=page.last();afterTimestamp=last.timestamp;afterId=last.id
        }
        if(attitudeWindow.isNotEmpty()){
            val last=attitudeWindow.last()
            if(!firstAttitudeFrameEmitted)processAttitudeFrame(last,last.usable,false)
            else processAttitudeFrame(last,last.usable,false)
            attitudeWindow.clear()
        }

        val eventCounts=mutableMapOf<String,Int>();var totalEvents=0;afterTimestamp=Long.MIN_VALUE;afterId=Long.MIN_VALUE
        while(true){val page=dao.eventsPage(sessionId,afterTimestamp,afterId,PAGE_SIZE);if(page.isEmpty())break;page.forEach{event->eventCounts[event.type]=(eventCounts[event.type]?:0)+1;totalEvents++;if(event.type in SOURCE_TIMELINE_EVENTS)sourceTimeline+=TripSourceTimelineEntry(event.timestamp,event.type,event.detailJson)};val last=page.last();afterTimestamp=last.timestamp;afterId=last.id}
        val waypointCount=waypoints.size
        val impactCount=eventCounts["IMPACT_CANDIDATE"]?:0;val gapCount=eventCounts["POSITION_GAP_STARTED"]?:0;val nmeaGapCount=eventCounts["NMEA_DATA_GAP"]?:0;val depthGapCount=eventCounts["DEPTH_DATA_UNAVAILABLE"]?:0;val windGapCount=eventCounts["WIND_DATA_UNAVAILABLE"]?:0;val phoneMotionGapCount=eventCounts["PHONE_MOTION_UNAVAILABLE"]?:0;val highMotionCount=eventCounts["HIGH_MOTION"]?:0;val positionSourceChangeCount=eventCounts["POSITION_SOURCE_CHANGED"]?:0;val headingSourceChangeCount=eventCounts["HEADING_SOURCE_CHANGED"]?:0
        val positionCoverage=percent(positionCount,count);val depthCoverage=percent(depthCount,count);val attitudeCoverage=percent(attitudeCount,count);val windCoverage=percent(windCount,count);val trueWindCoverage=percent(trueWindCount,count);val apparentWindCoverage=percent(apparentWindCount,count)
        val externalTrueWindCoverage=percent(externalTrueWindCount,count);val derivedWaterTrueWindCoverage=percent(derivedWaterTrueWindCount,count);val derivedGroundTrueWindCoverage=percent(derivedGroundTrueWindCount,count)
        // Overall GOOD requires both navigational continuity and meaningful
        // environmental coverage. A perfect GPS trace with no wind must not be
        // summarized as a uniformly good data set.
        val quality=when{positionCoverage>=90&&windCoverage>=60->ReportQuality.GOOD;positionCoverage>=60->ReportQuality.PARTIAL;else->ReportQuality.LIMITED}
        val setDrift=if(setCount>0){val north=setNorth/setCount;val east=setEast/setCount;((Math.toDegrees(atan2(east,north))+360)%360) to sqrt(north*north+east*east)}else null
        val signedMedian=headingCog.quantile(.5);val headingBias=signedMedian?.takeIf{abs(it)>=1.0}?.let{if(it>0)"STARBOARD" else "PORT"}?:signedMedian?.let{"NEUTRAL"}
        val sailingSummary=sailing.summary()
        val findings=buildList{
            if(positionCoverage<95)add(TripFinding("ATTENTION","Position coverage is incomplete","${positionCoverage.toInt()}% of recorded samples had a usable position."))
            if(gapCount>0)add(TripFinding("ATTENTION","Position gaps recorded","$gapCount position gaps began during this trip."))
            if(nmeaGapCount>0)add(TripFinding("ATTENTION","NMEA interruptions recorded","$nmeaGapCount NMEA data interruptions began during this trip."))
            if(depth.count>0&&depthCoverage<80)add(TripFinding("INFO","Depth coverage is incomplete","Usable depth was present for ${depthCoverage.toInt()}% of samples."))
            if(depthGapCount>0)add(TripFinding("ATTENTION","Depth data became unavailable","Depth became unavailable $depthGapCount times after it had first been observed."))
            if(windGapCount>0)add(TripFinding("ATTENTION","Wind data became unavailable","Wind became unavailable $windGapCount times after it had first been observed."))
            if(windCoverage<60)add(TripFinding("ATTENTION","Wind coverage is limited","Usable apparent or true wind was present for ${windCoverage.toInt()}% of samples; overall quality is not rated GOOD."))
            if(phoneMotionGapCount>0)add(TripFinding("ATTENTION","Phone motion data became unavailable","Requested phone motion data became unavailable $phoneMotionGapCount times."))
            if(positionSourceChangeCount+headingSourceChangeCount>0)add(TripFinding("INFO","Data sources changed","Position source changed $positionSourceChangeCount times and heading source changed $headingSourceChangeCount times."))
            if(session.droppedSampleCount>0)add(TripFinding("ATTENTION","Recorder backpressure","${session.droppedSampleCount} samples were dropped from the bounded write queue."))
            if(sustainedHeelMillis>=60_000)add(TripFinding("ATTENTION","Sustained heel recorded","Absolute heel remained at or above ${SUSTAINED_HEEL_DEGREES.toInt()}° for a total of ${sustainedHeelMillis/1_000} seconds. This is an observation, not a sailing instruction."))
            if(highMotionCount>0)add(TripFinding("REVIEW","High-motion periods recorded","$highMotionCount sustained high-motion periods crossed the local observation threshold."))
            if(impactCount>0)add(TripFinding("REVIEW","Impact candidates recorded","$impactCount acceleration peaks crossed the candidate threshold. They are observations, not proof of wave slamming."))
            pressureStart?.let{first->pressureEnd?.let{last->if(abs(last-first)>=4.0)add(TripFinding("INFO","Pressure changed during the trip","Recorded pressure changed ${"%+.1f".format(last-first)} hPa; no weather conclusion is inferred."))}}
        }
        val duration=((session.endedAt?:System.currentTimeMillis())-session.startedAt-session.accumulatedPausedMillis).coerceAtLeast(0)
        return TripReport(
            session=session,durationMillis=duration,distanceMeters=if(distance>0)distance else session.distanceMeters,movingMillis=session.movingDurationMillis,recordedSampleCount=count,
            startLatitude=startLatitude,startLongitude=startLongitude,endLatitude=endLatitude,endLongitude=endLongitude,
            averageSogKnots=movingSog.mean(),medianSogKnots=movingSog.quantile(.5),p95SogKnots=movingSog.quantile(.95),maxSogKnots=movingSog.maximum?:session.maxSogKnots,maxSogTimestamp=maxSogTimestamp,
            averageAbsHeelDegrees=absoluteHeel.mean(),rmsHeelDegrees=heel.rms(),p95AbsHeelDegrees=absoluteHeel.quantile(.95),maxPortHeelDegrees=heel.minimum?.takeIf{it<0},maxStarboardHeelDegrees=heel.maximum?.takeIf{it>0},heelDistribution=HeelDistribution(percent(heelBins[0],attitudeCount),percent(heelBins[1],attitudeCount),percent(heelBins[2],attitudeCount),percent(heelBins[3],attitudeCount)),
            meanPitchDegrees=pitch.mean(),p95AbsPitchDegrees=absolutePitch.quantile(.95),maxBowUpDegrees=pitch.maximum?.takeIf{it>0},maxBowDownDegrees=pitch.minimum?.takeIf{it<0},
            motionMean=motion.mean(),motionMedian=motion.quantile(.5),motionP95=motion.quantile(.95),motionMaximum=motion.maximum,rollRateRmsDegreesPerSecond=rollRate.rms(),pitchRateRmsDegreesPerSecond=pitchRate.rms(),dominantRollPeriodSeconds=rollPeriod.quantile(.5),
            minDepthMeters=depth.minimum?:session.minDepthMeters,medianDepthMeters=depth.quantile(.5),p05DepthMeters=depth.quantile(.05),minUkcMeters=ukc.minimum?:session.minUkcMeters,p05UkcMeters=ukc.quantile(.05),
            apparentWindMeanKnots=apparentWind.mean(),apparentWindP95Knots=apparentWind.quantile(.95),apparentWindMaximumKnots=apparentWind.maximum,trueWindMeanKnots=trueWind.mean(),trueWindP95Knots=trueWind.quantile(.95),maximumTrueWindKnots=trueWind.maximum,meanTrueWindDirectionDegrees=trueWindDirection.degrees(),windHeelBands=windHeel.mapIndexed{index,stats->WindHeelBand(WIND_BAND_LABELS[index],stats.count,stats.quantile(.5),stats.quantile(.95))},
            pressureStartHpa=pressureStart,pressureEndHpa=pressureEnd,pressureMinimumHpa=pressure.minimum,pressureMaximumHpa=pressure.maximum,pressureChangeHpa=if(pressureStart!=null&&pressureEnd!=null)pressureEnd!!-pressureStart!! else null,
            headingCogMedianDegrees=signedMedian,headingCogP95AbsoluteDegrees=headingCogAbs.quantile(.95),headingCogBias=headingBias,
            estimatedSetDegrees=setDrift?.first,estimatedDriftKnots=setDrift?.second,setDriftSampleCount=setCount,
            positionCoveragePercent=positionCoverage,depthCoveragePercent=depthCoverage,attitudeCoveragePercent=attitudeCoverage,windCoveragePercent=windCoverage,
            impactCandidateCount=impactCount,positionGapCount=gapCount,nmeaGapCount=nmeaGapCount,depthGapCount=depthGapCount,windGapCount=windGapCount,phoneMotionGapCount=phoneMotionGapCount,highMotionCount=highMotionCount,
            positionSourceChangeCount=positionSourceChangeCount,headingSourceChangeCount=headingSourceChangeCount,eventCount=totalEvents,waypointCount=waypointCount,sustainedHeelMillis=sustainedHeelMillis,
            quality=quality,findings=findings,motionAlgorithmVersion=session.motionAlgorithmVersion,averageBoatSpeedKnots=boatSpeed.mean(),p95BoatSpeedKnots=boatSpeed.quantile(.95),maxBoatSpeedKnots=boatSpeed.maximum,fastest500mAverageKnots=fastest500m,fastest500mStartedAt=fastest500mStart,fastest500mEndedAt=fastest500mEnd,
            sailingUsableSampleCount=sailingSummary.usableSampleCount,portTackMillis=sailingSummary.portTackMillis,starboardTackMillis=sailingSummary.starboardTackMillis,pointOfSailMillis=sailingSummary.pointOfSailMillis,tackCount=sailingSummary.tackCount,gybeCount=sailingSummary.gybeCount,
            legs=legAnalytics.summaries(session.endedAt?:System.currentTimeMillis()),trueWindCoveragePercent=trueWindCoverage,apparentWindCoveragePercent=apparentWindCoverage,
            externalTrueWindCoveragePercent=externalTrueWindCoverage,derivedWaterTrueWindCoveragePercent=derivedWaterTrueWindCoverage,derivedGroundTrueWindCoveragePercent=derivedGroundTrueWindCoverage,
            sourceTimeline=sourceTimeline,
            maximumSogWithAttitudeKnots=maximumSogWithAttitude,heelAtMaximumSogDegrees=heelAtMaximumSog,maximumSogWithAttitudeTimestamp=maximumSogWithAttitudeAt,
            speedHeelBands=speedByHeel.mapIndexed{index,stats->SpeedHeelBand(HEEL_SPEED_BAND_LABELS[index],stats.count,stats.mean(),stats.maximum,boatSpeedByHeel[index].mean(),boatSpeedByHeel[index].maximum)},
            attitudeArtifactFilteredCount=attitudeArtifactFilteredCount,
        )
    }

    private fun percent(value:Long,total:Long)=if(total==0L)0.0 else value*100.0/total
    private fun shortestSigned(value:Double)=((value+540.0)%360.0)-180.0
    private fun windBand(knots:Double)=when{knots<10->0;knots<15->1;knots<20->2;knots<25->3;else->4}
    private fun heelSpeedBand(degrees:Double)=when{degrees<5->0;degrees<10->1;degrees<15->2;degrees<20->3;else->4}

    private companion object{
        const val PAGE_SIZE=1_000
        const val POSITION_USABLE_MILLIS=30_000L
        const val DEPTH_USABLE_MILLIS=60_000L
        const val WIND_USABLE_MILLIS=60_000L
        const val ATTITUDE_USABLE_MILLIS=5_000L
        const val PRESSURE_USABLE_MILLIS=10*60_000L
        const val NAVIGATION_ANALYTICS_FRESH_MILLIS=5_000L
        const val MAX_DISTANCE_SEGMENT_GAP_MILLIS=10_000L
        const val MAX_ATTITUDE_SEGMENT_GAP_MILLIS=2_000L
        const val MAX_CONTINUOUS_SEGMENT_METERS=500.0
        const val MOVING_SOG_KNOTS=.5
        const val HEADING_COG_MIN_SOG=1.5
        const val SUSTAINED_HEEL_DEGREES=25.0
        val WIND_BAND_LABELS=listOf("0–10 kn","10–15 kn","15–20 kn","20–25 kn","25+ kn")
        val HEEL_SPEED_BAND_LABELS=listOf("0–5°","5–10°","10–15°","15–20°","20°+")
        val SOURCE_TIMELINE_EVENTS=setOf("POSITION_SOURCE_CHANGED","HEADING_SOURCE_CHANGED","WIND_SOURCE_CHANGED","PHONE_MOUNT_SUSPECT","NMEA_DATA_GAP","NMEA_DATA_RESTORED")
    }
}
