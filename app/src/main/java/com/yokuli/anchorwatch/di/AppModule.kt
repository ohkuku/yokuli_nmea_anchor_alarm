package com.yokuli.anchorwatch.di
import android.content.Context
import androidx.room.Room
import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import com.yokuli.anchorwatch.runtime.MonotonicClock
import com.yokuli.anchorwatch.runtime.SystemMonotonicClock
import com.yokuli.anchorwatch.runtime.SystemWallClock
import com.yokuli.anchorwatch.runtime.WallClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
@Module @InstallIn(SingletonComponent::class)object AppModule{
 @Provides @Singleton fun db(@ApplicationContext c:Context)=Room.databaseBuilder(c,AppDatabase::class.java,"anchor-watch.db").addMigrations(Migration1To2,Migration2To3,Migration3To4,Migration4To5,Migration5To6,Migration6To7,Migration7To8,Migration8To9,Migration9To10,Migration10To11,Migration11To12,Migration12To13,Migration13To14,Migration14To15,Migration15To16,Migration16To17).build()
 @Provides fun dao(db:AppDatabase)=db.anchorDao()
 @Provides fun anchorageDao(db:AppDatabase)=db.anchorageDao()
 @Provides fun sonarDao(db:AppDatabase)=db.sonarDao()
 @Provides fun linzDepthCacheDao(db:AppDatabase)=db.linzDepthCacheDao()
 @Provides fun tidePredictionCacheDao(db:AppDatabase)=db.tidePredictionCacheDao()
 @Provides fun incidentLogDao(db:AppDatabase)=db.incidentLogDao()
 @Provides fun tripDao(db:AppDatabase)=db.tripDao()
 @Provides @Singleton fun settings(@ApplicationContext c:Context)=SettingsRepository(c)
 @Provides @Singleton fun monotonicClock():MonotonicClock=SystemMonotonicClock
 @Provides @Singleton fun wallClock():WallClock=SystemWallClock
}
