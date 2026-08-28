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

/** Independent instrument freshness and continuous anchor-estimator epochs. */
object Migration16To17:Migration(16,17){
 override fun migrate(db:SupportSQLiteDatabase){
  listOf(
   "sogAgeMillis INTEGER","cogAgeMillis INTEGER",
   "trueWindSpeedAgeMillis INTEGER","trueWindDirectionAgeMillis INTEGER","trueWindAngleAgeMillis INTEGER",
   "apparentWindSpeedAgeMillis INTEGER","apparentWindAngleAgeMillis INTEGER",
  ).forEach{column->db.execSQL("ALTER TABLE trip_samples ADD COLUMN $column")}
  db.execSQL("ALTER TABLE trip_samples ADD COLUMN attitudeQuality TEXT NOT NULL DEFAULT 'UNKNOWN'")
  db.execSQL("ALTER TABLE trip_samples ADD COLUMN attitudeMountSuspect INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN estimationEpoch INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN estimationEpochStartedAt INTEGER")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN adoptedCenterEpoch INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN latestEstimateEpoch INTEGER NOT NULL DEFAULT 0")
 }
}

/** Field-level source identity and reference provenance for reports, replay and sonar audits. */
object Migration17To18:Migration(17,18){
 override fun migrate(db:SupportSQLiteDatabase){
  listOf(
   "positionSourceId TEXT","headingSourceId TEXT","headingReference TEXT","stwSourceId TEXT",
   "apparentWindAngleSourceId TEXT","apparentWindSpeedSourceId TEXT",
   "trueWindAngleSourceId TEXT","trueWindSpeedSourceId TEXT","trueWindDirectionSourceId TEXT",
   "trueWindProvenance TEXT","trueWindReference TEXT","depthSourceId TEXT","publicationOwnershipState TEXT",
  ).forEach{column->db.execSQL("ALTER TABLE trip_samples ADD COLUMN $column")}
  listOf(
   "positionSourceId TEXT","headingSourceId TEXT","headingReference TEXT","stwSourceId TEXT",
   "apparentWindAngleSourceId TEXT","apparentWindSpeedSourceId TEXT",
   "trueWindAngleSourceId TEXT","trueWindSpeedSourceId TEXT","trueWindDirectionSourceId TEXT",
   "trueWindProvenance TEXT","trueWindReference TEXT","depthSourceId TEXT",
  ).forEach{column->db.execSQL("ALTER TABLE trip_waypoints ADD COLUMN $column")}
  listOf(
   "apparentWindSpeedKnots REAL","apparentWindAngleDegrees REAL","apparentWindSpeedAgeMillis INTEGER","apparentWindAngleAgeMillis INTEGER",
   "trueWindSpeedAgeMillis INTEGER","trueWindDirectionAgeMillis INTEGER","trueWindAngleDegrees REAL","trueWindAngleAgeMillis INTEGER",
   "trueWindProvenance TEXT","trueWindReference TEXT","headingTrueDegrees REAL","headingSourceId TEXT","headingAgeMillis INTEGER","attitudeQuality TEXT",
  ).forEach{column->db.execSQL("ALTER TABLE anchor_telemetry_samples ADD COLUMN $column")}
  db.execSQL("ALTER TABLE anchor_telemetry_samples ADD COLUMN attitudeMountSuspect INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE depth_samples ADD COLUMN depthSourceId TEXT")
  db.execSQL("ALTER TABLE depth_samples ADD COLUMN positionSourceId TEXT")
  db.execSQL("ALTER TABLE depth_samples ADD COLUMN connectionGeneration INTEGER")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN headingEvidenceEnabled INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN headingEvidenceEpoch INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN headingEvidenceEnabledAt INTEGER")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN headingEvidenceSourceId TEXT")
  db.execSQL("UPDATE anchor_sessions SET headingEvidenceEnabled=usePhoneHeading, headingEvidenceEpoch=CASE WHEN usePhoneHeading=1 THEN 1 ELSE 0 END, headingEvidenceEnabledAt=CASE WHEN usePhoneHeading=1 THEN startedAt ELSE NULL END")
 }
}

/** Restart-safe, source-specific pressure history sampled at one row per UTC minute. */
object Migration18To19:Migration(18,19){
 override fun migrate(db:SupportSQLiteDatabase){
  db.execSQL("CREATE TABLE IF NOT EXISTS pressure_history (sourceStableKey TEXT NOT NULL, bucketUtcMinute INTEGER NOT NULL, sampledAtUtcMillis INTEGER NOT NULL, pressureHpa REAL NOT NULL, sourceDisplayName TEXT NOT NULL, PRIMARY KEY(sourceStableKey,bucketUtcMinute))")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_pressure_history_sampledAtUtcMillis ON pressure_history(sampledAtUtcMillis)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_pressure_history_sourceStableKey_sampledAtUtcMillis ON pressure_history(sourceStableKey,sampledAtUtcMillis)")
 }
}

