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

    fun acceptedPosition(fix: NavigationFix, nowElapsed: Long): List<String> {
        if (!fix.valid || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            fix.positionProvider !in setOf(PositionProvider.ANDROID_GNSS, PositionProvider.NMEA, PositionProvider.DEMO) ||
            (fix.isMockLocation && fix.positionProvider != PositionProvider.DEMO)
        ) return emptyList()
        if (nowElapsed - fix.receivedElapsedRealtime !in 0..3_000L) return emptyList()
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
