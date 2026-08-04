package com.ronin.phoneshm

import android.app.Application

import com.google.firebase.auth.FirebaseAuth

/**
 * PhoneSHMApplication: Sovereign Android Native Structural Health Monitoring Application.
 * Initializes core DI container and application lifecycle for research-grade SHM.
 */
class PhoneSHMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        bootstrapAnonymousAuth()
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
}
