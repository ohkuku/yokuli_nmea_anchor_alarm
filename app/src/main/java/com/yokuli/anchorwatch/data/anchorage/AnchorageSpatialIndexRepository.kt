package com.yokuli.anchorwatch.data.anchorage

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.dao.AnchorageSpatialDao
import com.yokuli.anchorwatch.data.database.entity.AnchoragePlaceEntity
import com.yokuli.anchorwatch.data.database.entity.AnchorageSpotEntity
import com.yokuli.anchorwatch.domain.anchorage.AnchorageGeoPoint
import com.yokuli.anchorwatch.domain.anchorage.AnchorageGeometryOps
import com.yokuli.anchorwatch.domain.anchorage.AnchorageViewport
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max

data class AnchorageSpatialIndexHealth(val places:Long,val indexedPlaces:Long,val spots:Long,val indexedSpots:Long,val rebuilt:Boolean,val backend:String)

@Singleton
class AnchorageSpatialIndexRepository @Inject constructor(
    private val database:AppDatabase,
    private val spatialDao:AnchorageSpatialDao,
){
    suspend fun viewport(viewport:AnchorageViewport):List<AnchoragePlaceEntity> = viewport.queryWindows().flatMap{window->
        spatialDao.places(SimpleSQLiteQuery("""SELECT p.* FROM anchorage_places p JOIN anchorage_place_rtree r ON p.id=r.id WHERE r.maxLat>=? AND r.minLat<=? AND r.maxLon>=? AND r.minLon<=? AND p.archived=0 ORDER BY p.favorite DESC,COALESCE(p.lastVisitedAt,p.updatedAt) DESC""",arrayOf(window.south,window.north,window.west,window.east)))
    }.distinctBy{it.id}

    suspend fun spotsInViewport(viewport:AnchorageViewport):List<AnchorageSpotEntity> = viewport.queryWindows().flatMap{window->
        spatialDao.spots(SimpleSQLiteQuery("""SELECT s.* FROM anchorage_spots s JOIN anchorage_spot_rtree r ON s.id=r.id JOIN anchorage_places p ON p.id=s.placeId WHERE r.maxLat>=? AND r.minLat<=? AND r.maxLon>=? AND r.minLon<=? AND p.archived=0 ORDER BY COALESCE(s.lastVisitedAt,s.updatedAt) DESC""",arrayOf(window.south,window.north,window.west,window.east)))
    }.distinctBy{it.id}

    suspend fun nearbySpots(latitude:Double,longitude:Double,radiusMeters:Double):List<Pair<AnchorageSpotEntity,Double>>{
        require(latitude in -90.0..90.0&&longitude in -180.0..180.0&&radiusMeters>=0)
        val latDelta=radiusMeters/111_320.0
        val lonDelta=radiusMeters/(111_320.0*cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        val viewport=AnchorageViewport((latitude-latDelta).coerceAtLeast(-90.0),normalize(longitude-lonDelta),(latitude+latDelta).coerceAtMost(90.0),normalize(longitude+lonDelta))
        val origin=AnchorageGeoPoint(latitude,longitude)
        return spotsInViewport(viewport).map{it to AnchorageGeometryOps.distance(origin,AnchorageGeoPoint(it.latitude,it.longitude))}.filter{it.second<=radiusMeters+maxEnvelope(it.first)}.sortedBy{it.second}
    }

    suspend fun upsertPlace(value:AnchoragePlaceEntity)=database.withTransaction{
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO anchorage_place_rtree(id,minLat,maxLat,minLon,maxLon) VALUES(?,?,?,?,?)",arrayOf<Any?>(value.id,value.bboxMinLatitude,value.bboxMaxLatitude,value.bboxMinLongitude,value.bboxMaxLongitude))
    }
    suspend fun upsertSpot(value:AnchorageSpotEntity)=database.withTransaction{
        val radius=maxEnvelope(value);val box=AnchorageGeometryOps.envelope(AnchorageGeoPoint(value.latitude,value.longitude),radius)
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO anchorage_spot_rtree(id,minLat,maxLat,minLon,maxLon) VALUES(?,?,?,?,?)",arrayOf<Any?>(value.id,box.minLatitude,box.maxLatitude,box.minLongitude,box.maxLongitude))
    }
    suspend fun deletePlace(id:Long)=database.withTransaction{database.openHelper.writableDatabase.execSQL("DELETE FROM anchorage_place_rtree WHERE id=?",arrayOf(id))}
    suspend fun deleteSpot(id:Long)=database.withTransaction{database.openHelper.writableDatabase.execSQL("DELETE FROM anchorage_spot_rtree WHERE id=?",arrayOf(id))}

    suspend fun verifyAndRepair():AnchorageSpatialIndexHealth=database.withTransaction{
        val db=database.openHelper.writableDatabase
        fun count(table:String)=db.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getLong(0)}
        val places=count("anchorage_places");val indexedPlaces=count("anchorage_place_rtree");val spots=count("anchorage_spots");val indexedSpots=count("anchorage_spot_rtree")
        val repair=places!=indexedPlaces||spots!=indexedSpots
        if(repair){
            db.execSQL("DELETE FROM anchorage_place_rtree");db.execSQL("DELETE FROM anchorage_spot_rtree")
            database.anchoragePlaceDao().allNow().forEach{value->db.execSQL("INSERT OR REPLACE INTO anchorage_place_rtree(id,minLat,maxLat,minLon,maxLon) VALUES(?,?,?,?,?)",arrayOf<Any?>(value.id,value.bboxMinLatitude,value.bboxMaxLatitude,value.bboxMinLongitude,value.bboxMaxLongitude))}
            database.anchorageSpotDao().allNow().forEach{value->val box=AnchorageGeometryOps.envelope(AnchorageGeoPoint(value.latitude,value.longitude),maxEnvelope(value));db.execSQL("INSERT OR REPLACE INTO anchorage_spot_rtree(id,minLat,maxLat,minLon,maxLon) VALUES(?,?,?,?,?)",arrayOf<Any?>(value.id,box.minLatitude,box.maxLatitude,box.minLongitude,box.maxLongitude))}
        }
        AnchorageSpatialIndexHealth(places,if(repair)places else indexedPlaces,spots,if(repair)spots else indexedSpots,repair,backend())
    }

    private fun backend():String=database.openHelper.writableDatabase.query("SELECT sql FROM sqlite_master WHERE name='anchorage_place_rtree'").use{if(it.moveToFirst()&&it.getString(0).contains("VIRTUAL TABLE",true))"RTREE" else "INDEXED_BBOX"}
    private fun maxEnvelope(value:AnchorageSpotEntity)=max(20.0,max(value.preferredAlarmRadiusMeters.valid(),value.coordinateUncertaintyMeters.valid()))
    private fun Double?.valid()=this?.takeIf{it.isFinite()&&it>=0}?:0.0
    private fun normalize(value:Double):Double=((value+540)%360)-180
}
