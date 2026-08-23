package com.yokuli.anchorwatch.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN placementMode TEXT NOT NULL DEFAULT 'CENTER_DROP'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN centerStatus TEXT NOT NULL DEFAULT 'RESOLVED'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN centerResolvedAt INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN centerConfidence TEXT NOT NULL DEFAULT 'HIGH'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN centerSampleCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN boatLengthMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN rangeMode TEXT NOT NULL DEFAULT 'BASIC'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN safetyPreset TEXT NOT NULL DEFAULT 'BALANCED'")
        db.execSQL("UPDATE anchor_sessions SET placementMode='BACKDOWN', centerConfidence='MEDIUM', rodeLengthMeters=0 WHERE rodeLengthMeters<0")
    }
}

object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN alarmSnoozedUntil INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN learningReferenceLatitude REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN learningReferenceLongitude REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN provisionalAnchorLatitude REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN provisionalAnchorLongitude REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN provisionalRadiusMeters REAL")
        db.execSQL("UPDATE anchor_sessions SET learningReferenceLatitude=anchorLatitude, learningReferenceLongitude=anchorLongitude WHERE placementMode='BACKDOWN' AND centerStatus='LEARNING'")
    }
}

object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE track_points ADD COLUMN windDirectionTrue REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN windSpeedKnots REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN apparentWindAngle REAL")
    }
}

object Migration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE track_points ADD COLUMN trueWindAngle REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN trueWindSpeedKnots REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN apparentWindSpeedKnots REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN headingMeasured INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE track_points ADD COLUMN headingSampleSequence INTEGER")
        db.execSQL("ALTER TABLE track_points ADD COLUMN windSampleSequence INTEGER")
    }
}

object Migration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN positionSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN anchorPositionMode TEXT NOT NULL DEFAULT 'KNOWN'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN centerSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN usePhoneHeading INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateId INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateCreatedAt INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateDecision TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateNotificationShown INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateRmsErrorMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateAngularCoverageDegrees REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateAngularSectorCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateSwingReversalCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateTemporalFitConsistent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateEffectiveDurationMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateDirectionEvidenceConsistent INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN maxDistanceMeters REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN alarmCount INTEGER NOT NULL DEFAULT 0")

        db.execSQL("ALTER TABLE track_points ADD COLUMN positionSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE track_points ADD COLUMN positionProvider TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE track_points ADD COLUMN horizontalAccuracyMeters REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN fixTrust TEXT NOT NULL DEFAULT 'TRUSTED'")
        db.execSQL("ALTER TABLE track_points ADD COLUMN wasQuarantined INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE track_points ADD COLUMN quarantineReason TEXT")
        db.execSQL("ALTER TABLE track_points ADD COLUMN headingSource TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE track_points ADD COLUMN headingQuality TEXT NOT NULL DEFAULT 'UNAVAILABLE'")
        db.execSQL("ALTER TABLE track_points ADD COLUMN headingEpoch INTEGER")
        // Old rows cannot safely reveal the session source or heading origin.
        db.execSQL("UPDATE track_points SET headingSource='NMEA_PHYSICAL', headingQuality='STABLE' WHERE headingMeasured=1")
    }
}

object Migration6To7 : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `sonar_surveys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `active` INTEGER NOT NULL, `tideMode` TEXT NOT NULL DEFAULT 'OFF', `manualTideOffsetMeters` REAL NOT NULL DEFAULT 0, `transducerDraftMeters` REAL NOT NULL DEFAULT 0, `keelOffsetMeters` REAL NOT NULL DEFAULT 0, `gpsToTransducerMeters` REAL NOT NULL DEFAULT 0, `configuredDepthReference` TEXT NOT NULL DEFAULT 'UNKNOWN', `sampleCount` INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `depth_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `surveyId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `baseGridX` INTEGER NOT NULL, `baseGridY` INTEGER NOT NULL, `sourceElapsedRealtime` INTEGER NOT NULL, `rawDepthMeters` REAL NOT NULL, `measuredDepthMeters` REAL NOT NULL, `normalizedDepthMeters` REAL, `depthReference` TEXT NOT NULL, `sentenceType` TEXT NOT NULL, `nmeaOffsetMeters` REAL, `horizontalAccuracyMeters` REAL, `gpsSource` TEXT NOT NULL, `positionProvider` TEXT NOT NULL, `hdop` REAL, `sogKnots` REAL, `fixTrust` TEXT NOT NULL DEFAULT 'DEGRADED', `positionAgeMillis` INTEGER NOT NULL, `disposition` TEXT NOT NULL DEFAULT 'ACCEPTED', `usable` INTEGER NOT NULL DEFAULT 1, `integrityReason` TEXT, `positionCorrectionApplied` INTEGER NOT NULL DEFAULT 0, `positionCorrectionMethod` TEXT NOT NULL DEFAULT 'NONE', FOREIGN KEY(`surveyId`) REFERENCES `sonar_surveys`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_depth_samples_surveyId` ON `depth_samples` (`surveyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_depth_samples_surveyId_timestamp` ON `depth_samples` (`surveyId`, `timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_depth_samples_baseGridX_baseGridY` ON `depth_samples` (`baseGridX`, `baseGridY`)")
    }
}

