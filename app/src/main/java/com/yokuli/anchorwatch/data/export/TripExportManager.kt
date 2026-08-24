package com.yokuli.anchorwatch.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.gson.Gson
import com.yokuli.anchorwatch.BuildConfig
import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.domain.report.AnchorReportEngine
import com.yokuli.anchorwatch.domain.report.TripReportEngine
import com.yokuli.anchorwatch.domain.vessel.VesselMotionAnalyzer
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Local-only exports. Nothing in this class performs a network upload. */
@Singleton
class TripExportManager @Inject constructor(
    @ApplicationContext private val context:Context,
    private val tripDao:TripDao,
    private val anchorDao:AnchorDao,
    private val tripReports:TripReportEngine,
    private val anchorReports:AnchorReportEngine,
){
    suspend fun csv(session:TripSessionEntity)=temp("trip-${session.id}.csv"){file->writeTripSamples(file,session.id)}

    suspend fun gpx(session:TripSessionEntity)=temp("trip-${session.id}.gpx"){file->file.bufferedWriter().use{writer->
        writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.appendLine("<gpx version=\"1.1\" creator=\"Anchor Watch\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        pageTripWaypoints(session.id){waypoint->writer.appendLine("<wpt lat=\"${waypoint.latitude}\" lon=\"${waypoint.longitude}\"><time>${Instant.ofEpochMilli(waypoint.timestamp)}</time><name>${xml(waypoint.name)}</name><desc>${xml(waypoint.note)}</desc><type>${xml(waypoint.type)}</type></wpt>")}
        writer.appendLine("<trk><name>${xml(session.name)}</name><trkseg>")
        forEachTripSample(session.id){s->if(s.latitude!=null&&s.longitude!=null)writer.appendLine("<trkpt lat=\"${s.latitude}\" lon=\"${s.longitude}\"><time>${Instant.ofEpochMilli(s.timestamp)}</time></trkpt>")}
        writer.appendLine("</trkseg></trk></gpx>")
    }}

    suspend fun eventsCsv(session:TripSessionEntity)=temp("trip-${session.id}-events.csv"){writeTripEvents(it,session.id)}
    suspend fun waypointsCsv(session:TripSessionEntity)=temp("trip-${session.id}-waypoints.csv"){writeTripWaypoints(it,session.id)}
    suspend fun customMetricsCsv(session:TripSessionEntity)=temp("trip-${session.id}-custom-nmea.csv"){target->target.bufferedWriter().use{writer->writer.appendLine("timestamp_utc,timestamp_epoch_ms,field_id,display_name,numeric_value,text_value,unit,sentence_type,field_age_ms");tripDao.customMetrics(session.id).forEach{value->writer.row(Instant.ofEpochMilli(value.timestamp),value.timestamp,value.fieldId,value.displayName,value.numericValue,value.textValue,value.unit,value.sentenceType,value.fieldAgeMillis)}}}

    suspend fun kml(session:TripSessionEntity)=temp("trip-${session.id}.kml"){file->writeKml(file,session)}
    suspend fun kmz(session:TripSessionEntity):File{val source=kml(session);return zip("trip-${session.id}.kmz"){file("doc.kml",source)}.also{source.delete()}}

    suspend fun liveSnapshot(snapshot:VesselDataSnapshot,title:String="Trip Watch"):File=temp("trip-watch-live.png"){target->renderSnapshot(target,title,listOf(
        "SOG" to snapshot.sogKnots.value?.let{"%.1f kn".format(it)},"COG" to snapshot.cogTrueDegrees.value?.let{"%03.0f°T".format(it)},"Heading" to snapshot.headingTrueDegrees.value?.let{"%03.0f°T".format(it)},"Boat speed" to snapshot.speedThroughWaterKnots.value?.let{"%.1f kn".format(it)},"Depth" to snapshot.depthMeters.value?.let{"%.1f m".format(it)},"True wind" to snapshot.trueWind.speedKnots.value?.let{"%.1f kn".format(it)},"Apparent wind" to snapshot.apparentWind.speedKnots.value?.let{"%.1f kn".format(it)},"Heel" to snapshot.attitude.value?.heelDegrees?.let{"%+.1f°".format(it)},"Pressure" to snapshot.pressureHpa.value?.let{"%.1f hPa".format(it)}))}

    suspend fun reportSnapshot(session:TripSessionEntity):File{val report=tripReports.generate(session.id);val maxHeel=listOfNotNull(report?.maxPortHeelDegrees?.let{kotlin.math.abs(it)},report?.maxStarboardHeelDegrees?.let{kotlin.math.abs(it)}).maxOrNull();return temp("trip-${session.id}-summary.png"){target->renderSnapshot(target,session.name,listOf("Started" to Instant.ofEpochMilli(session.startedAt).toString(),"Distance" to "%.2f NM".format(session.distanceMeters/1852.0),"Duration" to "%.1f h".format(((session.endedAt?:System.currentTimeMillis())-session.startedAt-session.accumulatedPausedMillis)/3_600_000.0),"Max SOG" to report?.maxSogKnots?.let{"%.1f kn".format(it)},"Max heel" to maxHeel?.let{"%.1f°".format(it)},"Min depth" to report?.minDepthMeters?.let{"%.1f m".format(it)},"Position coverage" to report?.positionCoveragePercent?.let{"%.0f%%".format(it)},"Quality" to report?.quality?.name))}}

    suspend fun aiZip(session:TripSessionEntity):File{
        val date=utcDate(session.startedAt);val report=tripReports.generate(session.id)
        val samples=temp("ai-trip-${session.id}-samples.csv"){writeTripSamples(it,session.id)}
        val events=temp("ai-trip-${session.id}-events.csv"){writeTripEvents(it,session.id)}
        val waypoints=temp("ai-trip-${session.id}-waypoints.csv"){writeTripWaypoints(it,session.id)}
        return zip("AnchorWatch_AI_Trip_$date.zip"){
            text("manifest.json",Gson().toJson(manifest("TRIP",report?.reportEngineVersion?:"TRIP_REPORT_V2",report?.motionAlgorithmVersion)))
            text("README_AI.md",readme("Trip"));text("trip.json",Gson().toJson(session));text("report.json",Gson().toJson(report))
            file("trip_samples.csv",samples);file("trip_events.csv",events);file("trip_waypoints.csv",waypoints)
            text("data_quality.json",Gson().toJson(mapOf("reportQuality" to report?.quality?.name,"positionCoveragePercent" to report?.positionCoveragePercent,"depthCoveragePercent" to report?.depthCoveragePercent,"attitudeCoveragePercent" to report?.attitudeCoveragePercent,"windCoveragePercent" to report?.windCoveragePercent,"trueWindCoveragePercent" to report?.trueWindCoveragePercent,"apparentWindCoveragePercent" to report?.apparentWindCoveragePercent,"droppedSamples" to session.droppedSampleCount)))
        }.also{samples.delete();events.delete();waypoints.delete()}
    }

    suspend fun anchorAiZip(session:AnchorSessionEntity):File{
        val date=utcDate(session.startedAt);val report=anchorReports.generate(session.id)
        val points=temp("ai-anchor-${session.id}-points.csv"){writeAnchorPoints(it,session.id)}
        val events=temp("ai-anchor-${session.id}-events.csv"){writeAnchorEvents(it,session.id)}
        val telemetry=temp("ai-anchor-${session.id}-telemetry.csv"){writeAnchorTelemetry(it,session.id)}
        return zip("AnchorWatch_AI_Anchor_$date.zip"){
            text("manifest.json",Gson().toJson(manifest("ANCHOR",report?.reportEngineVersion?:"ANCHOR_REPORT_V2",VesselMotionAnalyzer.ALGORITHM_VERSION)))
            text("README_AI.md",readme("Anchor"));text("anchor_session.json",Gson().toJson(session));text("report.json",Gson().toJson(report))
            file("anchor_track_points.csv",points);file("anchor_events.csv",events);file("anchor_telemetry_samples.csv",telemetry)
            text("data_quality.json",Gson().toJson(mapOf("reportQuality" to report?.quality?.name,"positionCoveragePercent" to report?.positionCoveragePercent,"gpsGapCount" to report?.gpsGapCount,"centreResolved" to (session.centerStatus=="RESOLVED"))))
        }.also{points.delete();events.delete();telemetry.delete()}
    }

    private suspend fun writeTripSamples(target:File,id:Long)=target.bufferedWriter().use{w->
        w.appendLine("timestamp_utc,timestamp_epoch_ms,latitude,longitude,position_source,position_quality,position_age_ms,sog_knots,sog_age_ms,cog_true_deg,cog_age_ms,heading_true_deg,heading_source,heading_age_ms,depth_m,depth_source,depth_age_ms,stw_knots,stw_source,stw_age_ms,true_wind_knots,true_wind_speed_age_ms,true_wind_direction_deg,true_wind_direction_age_ms,true_wind_angle_deg,true_wind_angle_age_ms,apparent_wind_knots,apparent_wind_speed_age_ms,apparent_wind_angle_deg,apparent_wind_angle_age_ms,wind_source,legacy_wind_age_ms,heel_deg,pitch_deg,roll_rate_deg_s,pitch_rate_deg_s,yaw_rate_deg_s,motion_score,roll_period_s,roll_period_confidence,attitude_age_ms,attitude_quality,attitude_mount_suspect,pressure_hpa,pressure_age_ms,ukc_m,source_flags")
        forEachTripSample(id){s->w.row(Instant.ofEpochMilli(s.timestamp),s.timestamp,s.latitude,s.longitude,s.positionSource,s.positionQuality,s.positionAgeMillis,s.sogKnots,s.sogAgeMillis,s.cogTrueDegrees,s.cogAgeMillis,s.headingTrueDegrees,s.headingSource,s.headingAgeMillis,s.depthMeters,s.depthSource,s.depthAgeMillis,s.speedThroughWaterKnots,s.stwSource,s.stwAgeMillis,s.trueWindSpeedKnots,s.trueWindSpeedAgeMillis,s.trueWindDirectionDegrees,s.trueWindDirectionAgeMillis,s.trueWindAngleDegrees,s.trueWindAngleAgeMillis,s.apparentWindSpeedKnots,s.apparentWindSpeedAgeMillis,s.apparentWindAngleDegrees,s.apparentWindAngleAgeMillis,s.windSource,s.windAgeMillis,s.heelDegrees,s.pitchDegrees,s.rollRateDegPerSec,s.pitchRateDegPerSec,s.yawRateDegPerSec,s.motionScore,s.rollPeriodSeconds,s.rollPeriodConfidence,s.attitudeAgeMillis,s.attitudeQuality,s.attitudeMountSuspect,s.pressureHpa,s.pressureAgeMillis,s.ukcMeters,s.sourceFlags)}
    }
    private suspend fun writeTripEvents(target:File,id:Long)=target.bufferedWriter().use{w->w.appendLine("timestamp_utc,timestamp_epoch_ms,type,severity,latitude,longitude,detail_json");pageTripEvents(id){e->w.row(Instant.ofEpochMilli(e.timestamp),e.timestamp,e.type,e.severity,e.latitude,e.longitude,e.detailJson)}}
    private suspend fun writeTripWaypoints(target:File,id:Long)=target.bufferedWriter().use{w->w.appendLine("timestamp_utc,timestamp_epoch_ms,type,name,note,latitude,longitude,position_source,sog_knots,cog_true_deg,heading_true_deg,stw_knots,depth_m,true_wind_knots,true_wind_angle_deg,apparent_wind_knots,apparent_wind_angle_deg,heel_deg,pitch_deg,pressure_hpa");pageTripWaypoints(id){p->w.row(Instant.ofEpochMilli(p.timestamp),p.timestamp,p.type,p.name,p.note,p.latitude,p.longitude,p.positionSource,p.sogKnots,p.cogTrueDegrees,p.headingTrueDegrees,p.speedThroughWaterKnots,p.depthMeters,p.trueWindSpeedKnots,p.trueWindAngleDegrees,p.apparentWindSpeedKnots,p.apparentWindAngleDegrees,p.heelDegrees,p.pitchDegrees,p.pressureHpa)}}

    private suspend fun writeKml(target:File,session:TripSessionEntity)=target.bufferedWriter().use{writer->
        writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");writer.appendLine("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${xml(session.name)}</name>")
        writer.appendLine("<Placemark><name>${xml(session.name)}</name><LineString><tessellate>1</tessellate><coordinates>")
        forEachTripSample(session.id){sample->if(sample.latitude!=null&&sample.longitude!=null)writer.appendLine("${sample.longitude},${sample.latitude},0")}
        writer.appendLine("</coordinates></LineString></Placemark>")
        pageTripWaypoints(session.id){point->writer.appendLine("<Placemark><name>${xml(point.name)}</name><description>${xml(point.note)}</description><Point><coordinates>${point.longitude},${point.latitude},0</coordinates></Point></Placemark>")}
        pageTripEvents(session.id){event->if(event.latitude!=null&&event.longitude!=null)writer.appendLine("<Placemark><name>${xml(event.type)}</name><description>${xml(event.detailJson)}</description><Point><coordinates>${event.longitude},${event.latitude},0</coordinates></Point></Placemark>")}
        writer.appendLine("</Document></kml>")
    }

    private fun renderSnapshot(target:File,title:String,rows:List<Pair<String,String?>>){
        val bitmap=Bitmap.createBitmap(1080,1350,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(7,31,45))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE};paint.textSize=54f;paint.isFakeBoldText=true;canvas.drawText("Anchor Watch",72f,100f,paint);paint.textSize=42f;paint.color=Color.rgb(107,224,226);canvas.drawText(title.take(34),72f,175f,paint)
        paint.isFakeBoldText=false;var y=285f;rows.forEach{(label,value)->paint.textSize=30f;paint.color=Color.rgb(170,196,207);canvas.drawText(label,72f,y,paint);paint.textSize=54f;paint.color=Color.WHITE;paint.isFakeBoldText=true;canvas.drawText(value?:"—",520f,y,paint);paint.isFakeBoldText=false;y+=105f}
        paint.textSize=28f;paint.color=Color.rgb(170,196,207);canvas.drawText("Generated locally · precise position omitted by default",72f,1260f,paint);paint.textSize=25f;canvas.drawText("Developed aboard SV Yokuli",72f,1305f,paint)
        target.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private suspend fun writeAnchorPoints(target:File,id:Long)=target.bufferedWriter().use{w->w.appendLine("timestamp_utc,timestamp_epoch_ms,latitude,longitude,distance_from_anchor_m,sog_knots,cog_deg,heading_deg,hdop,true_wind_direction_deg,wind_speed_knots,apparent_wind_angle_deg,true_wind_angle_deg,true_wind_speed_knots,apparent_wind_speed_knots,heading_measured,heading_sample_sequence,wind_sample_sequence,position_source,position_provider,horizontal_accuracy_m,fix_trust,was_quarantined,quarantine_reason,heading_source,heading_quality,heading_epoch");pageAnchorPoints(id){p->w.row(Instant.ofEpochMilli(p.timestamp),p.timestamp,p.latitude,p.longitude,p.distanceFromAnchor,p.sog,p.cog,p.heading,p.hdop,p.windDirectionTrue,p.windSpeedKnots,p.apparentWindAngle,p.trueWindAngle,p.trueWindSpeedKnots,p.apparentWindSpeedKnots,p.headingMeasured,p.headingSampleSequence,p.windSampleSequence,p.positionSource,p.positionProvider,p.horizontalAccuracyMeters,p.fixTrust,p.wasQuarantined,p.quarantineReason,p.headingSource,p.headingQuality,p.headingEpoch)}}
    private suspend fun writeAnchorEvents(target:File,id:Long)=target.bufferedWriter().use{w->w.appendLine("timestamp_utc,timestamp_epoch_ms,type,detail");pageAnchorEvents(id){e->w.row(Instant.ofEpochMilli(e.timestamp),e.timestamp,e.type,e.detail)}}
    private suspend fun writeAnchorTelemetry(target:File,id:Long)=target.bufferedWriter().use{w->w.appendLine("timestamp_utc,timestamp_epoch_ms,depth_m,depth_age_ms,true_wind_knots,true_wind_direction_deg,wind_age_ms,heel_deg,pitch_deg,roll_rate_deg_s,pitch_rate_deg_s,yaw_rate_deg_s,motion_score,roll_period_s,roll_period_confidence,pressure_hpa");pageAnchorTelemetry(id){s->w.row(Instant.ofEpochMilli(s.timestamp),s.timestamp,s.depthMeters,s.depthAgeMillis,s.trueWindSpeedKnots,s.trueWindDirectionDegrees,s.windAgeMillis,s.heelDegrees,s.pitchDegrees,s.rollRateDegPerSec,s.pitchRateDegPerSec,s.yawRateDegPerSec,s.motionScore,s.rollPeriodSeconds,s.rollPeriodConfidence,s.pressureHpa)}}

    private suspend fun forEachTripSample(id:Long,action:(TripSampleEntity)->Unit)=page({time,row->tripDao.samplesPage(id,time,row,PAGE)},action)
    private suspend fun pageTripEvents(id:Long,action:(TripEventEntity)->Unit)=page({time,row->tripDao.eventsPage(id,time,row,PAGE)},action)
    private suspend fun pageTripWaypoints(id:Long,action:(TripWaypointEntity)->Unit)=page({time,row->tripDao.waypointsPage(id,time,row,PAGE)},action)
    private suspend fun pageAnchorPoints(id:Long,action:(TrackPointEntity)->Unit)=page({time,row->anchorDao.pointsPage(id,time,row,PAGE)},action)
    private suspend fun pageAnchorEvents(id:Long,action:(AlarmEventEntity)->Unit)=page({time,row->anchorDao.eventsPage(id,time,row,PAGE)},action)
    private suspend fun pageAnchorTelemetry(id:Long,action:(AnchorTelemetrySampleEntity)->Unit)=page({time,row->tripDao.anchorTelemetryPage(id,time,row,PAGE)},action)
    private suspend fun <T:Any> page(fetch:suspend (Long,Long)->List<T>,action:(T)->Unit){
        var timestamp=Long.MIN_VALUE;var rowId=Long.MIN_VALUE
        while(true){val values=fetch(timestamp,rowId);if(values.isEmpty())break;values.forEach(action);val last=values.last();timestamp=rowTimestamp(last);rowId=rowIdentifier(last)}
    }
    private fun rowTimestamp(value:Any)=when(value){is TripSampleEntity->value.timestamp;is TripEventEntity->value.timestamp;is TripWaypointEntity->value.timestamp;is TrackPointEntity->value.timestamp;is AlarmEventEntity->value.timestamp;is AnchorTelemetrySampleEntity->value.timestamp;else->error("Unsupported export row")}
    private fun rowIdentifier(value:Any)=when(value){is TripSampleEntity->value.id;is TripEventEntity->value.id;is TripWaypointEntity->value.id;is TrackPointEntity->value.id;is AlarmEventEntity->value.id;is AnchorTelemetrySampleEntity->value.id;else->error("Unsupported export row")}

    private fun manifest(kind:String,reportVersion:String,motionVersion:String?)=mapOf(
        "format" to "ANCHOR_WATCH_AI_EXPORT","version" to 1,"kind" to kind,"appVersion" to BuildConfig.VERSION_NAME,
        "reportEngineVersion" to reportVersion,"motionAlgorithmVersion" to motionVersion,"createdAtUtc" to Instant.now().toString(),
        "units" to mapOf("distance" to "m","speed" to "kn","angle" to "deg","pressure" to "hPa","acceleration" to "g"),"containsPreciseLocations" to true,
    )
    private fun readme(kind:String)="""# Anchor Watch $kind source data

This local export contains precise positions, timestamps, sensor observations and session notes. Anchor Watch does not upload it automatically.

## Semantics

- `*_samples.csv` and track CSV files are recorded observations; `report.json` contains derived metrics and findings.
- Empty values mean unavailable or not observed. They do not mean zero and must not be invented.
- Age fields are milliseconds since the source observation. Held values retain their original timestamp and are not fresh readings.
- Speeds are knots, distances/depth are metres, angles are degrees, pressure is hPa and acceleration is g.
- Positive heel is starboard-down and positive pitch is bow-up after vessel-mount calibration.
- Depth references and offsets must be checked before comparing with chart datum.
- Motion scores and impact candidates are versioned observations, not a diagnosis.

Suggested prompt: Analyze this Anchor Watch session using the supplied report and source data. Separate direct observations from hypotheses, identify gaps and source changes, and never infer navigation safety from missing data.
"""
    private suspend fun temp(name:String,write:suspend (File)->Unit):File{val value=File(context.cacheDir,name);write(value);return value}
    private fun zip(name:String,content:ZipBuilder.()->Unit):File{val target=File(context.cacheDir,name);ZipOutputStream(target.outputStream().buffered()).use{content(ZipBuilder(it))};return target}
    private class ZipBuilder(private val zip:ZipOutputStream){fun text(name:String,value:String){zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()};fun file(name:String,value:File){zip.putNextEntry(ZipEntry(name));value.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}
    private fun java.io.Writer.row(vararg values:Any?){appendLine(values.joinToString(",",transform=::csv))}
    private fun csv(value:Any?)=when(value){null->"";is Number,is Boolean->value.toString();else->"\"${value.toString().replace("\"","\"\"")}\""}
    private fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun utcDate(epoch:Long)=DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC))
    private companion object{const val PAGE=1_000}
}
