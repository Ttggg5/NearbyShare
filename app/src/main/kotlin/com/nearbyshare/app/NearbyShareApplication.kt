package com.nearbyshare.app

import android.app.Application

/** Owns the process-lifetime [AppContainer]. */
class NearbyShareApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
