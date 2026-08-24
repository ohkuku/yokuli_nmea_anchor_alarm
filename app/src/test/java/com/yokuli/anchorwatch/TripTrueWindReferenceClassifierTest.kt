package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.report.TripTrueWindReference
import com.yokuli.anchorwatch.domain.report.TripTrueWindReferenceClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripTrueWindReferenceClassifierTest{
    @Test fun separatesExternalWaterAndGroundCoverage(){
        assertEquals(TripTrueWindReference.EXTERNAL,TripTrueWindReferenceClassifier.from("external NMEA true wind"))
        assertEquals(TripTrueWindReference.EXTERNAL,TripTrueWindReferenceClassifier.from("BOAT_NMEA"))
        assertEquals(TripTrueWindReference.WATER,TripTrueWindReferenceClassifier.from("derived from AWA/AWS + STW/HDT (water reference)"))
        assertEquals(TripTrueWindReference.GROUND,TripTrueWindReferenceClassifier.from("derived from AWA/AWS + SOG/COG/HDT (ground fallback)"))
        assertNull(TripTrueWindReferenceClassifier.from(null))
    }
}
