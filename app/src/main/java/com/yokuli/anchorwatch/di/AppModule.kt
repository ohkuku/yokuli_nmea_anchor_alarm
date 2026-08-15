package com.yokuli.anchorwatch.di
import android.content.Context
import androidx.room.Room
import com.yokuli.anchorwatch.data.database.*
import com.yokuli.anchorwatch.data.preferences.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
@Module @InstallIn(SingletonComponent::class)object AppModule{
 @Provides @Singleton fun db(@ApplicationContext c:Context)=Room.databaseBuilder(c,AppDatabase::class.java,"anchor-watch.db").addMigrations(Migration1To2).build()
 @Provides fun dao(db:AppDatabase)=db.anchorDao()
 @Provides @Singleton fun settings(@ApplicationContext c:Context)=SettingsRepository(c)
}