/** Derived caches are created empty; raw soundings remain the source of truth. */
object Migration7To8 : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sonar_surveys ADD COLUMN sounderOffsetMeters REAL NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_depth_samples_surveyId_baseGridX_baseGridY` ON `depth_samples` (`surveyId`, `baseGridX`, `baseGridY`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_depth_samples_surveyId_sourceElapsedRealtime` ON `depth_samples` (`surveyId`, `sourceElapsedRealtime`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sonar_grid_cells` (`scopeType` TEXT NOT NULL, `scopeId` INTEGER NOT NULL, `gridX` INTEGER NOT NULL, `gridY` INTEGER NOT NULL, `cellSizeMeters` REAL NOT NULL, `depthMeters` REAL NOT NULL, `uncertaintyMeters` REAL NOT NULL, `sampleCount` INTEGER NOT NULL, `lastUpdatedAt` INTEGER NOT NULL, PRIMARY KEY(`scopeType`, `scopeId`, `gridX`, `gridY`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sonar_grid_cells_scopeType_scopeId` ON `sonar_grid_cells` (`scopeType`, `scopeId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sonar_grid_cells_gridX_gridY` ON `sonar_grid_cells` (`gridX`, `gridY`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `linz_depth_cache` (`cellKey` TEXT NOT NULL, `queriedLatitude` REAL NOT NULL, `queriedLongitude` REAL NOT NULL, `queriedAt` INTEGER NOT NULL, `depthAreaMinMeters` REAL, `depthAreaMaxMeters` REAL, `nearestSoundingDepthMeters` REAL, `nearestSoundingDistanceMeters` REAL, `nearestSoundingLatitude` REAL, `nearestSoundingLongitude` REAL, `nearestContourDepthMeters` REAL, `nearestContourDistanceMeters` REAL, `sourceLayers` TEXT NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`cellKey`))")
    }
}

/** Tide prediction files are a rebuildable cache; raw soundings remain authoritative. */
object Migration8To9 : Migration(8,9){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE sonar_surveys ADD COLUMN tideStationId TEXT")
        db.execSQL("ALTER TABLE sonar_surveys ADD COLUMN tideStationName TEXT")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideHeightMetersApplied REAL")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideCorrectionMode TEXT NOT NULL DEFAULT 'OFF'")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideStationId TEXT")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideStationName TEXT")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tidePredictionYear INTEGER")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideCorrectionMethod TEXT")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideCorrectionStatus TEXT NOT NULL DEFAULT 'NOT_REQUESTED'")
        db.execSQL("CREATE TABLE IF NOT EXISTS tide_prediction_cache (stationId TEXT NOT NULL, year INTEGER NOT NULL, downloadedAt INTEGER NOT NULL, sourceUrl TEXT NOT NULL, csv TEXT NOT NULL, PRIMARY KEY(stationId,year))")
    }
}

/** Adds explicit provenance without changing raw or normalized depth values. */
object Migration9To10 : Migration(9,10){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE sonar_surveys ADD COLUMN tideStationDistanceMeters REAL")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideStationDistanceMeters REAL")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideSource TEXT")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN tideSourceUpdatedAt INTEGER")
    }
}

