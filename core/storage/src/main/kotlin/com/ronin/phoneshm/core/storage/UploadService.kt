package com.ronin.phoneshm.core.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileInputStream

class UploadService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val sessionId = intent.getStringExtra(KEY_SESSION_ID) ?: return stopAndReturn(startId)
        val binFilePath = intent.getStringExtra(KEY_BIN_FILE_PATH) ?: return stopAndReturn(startId)
        val qualityGatePassed = intent.getBooleanExtra(KEY_QUALITY_GATE_PASSED, true)
        val qualityGateFailureReason = intent.getStringExtra(KEY_QUALITY_GATE_FAILURE_REASON)

        createNotificationChannel()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "upload_channel")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("PhoneSHM Uploading")
            .setContentText("Syncing session $sessionId to cloud...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()

        startForeground(2, notification)

        serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneSHM::UploadWakeLock")
            wakeLock.acquire(15 * 60 * 1000L) // 15 mins max safety timeout

            try {
                performUpload(sessionId, binFilePath, qualityGatePassed, qualityGateFailureReason, builder, 2)
            } catch (e: Exception) {
                Log.e(TAG, "UploadService failed for session $sessionId", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(true)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun performUpload(
        sessionId: String,
        binFilePath: String,
        qualityGatePassed: Boolean,
        qualityGateFailureReason: String?,
        notificationBuilder: Notification.Builder,
        notificationId: Int
    ) {
        val binFile = File(binFilePath)
        if (!binFile.exists()) {
            Log.e(TAG, "Storage binary file does not exist: $binFilePath")
            return
        }

        val metaFile = File(binFile.parentFile, "$sessionId.meta.json")
        val progressFile = File(binFile.parentFile, "$sessionId.progress")

        // 1. Ensure authenticated user
        val auth = FirebaseAuth.getInstance()
        var currentUser = auth.currentUser
        if (currentUser == null) {
            val authResult = auth.signInAnonymously().await()
            currentUser = authResult.user
        }
        val uid = currentUser?.uid ?: return

        val db = FirebaseFirestore.getInstance()

        // 2. Upload .bin file to Cloud Firestore in chunks
        val chunkSize = 500 * 1024 // ~500KB per chunk
        val fileLength = binFile.length()
        val totalChunks = if (fileLength == 0L) 0 else ((fileLength + chunkSize - 1) / chunkSize).toInt()

        // --- METADATA UPLOAD (Done first to guarantee existence) ---
        // 3. Read metadata JSON sidecar
        val metadataMap = hashMapOf<String, Any?>()
        val deviceReportMap = hashMapOf<String, Any?>()

        if (metaFile.exists()) {
            try {
                val root = org.json.JSONObject(metaFile.readText())
                if (root.has("metadata")) {
                    val metaObj = root.getJSONObject("metadata")
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val value = metaObj.get(k)
                        if (value is org.json.JSONArray) {
                            val list = mutableListOf<Any>()
                            for (i in 0 until value.length()) {
                                list.add(value.get(i))
                            }
                            metadataMap[k] = list
                        } else {
                            metadataMap[k] = value
                        }
                    }
                }
                if (root.has("deviceReport")) {
                    val devObj = root.getJSONObject("deviceReport")
                    val keys = devObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val value = devObj.get(k)
                        if (value is org.json.JSONArray) {
                            val list = mutableListOf<Any>()
                            for (i in 0 until value.length()) {
                                list.add(value.get(i))
                            }
                            deviceReportMap[k] = list
                        } else {
                            deviceReportMap[k] = value
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing meta.json sidecar", e)
            }
        }

        // Fallback defaults if fields missing
        if (!metadataMap.containsKey("sessionId")) metadataMap["sessionId"] = sessionId
        if (!metadataMap.containsKey("measurementProfileId")) metadataMap["measurementProfileId"] = "ambient_baseline_continuous"
        if (!metadataMap.containsKey("appVersionName")) metadataMap["appVersionName"] = "1.2.0-research-grade"
        if (!metadataMap.containsKey("appVersionCode")) metadataMap["appVersionCode"] = 1
        if (!metadataMap.containsKey("gitCommitHash")) metadataMap["gitCommitHash"] = "unknown"
        if (!metadataMap.containsKey("rawStorageFileUri")) metadataMap["rawStorageFileUri"] = binFilePath

        if (!deviceReportMap.containsKey("deviceModel")) deviceReportMap["deviceModel"] = android.os.Build.MODEL ?: "Unknown"

        // 4. Construct Firestore payload
        val firestorePayload = hashMapOf<String, Any?>()
        firestorePayload.putAll(metadataMap)
        firestorePayload["firebaseAuthUid"] = uid
        firestorePayload["uploadTimestamp"] = FieldValue.serverTimestamp()
        firestorePayload["qualityGatePassed"] = qualityGatePassed
        firestorePayload["qualityGateFailureReason"] = qualityGateFailureReason
        firestorePayload["deviceReport"] = deviceReportMap
        firestorePayload["totalChunks"] = totalChunks // Added for reader

        // 5. Write Firestore document
        db.collection("sessions")
            .document(sessionId)
            .set(firestorePayload)
            .await()

        Log.d(TAG, "Successfully wrote session metadata to Firestore: sessions/$sessionId")

        // --- CHUNK UPLOAD ---
        val startChunk = if (progressFile.exists()) {
            progressFile.readText().toIntOrNull() ?: 0
        } else {
            0
        }

        val notificationManager = getSystemService(NotificationManager::class.java)

        if (totalChunks > 0) {
            FileInputStream(binFile).use { fis ->
                val buffer = ByteArray(chunkSize)
                var chunkIndex = 0
                while (true) {
                    val bytesRead = fis.read(buffer)
                    if (bytesRead <= 0) break

                    if (chunkIndex >= startChunk) {
                        notificationBuilder.setContentText("Uploading chunk ${chunkIndex + 1}/$totalChunks...")
                        notificationManager?.notify(notificationId, notificationBuilder.build())

                        val actualBytes = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                        val base64Data = Base64.encodeToString(actualBytes, Base64.NO_WRAP)

                        val chunkPayload = hashMapOf<String, Any>(
                            "data" to base64Data,
                            "chunkIndex" to chunkIndex,
                            "totalChunks" to totalChunks
                        )

                        db.collection("sessions")
                            .document(sessionId)
                            .collection("chunks")
                            .document(chunkIndex.toString())
                            .set(chunkPayload)
                            .await()

                        progressFile.writeText((chunkIndex + 1).toString())
                        Log.d(TAG, "Successfully uploaded chunk $chunkIndex/$totalChunks for session $sessionId")
                    }

                    chunkIndex++
                }
            }
        }

        // Clean up progress file since upload is complete
        if (progressFile.exists()) {
            progressFile.delete()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "upload_channel",
                "Upload Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopAndReturn(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "UploadService"
        const val KEY_SESSION_ID = "KEY_SESSION_ID"
        const val KEY_BIN_FILE_PATH = "KEY_BIN_FILE_PATH"
        const val KEY_QUALITY_GATE_PASSED = "KEY_QUALITY_GATE_PASSED"
        const val KEY_QUALITY_GATE_FAILURE_REASON = "KEY_QUALITY_GATE_FAILURE_REASON"
    }
}
