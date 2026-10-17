package com.lookfirst.data.reputation

import com.lookfirst.di.ApplicationScope
import com.lookfirst.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App start: applies the stored update schedule and loads the installed bundle (mapping the indices, parsing the
 * Public Suffix List) off the main thread, so that the first scan does not pay for it. Until the load is done a
 * lookup says "lists missing" rather than wait.
 */
@Singleton
class ListsStartup @Inject internal constructor(
    private val store: ReputationStore,
    private val control: ListUpdateControl,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun start() {
        scope.launch(io) {
            control.applySchedule()
            store.preload()
            control.refresh()
        }
    }
}
