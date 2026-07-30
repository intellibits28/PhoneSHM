package com.ronin.phoneshm.core.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val duration = intent?.getIntExtra("DURATION_SEC", 60) ?: 60
        createNotificationChannel()
        
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "recording_channel")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        
        val notification = builder
            .setContentTitle("PhoneSHM Recording")
            .setContentText("Recording in progress — $duration seconds remaining")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
            
        startForeground(1, notification)
        return START_NOT_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "recording_channel",
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
