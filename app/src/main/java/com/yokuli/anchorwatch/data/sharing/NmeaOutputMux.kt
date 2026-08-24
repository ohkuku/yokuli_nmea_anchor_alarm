package com.yokuli.anchorwatch.data.sharing

import com.yokuli.anchorwatch.data.nmea.NmeaChecksum
import com.yokuli.anchorwatch.domain.model.GpsDataSource
import com.yokuli.anchorwatch.domain.model.NavigationFix
import com.yokuli.anchorwatch.domain.model.PositionProvider
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import com.yokuli.anchorwatch.domain.vessel.VesselAttitude
import com.yokuli.anchorwatch.domain.vessel.VesselMotion
import com.yokuli.anchorwatch.domain.vessel.VesselDataSnapshot
import com.yokuli.anchorwatch.domain.vessel.VesselDataSource
import com.yokuli.anchorwatch.domain.vessel.VesselDataFreshness

@Singleton
class NmeaOutputMux @Inject constructor() {
    private val positionTypes = setOf("RMC", "GGA", "GLL", "VTG")

    fun boatSentence(line: String, selectedSource: GpsDataSource): String? {
        val normalized = line.trim()
        if (!NmeaChecksum.validate(normalized, required = false)) return null
        val type = sentenceType(normalized) ?: return null
        // Position sentences are regenerated only after the unique integrity
        // gate accepts a fix. Forwarding the boat's raw RMC/GGA/GLL/VTG here
        // would let a quarantined spike escape through NMEA Sharing.
        if (type in positionTypes) return null
        val withChecksum = if ('*' in normalized) normalized else NmeaChecksum.append(normalized.removePrefix("$"))
        return "$withChecksum\r\n"
    }

    fun acceptedPosition(fix: NavigationFix, nowElapsed: Long, maxAgeMillis:Long=3_000L): List<String> {
        if (!fix.valid || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            fix.positionProvider !in setOf(PositionProvider.ANDROID_GNSS, PositionProvider.NMEA, PositionProvider.DEMO) ||
            (fix.isMockLocation && fix.positionProvider != PositionProvider.DEMO)
        ) return emptyList()
        if (nowElapsed - fix.receivedElapsedRealtime !in 0..maxAgeMillis.coerceIn(1_000L,30_000L)) return emptyList()
        // A missing accuracy does not become permission to invent one, but the
        // Accepted Position gate has already validated this event. Reject only
        // an explicitly poor accuracy value here.
        if (fix.horizontalAccuracyMeters?.let { it > 30.0 } == true) return emptyList()
        val instant = Instant.ofEpochMilli(fix.timestampUtcMillis ?: System.currentTimeMillis()).atZone(ZoneOffset.UTC)
        val time = instant.format(DateTimeFormatter.ofPattern("HHmmss.SS", Locale.US))
        val date = instant.format(DateTimeFormatter.ofPattern("ddMMyy", Locale.US))
        val (lat, ns) = coordinate(fix.latitude, 2)
        val (lon, ew) = coordinate(fix.longitude, 3)
        val sog = fix.sogKnots?.coerceAtLeast(0.0)
        val cog = fix.cogTrueDegrees?.let { normalizeDegrees(it) }
        val satellites = fix.satellites?.coerceIn(0, 99)?.toString()?.padStart(2, '0').orEmpty()
        // Android does not expose NMEA HDOP consistently. Accuracy / 3 is an
        // explicitly approximate compatibility mapping; unlike satellites or
        // altitude it is never replaced by a made-up constant.
        val hdop = fix.hdop ?: fix.horizontalAccuracyMeters?.div(3.0)?.coerceIn(.5, 9.9)
        val altitude = fix.altitudeMeters?.let { f(it, 1) }.orEmpty()
        return buildList {
            add(sentence("GNRMC,$time,A,$lat,$ns,$lon,$ew,${sog?.let { f(it, 2) }.orEmpty()},${cog?.let { f(it, 2) }.orEmpty()},$date,,,A"))
            add(sentence("GNGGA,$time,$lat,$ns,$lon,$ew,1,$satellites,${hdop?.let{f(it,1)}.orEmpty()},$altitude,M,,M,,"))
            if (sog != null && cog != null) add(sentence("GNVTG,${f(cog, 2)},T,,M,${f(sog, 2)},N,${f(sog * 1.852, 2)},K,A"))
        }
    }

