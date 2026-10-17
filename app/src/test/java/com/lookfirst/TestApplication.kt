package com.lookfirst

import android.app.Application

/**
 * The application class of the Robolectric tests (`robolectric.properties`): a bare [Application]. The real
 * [LookFirstApp] starts the list preload and schedules WorkManager, which a screen test must not do.
 */
class TestApplication : Application()
