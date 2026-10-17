package com.lookfirst.di

import android.content.Context
import com.lookfirst.data.reputation.AndroidDirectorySync
import com.lookfirst.data.reputation.DefaultListUpdateControl
import com.lookfirst.data.reputation.ListSources
import com.lookfirst.data.reputation.ListStore
import com.lookfirst.data.reputation.ListUpdateControl
import com.lookfirst.data.reputation.LocalReputationLookup
import com.lookfirst.domain.analysis.ReputationLookup
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReputationModule {
    @Binds
    abstract fun bindReputationLookup(impl: LocalReputationLookup): ReputationLookup

    @Binds
    abstract fun bindListUpdateControl(impl: DefaultListUpdateControl): ListUpdateControl

    companion object {
        /**
         * Indexes live in `filesDir/reputation`, which the backup rules exclude (`data_extraction_rules.xml`,
         * `allowBackup="false"`). Until a first download succeeds nothing is installed and lookups say "lists missing".
         */
        @Provides
        @Singleton
        fun provideListStore(@ApplicationContext context: Context): ListStore = ListStore(
            root = File(context.filesDir, "reputation"),
            sources = ListSources.ALL,
            clock = System::currentTimeMillis,
            directorySync = AndroidDirectorySync,
        )
    }
}
