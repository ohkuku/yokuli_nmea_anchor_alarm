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
