package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchor.AnchorGeometry
import com.yokuli.anchorwatch.domain.anchorage.AnchorageApproachEngine
import com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterDistance
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterer
import com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsPolicy
import com.yokuli.anchorwatch.domain.anchorage.AnchorageDetailsTarget
import com.yokuli.anchorwatch.domain.anchorage.AnchorageNearbyEpisodeTracker
import com.yokuli.anchorwatch.domain.anchorage.AnchorageNearbyPolicy
import com.yokuli.anchorwatch.domain.anchorage.ApproachDirectionPolicy
import com.yokuli.anchorwatch.domain.anchorage.ApproachDirectionReference
import com.yokuli.anchorwatch.domain.anchorage.ApproachDistanceFormatter
import com.yokuli.anchorwatch.domain.anchorage.ApproachPhase
import com.yokuli.anchorwatch.domain.anchorage.SavedAnchorageReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_LATITUDE = -36.8
private const val TEST_LONGITUDE = 175.1

private fun reference(
    id: Long,
    northMeters: Double = 0.0,
    eastMeters: Double = 0.0,
    radiusMeters: Double? = 50.0,
    depthMeters: Double? = 6.0,
    rodeMeters: Double? = 40.0,
    sourceSessionId: Long? = null,
): SavedAnchorageReference {
    val north = AnchorGeometry.project(TEST_LATITUDE, TEST_LONGITUDE, 0.0, northMeters)
    val coordinate = AnchorGeometry.project(north.first, north.second, 90.0, eastMeters)
    return SavedAnchorageReference(
        id = id,
        name = "Saved $id",
        latitude = coordinate.first,
        longitude = coordinate.second,
        preferredAlarmRadiusMeters = radiusMeters,
        typicalWaterDepthMeters = depthMeters,
        typicalRodeLengthMeters = rodeMeters,
        seabedType = "SAND",
        rating = 4,
        notes = "",
        sourceSessionId = sourceSessionId,
        updatedAt = id,
        lastVisitedAt = null,
    )
}

private fun singleCluster(radiusMeters: Double = 80.0): AnchorageCluster = AnchorageClusterer.cluster(
    listOf(reference(1, radiusMeters = radiusMeters)),
).single()

class AnchorageClustererTest {
    @Test fun singleSavedAnchorageCreatesOneCluster() {
        val cluster = AnchorageClusterer.cluster(listOf(reference(1))).single()
        assertEquals(listOf(1L), cluster.savedAnchorageIds)
        assertEquals(1, cluster.savedPointCount)
    }

    @Test fun threeNearbySavedAnchoragesCreateOneCluster() {
        val clusters = AnchorageClusterer.cluster(listOf(reference(1), reference(2, eastMeters = 35.0), reference(3, eastMeters = 70.0)))
        assertEquals(1, clusters.size)
        assertEquals(3, clusters.single().savedPointCount)
    }

    @Test fun aSingleLinkChainCannotExceedTheMaximumDiameter() {
        val clusters = AnchorageClusterer.cluster(listOf(reference(1), reference(2, eastMeters = 95.0), reference(3, eastMeters = 190.0)))
        assertEquals(2, clusters.size)
        assertEquals(listOf(1, 2), clusters.map { it.savedPointCount }.sorted())
    }

    @Test fun historyAndSourceSessionProvenanceDoNotCreateGeometryPoints() {
        assertTrue(AnchorageClusterer.cluster(emptyList()).isEmpty())
        val cluster = AnchorageClusterer.cluster(listOf(reference(1, sourceSessionId = 999))).single()
        assertEquals(1, cluster.savedPointCount)
        assertEquals(listOf(1L), cluster.savedAnchorageIds)
    }
}

class AnchorageClusterRadiusTest {
    @Test fun clusterRadiusCoversEverySavedCentreAndItsOwnRadius() {
        val points = listOf(reference(1, eastMeters = 0.0, radiusMeters = 45.0), reference(2, eastMeters = 70.0, radiusMeters = 55.0))
        val cluster = AnchorageClusterer.cluster(points).single()
        val expected = points.maxOf {
            AnchorGeometry.distanceMeters(cluster.centerLatitude, cluster.centerLongitude, it.latitude, it.longitude) + requireNotNull(it.preferredAlarmRadiusMeters)
        }
        assertEquals(expected, cluster.radiusMeters, .01)
    }

    @Test fun missingSavedRadiusUsesFortyMetresAndIsMarkedEstimated() {
        val cluster = AnchorageClusterer.cluster(listOf(reference(1, radiusMeters = null))).single()
        assertEquals(40.0, cluster.radiusMeters, .01)
        assertTrue(cluster.radiusEstimated)
    }
}

