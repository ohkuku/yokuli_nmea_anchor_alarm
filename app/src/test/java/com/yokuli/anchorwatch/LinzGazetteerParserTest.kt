package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.data.anchorage.gis.LinzGazetteerParser
import com.yokuli.anchorwatch.data.anchorage.gis.LinzGazetteerProvider
import com.yokuli.anchorwatch.domain.anchorage.AnchorageRegionFeatureType
import org.junit.Assert.*
import org.junit.Test

class LinzGazetteerParserTest {
    @Test fun officialPointKeepsMaoriMacronAndMapsMarineFeature(){
        val json="""{"type":"FeatureCollection","features":[{"id":"layer-51681.42","properties":{"name":"Tīkapa Moana","feat_type":"Gulf"},"geometry":{"type":"Point","coordinates":[175.0,-36.5]}}]}"""
        val value=LinzGazetteerParser.parse(json,"51681",-36.5,175.0).single()
        assertEquals("Tīkapa Moana",value.displayName);assertEquals(AnchorageRegionFeatureType.GULF,value.featureType);assertTrue(value.containsPoint)
    }

    @Test fun polygonContainmentMakesBayCandidateSpecificAndOfficial(){
        val json="""{"type":"FeatureCollection","features":[{"id":"layer-52424.8","properties":{"name":"Smokehouse Bay","feature_type":"Bay"},"geometry":{"type":"Polygon","coordinates":[[[175.30,-36.25],[175.40,-36.25],[175.40,-36.15],[175.30,-36.15],[175.30,-36.25]]]}}]}"""
        val value=LinzGazetteerParser.parse(json,"52424",-36.20,175.35).single()
        assertTrue(value.containsPoint);assertEquals(AnchorageRegionFeatureType.BAY,value.featureType);assertTrue(value.official)
    }

    @Test fun providerNeverQueriesOutsideNewZealandBounds(){
        assertTrue(LinzGazetteerProvider.isNewZealand(-36.8,174.8));assertFalse(LinzGazetteerProvider.isNewZealand(48.8,2.3))
    }
}
