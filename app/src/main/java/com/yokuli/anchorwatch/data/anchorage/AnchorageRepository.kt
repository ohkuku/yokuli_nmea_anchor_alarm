package com.yokuli.anchorwatch.data.anchorage

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AnchorageDao
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class SeabedType { UNKNOWN, MUD, SAND, MUD_SAND, GRAVEL, ROCK, WEED, SHELL, OTHER }
enum class AnchorageCoordinateSource { CONFIRMED_ANCHOR, ESTIMATED_REGION_CENTRE, TEMPORARY_WATCH_REFERENCE }

data class AnchorageSavePosition(val latitude:Double,val longitude:Double,val source:AnchorageCoordinateSource,val uncertaintyMeters:Double?)

object AnchorageSavePositionPolicy{
    fun resolve(session:AnchorSessionEntity):AnchorageSavePosition{
        if(session.centerStatus=="RESOLVED"&&valid(session.anchorLatitude,session.anchorLongitude))return AnchorageSavePosition(session.anchorLatitude,session.anchorLongitude,AnchorageCoordinateSource.CONFIRMED_ANCHOR,null)
        val uncertainty=(session.provisionalRadiusMeters?:session.expectedSwingRadiusMeters).takeIf{it.isFinite()&&it>0}
        if(valid(session.provisionalAnchorLatitude,session.provisionalAnchorLongitude))return AnchorageSavePosition(session.provisionalAnchorLatitude!!,session.provisionalAnchorLongitude!!,AnchorageCoordinateSource.ESTIMATED_REGION_CENTRE,uncertainty)
        if(valid(session.learningReferenceLatitude,session.learningReferenceLongitude))return AnchorageSavePosition(session.learningReferenceLatitude!!,session.learningReferenceLongitude!!,AnchorageCoordinateSource.TEMPORARY_WATCH_REFERENCE,uncertainty)
        require(valid(session.anchorLatitude,session.anchorLongitude)){"No valid session reference coordinate"}
        return AnchorageSavePosition(session.anchorLatitude,session.anchorLongitude,AnchorageCoordinateSource.TEMPORARY_WATCH_REFERENCE,uncertainty)
    }
    private fun valid(latitude:Double?,longitude:Double?)=latitude!=null&&longitude!=null&&latitude.isFinite()&&longitude.isFinite()&&latitude in -90.0..90.0&&longitude in -180.0..180.0
}

class DuplicateAnchorageException(val existing:SavedAnchorageEntity):IllegalStateException(
    "A saved anchorage already exists within ${AnchorageRepository.DUPLICATE_RADIUS_METERS.toInt()} m",
)

@Singleton
class AnchorageRepository @Inject constructor(private val dao:AnchorageDao){
    private val writeMutex=Mutex()
    val anchorages=dao.anchorages()
    suspend fun get(id:Long)=dao.get(id)
    suspend fun nearby(latitude:Double,longitude:Double,radiusMeters:Double=250.0)=dao.allNow().filter{AnchorGeometry.distanceMeters(latitude,longitude,it.latitude,it.longitude)<=radiusMeters}.sortedBy{AnchorGeometry.distanceMeters(latitude,longitude,it.latitude,it.longitude)}
    suspend fun duplicate(latitude:Double,longitude:Double,radiusMeters:Double=DUPLICATE_RADIUS_METERS,excludeId:Long?=null)=
        nearby(latitude,longitude,radiusMeters).firstOrNull{it.id!=excludeId}
    suspend fun save(value:SavedAnchorageEntity):Long=writeMutex.withLock{
        require(value.latitude in -90.0..90.0&&value.longitude in -180.0..180.0)
        require(value.rating==null||value.rating in 1..5)
        require(value.coordinateSource in AnchorageCoordinateSource.entries.map{it.name})
        require(value.coordinateUncertaintyMeters==null||value.coordinateUncertaintyMeters.isFinite()&&value.coordinateUncertaintyMeters>=0)
        require(value.name.length<=200&&value.notes.length<=20_000&&(value.customSeabedText?.length?:0)<=200)
        duplicate(value.latitude,value.longitude,excludeId=value.id.takeIf{it>0})?.let{throw DuplicateAnchorageException(it)}
        if(value.id==0L)dao.insert(value)else{dao.update(value);value.id}
    }
    suspend fun saveFromSession(session:AnchorSessionEntity,name:String,notes:String=""):Long{
        val position=AnchorageSavePositionPolicy.resolve(session);val now=System.currentTimeMillis()
        return save(SavedAnchorageEntity(name=name.trim().ifBlank{"Saved anchorage"},latitude=position.latitude,longitude=position.longitude,createdAt=now,updatedAt=now,preferredAlarmRadiusMeters=session.alarmRadiusMeters,typicalWaterDepthMeters=session.waterDepthMeters?:session.minObservedDepthMeters,typicalRodeLengthMeters=session.rodeLengthMeters,notes=notes,sourceSessionId=session.id,coordinateSource=position.source.name,coordinateUncertaintyMeters=position.uncertaintyMeters))
    }
    /** Serializes delete with save so a rapid delete → re-save cannot interleave. */
    suspend fun delete(id:Long)=writeMutex.withLock{dao.delete(id)}

    companion object{const val DUPLICATE_RADIUS_METERS=75.0}
}