/** Personal Anchorage GIS. The legacy saved_anchorages table deliberately
 * remains intact for audit/repair during the first GIS release. */
object Migration19To20:Migration(19,20){
 override fun migrate(db:SupportSQLiteDatabase){
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN anchoragePlaceId INTEGER")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN anchorageSpotId INTEGER")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN anchorageVisitId INTEGER")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_regions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,parentRegionId INTEGER,displayName TEXT NOT NULL,officialName TEXT,alternateNamesJson TEXT NOT NULL,provider TEXT NOT NULL,externalId TEXT,featureType TEXT NOT NULL,geometryType TEXT NOT NULL,geometryGeoJson TEXT,centerLatitude REAL NOT NULL,centerLongitude REAL NOT NULL,bboxMinLatitude REAL NOT NULL,bboxMaxLatitude REAL NOT NULL,bboxMinLongitude REAL NOT NULL,bboxMaxLongitude REAL NOT NULL,official INTEGER NOT NULL,userConfirmed INTEGER NOT NULL,custom INTEGER NOT NULL,sourceUpdatedAt INTEGER,lastResolvedAt INTEGER,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(parentRegionId) REFERENCES anchorage_regions(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_regions_parentRegionId ON anchorage_regions(parentRegionId)")
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_anchorage_regions_provider_externalId ON anchorage_regions(provider,externalId)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_regions_featureType ON anchorage_regions(featureType)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_regions_updatedAt ON anchorage_regions(updatedAt)")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_places (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,primaryRegionId INTEGER,displayName TEXT NOT NULL,officialName TEXT,aliasesJson TEXT NOT NULL,placeType TEXT NOT NULL,geometryType TEXT NOT NULL,geometryGeoJson TEXT,centerLatitude REAL NOT NULL,centerLongitude REAL NOT NULL,bboxMinLatitude REAL NOT NULL,bboxMaxLatitude REAL NOT NULL,bboxMinLongitude REAL NOT NULL,bboxMaxLongitude REAL NOT NULL,description TEXT NOT NULL,personalNotes TEXT NOT NULL,verificationStatus TEXT NOT NULL,planningStatus TEXT NOT NULL,favorite INTEGER NOT NULL,archived INTEGER NOT NULL,visitCountCached INTEGER NOT NULL,legacyVisitCount INTEGER NOT NULL,lastVisitedAt INTEGER,legacySavedAnchorageId INTEGER,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(primaryRegionId) REFERENCES anchorage_regions(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
  listOf("primaryRegionId","updatedAt","lastVisitedAt","planningStatus","favorite").forEach{db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_places_$it ON anchorage_places($it)")}
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_anchorage_places_legacySavedAnchorageId ON anchorage_places(legacySavedAnchorageId)")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_place_regions (placeId INTEGER NOT NULL,regionId INTEGER NOT NULL,relationType TEXT NOT NULL,sortOrder INTEGER NOT NULL,PRIMARY KEY(placeId,regionId),FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(regionId) REFERENCES anchorage_regions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_place_regions_regionId ON anchorage_place_regions(regionId)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_place_regions_placeId_sortOrder ON anchorage_place_regions(placeId,sortOrder)")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_spots (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,placeId INTEGER NOT NULL,name TEXT NOT NULL,spotType TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,coordinateSource TEXT NOT NULL,coordinateUncertaintyMeters REAL,preferredAlarmRadiusMeters REAL,typicalWaterDepthMeters REAL,typicalRodeLengthMeters REAL,seabedType TEXT NOT NULL,customSeabedText TEXT,approachNotes TEXT NOT NULL,personalNotes TEXT NOT NULL,verificationStatus TEXT NOT NULL,visitCountCached INTEGER NOT NULL,legacyVisitCount INTEGER NOT NULL,lastVisitedAt INTEGER,legacySavedAnchorageId INTEGER,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_spots_placeId ON anchorage_spots(placeId)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_spots_placeId_lastVisitedAt ON anchorage_spots(placeId,lastVisitedAt)")
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_anchorage_spots_legacySavedAnchorageId ON anchorage_spots(legacySavedAnchorageId)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_spots_updatedAt ON anchorage_spots(updatedAt)")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_visits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,placeId INTEGER NOT NULL,spotId INTEGER,anchorSessionId INTEGER,visitKind TEXT NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER,actualAnchorLatitude REAL,actualAnchorLongitude REAL,coordinateSource TEXT,coordinateUncertaintyMeters REAL,waterDepthMeters REAL,rodeLengthMeters REAL,alarmRadiusMeters REAL,maxExcursionMeters REAL,alarmCount INTEGER NOT NULL,minDepthMeters REAL,maxDepthMeters REAL,maxWindKnots REAL,maxWindSource TEXT,typicalMotionScore REAL,p95MotionScore REAL,p95AbsoluteHeelDegrees REAL,dominantRollPeriodSeconds REAL,impactCount INTEGER,userNotes TEXT NOT NULL,summaryVersion TEXT NOT NULL,createdAt INTEGER NOT NULL,FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(spotId) REFERENCES anchorage_spots(id) ON UPDATE NO ACTION ON DELETE SET NULL,FOREIGN KEY(anchorSessionId) REFERENCES anchor_sessions(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
  listOf("placeId","spotId","startedAt").forEach{db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_visits_$it ON anchorage_visits($it)")}
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_anchorage_visits_anchorSessionId ON anchorage_visits(anchorSessionId)")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_collections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,description TEXT NOT NULL,icon TEXT,sortOrder INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_collections_sortOrder ON anchorage_collections(sortOrder)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_collections_updatedAt ON anchorage_collections(updatedAt)")
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_collection_places (collectionId INTEGER NOT NULL,placeId INTEGER NOT NULL,addedAt INTEGER NOT NULL,PRIMARY KEY(collectionId,placeId),FOREIGN KEY(collectionId) REFERENCES anchorage_collections(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_collection_places_placeId ON anchorage_collection_places(placeId)")

  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_protection_sectors (placeId INTEGER NOT NULL,medium TEXT NOT NULL,sector TEXT NOT NULL,rating TEXT NOT NULL,source TEXT NOT NULL,confidence REAL,notes TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(placeId,medium,sector),FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_facilities (placeId INTEGER NOT NULL,type TEXT NOT NULL,availability TEXT NOT NULL,source TEXT NOT NULL,notes TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(placeId,type),FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_personal_ratings (placeId INTEGER NOT NULL,holding INTEGER,shelter INTEGER,comfort INTEGER,quietness INTEGER,shoreAccess INTEGER,crowding INTEGER,overallPreference TEXT NOT NULL,legacyOverallRating INTEGER,notes TEXT NOT NULL,updatedAt INTEGER NOT NULL,PRIMARY KEY(placeId),FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_photos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,placeId INTEGER NOT NULL,spotId INTEGER,visitId INTEGER,relativeFileName TEXT NOT NULL,thumbnailRelativeFileName TEXT,mimeType TEXT NOT NULL,sha256 TEXT NOT NULL,width INTEGER,height INTEGER,caption TEXT NOT NULL,capturedAt INTEGER,createdAt INTEGER NOT NULL,FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(spotId) REFERENCES anchorage_spots(id) ON UPDATE NO ACTION ON DELETE SET NULL,FOREIGN KEY(visitId) REFERENCES anchorage_visits(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
  listOf("placeId","spotId","visitId").forEach{db.execSQL("CREATE INDEX IF NOT EXISTS index_anchorage_photos_$it ON anchorage_photos($it)")}
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_place_summaries (placeId INTEGER NOT NULL,generatedAt INTEGER NOT NULL,engineVersion TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(placeId),FOREIGN KEY(placeId) REFERENCES anchorage_places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS anchorage_search_fts USING FTS4(placeId INTEGER NOT NULL,placeName TEXT NOT NULL,aliases TEXT NOT NULL,regionPath TEXT NOT NULL,spotNames TEXT NOT NULL,notes TEXT NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_gis_meta (`key` TEXT NOT NULL,longValue INTEGER,textValue TEXT,PRIMARY KEY(`key`))")
  db.execSQL("CREATE TABLE IF NOT EXISTS anchorage_region_packs (regionId INTEGER NOT NULL,downloadedAt INTEGER NOT NULL,providerRevision TEXT,featureCount INTEGER NOT NULL,PRIMARY KEY(regionId),FOREIGN KEY(regionId) REFERENCES anchorage_regions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
  AnchorageSpatialSchema.ensure(db)

  // Deterministic and lossless: one legacy row becomes exactly one Place and
  // one Spot. Nearby legacy points are never silently merged.
  db.execSQL("""INSERT OR IGNORE INTO anchorage_places(id,primaryRegionId,displayName,officialName,aliasesJson,placeType,geometryType,geometryGeoJson,centerLatitude,centerLongitude,bboxMinLatitude,bboxMaxLatitude,bboxMinLongitude,bboxMaxLongitude,description,personalNotes,verificationStatus,planningStatus,favorite,archived,visitCountCached,legacyVisitCount,lastVisitedAt,legacySavedAnchorageId,createdAt,updatedAt) SELECT id,NULL,name,NULL,'[]','UNKNOWN','POINT',NULL,latitude,longitude,latitude,latitude,longitude,longitude,'',notes,CASE WHEN sourceSessionId IS NOT NULL OR visitCount>0 THEN 'VERIFIED_BY_SESSION' ELSE 'PLANNED' END,'NONE',0,0,CASE WHEN visitCount<0 THEN 0 ELSE visitCount END,CASE WHEN visitCount<0 THEN 0 ELSE visitCount END,lastVisitedAt,id,createdAt,updatedAt FROM saved_anchorages""")
  db.execSQL("""INSERT OR IGNORE INTO anchorage_spots(id,placeId,name,spotType,latitude,longitude,coordinateSource,coordinateUncertaintyMeters,preferredAlarmRadiusMeters,typicalWaterDepthMeters,typicalRodeLengthMeters,seabedType,customSeabedText,approachNotes,personalNotes,verificationStatus,visitCountCached,legacyVisitCount,lastVisitedAt,legacySavedAnchorageId,createdAt,updatedAt) SELECT id,id,'Main spot',CASE WHEN coordinateSource='CONFIRMED_ANCHOR' THEN 'ANCHOR_SPOT' ELSE 'PLANNED_REFERENCE' END,latitude,longitude,coordinateSource,coordinateUncertaintyMeters,preferredAlarmRadiusMeters,typicalWaterDepthMeters,typicalRodeLengthMeters,seabedType,customSeabedText,'','',CASE WHEN sourceSessionId IS NOT NULL OR visitCount>0 THEN 'VERIFIED_BY_SESSION' ELSE 'PLANNED' END,CASE WHEN visitCount<0 THEN 0 ELSE visitCount END,CASE WHEN visitCount<0 THEN 0 ELSE visitCount END,lastVisitedAt,id,createdAt,updatedAt FROM saved_anchorages""")
  db.execSQL("""INSERT OR IGNORE INTO anchorage_personal_ratings(placeId,holding,shelter,comfort,quietness,shoreAccess,crowding,overallPreference,legacyOverallRating,notes,updatedAt) SELECT id,NULL,NULL,NULL,NULL,NULL,NULL,'UNKNOWN',rating,'',updatedAt FROM saved_anchorages WHERE rating IS NOT NULL""")
  db.execSQL("""INSERT OR IGNORE INTO anchorage_visits(placeId,spotId,anchorSessionId,visitKind,startedAt,endedAt,actualAnchorLatitude,actualAnchorLongitude,coordinateSource,coordinateUncertaintyMeters,waterDepthMeters,rodeLengthMeters,alarmRadiusMeters,maxExcursionMeters,alarmCount,minDepthMeters,maxDepthMeters,maxWindKnots,maxWindSource,typicalMotionScore,p95MotionScore,p95AbsoluteHeelDegrees,dominantRollPeriodSeconds,impactCount,userNotes,summaryVersion,createdAt) SELECT a.id,a.id,s.id,'SESSION',s.startedAt,s.endedAt,s.anchorLatitude,s.anchorLongitude,a.coordinateSource,a.coordinateUncertaintyMeters,s.waterDepthMeters,s.rodeLengthMeters,s.alarmRadiusMeters,s.maxDistanceMeters,s.alarmCount,s.minObservedDepthMeters,s.maxObservedDepthMeters,s.maxObservedWindKnots,s.maxObservedWindSource,NULL,NULL,NULL,NULL,NULL,'','1',a.updatedAt FROM saved_anchorages a JOIN anchor_sessions s ON s.id=a.sourceSessionId""")
  db.execSQL("UPDATE anchor_sessions SET anchoragePlaceId=savedAnchorageId,anchorageSpotId=savedAnchorageId WHERE savedAnchorageId IN (SELECT id FROM saved_anchorages)")
  db.execSQL("UPDATE anchor_sessions SET anchorageVisitId=(SELECT v.id FROM anchorage_visits v WHERE v.anchorSessionId=anchor_sessions.id LIMIT 1) WHERE id IN (SELECT anchorSessionId FROM anchorage_visits WHERE anchorSessionId IS NOT NULL)")

  db.execSQL("INSERT OR REPLACE INTO anchorage_place_rtree SELECT id,centerLatitude,centerLatitude,centerLongitude,centerLongitude FROM anchorage_places")
  // Conservative migration envelope; repository rewrites this with the exact
  // latitude-aware calculation on the first verifier pass.
  db.execSQL("""INSERT OR REPLACE INTO anchorage_spot_rtree SELECT id,latitude-(MAX(20.0,COALESCE(preferredAlarmRadiusMeters,0),COALESCE(coordinateUncertaintyMeters,0))/111320.0),latitude+(MAX(20.0,COALESCE(preferredAlarmRadiusMeters,0),COALESCE(coordinateUncertaintyMeters,0))/111320.0),longitude-(MAX(20.0,COALESCE(preferredAlarmRadiusMeters,0),COALESCE(coordinateUncertaintyMeters,0))/50000.0),longitude+(MAX(20.0,COALESCE(preferredAlarmRadiusMeters,0),COALESCE(coordinateUncertaintyMeters,0))/50000.0) FROM anchorage_spots""")
  db.execSQL("INSERT INTO anchorage_search_fts(rowid,placeId,placeName,aliases,regionPath,spotNames,notes) SELECT p.id,p.id,p.displayName,p.aliasesJson,'',COALESCE((SELECT group_concat(name,' ') FROM anchorage_spots s WHERE s.placeId=p.id),''),p.personalNotes FROM anchorage_places p")
  val now=System.currentTimeMillis()
  db.execSQL("INSERT OR REPLACE INTO anchorage_gis_meta(`key`,longValue,textValue) SELECT 'LEGACY_ROW_COUNT',COUNT(*),NULL FROM saved_anchorages")
  db.execSQL("INSERT OR REPLACE INTO anchorage_gis_meta(`key`,longValue,textValue) SELECT 'MIGRATED_PLACE_COUNT',COUNT(*),NULL FROM anchorage_places WHERE legacySavedAnchorageId IS NOT NULL")
  db.execSQL("INSERT OR REPLACE INTO anchorage_gis_meta(`key`,longValue,textValue) SELECT 'MIGRATED_SPOT_COUNT',COUNT(*),NULL FROM anchorage_spots WHERE legacySavedAnchorageId IS NOT NULL")
  db.execSQL("INSERT OR REPLACE INTO anchorage_gis_meta(`key`,longValue,textValue) VALUES('MIGRATION_COMPLETED_AT',$now,NULL)")
  db.execSQL("INSERT OR IGNORE INTO anchorage_collections(name,description,icon,sortOrder,createdAt,updatedAt) VALUES('Favorites','','favorite',0,$now,$now),('Want to visit','','planned',1,$now,$now),('Backup','','backup',2,$now,$now)")
 }

}

/** Makes session creation distinct from live position monitoring. */
object Migration20To21:Migration(20,21){
 override fun migrate(db:SupportSQLiteDatabase){
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN anchorOriginMode TEXT NOT NULL DEFAULT 'CURRENT_ACCEPTED_POSITION'")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN monitoringPhase TEXT NOT NULL DEFAULT 'ARMED'")
  db.execSQL("ALTER TABLE anchor_sessions ADD COLUMN monitoringActivatedAt INTEGER")
  db.execSQL("""UPDATE anchor_sessions SET anchorOriginMode=CASE WHEN placementMode='BACKDOWN' THEN 'BACKDOWN_FROM_ACCEPTED_POSITION' WHEN centerSource='MANUAL_COORDINATES' THEN 'MANUAL_COORDINATE' WHEN centerSource='MAP_PICK' THEN 'MAP_PICK' ELSE 'CURRENT_ACCEPTED_POSITION' END""")
  db.execSQL("""UPDATE anchor_sessions SET monitoringPhase=CASE WHEN active=0 OR endedAt IS NOT NULL THEN 'ENDED' WHEN paused=1 THEN 'PAUSED' WHEN placementMode='BACKDOWN' AND centerStatus!='RESOLVED' THEN 'LEARNING' ELSE 'ARMED' END, monitoringActivatedAt=CASE WHEN active=1 AND paused=0 THEN startedAt ELSE NULL END""")
 }
}

/** Stable per-trip identity for reconciling Room batches with the canonical
 * in-memory live tail. Wall-clock timestamps are deliberately not used as an
 * identity because several sensors can legitimately produce duplicate UTC
 * timestamps. */
object Migration21To22:Migration(21,22){
 override fun migrate(db:SupportSQLiteDatabase){
  db.execSQL("ALTER TABLE trip_samples ADD COLUMN recordingSequence INTEGER NOT NULL DEFAULT 0")
  db.execSQL("UPDATE trip_samples SET recordingSequence=id WHERE recordingSequence=0")
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_trip_samples_tripId_recordingSequence ON trip_samples(tripId,recordingSequence)")
 }
}
