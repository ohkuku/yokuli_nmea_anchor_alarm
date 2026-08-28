package com.yokuli.anchorwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveMarineLayoutPolicyTest{
    @Test fun supportedDimensionsChooseIntentionalLayouts(){
        assertEquals(AdaptiveMarineLayoutMode.COMPACT_SQUARE,AdaptiveMarineLayoutPolicy.classify(320f,320f))
        assertEquals(AdaptiveMarineLayoutMode.COMPACT_SQUARE,AdaptiveMarineLayoutPolicy.classify(360f,360f))
        assertEquals(AdaptiveMarineLayoutMode.COMPACT_PORTRAIT,AdaptiveMarineLayoutPolicy.classify(360f,640f))
        assertEquals(AdaptiveMarineLayoutMode.REGULAR_PORTRAIT,AdaptiveMarineLayoutPolicy.classify(412f,915f))
        assertEquals(AdaptiveMarineLayoutMode.WIDE,AdaptiveMarineLayoutPolicy.classify(800f,480f))
    }
}
