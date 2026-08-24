package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchorage.*
import org.junit.Assert.*
import org.junit.Test

class AnchorageMapModelsTest{
    private fun place(id:Long,lat:Double,lon:Double)=AnchorageMapPlace(id,lat,lon,"Place $id",false,AnchoragePlanningStatus.NONE,0,1)
    @Test fun lowZoomAggregationIsVisualOnlyAndNeverChangesPlaceIdentity(){
        val values=listOf(place(10,-36.1,175.1),place(11,-36.11,175.11))
        val aggregate=AnchorageVisualClusterer.aggregate(values,6f).single()
        assertTrue(aggregate.key.startsWith("visual:"));assertEquals(listOf(10L,11L),aggregate.placeIds)
        val high=AnchorageVisualClusterer.aggregate(values,13f)
        assertEquals(setOf("place:10","place:11"),high.map{it.key}.toSet());assertTrue(high.all{it.count==1})
    }
}
