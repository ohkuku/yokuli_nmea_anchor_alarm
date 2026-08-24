package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.anchorage.*
import org.junit.Assert.*
import org.junit.Test

class AnchorageGeometryTest {
    private val polygon=AnchorageGeometry.Polygon(listOf(listOf(
        AnchorageGeoPoint(-36.9,174.7),AnchorageGeoPoint(-36.9,174.9),
        AnchorageGeoPoint(-36.7,174.9),AnchorageGeoPoint(-36.7,174.7),AnchorageGeoPoint(-36.9,174.7),
    )))

    @Test fun polygonContainsPointAndExcludesOutsidePoint(){
        assertTrue(AnchorageGeometryOps.contains(polygon,AnchorageGeoPoint(-36.8,174.8)))
        assertFalse(AnchorageGeometryOps.contains(polygon,AnchorageGeoPoint(-37.0,174.8)))
        val box=AnchorageGeometryOps.bbox(polygon)
        assertEquals(-36.9,box.minLatitude,0.0);assertEquals(174.9,box.maxLongitude,0.0)
    }

    @Test fun viewportCrossingAntiMeridianSplitsIntoTwoDatabaseWindows(){
        val windows=AnchorageViewport(-45.0,170.0,-30.0,-175.0).queryWindows()
        assertEquals(2,windows.size);assertEquals(180.0,windows[0].east,0.0);assertEquals(-180.0,windows[1].west,0.0)
    }

    @Test fun geoJsonRoundTripsPointPolygonAndMultiPolygon(){
        val values=listOf<AnchorageGeometry>(AnchorageGeometry.Point(AnchorageGeoPoint(-36.8,174.8)),polygon,AnchorageGeometry.MultiPolygon(listOf(polygon)))
        values.forEach{assertEquals(it,AnchorageGeometryCodec.decode(AnchorageGeometryCodec.encode(it)))}
    }

    @Test fun geoJsonRejectsOpenOrInvalidPolygon(){
        val open="""{"type":"Polygon","coordinates":[[[174.7,-36.9],[174.9,-36.9],[174.9,-36.7],[174.7,-36.7]]]}"""
        assertTrue(runCatching{AnchorageGeometryCodec.decode(open)}.isFailure)
        assertTrue(runCatching{AnchorageGeometryCodec.decode("""{"type":"Point","coordinates":[181,0]}""")}.isFailure)
    }
}
