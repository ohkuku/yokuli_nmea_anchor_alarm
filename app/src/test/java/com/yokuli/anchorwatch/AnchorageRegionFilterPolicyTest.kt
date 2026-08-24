package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.ui.anchor.anchorages.AnchorageRegionFilterPolicy
import com.yokuli.anchorwatch.ui.anchor.anchorages.UNASSIGNED_REGION_ID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorageRegionFilterPolicyTest{
    @Test fun allRegionsKeepsClassifiedAndUnassignedPlaces(){
        assertTrue(AnchorageRegionFilterPolicy.matches(7L,null))
        assertTrue(AnchorageRegionFilterPolicy.matches(null,null))
    }

    @Test fun unassignedBucketDoesNotHideLegacyOrImportedPlaces(){
        assertTrue(AnchorageRegionFilterPolicy.matches(null,UNASSIGNED_REGION_ID))
        assertFalse(AnchorageRegionFilterPolicy.matches(7L,UNASSIGNED_REGION_ID))
        assertTrue(AnchorageRegionFilterPolicy.matches(7L,7L))
        assertFalse(AnchorageRegionFilterPolicy.matches(null,7L))
    }
}
