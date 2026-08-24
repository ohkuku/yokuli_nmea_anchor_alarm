package com.yokuli.anchorwatch.map

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.asin

data class MapScaleBar(
    val distanceMeters: Double,
    val widthPixels: Float,
    val label: String,
)

/** Pure Web-Mercator scale and marine-distance formatting shared by the map and tests. */
object MapDistanceTools {
    private const val EQUATOR_METERS_PER_PIXEL = 156_543.03392
    private const val METERS_PER_NAUTICAL_MILE = 1_852.0

    fun metersPerPixel(latitude: Double, zoom: Float): Double {
        val safeLatitude = latitude.coerceIn(-85.0, 85.0)
        return EQUATOR_METERS_PER_PIXEL * cos(Math.toRadians(safeLatitude)) / 2.0.pow(zoom.toDouble())
    }

    fun scaleBar(latitude: Double, zoom: Float, targetWidthPixels: Float): MapScaleBar {
        val maximumMeters = metersPerPixel(latitude, zoom) * targetWidthPixels.coerceAtLeast(1f)
        val candidates = buildList {
            listOf(1.0, 2.0, 5.0).forEach { multiplier ->
                for (power in -1..5) add(multiplier * 10.0.pow(power))
            }
        }.sorted()
        val distance = candidates.lastOrNull { it <= maximumMeters } ?: candidates.first()
        return MapScaleBar(
            distanceMeters = distance,
            widthPixels = (distance / metersPerPixel(latitude, zoom)).toFloat().coerceAtLeast(1f),
            label = scaleLabel(distance),
        )
    }

    fun distanceMeters(firstLatitude: Double, firstLongitude: Double, secondLatitude: Double, secondLongitude: Double): Double =
        AnchorGeometry.distanceMeters(firstLatitude, firstLongitude, secondLatitude, secondLongitude)

    fun initialBearingDegrees(firstLatitude:Double,firstLongitude:Double,secondLatitude:Double,secondLongitude:Double):Double{
        val first=Math.toRadians(firstLatitude);val second=Math.toRadians(secondLatitude);val longitudeDelta=Math.toRadians(secondLongitude-firstLongitude)
        val east=sin(longitudeDelta)*cos(second)
        val north=cos(first)*sin(second)-sin(first)*cos(second)*cos(longitudeDelta)
        return (Math.toDegrees(atan2(east,north))+360.0)%360.0
    }

    fun midpoint(firstLatitude:Double,firstLongitude:Double,secondLatitude:Double,secondLongitude:Double)=
        ((firstLatitude+secondLatitude)/2.0) to ((firstLongitude+secondLongitude)/2.0)

    /** Move the whole ruler around a new midpoint while retaining its geodesic
     * length and initial true bearing. */
    fun translateRuler(firstLatitude:Double,firstLongitude:Double,secondLatitude:Double,secondLongitude:Double,newMidLatitude:Double,newMidLongitude:Double):Pair<Pair<Double,Double>,Pair<Double,Double>>{
        val distance=distanceMeters(firstLatitude,firstLongitude,secondLatitude,secondLongitude)
        val bearing=initialBearingDegrees(firstLatitude,firstLongitude,secondLatitude,secondLongitude)
        val translatedStart=destination(newMidLatitude,newMidLongitude,(bearing+180.0)%360.0,distance/2.0)
        return translatedStart to destination(translatedStart.first,translatedStart.second,bearing,distance)
    }

    private fun destination(latitude:Double,longitude:Double,bearingDegrees:Double,distanceMeters:Double):Pair<Double,Double>{
        val angularDistance=distanceMeters/6_371_000.0;val bearing=Math.toRadians(bearingDegrees);val startLatitude=Math.toRadians(latitude);val startLongitude=Math.toRadians(longitude)
        val latitudeResult=asin(sin(startLatitude)*cos(angularDistance)+cos(startLatitude)*sin(angularDistance)*cos(bearing))
        val longitudeResult=startLongitude+atan2(sin(bearing)*sin(angularDistance)*cos(startLatitude),cos(angularDistance)-sin(startLatitude)*sin(latitudeResult))
        return Math.toDegrees(latitudeResult) to (((Math.toDegrees(longitudeResult)+540.0)%360.0)-180.0)
    }

    fun measurementLabel(distanceMeters: Double): String = when {
        !distanceMeters.isFinite() || distanceMeters < 0.0 -> "—"
        distanceMeters < 1_000.0 -> "${distanceMeters.coerceAtLeast(0.0).toInt()} m"
        else -> "${decimal(distanceMeters / METERS_PER_NAUTICAL_MILE, 2)} NM · ${decimal(distanceMeters / 1_000.0, 2)} km"
    }

    private fun scaleLabel(distanceMeters: Double): String = when {
        distanceMeters < 1_000.0 -> "${distanceMeters.toInt()} m"
        else -> "${decimal(distanceMeters / METERS_PER_NAUTICAL_MILE, if (distanceMeters < METERS_PER_NAUTICAL_MILE) 2 else 1)} NM"
    }

    private fun decimal(value: Double, places: Int): String {
        val factor = 10.0.pow(places)
        val rounded = kotlin.math.round(value * factor) / factor
        return if (abs(rounded - floor(rounded)) < 1e-9) rounded.toInt().toString()
        else "%.${places}f".format(java.util.Locale.US, rounded).trimEnd('0').trimEnd('.')
    }
}