object Migration10To11 : Migration(10,11){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS `incident_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `elapsedRealtime` INTEGER NOT NULL, `severity` TEXT NOT NULL, `category` TEXT NOT NULL, `event` TEXT NOT NULL, `sessionId` INTEGER, `details` TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_log_timestamp` ON `incident_log` (`timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_log_category` ON `incident_log` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_incident_log_severity` ON `incident_log` (`severity`)")
    }
}

object Migration11To12:Migration(11,12){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN depthGuardEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN shallowDepthAlarmMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN deepDepthAlarmMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windGuardEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windWarningKnots REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windAlarmKnots REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windShiftEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windShiftThresholdDegrees REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windAllowApparentFallback INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windBaselineDirectionDegrees REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windBaselineEstablishedAt INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windBaselineSource TEXT")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN depthAlarmSnoozedUntil INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windAlarmSnoozedUntil INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windShiftAlarmSnoozedUntil INTEGER")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN minObservedDepthMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN maxObservedDepthMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN maxObservedWindKnots REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN maxObservedWindSource TEXT")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN depthAlarmCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN windAlarmCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN savedAnchorageId INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS saved_anchorages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,lastVisitedAt INTEGER,visitCount INTEGER NOT NULL DEFAULT 0,preferredAlarmRadiusMeters REAL,typicalWaterDepthMeters REAL,typicalRodeLengthMeters REAL,seabedType TEXT NOT NULL DEFAULT 'UNKNOWN',customSeabedText TEXT,rating INTEGER,notes TEXT NOT NULL,sourceSessionId INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_anchorages_updatedAt ON saved_anchorages(updatedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_anchorages_lastVisitedAt ON saved_anchorages(lastVisitedAt)")
    }
}

/** Saved places distinguish a confirmed anchor from an estimated/temporary reference. */
object Migration12To13:Migration(12,13){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE saved_anchorages ADD COLUMN coordinateSource TEXT NOT NULL DEFAULT 'CONFIRMED_ANCHOR'")
        db.execSQL("ALTER TABLE saved_anchorages ADD COLUMN coordinateUncertaintyMeters REAL")
    }
}

/** Persists whether a sounding reused the last real DPT/DBT and exactly how old it was. */
object Migration13To14:Migration(13,14){
    override fun migrate(db:SupportSQLiteDatabase){
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateTrackDiameterMeters REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateFittedRadiusMeters REAL")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateMaximumRodeMeters REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateGpsMarginMeters REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateRadialObservable INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN candidateObservabilityReason TEXT NOT NULL DEFAULT 'NO_USABLE_CIRCLE_FIT'")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN depthHeld INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN depthAgeMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE depth_samples ADD COLUMN depthSourceElapsedRealtime INTEGER")
        db.execSQL("UPDATE depth_samples SET depthSourceElapsedRealtime=sourceElapsedRealtime WHERE depthSourceElapsedRealtime IS NULL")
    }
}

/** Adds Trip Watch persistence without changing the Anchor safety tables. */
object Migration14To15:Migration(14,15){
 override fun migrate(db:SupportSQLiteDatabase){
  db.execSQL("CREATE TABLE IF NOT EXISTS `trip_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `active` INTEGER NOT NULL, `paused` INTEGER NOT NULL, `accumulatedPausedMillis` INTEGER NOT NULL, `pausedAt` INTEGER, `boatLengthMeters` REAL, `draftMeters` REAL, `positionPreference` TEXT NOT NULL, `headingPreference` TEXT NOT NULL, `phoneMotionEnabled` INTEGER NOT NULL, `mountCalibrationVersion` INTEGER, `motionAlgorithmVersion` TEXT NOT NULL, `sampleCount` INTEGER NOT NULL, `eventCount` INTEGER NOT NULL, `waypointCount` INTEGER NOT NULL, `droppedSampleCount` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, `movingDurationMillis` INTEGER NOT NULL, `maxSogKnots` REAL, `maxAbsHeelDegrees` REAL, `minDepthMeters` REAL, `minUkcMeters` REAL, `nmeaWasActiveAtStart` INTEGER NOT NULL, `restoredAfterProcessDeath` INTEGER NOT NULL)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_startedAt` ON `trip_sessions` (`startedAt`)");db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_sessions_endedAt` ON `trip_sessions` (`endedAt`)")
  db.execSQL("CREATE TABLE IF NOT EXISTS `trip_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, `positionSource` TEXT NOT NULL, `positionQuality` TEXT NOT NULL, `positionAgeMillis` INTEGER, `sogKnots` REAL, `cogTrueDegrees` REAL, `headingTrueDegrees` REAL, `headingSource` TEXT NOT NULL, `headingAgeMillis` INTEGER, `depthMeters` REAL, `depthSource` TEXT NOT NULL, `depthAgeMillis` INTEGER, `speedThroughWaterKnots` REAL, `stwSource` TEXT, `stwAgeMillis` INTEGER, `trueWindSpeedKnots` REAL, `trueWindDirectionDegrees` REAL, `trueWindAngleDegrees` REAL, `apparentWindSpeedKnots` REAL, `apparentWindAngleDegrees` REAL, `windSource` TEXT, `windAgeMillis` INTEGER, `heelDegrees` REAL, `pitchDegrees` REAL, `rollRateDegPerSec` REAL, `pitchRateDegPerSec` REAL, `yawRateDegPerSec` REAL, `motionScore` REAL, `rollPeriodSeconds` REAL, `rollPeriodConfidence` TEXT, `attitudeAgeMillis` INTEGER, `pressureHpa` REAL, `pressureAgeMillis` INTEGER, `ukcMeters` REAL, `sourceFlags` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trip_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_samples_tripId` ON `trip_samples` (`tripId`)");db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_samples_tripId_timestamp` ON `trip_samples` (`tripId`,`timestamp`)")
  db.execSQL("CREATE TABLE IF NOT EXISTS `trip_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `severity` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `detailJson` TEXT NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trip_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_events_tripId` ON `trip_events` (`tripId`)");db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_events_tripId_timestamp` ON `trip_events` (`tripId`,`timestamp`)")
  db.execSQL("CREATE TABLE IF NOT EXISTS `trip_waypoints` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `name` TEXT NOT NULL, `note` TEXT NOT NULL, `type` TEXT NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trip_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_waypoints_tripId` ON `trip_waypoints` (`tripId`)");db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_waypoints_tripId_timestamp` ON `trip_waypoints` (`tripId`,`timestamp`)")
  db.execSQL("CREATE TABLE IF NOT EXISTS `anchor_telemetry_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `depthMeters` REAL, `depthAgeMillis` INTEGER, `trueWindSpeedKnots` REAL, `trueWindDirectionDegrees` REAL, `windAgeMillis` INTEGER, `heelDegrees` REAL, `pitchDegrees` REAL, `rollRateDegPerSec` REAL, `pitchRateDegPerSec` REAL, `yawRateDegPerSec` REAL, `motionScore` REAL, `rollPeriodSeconds` REAL, `rollPeriodConfidence` TEXT, `pressureHpa` REAL, FOREIGN KEY(`sessionId`) REFERENCES `anchor_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_anchor_telemetry_samples_sessionId` ON `anchor_telemetry_samples` (`sessionId`)");db.execSQL("CREATE INDEX IF NOT EXISTS `index_anchor_telemetry_samples_sessionId_timestamp` ON `anchor_telemetry_samples` (`sessionId`,`timestamp`)")
 }
}

