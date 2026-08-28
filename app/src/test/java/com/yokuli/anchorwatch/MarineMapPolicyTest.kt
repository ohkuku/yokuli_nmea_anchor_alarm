package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.map.*
import org.junit.Assert.*
import org.junit.Test

class MarineMapPolicyTest{
    @Test fun sailPreviewGesturesDisabled(){
        val settings=MarineMapPolicy.uiSettings(MarineMapContext.SAIL_PREVIEW)
        assertFalse(settings.scrollGesturesEnabled);assertFalse(settings.zoomGesturesEnabled);assertFalse(settings.rotationGesturesEnabled);assertFalse(settings.tiltGesturesEnabled);assertFalse(settings.mapToolbarEnabled)
    }
    @Test fun dedicatedTripAndAnchorageMapsOwnGestures(){
        listOf(MarineMapContext.LIVE_TRIP,MarineMapContext.TRIP_HISTORY,MarineMapContext.ANCHORAGE_DETAIL).forEach{context->
            val settings=MarineMapPolicy.uiSettings(context);assertTrue(settings.scrollGesturesEnabled);assertTrue(settings.zoomGesturesEnabled);assertTrue(MarineMapPolicy.capabilities(context).ruler)
        }
    }
}
