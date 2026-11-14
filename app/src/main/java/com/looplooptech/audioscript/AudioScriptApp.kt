package com.looplooptech.audioscript

import android.app.Application
import com.looplooptech.audioscript.core.Hub

class AudioScriptApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}
