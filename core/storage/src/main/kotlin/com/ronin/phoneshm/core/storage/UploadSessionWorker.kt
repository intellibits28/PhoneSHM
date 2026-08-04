package com.ronin.phoneshm.core.storage

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileInputStream

class UploadSessionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val binFilePath = inputData.getString(KEY_BIN_FILE_PATH) ?: return Result.failure()
        val qualityGatePassed = inputData.getBoolean(KEY_QUALITY_GATE_PASSED, true)
        val qualityGateFailureReason = inputData.getString(KEY_QUALITY_GATE_FAILURE_REASON)

        val binFile = File(binFilePath)
        if (!binFile.exists()) {
            Log.e(TAG, "Storage binary file does not exist: $binFilePath")
            return Result.failure()
        }

        val metaFile = File(binFile.parentFile, "$sessionId.meta.json")
        val progressFile = File(binFile.parentFile, "$sessionId.progress")

        // 1. Ensure authenticated user
        val auth = FirebaseAuth.getInstance()
        var currentUser = auth.currentUser
        if (currentUser == null) {
            try {
                val authResult = auth.signInAnonymously().await()
                currentUser = authResult.user
            } catch (e: Exception) {
                Log.e(TAG, "Failed anonymous auth in UploadSessionWorker", e)
                return Result.retry()
            }
        }
        val uid = currentUser?.uid ?: return Result.retry()

        try {
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

            if (totalChunks > 0) {
                FileInputStream(binFile).use { fis ->
                    val buffer = ByteArray(chunkSize)
                    var chunkIndex = 0
                    while (true) {
                        val bytesRead = fis.read(buffer)
                        if (bytesRead <= 0) break

                        if (chunkIndex >= startChunk) {
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
            
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for session $sessionId, scheduling retry", e)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "UploadSessionWorker"
        const val KEY_SESSION_ID = "KEY_SESSION_ID"
        const val KEY_BIN_FILE_PATH = "KEY_BIN_FILE_PATH"
        const val KEY_QUALITY_GATE_PASSED = "KEY_QUALITY_GATE_PASSED"
        const val KEY_QUALITY_GATE_FAILURE_REASON = "KEY_QUALITY_GATE_FAILURE_REASON"
    }
}
