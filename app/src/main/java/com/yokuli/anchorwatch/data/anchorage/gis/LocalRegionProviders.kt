package com.yokuli.anchorwatch.data.anchorage.gis

import com.yokuli.anchorwatch.data.database.dao.AnchorageRegionDao
import com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity
import com.yokuli.anchorwatch.domain.anchorage.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

@Singleton class CachedRegionProvider @Inject constructor(private val dao:AnchorageRegionDao):AnchorageRegionProvider{
    override val providerId="CACHE"
    override suspend fun resolveCandidates(latitude:Double,longitude:Double,radiusMeters:Double):Result<List<AnchorageRegionCandidate>> = runCatching{
        val (south,west,north,east)=bounds(latitude,longitude,radiusMeters)
        dao.inBounds(south,west,north,east).filterNot{it.custom}.mapNotNull{it.candidate(latitude,longitude)}
    }
}

@Singleton class UserRegionProvider @Inject constructor(private val dao:AnchorageRegionDao):AnchorageRegionProvider{
    override val providerId="USER"
    override suspend fun resolveCandidates(latitude:Double,longitude:Double,radiusMeters:Double):Result<List<AnchorageRegionCandidate>> = runCatching{
        dao.customRegions().mapNotNull{it.candidate(latitude,longitude)}.filter{it.containsPoint||it.distanceMeters<=radiusMeters}
    }
}

internal fun AnchorageRegionEntity.candidate(latitude:Double,longitude:Double):AnchorageRegionCandidate?{
    val geometry=geometryGeoJson?.let{runCatching{AnchorageGeometryCodec.decode(it)}.getOrNull()}
    val point=AnchorageGeoPoint(latitude,longitude);val center=AnchorageGeoPoint(centerLatitude,centerLongitude)
    return AnchorageRegionCandidate(provider,externalId,displayName,officialName,runCatching{AnchorageRegionFeatureType.valueOf(featureType)}.getOrDefault(AnchorageRegionFeatureType.UNKNOWN),geometry,centerLatitude,centerLongitude,geometry?.let{AnchorageGeometryOps.contains(it,point)}?:false,AnchorageGeometryOps.distance(point,center),official,sourceUpdatedAt=sourceUpdatedAt)
}

internal fun bounds(latitude:Double,longitude:Double,radiusMeters:Double):List<Double>{val lat=radiusMeters/111_320.0;val lon=radiusMeters/(111_320.0*cos(Math.toRadians(latitude)).coerceAtLeast(.15));return listOf(latitude-lat,longitude-lon,latitude+lat,longitude+lon)}
