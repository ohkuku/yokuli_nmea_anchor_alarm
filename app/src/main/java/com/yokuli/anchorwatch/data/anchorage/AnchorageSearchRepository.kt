package com.yokuli.anchorwatch.data.anchorage

import androidx.room.withTransaction
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.dao.AnchorageSearchDao
import com.yokuli.anchorwatch.data.database.entity.AnchorageSearchFtsEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class AnchorageSearchRepository @Inject constructor(private val database:AppDatabase,private val dao:AnchorageSearchDao){
    suspend fun search(text:String,limit:Int=100)=text.trim().takeIf{it.isNotEmpty()}?.let{dao.search(query(it),limit.coerceIn(1,200))}.orEmpty()
    suspend fun rebuildPlace(placeId:Long)=database.withTransaction{
        val place=database.anchoragePlaceDao().get(placeId)?:return@withTransaction dao.deletePlace(placeId)
        val spots=database.anchorageSpotDao().forPlaceNow(placeId)
        val regionPath=buildRegionPath(place.primaryRegionId).joinToString(" "){it.displayName}
        dao.deletePlace(placeId)
        dao.put(AnchorageSearchFtsEntity(placeId,placeId,place.displayName,place.aliasesJson,regionPath,spots.joinToString(" "){it.name},listOf(place.description,place.personalNotes,spots.joinToString(" "){it.approachNotes+" "+it.personalNotes}).joinToString(" ")))
    }
    suspend fun removePlace(placeId:Long)=dao.deletePlace(placeId)
    suspend fun rebuildAll()=database.withTransaction{dao.clear();database.anchoragePlaceDao().allNow().forEach{rebuildPlace(it.id)}}
    private suspend fun buildRegionPath(id:Long?):List<com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity>{val result=mutableListOf<com.yokuli.anchorwatch.data.database.entity.AnchorageRegionEntity>();var current=id;repeat(32){val region=current?.let{database.anchorageRegionDao().get(it)}?:return result;result+=region;current=region.parentRegionId};return result}
    private fun query(value:String)=value.split(Regex("\\s+")).mapNotNull{token->token.filter{it.isLetterOrDigit()||it in "-_āēīōūĀĒĪŌŪ"}.takeIf(String::isNotBlank)}.joinToString(" AND "){"\"$it\"*"}.ifBlank{"\"$value\""}
}
