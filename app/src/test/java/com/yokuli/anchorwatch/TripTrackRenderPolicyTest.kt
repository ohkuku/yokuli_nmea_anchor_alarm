package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.trip.*
import org.junit.Assert.*
import org.junit.Test

class TripTrackRenderPolicyTest{
    @Test fun gpsGapCreatesSeparatePolylineSegments(){
        val values=listOf(point(1,0.0,0.0),point(2,0.0,.001),point(3,null,null),point(4,0.0,.002),point(5,0.0,.003))
        val segments=TripTrackRenderPolicy.render(values,100)
        assertEquals(2,segments.size);assertEquals(listOf(2,2),segments.map{it.points.size})
    }

    @Test fun largeTimeOrSpatialDiscontinuityIsNeverBridged(){
        val timeGap=listOf(point(1,0.0,0.0,0),point(2,0.0,.001,TripTrackRenderPolicy.GAP_MILLIS+1))
        val jump=listOf(point(1,0.0,0.0,0),point(2,1.0,1.0,1_000))
        assertEquals(2,TripTrackRenderPolicy.render(timeGap,100).size)
        assertEquals(2,TripTrackRenderPolicy.render(jump,100).size)
    }

    @Test fun twentyFourHourTripUsesBoundedRenderPointCount(){
        val values=(0 until 172_800).map{index->point(index.toLong(),-36.8+index*.000001,174.7+index*.000001,index*500L)}
        val rendered=TripTrackRenderPolicy.render(values,TripTrackRenderPolicy.HISTORY_DETAIL_BUDGET)
        assertTrue(rendered.sumOf{it.points.size}<=TripTrackRenderPolicy.HISTORY_DETAIL_BUDGET)
        assertEquals(values.last().stableKey,rendered.last().points.last().stableKey)
    }

    @Test fun repositoryCompactionPreservesGpsGapWithinItsHardBudget(){
        val first=(0L until 40L).map{sequence->point(sequence,0.0,sequence*.00001)}
        val gap=point(40,null,null)
        val second=(41L until 81L).map{sequence->point(sequence,0.0,sequence*.00001)}

        val compacted=TripTrackRenderPolicy.compact(first+gap+second,10)
        val rendered=TripTrackRenderPolicy.render(compacted,10)

        assertTrue(compacted.size<=10)
        assertEquals(2,rendered.size)
        assertTrue(rendered.all{it.points.isNotEmpty()})
        assertEquals(second.last().stableKey,rendered.last().points.last().stableKey)
    }

    @Test fun liveTailBecomesPersistedWithoutDuplicateGeometry(){
        val persisted=listOf(point(1,0.0,0.0),point(2,0.0,.001))
        val snapshot=TripTrackSnapshot(1,listOf(TripTrackSegment(persisted)),listOf(persisted.last(),point(3,0.0,.002)),true,7)
        val rendered=snapshot.rendered(20).flatMap{it.points}
        assertEquals(listOf("1:1","1:2","1:3"),rendered.map{it.stableKey})
    }

    @Test fun duplicateWallClockTimesStillHaveStableDistinctIdentity(){
        val values=listOf(point(1,0.0,0.0,100),point(2,0.0,.001,100))
        assertEquals(2,TripTrackRenderPolicy.render(values,10).single().points.size)
    }

    private fun point(sequence:Long,latitude:Double?,longitude:Double?,timestamp:Long=sequence*500)=TripTrackPoint(1,sequence,timestamp,latitude,longitude)
}
