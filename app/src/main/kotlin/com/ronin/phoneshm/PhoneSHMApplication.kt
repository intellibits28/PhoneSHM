package com.ronin.phoneshm

import android.app.Application

import com.google.firebase.auth.FirebaseAuth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PhoneSHMApplication: Sovereign Android Native Structural Health Monitoring Application.
 * Initializes core DI container and application lifecycle for research-grade SHM.
 */
class PhoneSHMApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        bootstrapAnonymousAuth()
        try {
            val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            com.ronin.phoneshm.core.storage.RemoteConfigManager.initialize(isDebug)
        } catch (e: Exception) {
            android.util.Log.e("PhoneSHMAuth", "RemoteConfigManager initialization exception", e)
        }
        cleanupOrphanedTmpFiles()
    }

    private fun bootstrapAnonymousAuth() {
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously()
                    .addOnSuccessListener { result ->
                        android.util.Log.d("PhoneSHMAuth", "Anonymous auth succeeded: UID=${result.user?.uid}")
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("PhoneSHMAuth", "Anonymous auth failed", e)
                    }
            } else {
                android.util.Log.d("PhoneSHMAuth", "Already authenticated: UID=${auth.currentUser?.uid}")
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneSHMAuth", "FirebaseAuth initialization exception", e)
        }
    }

    internal fun cleanupOrphanedTmpFiles() {
        applicationScope.launch {
            try {
                val dirsToScan = listOf(
                    java.io.File(filesDir, "raw_sessions")
                )
                
                var deletedCount = 0
                
                for (dir in dirsToScan) {
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && file.name.endsWith(".tmp")) {
                                if (file.delete()) {
                                    deletedCount++
                                }
                            }
                        }
                    }
                }
                if (deletedCount > 0) {
                    android.util.Log.i("PhoneSHMStorage", "Cleaned up $deletedCount orphaned .tmp session files")
                }
            } catch (e: Exception) {
                android.util.Log.e("PhoneSHMStorage", "Failed to cleanup orphan files", e)
            }
        }
    }
}
