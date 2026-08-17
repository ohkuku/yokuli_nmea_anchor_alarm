package com.yokuli.anchorwatch.data.anchorage

import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AnchorageDao
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import javax.inject.Inject
import javax.inject.Singleton

enum class SeabedType { UNKNOWN, MUD, SAND, MUD_SAND, GRAVEL, ROCK, WEED, SHELL, OTHER }

@Singleton
class AnchorageRepository @Inject constructor(private val dao:AnchorageDao){
    val anchorages=dao.anchorages()
    suspend fun get(id:Long)=dao.get(id)
    suspend fun nearby(latitude:Double,longitude:Double,radiusMeters:Double=250.0)=dao.allNow().filter{AnchorGeometry.distanceMeters(latitude,longitude,it.latitude,it.longitude)<=radiusMeters}.sortedBy{AnchorGeometry.distanceMeters(latitude,longitude,it.latitude,it.longitude)}
    suspend fun duplicate(latitude:Double,longitude:Double,radiusMeters:Double=75.0)=nearby(latitude,longitude,radiusMeters).firstOrNull()
    suspend fun save(value:SavedAnchorageEntity):Long{
        require(value.latitude in -90.0..90.0&&value.longitude in -180.0..180.0)
        require(value.rating==null||value.rating in 1..5)
        require(value.name.length<=200&&value.notes.length<=20_000&&(value.customSeabedText?.length?:0)<=200)
        return if(value.id==0L)dao.insert(value)else{dao.update(value);value.id}
    }
    suspend fun saveFromSession(session:AnchorSessionEntity,name:String,notes:String=""):Long{
        require(session.centerStatus=="RESOLVED")
        val now=System.currentTimeMillis()
        return save(SavedAnchorageEntity(name=name.trim().ifBlank{"Saved anchorage"},latitude=session.anchorLatitude,longitude=session.anchorLongitude,createdAt=now,updatedAt=now,preferredAlarmRadiusMeters=session.alarmRadiusMeters,typicalWaterDepthMeters=session.waterDepthMeters?:session.minObservedDepthMeters,typicalRodeLengthMeters=session.rodeLengthMeters,notes=notes,sourceSessionId=session.id))
    }
    suspend fun markUsed(id:Long):SavedAnchorageEntity?=dao.get(id)?.let{value->val now=System.currentTimeMillis();value.copy(updatedAt=now,lastVisitedAt=now,visitCount=value.visitCount+1).also{dao.update(it)}}
    suspend fun updateFromVisit(id:Long,session:AnchorSessionEntity):SavedAnchorageEntity?=dao.get(id)?.let{value->
        val now=session.endedAt?:System.currentTimeMillis()
        value.copy(
            updatedAt=now,lastVisitedAt=now,
            preferredAlarmRadiusMeters=session.alarmRadiusMeters,
            typicalWaterDepthMeters=session.waterDepthMeters?:session.minObservedDepthMeters?:value.typicalWaterDepthMeters,
            typicalRodeLengthMeters=session.rodeLengthMeters.takeIf{it>0}?:value.typicalRodeLengthMeters,
        ).also{dao.update(it)}
    }
    suspend fun delete(id:Long)=dao.delete(id)
}