/** Trip Watch V2: immutable waypoint snapshots, custom NMEA samples and durable dashboards. */
object Migration15To16:Migration(15,16){
 override fun migrate(db:SupportSQLiteDatabase){
  listOf("positionSource TEXT","sogKnots REAL","cogTrueDegrees REAL","headingTrueDegrees REAL","speedThroughWaterKnots REAL","depthMeters REAL","trueWindSpeedKnots REAL","trueWindAngleDegrees REAL","apparentWindSpeedKnots REAL","apparentWindAngleDegrees REAL","heelDegrees REAL","pitchDegrees REAL","pressureHpa REAL").forEach{column->db.execSQL("ALTER TABLE trip_waypoints ADD COLUMN $column")}
  db.execSQL("CREATE TABLE IF NOT EXISTS `trip_custom_metric_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `fieldId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `numericValue` REAL, `textValue` TEXT, `unit` TEXT, `sentenceType` TEXT NOT NULL, `fieldAgeMillis` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trip_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_custom_metric_samples_tripId` ON `trip_custom_metric_samples` (`tripId`)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_custom_metric_samples_tripId_timestamp` ON `trip_custom_metric_samples` (`tripId`,`timestamp`)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_custom_metric_samples_tripId_fieldId_timestamp` ON `trip_custom_metric_samples` (`tripId`,`fieldId`,`timestamp`)")
  db.execSQL("CREATE TABLE IF NOT EXISTS `trip_dashboards` (`id` TEXT NOT NULL, `preset` TEXT NOT NULL, `title` TEXT NOT NULL, `layoutJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_dashboards_preset` ON `trip_dashboards` (`preset`)")
 }
}