    fun phonePosition(fix:NavigationFix,nowElapsed:Long,maxAgeMillis:Long=3_000L):List<String>{
        val position=acceptedPosition(fix,nowElapsed,maxAgeMillis)
        if(position.isEmpty())return emptyList()
        val instant=Instant.ofEpochMilli(fix.timestampUtcMillis?:System.currentTimeMillis()).atZone(ZoneOffset.UTC)
        val time=instant.format(DateTimeFormatter.ofPattern("HHmmss.SS",Locale.US))
        val date=instant.toLocalDate()
        return position+sentence("GNZDA,$time,${date.dayOfMonth.toString().padStart(2,'0')},${date.monthValue.toString().padStart(2,'0')},${date.year},00,00")
    }

    fun phoneHeading(trueHeadingDegrees:Double):String=sentence("IIHDT,${f(normalizeDegrees(trueHeadingDegrees),2)},T")
    fun phoneMagneticHeading(magneticHeadingDegrees:Double,variationDegrees:Double):String{
        val variation=kotlin.math.abs(variationDegrees);val direction=if(variationDegrees<0.0)"W" else "E"
        return sentence("IIHDG,${f(normalizeDegrees(magneticHeadingDegrees),2)},,,${f(variation,2)},$direction")
    }
    fun derivedTrueWind(speedKnots:Double,directionTrueDegrees:Double,angleDegrees:Double):List<String>{
        val side=if(angleDegrees<0)"L" else "R";val magnitude=abs(angleDegrees);val mwvAngle=normalizeDegrees(angleDegrees)
        return listOf(sentence("WIMWD,${f(normalizeDegrees(directionTrueDegrees),2)},T,,M,${f(speedKnots.coerceAtLeast(0.0),2)},N,,M/S"),sentence("WIMWV,${f(mwvAngle,2)},T,${f(speedKnots.coerceAtLeast(0.0),2)},N,A"),sentence("WIVWT,${f(magnitude,2)},$side,${f(speedKnots.coerceAtLeast(0.0),2)},N,,,"))
    }
    /** Fixed-heartbeat, source-agnostic feed for chart plotters and clients.
     * Every value comes from VesselDataHub's selected canonical observation;
     * candidates that lost arbitration are never re-published here. */
    fun canonicalFeed(snapshot:VesselDataSnapshot,nowElapsed:Long,wallUtcMillis:Long=System.currentTimeMillis()):List<String>{
        fun fresh(received:Long?,freshness:VesselDataFreshness)=received!=null&&freshness==VesselDataFreshness.FRESH&&nowElapsed-received in 0L..30_000L
        val result=mutableListOf<String>()
        snapshot.position.value?.takeIf{fresh(snapshot.position.receivedElapsedRealtime,snapshot.position.freshness)}?.let{position->
            val provider=when(snapshot.position.source){VesselDataSource.PHONE_GNSS->PositionProvider.ANDROID_GNSS;VesselDataSource.DEMO->PositionProvider.DEMO;else->PositionProvider.NMEA}
            result+=acceptedPosition(NavigationFix(position.latitude,position.longitude,snapshot.position.observedAtUtcMillis?:wallUtcMillis,snapshot.position.receivedElapsedRealtime?:nowElapsed,sogKnots=snapshot.sogKnots.value,cogTrueDegrees=snapshot.cogTrueDegrees.value,hdop=position.hdop,satellites=position.satellites,altitudeMeters=position.altitudeMeters,horizontalAccuracyMeters=position.horizontalAccuracyMeters,positionProvider=provider,sourceSentence="CANONICAL",valid=true),nowElapsed,30_000L)
        }
        snapshot.headingTrueDegrees.value?.takeIf{fresh(snapshot.headingTrueDegrees.receivedElapsedRealtime,snapshot.headingTrueDegrees.freshness)}?.let{result+=phoneHeading(it)}
        snapshot.speedThroughWaterKnots.value?.takeIf{fresh(snapshot.speedThroughWaterKnots.receivedElapsedRealtime,snapshot.speedThroughWaterKnots.freshness)}?.let{stw->
            val heading=snapshot.headingTrueDegrees.value?.takeIf{fresh(snapshot.headingTrueDegrees.receivedElapsedRealtime,snapshot.headingTrueDegrees.freshness)}
            result+=sentence("IIVHW,${heading?.let{f(it,2)}.orEmpty()},T,,M,${f(stw,2)},N,${f(stw*1.852,2)},K")
        }
        val aws=snapshot.apparentWind.speedKnots;val awa=snapshot.apparentWind.angleDegrees
        if(aws.value!=null&&awa.value!=null&&fresh(aws.receivedElapsedRealtime,aws.freshness)&&fresh(awa.receivedElapsedRealtime,awa.freshness))result+=sentence("WIMWV,${f(normalizeDegrees(awa.value!!),2)},R,${f(aws.value!!,2)},N,A")
        val tws=snapshot.trueWind.speedKnots;val twd=snapshot.trueWind.directionDegrees;val twa=snapshot.trueWind.angleDegrees
        if(tws.value!=null&&twd.value!=null&&twa.value!=null&&fresh(tws.receivedElapsedRealtime,tws.freshness)&&fresh(twd.receivedElapsedRealtime,twd.freshness)&&fresh(twa.receivedElapsedRealtime,twa.freshness))result+=derivedTrueWind(tws.value!!,twd.value!!,twa.value!!)
        snapshot.depthMeters.value?.takeIf{fresh(snapshot.depthMeters.receivedElapsedRealtime,snapshot.depthMeters.freshness)}?.let{result+=sentence("SDDBT,,f,${f(it,2)},M,,F")}
        snapshot.rateOfTurnDegreesPerMinute.value?.takeIf{fresh(snapshot.rateOfTurnDegreesPerMinute.receivedElapsedRealtime,snapshot.rateOfTurnDegreesPerMinute.freshness)}?.let{result+=phoneRateOfTurn(it)}
        snapshot.pressureHpa.value?.takeIf{fresh(snapshot.pressureHpa.receivedElapsedRealtime,snapshot.pressureHpa.freshness)}?.let{phoneXdr(null,it)?.let(result::add)}
        return result.distinct()
    }
    fun diagnostic():String=sentence("PYOK,TEST,ANCHOR_WATCH,1")
    fun diagnosticMagneticHeading(headingDegrees:Double=123.4):String=sentence("IIHDG,${f(normalizeDegrees(headingDegrees),2)},,,,")
    fun phoneRateOfTurn(degreesPerMinute:Double):String=sentence("IIROT,${f(degreesPerMinute.coerceIn(-720.0,720.0),2)},A")
    fun phoneXdr(attitude:VesselAttitude?,pressureHpa:Double?):String?{
        val groups=buildList{
            attitude?.let{add("A,${f(it.heelDegrees,2)},D,PHONE_HEEL");add("A,${f(it.pitchDegrees,2)},D,PHONE_PITCH")}
            pressureHpa?.takeIf{it in 800.0..1_200.0}?.let{add("P,${f(it/1_000.0,5)},B,PHONE_BARO")}
        }
        return groups.takeIf{it.isNotEmpty()}?.let{sentence("IIXDR,${it.joinToString(",")}")}
    }
    fun phoneProprietary(attitude:VesselAttitude?,motion:VesselMotion?,headingDegrees:Double?,pressureHpa:Double?):String?{
        if(attitude==null&&motion==null&&headingDegrees==null&&pressureHpa==null)return null
        return sentence("PYOK,SENS,${headingDegrees?.let{f(normalizeDegrees(it),2)}.orEmpty()},${attitude?.heelDegrees?.let{f(it,2)}.orEmpty()},${attitude?.pitchDegrees?.let{f(it,2)}.orEmpty()},${attitude?.yawRateDegreesPerSecond?.let{f(it,2)}.orEmpty()},${motion?.score?.let{f(it,1)}.orEmpty()},${pressureHpa?.let{f(it,2)}.orEmpty()}")
    }

    fun sentenceType(line: String): String? = line.trim().removePrefix("$").substringBefore('*').substringBefore(',')
        .takeIf { it.length >= 3 }?.takeLast(3)?.uppercase(Locale.US)

    private fun sentence(body: String) = NmeaChecksum.append(body) + "\r\n"
    private fun coordinate(value: Double, degreeDigits: Int): Pair<String, String> {
        val positive = abs(value)
        val degrees = positive.toInt()
        val minutes = (positive - degrees) * 60.0
        val text = String.format(Locale.US, "%0${degreeDigits}d%08.5f", degrees, minutes)
        val hemisphere = if (degreeDigits == 2) if (value >= 0) "N" else "S" else if (value >= 0) "E" else "W"
        return text to hemisphere
    }

    private fun f(value: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", value)
    private fun normalizeDegrees(value: Double) = (value % 360.0 + 360.0) % 360.0
}
