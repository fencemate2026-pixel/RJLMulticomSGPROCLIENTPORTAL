package com.example.rjlmulticomsg_proclientportal

import android.app.Application
import android.util.Log
import com.example.rjlmulticomsg_proclientportal.data.local.AppDatabase
import com.example.rjlmulticomsg_proclientportal.data.remote.ClientCloudStore
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.data.session.SessionStore
import com.example.rjlmulticomsg_proclientportal.security.AppLockManager
import com.example.rjlmulticomsg_proclientportal.security.GateScheduleManager
import com.google.firebase.FirebaseApp

class ClientPortalApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var repository: PortalRepository
        private set
    lateinit var appLockManager: AppLockManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching { FirebaseApp.initializeApp(this) }
            .onFailure { Log.w(TAG, "Firebase init: ${it.message}") }
        database = AppDatabase.get(this)
        appLockManager = AppLockManager(this)
        repository = PortalRepository(
            db = database,
            sessionStore = SessionStore(this),
            cloud = ClientCloudStore(this)
        )
        
        // Initialize automatic gate scheduling
        GateScheduleManager.schedule(this)
    }

    companion object {
        private const val TAG = "ClientPortalApp"
        lateinit var instance: ClientPortalApp
            private set
    }
}

fun Application.portalRepo(): PortalRepository =
    (this as ClientPortalApp).repository
