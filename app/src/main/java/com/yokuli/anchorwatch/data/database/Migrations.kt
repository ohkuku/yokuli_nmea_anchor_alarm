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
