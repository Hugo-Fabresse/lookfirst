package com.lookfirst

import android.app.Application
import com.lookfirst.data.reputation.ListsStartup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LookFirstApp : Application() {
    @Inject
    lateinit var listsStartup: ListsStartup

    override fun onCreate() {
        super.onCreate()
        listsStartup.start()
    }
}