class AnchorageDetailsPolicyTest {
    @Test fun oneSavedAnchorageOpensItsRealDetailsDirectly() {
        val target = AnchorageDetailsPolicy.resolve(singleCluster())
        assertEquals(AnchorageDetailsTarget.SavedAnchorage(1L), target)
    }

    @Test fun multipleSavedAnchoragesOpenASelectionList() {
        val cluster = AnchorageClusterer.cluster(listOf(reference(1), reference(2, eastMeters = 30.0))).single()
        val target = AnchorageDetailsPolicy.resolve(cluster)
        assertEquals(AnchorageDetailsTarget.AnchorageList(listOf(1L, 2L)), target)
    }

    @Test fun multipleNearbyAreasAlsoOpenOneCombinedSelectionList() {
        val clusters = AnchorageClusterer.cluster(listOf(reference(1), reference(2, eastMeters = 500.0)))
        assertEquals(2, clusters.size)
        assertEquals(
            AnchorageDetailsTarget.AnchorageList(listOf(1L, 2L)),
            AnchorageDetailsPolicy.resolve(clusters),
        )
    }
}

class ApproachSheetPolicyTest {
    @Test fun approachTargetCollapsesTheAnchorWatchSheetToRevealTheGuidance() {
        assertTrue(ApproachSheetPolicy.shouldCollapse("saved:1"))
    }

    @Test fun idleNearbyDiscoveryDoesNotMoveTheAnchorWatchSheet() {
        assertFalse(ApproachSheetPolicy.shouldCollapse(null))
        assertFalse(ApproachSheetPolicy.shouldCollapse(""))
    }
}

class AnchorageNearbyPolicyTest {
    @Test fun nearbyUsesDistanceToTheReferenceAreaBoundary() {
        val cluster = singleCluster(50.0)
        fun distance(areaDistance: Double): AnchorageClusterDistance {
            val boat = AnchorGeometry.project(cluster.centerLatitude, cluster.centerLongitude, 0.0, cluster.radiusMeters + areaDistance)
            return AnchorageNearbyPolicy.distances(boat.first, boat.second, listOf(cluster)).single()
        }
        assertTrue(distance(1852.0).distanceToAreaMeters <= AnchorageNearbyPolicy.TRIGGER_DISTANCE_METERS + .01)
        assertTrue(distance(1853.0).distanceToAreaMeters > AnchorageNearbyPolicy.TRIGGER_DISTANCE_METERS)
    }

    @Test fun dismissDoesNotRepeatUntilTheBoatLeavesBeyondOnePointTwoFiveNm() {
        val cluster = singleCluster()
        fun candidate(areaDistance: Double) = AnchorageClusterDistance(cluster, cluster.radiusMeters + areaDistance, areaDistance)
        val tracker = AnchorageNearbyEpisodeTracker()
        assertEquals(listOf(cluster.id), tracker.update(listOf(candidate(100.0)), true))
        tracker.dismiss(listOf(cluster.id))
        assertTrue(tracker.update(listOf(candidate(100.0)), true).isEmpty())
        assertTrue(tracker.update(listOf(candidate(AnchorageNearbyPolicy.REARM_DISTANCE_METERS)), true).isEmpty())
        tracker.update(listOf(candidate(AnchorageNearbyPolicy.REARM_DISTANCE_METERS + 1.0)), true)
        assertEquals(listOf(cluster.id), tracker.update(listOf(candidate(100.0)), true))
    }

    @Test fun activeWatchSuppressionDoesNotConsumeANewNearbyEpisode() {
        val cluster = singleCluster()
        val candidate = AnchorageClusterDistance(cluster, 100.0, 20.0)
        val tracker = AnchorageNearbyEpisodeTracker()
        assertTrue(tracker.update(listOf(candidate), automaticPromptEnabled = false).isEmpty())
        assertEquals(listOf(cluster.id), tracker.update(listOf(candidate), automaticPromptEnabled = true))
    }
}

class ApproachDirectionPolicyTest {
    private val now = 10_000L

    @Test fun freshHdtWinsOverCogAndPhone() {
        val value = ApproachDirectionPolicy.resolve(now, 90.0, 80.0, 9_000L, 70.0, 5.0, 9_000L, 60.0, true)
        assertEquals(ApproachDirectionReference.HDT, value.reference)
    }

