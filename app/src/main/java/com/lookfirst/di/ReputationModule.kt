package com.lookfirst.di

import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.domain.analysis.ReputationLookup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReputationModule {
    @Binds
    abstract fun bindReputationLookup(impl: NoReputationLists): ReputationLookup
}
