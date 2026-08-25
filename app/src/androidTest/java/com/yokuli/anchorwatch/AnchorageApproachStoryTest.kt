package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchorage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageApproachStoryTest {
    @Test fun savedSpotsRemainSeparateStableApproachTargets() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try{
            repeat(3){index->database.anchorDao().insertSession(AnchorSessionEntity(startedAt=index+1L,endedAt=index+2L,active=false,anchorLatitude=-36.8,anchorLongitude=175.1,rodeLengthMeters=40.0,waterDepthMeters=6.0,bowRollerHeightMeters=1.5,gpsAntennaOffsetMeters=0.0,expectedSwingRadiusMeters=38.0,warningRadiusMeters=45.0,alarmRadiusMeters=50.0))}
            val spatial=AnchorageSpatialIndexRepository(database,database.anchorageSpatialDao());val search=AnchorageSearchRepository(database,database.anchorageSearchDao())
            val places=AnchoragePlaceRepository(database,spatial,search);val spots=AnchorageSpotRepository(database,spatial,search)
            val saver=AnchorageSaveRepository(database,places,spots,AnchorageVisitRepository(database),spatial,search)
            val repository=AnchorageApproachRepository(database,places,spots,saver)
            assertTrue(repository.targets.first().isEmpty())
            listOf(0.0,35.0,70.0).forEachIndexed{index,east->
                val point=AnchorGeometry.project(-36.8,175.1,90.0,east)
                saver.save(AnchorageSaveRequest(AnchorageSaveDraft((index+1).toLong(),point.first,point.second,"CONFIRMED_ANCHOR",null,5.8+index*.3,40.0+index*2,45.0+index*5,"UNKNOWN"),AnchorageSavePlaceInput(displayName="Little Bay ${index+1}"),AnchorageSaveSpotInput()))
            }
            val targets=repository.targets.first{it.size==3}
            assertEquals(3,targets.map{it.placeId}.distinct().size)
            assertEquals(3,targets.map{it.spotId}.distinct().size)
            val target=targets[1]
            val near=AnchorGeometry.project(target.latitude,target.longitude,0.0,target.areaRadiusMeters+120.0)
            val guidance=AnchorageSpotApproachEngine.evaluate(targets,target.spotId,near.first,near.second)
            assertEquals(target.spotId,guidance.target?.spotId);assertEquals(ApproachPhase.NEAR,guidance.phase);assertEquals("120 m",ApproachDistanceFormatter.format(requireNotNull(guidance.distanceToAreaMeters)))
            val arrived=AnchorGeometry.project(target.latitude,target.longitude,0.0,(target.areaRadiusMeters-1).coerceAtLeast(0.0))
            assertEquals(ApproachPhase.INSIDE_AREA,AnchorageSpotApproachEngine.evaluate(targets,target.spotId,arrived.first,arrived.second).phase)
            val reference=target.setupReference();assertEquals(target.placeId,reference.placeId);assertEquals(target.spotId,reference.spotId)
            assertFalse(AnchorageSetupReference::class.java.declaredFields.any{it.name.contains("latitude",true)||it.name.contains("longitude",true)})
        }finally{database.close()}
    }

    @Test fun explicitSpotTargetNeverAutoSwitchesToACloserSpot(){
        fun target(place:Long,spot:Long,east:Double):AnchorageSpotApproachTarget{
            val point=AnchorGeometry.project(-36.8,175.1,90.0,east)
            return AnchorageSpotApproachTarget(place,spot,"Place $place","Spot $spot",point.first,point.second,40.0,false,50.0,6.0,40.0)
        }
        val selected=target(1,11,0.0);val other=target(2,22,600.0)
        val boat=AnchorGeometry.project(other.latitude,other.longitude,270.0,20.0)
        val state=AnchorageSpotApproachEngine.evaluate(listOf(selected,other),selected.spotId,boat.first,boat.second)
        assertEquals(selected.placeId,state.target?.placeId);assertEquals(selected.spotId,state.target?.spotId)
    }
}
