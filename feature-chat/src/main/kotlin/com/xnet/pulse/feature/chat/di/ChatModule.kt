package com.xnet.pulse.feature.chat.di

import android.content.Context
import androidx.room.Room
import com.xnet.pulse.feature.chat.db.ChatDao
import com.xnet.pulse.feature.chat.db.PulseDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
  @Provides @Singleton
  fun provideDatabase(@ApplicationContext ctx: Context): PulseDatabase =
    Room.databaseBuilder(ctx, PulseDatabase::class.java, "pulse.db").build()

  @Provides @Singleton
  fun provideDao(db: PulseDatabase): ChatDao = db.chatDao()
}
