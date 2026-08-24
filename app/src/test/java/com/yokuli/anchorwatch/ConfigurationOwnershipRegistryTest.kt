package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.domain.config.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationOwnershipRegistryTest {
    @Test fun everyConfigurationHasExactlyOneOwner(){
        assertEquals(ConfigurationKey.entries.size,ConfigurationOwnershipRegistry.entries.size)
        assertEquals(ConfigurationKey.entries.toSet(),ConfigurationOwnershipRegistry.entries.map{it.key}.toSet())
    }

    @Test fun mapVisibilityAndHeadingSourceHaveTheirSingleProductOwners(){
        listOf(ConfigurationKey.BASE_MAP_STYLE,ConfigurationKey.NAUTICAL_SOURCE,ConfigurationKey.LINZ_NZ_OVERLAY,ConfigurationKey.PERSONAL_SONAR_OVERLAY,ConfigurationKey.MAP_DEPTH_READOUTS).forEach{
            assertTrue(ConfigurationOwnershipRegistry.owner(it).ownerRoute.startsWith("Map / Layers"))
        }
        assertEquals("Data / Vessel / Heading",ConfigurationOwnershipRegistry.owner(ConfigurationKey.INSTRUMENT_HEADING_SOURCE).ownerRoute)
        assertEquals("Data / NMEA output",ConfigurationOwnershipRegistry.owner(ConfigurationKey.NMEA_OUTPUT_DESTINATION).ownerRoute)
    }
}
