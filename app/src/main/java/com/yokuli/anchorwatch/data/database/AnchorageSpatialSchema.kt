package com.yokuli.anchorwatch.data.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Installs the optional spatial acceleration tables for both a fresh install
 * and an upgrade. Some vendor SQLite builds omit the RTREE extension, so an
 * indexed bbox table is the mandatory, query-compatible fallback. */
object AnchorageSpatialSchema {
    fun ensure(db:SupportSQLiteDatabase){
        create(db,"anchorage_place_rtree")
        create(db,"anchorage_spot_rtree")
    }
    private fun create(db:SupportSQLiteDatabase,name:String){
        runCatching{db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $name USING rtree(id,minLat,maxLat,minLon,maxLon)")}.getOrElse{
            db.execSQL("CREATE TABLE IF NOT EXISTS $name (id INTEGER NOT NULL PRIMARY KEY,minLat REAL NOT NULL,maxLat REAL NOT NULL,minLon REAL NOT NULL,maxLon REAL NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_${name}_latitude ON $name(maxLat,minLat)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_${name}_longitude ON $name(maxLon,minLon)")
        }
    }
}

object AnchorageDatabaseCallback:RoomDatabase.Callback(){
    override fun onCreate(db:SupportSQLiteDatabase)=AnchorageSpatialSchema.ensure(db)
    override fun onOpen(db:SupportSQLiteDatabase)=AnchorageSpatialSchema.ensure(db)
}
