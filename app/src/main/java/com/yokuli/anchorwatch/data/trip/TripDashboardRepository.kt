package com.yokuli.anchorwatch.data.trip

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yokuli.anchorwatch.data.database.TripDao
import com.yokuli.anchorwatch.data.database.TripDashboardEntity
import com.yokuli.anchorwatch.domain.vessel.InstrumentTileId
import com.yokuli.anchorwatch.domain.vessel.TripInstrumentPreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class InstrumentTileSize { SMALL, MEDIUM, WIDE, LARGE, HERO }
data class DashboardTileBinding(
    val tileId:InstrumentTileId?=null,
    val nmeaFieldId:String?=null,
    val size:InstrumentTileSize=InstrumentTileSize.MEDIUM,
    val label:String?=null,
    val unitOverride:String?=null,
    val scale:Double=1.0,
    val offset:Double=0.0,
    val recordInTrips:Boolean=false,
){
    fun transformed(value:Double?)=value?.let{it*scale+offset}
}
data class TripDashboard(val id:String,val preset:TripInstrumentPreset,val title:String,val tiles:List<DashboardTileBinding>)

object TripCustomMetricRecordingPolicy{
    fun bindings(dashboards:List<TripDashboard>):Map<String,DashboardTileBinding> = dashboards
        .flatMap{it.tiles}
        .filter{it.recordInTrips&&!it.nmeaFieldId.isNullOrBlank()}
        .associateBy{requireNotNull(it.nmeaFieldId)}
}

@Singleton
class TripDashboardRepository @Inject constructor(private val dao:TripDao){
    private val gson=Gson();private val type=object:TypeToken<List<DashboardTileBinding>>(){}.type
    val dashboards:Flow<List<TripDashboardEntity>> = dao.dashboards()
    val decoded:Flow<List<TripDashboard>> = dashboards.map{values->values.mapNotNull(::decode)}
    suspend fun save(value:TripDashboard){
        require(value.id.isNotBlank()&&value.id.length<=80)
        require(value.title.length<=120&&value.tiles.size<=24)
        require(value.tiles.all{(it.tileId==null) xor it.nmeaFieldId.isNullOrBlank()})
        require(value.tiles.all{it.scale.isFinite()&&it.offset.isFinite()&&(it.label?.length?:0)<=120&&(it.unitOverride?.length?:0)<=40})
        val sortValue=dao.dashboard(value.id)?.updatedAt?:System.currentTimeMillis()
        dao.upsertDashboard(TripDashboardEntity(value.id,value.preset.name,value.title.trim(),gson.toJson(value.tiles),sortValue))
    }
    suspend fun create(title:String):TripDashboard{
        val value=TripDashboard("custom-${UUID.randomUUID()}",TripInstrumentPreset.CUSTOM,title.trim().take(120).ifBlank{"Custom"},emptyList())
        save(value);return value
    }
    suspend fun delete(id:String)=dao.deleteDashboard(id)
    suspend fun reorder(ids:List<String>){
        val base=System.currentTimeMillis()-ids.size
        ids.distinct().forEachIndexed{index,id->dao.updateDashboardSort(id,base+index)}
    }
    fun decode(value:TripDashboardEntity):TripDashboard?=runCatching{TripDashboard(value.id,TripInstrumentPreset.valueOf(value.preset),value.title,gson.fromJson<List<DashboardTileBinding>>(value.layoutJson,type).take(24))}.getOrNull()
}
