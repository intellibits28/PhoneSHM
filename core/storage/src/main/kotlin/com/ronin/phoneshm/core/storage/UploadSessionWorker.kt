package com.ronin.phoneshm.core.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

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
            // 2. Upload .bin file to Cloud Storage
            val storageRef = FirebaseStorage.getInstance().reference
                .child("sessions")
                .child(uid)
                .child("$sessionId.bin")

            val fileUri = Uri.fromFile(binFile)
            storageRef.putFile(fileUri).await()
            Log.d(TAG, "Successfully uploaded .bin to Storage: sessions/$uid/$sessionId.bin")

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
                            metadataMap[k] = metaObj.get(k)
                        }
                    }
                    if (root.has("deviceReport")) {
                        val devObj = root.getJSONObject("deviceReport")
                        val keys = devObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            deviceReportMap[k] = devObj.get(k)
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

            // 5. Write Firestore document
            val db = FirebaseFirestore.getInstance()
            db.collection("sessions")
                .document(sessionId)
                .set(firestorePayload)
                .await()

            Log.d(TAG, "Successfully wrote session document to Firestore: sessions/$sessionId")
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
