package com.example.smartdispatch

import android.app.Application
import com.example.smartdispatch.data.AppDatabase
import com.example.smartdispatch.data.DispatchRepository
import com.example.smartdispatch.data.UserPreferences

class DispatchApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { DispatchRepository(database) }
    val userPrefs by lazy { UserPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: DispatchApplication
            private set
    }
}