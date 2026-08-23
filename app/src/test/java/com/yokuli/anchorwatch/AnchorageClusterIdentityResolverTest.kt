package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchorage.AnchorageCluster
import com.yokuli.anchorwatch.domain.anchorage.AnchorageClusterIdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnchorageClusterIdentityResolverTest {
    private fun cluster(id:String,vararg members:Long)=AnchorageCluster(
        id=id,centerLatitude=0.0,centerLongitude=0.0,radiusMeters=40.0,
        savedAnchorageIds=members.toList(),displayName=id,savedPointCount=members.size,
        minDepthMeters=null,maxDepthMeters=null,minRodeMeters=null,maxRodeMeters=null,
        minAlarmRadiusMeters=null,maxAlarmRadiusMeters=null,lastVisitedAt=null,radiusEstimated=true,
    )

    @Test fun exactIdentityWins(){
        assertEquals("old",AnchorageClusterIdentityResolver.resolve("old",setOf(1),listOf(cluster("old",2),cluster("new",1)))?.id)
    }

    @Test fun mergeOrSplitFollowsLargestMemberOverlap(){
        val resolved=AnchorageClusterIdentityResolver.resolve("saved:1-2-3",setOf(1,2,3),listOf(cluster("a",1),cluster("b",2,3),cluster("c",9)))
        assertEquals("b",resolved?.id)
    }

    @Test fun deletionOfEveryMemberCancelsTarget(){
        assertNull(AnchorageClusterIdentityResolver.resolve("gone",setOf(1,2),listOf(cluster("other",3))))
    }
}
