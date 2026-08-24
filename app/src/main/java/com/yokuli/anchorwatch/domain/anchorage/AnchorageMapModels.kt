package com.yokuli.anchorwatch.domain.anchorage

data class AnchorageMapPlace(val id:Long,val latitude:Double,val longitude:Double,val label:String,val favorite:Boolean,val planningStatus:AnchoragePlanningStatus,val visitCount:Int,val spotCount:Int)
data class AnchorageRegionAggregate(val key:String,val latitude:Double,val longitude:Double,val count:Int,val placeIds:List<Long>)

object AnchorageVisualClusterer {
    /** Visual-only grid aggregation. IDs are deliberately derived at runtime
     * and never cross the repository boundary or become Place identity. */
    fun aggregate(values:List<AnchorageMapPlace>,zoom:Float):List<AnchorageRegionAggregate>{
        if(values.isEmpty())return emptyList();val cell=when{zoom<5f->4.0;zoom<7f->1.0;zoom<9f->.25;else->.0}
        if(cell==0.0)return values.map{AnchorageRegionAggregate("place:${it.id}",it.latitude,it.longitude,1,listOf(it.id))}
        return values.groupBy{"${kotlin.math.floor(it.latitude/cell).toInt()}:${kotlin.math.floor(it.longitude/cell).toInt()}"}.map{(key,group)->AnchorageRegionAggregate("visual:$key",group.map{it.latitude}.average(),group.map{it.longitude}.average(),group.size,group.map{it.id})}
    }
}
