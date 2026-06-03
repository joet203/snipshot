package com.jt.snipshot

import android.app.Application

class SnipshotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
