package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.linz.LinzFeatureTypeSchemaParser
import com.yokuli.anchorwatch.data.linz.LinzWfsRequestBuilder
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinzWfsSchemaTest {
    @Test fun describeFeatureTypeSelectsPerLayerGeometryInsteadOfAssumingShape() {
        val schema = """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:linz="https://data.linz.govt.nz">
              <xsd:element name="layer-50858" type="linz:soundingType"/>
              <xsd:element name="layer-50671" type="linz:areaType"/>
              <xsd:complexType name="soundingType"><xsd:sequence>
                <xsd:element name="geometry" type="gml:PointPropertyType"/>
              </xsd:sequence></xsd:complexType>
              <xsd:complexType name="areaType"><xsd:sequence>
                <xsd:element name="shape" type="gml:MultiSurfacePropertyType"/>
              </xsd:sequence></xsd:complexType>
            </xsd:schema>
        """.trimIndent()

        assertEquals(
            mapOf("50858" to "geometry", "50671" to "shape"),
            LinzFeatureTypeSchemaParser.geometryProperties(schema, listOf("50858", "50671")),
        )
    }

    @Test fun getFeatureUsesAdvertisedGeometryAndWfsTwoAxisOrder() {
        val url = LinzWfsRequestBuilder.features(
            serviceRoot = "https://example.invalid/wfs",
            layerIds = listOf("50858", "50866"),
            geometryProperty = "geometry",
            latitude = -36.8485,
            longitude = 174.7633,
            radiusMeters = 100.0,
        )
        val decoded = URLDecoder.decode(url.query, "UTF-8")
        assertTrue(decoded.contains("typeNames=layer-50858,layer-50866"))
        assertTrue(decoded.contains("bbox(geometry,-36."))
        assertTrue(decoded.contains(",174."))
        assertTrue(decoded.contains("'EPSG:4326'"))
        assertFalse(decoded.contains("bbox(shape"))
    }

    @Test fun requestBuilderNeverNeedsOrLogsAnApiKey() {
        val url = LinzWfsRequestBuilder.describe("https://example.invalid/wfs", listOf("50858"))
        assertEquals("example.invalid", url.host)
        assertFalse(url.toString().contains("key="))
        assertTrue(URLDecoder.decode(url.query, "UTF-8").contains("request=DescribeFeatureType"))
    }
}
