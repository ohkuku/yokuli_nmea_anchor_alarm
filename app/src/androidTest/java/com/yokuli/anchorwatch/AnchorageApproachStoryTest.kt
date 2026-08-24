package com.yokuli.anchorwatch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yokuli.anchorwatch.data.anchorage.*
import com.yokuli.anchorwatch.data.database.AnchorSessionEntity
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.SavedAnchorageEntity
import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchorage.AnchorageApproachEngine
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterer
import com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsPolicy
import com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsTarget
import com.yokuli.anchorwatch.domain.anchorage.AnchorageNearbyPolicy
import com.yokuli.anchorwatch.domain.anchorage.ApproachDistanceFormatter
import com.yokuli.anchorwatch.domain.anchorage.ApproachPhase
import com.yokuli.anchorwatch.domain.anchorage.SavedAnchorageReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchorageApproachStoryTest {
    @Test fun onlySavedAnchoragesCreateNearbyApproachAndArrivalGeometry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            repeat(10) { index ->
                database.anchorDao().insertSession(
                    AnchorSessionEntity(
                        startedAt = index.toLong(),
                        endedAt = index.toLong() + 1,
                        active = false,
                        anchorLatitude = -36.8,
                        anchorLongitude = 175.1,
                        rodeLengthMeters = 40.0,
                        waterDepthMeters = 6.0,
                        bowRollerHeightMeters = 1.5,
                        gpsAntennaOffsetMeters = 0.0,
                        expectedSwingRadiusMeters = 38.0,
                        warningRadiusMeters = 45.0,
                        alarmRadiusMeters = 50.0,
                    ),
                )
            }
            val spatial=AnchorageSpatialIndexRepository(database,database.anchorageSpatialDao())
            val search=AnchorageSearchRepository(database,database.anchorageSearchDao())
            val placeRepository=AnchoragePlaceRepository(database,spatial,search)
            val spotRepository=AnchorageSpotRepository(database,spatial,search)
            val saver=AnchorageSaveRepository(database,placeRepository,spotRepository,AnchorageVisitRepository(database),spatial,search)
            val approachRepository = AnchorageApproachRepository(database,placeRepository,spotRepository,saver)
            assertTrue(approachRepository.clusters.first().isEmpty())

            val baseLatitude = -36.8
            val baseLongitude = 175.1
            listOf(0.0, 35.0, 70.0).forEachIndexed { index, eastMeters ->
                val coordinate = AnchorGeometry.project(baseLatitude, baseLongitude, 90.0, eastMeters)
                saver.save(AnchorageSaveRequest(
                    com.yokuli.anchorwatch.domain.anchorage.AnchorageSaveDraft((index+1).toLong(),coordinate.first,coordinate.second,"CONFIRMED_ANCHOR",null,5.8+index*.3,40.0+index*2.0,45.0+index*5.0,"UNKNOWN"),
                    AnchorageSavePlaceInput(displayName="Little Bay ${index + 1}"),
                    AnchorageSaveSpotInput(name="Main spot"),
                ))
            }

            val cluster = approachRepository.clusters.first { it.singleOrNull()?.savedPointCount == 3 }.single()
            assertEquals(3, cluster.savedPointCount)
            assertEquals(1, approachRepository.clusters.first().size)

            val outside = AnchorGeometry.project(cluster.centerLatitude, cluster.centerLongitude, 0.0, cluster.radiusMeters + 1853.0)
            assertEquals(ApproachPhase.IDLE, AnchorageApproachEngine.evaluate(listOf(cluster), null, outside.first, outside.second).phase)

            val nearby = AnchorGeometry.project(cluster.centerLatitude, cluster.centerLongitude, 0.0, cluster.radiusMeters + 1851.0)
            val nearbyState = AnchorageApproachEngine.evaluate(listOf(cluster), null, nearby.first, nearby.second)
            assertEquals(ApproachPhase.NEARBY, nearbyState.phase)
            assertEquals(1, nearbyState.nearbyClusters.size)

            val selected = AnchorageApproachEngine.evaluate(listOf(cluster), cluster.id, nearby.first, nearby.second)
            assertEquals(cluster.id, selected.selectedClusterId)
            assertTrue(requireNotNull(selected.targetBearingTrueDegrees) in 0.0..<360.0)

            val close = AnchorGeometry.project(cluster.centerLatitude, cluster.centerLongitude, 0.0, cluster.radiusMeters + 120.0)
            val closeState = AnchorageApproachEngine.evaluate(listOf(cluster), cluster.id, close.first, close.second)
            assertEquals(ApproachPhase.NEAR, closeState.phase)
            assertEquals("120 m", ApproachDistanceFormatter.format(requireNotNull(closeState.distanceToAreaMeters)))

            val arrived = AnchorGeometry.project(cluster.centerLatitude, cluster.centerLongitude, 0.0, cluster.radiusMeters - 1.0)
            val arrivalState = AnchorageApproachEngine.evaluate(listOf(cluster), cluster.id, arrived.first, arrived.second)
            assertEquals(ApproachPhase.INSIDE_AREA, arrivalState.phase)
            assertEquals(0.0, arrivalState.distanceToAreaMeters ?: -1.0, .01)

            // Arrival may prefill setup measurements, but the reference object has no
            // latitude/longitude fields and therefore cannot authoritatively set this anchor.
            val setupReference = cluster.setupReference()
            assertEquals(cluster.maxAlarmRadiusMeters, setupReference.alarmRadiusMeters)
            assertFalse(AnchorageSetupReference::class.java.declaredFields.any { it.name.contains("latitude", true) || it.name.contains("longitude", true) })
        } finally {
            database.close()
        }
    }

    @Test fun multipleNearbyClustersRequireAnExplicitTargetAndNeverAutoSwitch() {
        fun saved(id: Long, eastMeters: Double): SavedAnchorageReference {
            val coordinate = AnchorGeometry.project(-36.8, 175.1, 90.0, eastMeters)
            return SavedAnchorageReference(id, "Area $id", coordinate.first, coordinate.second, 50.0, 6.0, 40.0, "SAND", null, "", null, id, null)
        }
        val clusters = AnchorageClusterer.cluster(listOf(saved(1, 0.0), saved(2, 600.0)))
        assertEquals(2, clusters.size)
        assertEquals(AnchorageDetailsTarget.AnchorageList(listOf(1L,2L)),AnchorageDetailsPolicy.resolve(clusters))
        val boat = AnchorGeometry.project(-36.8, 175.1, 90.0, 300.0)
        assertEquals(2, AnchorageNearbyPolicy.distances(boat.first, boat.second, clusters).count { it.distanceToAreaMeters <= 1852.0 })

        val locked = clusters.first()
        val laterBoat = AnchorGeometry.project(clusters.last().centerLatitude, clusters.last().centerLongitude, 270.0, 20.0)
        val state = AnchorageApproachEngine.evaluate(clusters, locked.id, laterBoat.first, laterBoat.second)
        assertEquals(locked.id, state.selectedClusterId)
        assertEquals(locked.id, state.target?.id)
    }
}
