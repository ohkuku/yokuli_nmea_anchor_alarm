package com.yokuli.anchorwatch.domain.config

/** A setting may be edited only on its owner route. Secondary surfaces show
 * status and link to that route, but must not create another mutation path. */
enum class ConfigurationScope { GLOBAL, DEFAULT_FOR_NEW_SESSION, CURRENT_SESSION, RUNTIME_ONLY, CURRENT_DISPLAY }

enum class ConfigurationKey {
    VESSEL_PROFILE,
    PHONE_MOUNT_CALIBRATION,
    DEPTH_SOUNDER_CALIBRATION,
    DEFAULT_ANCHOR_GPS,
    CURRENT_ANCHOR_GPS,
    INSTRUMENT_POSITION_SOURCE,
    CURRENT_TRIP_POSITION_SOURCE,
    INSTRUMENT_HEADING_SOURCE,
    NMEA_INPUT_PROFILE,
    NMEA_OUTPUT_DESTINATION,
    NMEA_PUBLICATION_POLICY,
    BASE_MAP_STYLE,
    NAUTICAL_SOURCE,
    LINZ_NZ_OVERLAY,
    PERSONAL_SONAR_OVERLAY,
    MAP_DEPTH_READOUTS,
    ANCHOR_DEFAULTS,
    CURRENT_ANCHOR_CONDITIONS,
    TRIP_DASHBOARDS,
}

data class ConfigurationOwnership(
    val key:ConfigurationKey,
    val ownerRoute:String,
    val repository:String,
    val scope:ConfigurationScope,
)

object ConfigurationOwnershipRegistry {
    val entries=listOf(
        ConfigurationOwnership(ConfigurationKey.VESSEL_PROFILE,"Settings / Vessel profile","SettingsRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.PHONE_MOUNT_CALIBRATION,"Settings / Phone vessel sensors","VesselMountCalibrationRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.DEPTH_SOUNDER_CALIBRATION,"Settings / Sonar calibration","SettingsRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.DEFAULT_ANCHOR_GPS,"Data / Position source","SettingsRepository",ConfigurationScope.DEFAULT_FOR_NEW_SESSION),
        ConfigurationOwnership(ConfigurationKey.CURRENT_ANCHOR_GPS,"Data / Position source","AnchorWatchRuntime",ConfigurationScope.CURRENT_SESSION),
        ConfigurationOwnership(ConfigurationKey.INSTRUMENT_POSITION_SOURCE,"Data / Vessel / Position","VesselSettingsRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.CURRENT_TRIP_POSITION_SOURCE,"Sail / Start Trip preflight","TripRuntime",ConfigurationScope.CURRENT_SESSION),
        ConfigurationOwnership(ConfigurationKey.INSTRUMENT_HEADING_SOURCE,"Data / Vessel / Heading","VesselSettingsRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.NMEA_INPUT_PROFILE,"Data / NMEA input","SettingsRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.NMEA_OUTPUT_DESTINATION,"Data / NMEA output","OutputSettingsRepository",ConfigurationScope.GLOBAL),
        ConfigurationOwnership(ConfigurationKey.NMEA_PUBLICATION_POLICY,"Data / NMEA output","OutputSettingsRepository",ConfigurationScope.RUNTIME_ONLY),
        ConfigurationOwnership(ConfigurationKey.BASE_MAP_STYLE,"Map / Layers","SettingsRepository",ConfigurationScope.CURRENT_DISPLAY),
        ConfigurationOwnership(ConfigurationKey.NAUTICAL_SOURCE,"Map / Layers","SettingsRepository",ConfigurationScope.CURRENT_DISPLAY),
        ConfigurationOwnership(ConfigurationKey.LINZ_NZ_OVERLAY,"Map / Layers","SettingsRepository",ConfigurationScope.CURRENT_DISPLAY),
        ConfigurationOwnership(ConfigurationKey.PERSONAL_SONAR_OVERLAY,"Map / Layers","SettingsRepository",ConfigurationScope.CURRENT_DISPLAY),
        ConfigurationOwnership(ConfigurationKey.MAP_DEPTH_READOUTS,"Map / Layers","SettingsRepository",ConfigurationScope.CURRENT_DISPLAY),
        ConfigurationOwnership(ConfigurationKey.ANCHOR_DEFAULTS,"Settings / Anchor defaults","SettingsRepository",ConfigurationScope.DEFAULT_FOR_NEW_SESSION),
        ConfigurationOwnership(ConfigurationKey.CURRENT_ANCHOR_CONDITIONS,"Watch / Current anchor","AnchorWatchRuntime",ConfigurationScope.CURRENT_SESSION),
        ConfigurationOwnership(ConfigurationKey.TRIP_DASHBOARDS,"Sail / Dashboard editor","TripDashboardRepository",ConfigurationScope.GLOBAL),
    )

    fun owner(key:ConfigurationKey)=entries.single{it.key==key}
}