    @Test fun explicitPhoneModeUsesOnlyPhoneDirectionForSideBySideComparison() {
        val value = ApproachDirectionPolicy.resolve(
            nowElapsed = now,
            targetBearingDegrees = 90.0,
            nmeaTrueHeadingDegrees = 80.0,
            nmeaHeadingReceivedElapsed = 9_000L,
            cogTrueDegrees = 70.0,
            sogKnots = 5.0,
            cogReceivedElapsed = 9_000L,
            phoneTrueHeadingDegrees = 60.0,
            phoneHeadingTrusted = true,
            preferredMode = com.yokuli.anchorwatch.domain.anchorage.ApproachHeadingMode.PHONE,
        )
        assertEquals(ApproachDirectionReference.PHONE, value.reference)
        assertEquals(60.0, value.referenceHeadingDegrees!!, .001)
    }

    @Test fun staleHdtFallsBackToFreshCogAboveOneKnot() {
        val value = ApproachDirectionPolicy.resolve(now, 90.0, 80.0, 1_000L, 70.0, 1.1, 9_000L, 60.0, true)
        assertEquals(ApproachDirectionReference.COG, value.reference)
    }

    @Test fun trustedPhoneThenNorthUpAreTheFinalFallbacks() {
        val phone = ApproachDirectionPolicy.resolve(now, 90.0, null, null, null, null, null, 60.0, true)
        assertEquals(ApproachDirectionReference.PHONE, phone.reference)
        val north = ApproachDirectionPolicy.resolve(now, 90.0, null, null, null, null, null, 60.0, false)
        assertEquals(ApproachDirectionReference.NORTH_UP, north.reference)
    }

    @Test fun circularRelativeBearingUsesTheShortestAngle() {
        val value = ApproachDirectionPolicy.resolve(now, 1.0, 359.0, 9_000L, null, null, null, null, false)
        assertEquals(2.0, value.relativeBearingDegrees, .001)
    }
}

class ApproachDistanceTest {
    @Test fun distanceSwitchesFromNmToMetresBelowZeroPointTwoNm() {
        assertEquals("0.2 NM", ApproachDistanceFormatter.format(370.4))
        assertEquals("370 m", ApproachDistanceFormatter.format(369.6))
    }

    @Test fun userCanApproachASelectedTargetOutsideTheAutomaticOneNmRange() {
        val target = singleCluster()
        val boat = AnchorGeometry.project(target.centerLatitude, target.centerLongitude, 180.0, 10.0 * 1852.0)
        val state = AnchorageApproachEngine.evaluate(listOf(target), target.id, boat.first, boat.second)
        assertEquals(target.id, state.selectedClusterId)
        assertEquals(ApproachPhase.APPROACHING, state.phase)
        assertTrue(requireNotNull(state.distanceToAreaMeters) > 9.0 * 1852.0)
    }

    @Test fun dismissingTheOneShotPromptDoesNotHideThePersistentWatchReference() {
        val target = singleCluster(50.0)
        val boat = AnchorGeometry.project(target.centerLatitude, target.centerLongitude, 0.0, 353.0)
        val distance = AnchorageNearbyPolicy.distances(boat.first, boat.second, listOf(target)).single()
        val tracker = AnchorageNearbyEpisodeTracker()

        assertEquals(listOf(target.id), tracker.update(listOf(distance), automaticPromptEnabled = true))
        tracker.dismiss(listOf(target.id))
        assertTrue(tracker.update(listOf(distance), automaticPromptEnabled = true).isEmpty())

        val persistent = AnchorageApproachEngine.evaluate(listOf(target), null, boat.first, boat.second)
        assertEquals(listOf(target.id), persistent.nearbyClusters.map { it.cluster.id })
        assertEquals(303.0, persistent.nearbyClusters.single().distanceToAreaMeters, 0.1)
    }
}

class ApproachArrivalTest {
    @Test fun enteringClusterRadiusStopsTheArrowAtTheAreaBoundary() {
        val target = singleCluster(80.0)
        val boat = AnchorGeometry.project(target.centerLatitude, target.centerLongitude, 0.0, 79.0)
        val state = AnchorageApproachEngine.evaluate(listOf(target), target.id, boat.first, boat.second)
        assertEquals(ApproachPhase.INSIDE_AREA, state.phase)
        assertEquals(0.0, state.distanceToAreaMeters ?: -1.0, .01)
    }

    @Test fun unavailableAcceptedPositionDoesNotInventBearingOrDistance() {
        val target = singleCluster()
        val state = AnchorageApproachEngine.evaluate(listOf(target), target.id, null, null)
        assertFalse(state.positionAvailable)
        assertNull(state.targetBearingTrueDegrees)
        assertNull(state.distanceToAreaMeters)
    }
}
