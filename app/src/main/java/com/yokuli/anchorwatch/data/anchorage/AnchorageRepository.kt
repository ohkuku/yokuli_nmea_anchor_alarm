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
        require(value.name.length<=200&&value.notes.length<=20_000&&(value.customSeabedText?.length?:0)<=200)
        duplicate(value.latitude,value.longitude,excludeId=value.id.takeIf{it>0})?.let{throw DuplicateAnchorageException(it)}
        if(value.id==0L)dao.insert(value)else{dao.update(value);value.id}
    }
    suspend fun saveFromSession(session:AnchorSessionEntity,name:String,notes:String=""):Long{
        require(session.centerStatus=="RESOLVED")
        val now=System.currentTimeMillis()
        return save(SavedAnchorageEntity(name=name.trim().ifBlank{"Saved anchorage"},latitude=session.anchorLatitude,longitude=session.anchorLongitude,createdAt=now,updatedAt=now,preferredAlarmRadiusMeters=session.alarmRadiusMeters,typicalWaterDepthMeters=session.waterDepthMeters?:session.minObservedDepthMeters,typicalRodeLengthMeters=session.rodeLengthMeters,notes=notes,sourceSessionId=session.id))
    }
    /** Serializes delete with save so a rapid delete → re-save cannot interleave. */
    suspend fun delete(id:Long)=writeMutex.withLock{dao.delete(id)}

    companion object{const val DUPLICATE_RADIUS_METERS=75.0}
}
