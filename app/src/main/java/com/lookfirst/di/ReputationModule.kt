package com.lookfirst.di

import android.content.Context
import com.lookfirst.data.reputation.AssetEmbeddedBundle
import com.lookfirst.data.reputation.LocalReputationLookup
import com.lookfirst.data.reputation.ReputationStore
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

    companion object {
        /**
         * Bundles live in `filesDir/reputation`, which the backup rules exclude (`data_extraction_rules.xml`,
         * `allowBackup="false"`). Until a signed bundle exists nothing is installed and lookups say "lists missing".
         */
        @Provides
        @Singleton
        fun provideReputationStore(@ApplicationContext context: Context): ReputationStore = ReputationStore(
            root = File(context.filesDir, "reputation"),
            embedded = AssetEmbeddedBundle(context.assets),
            clock = System::currentTimeMillis,
        )
    }
}
