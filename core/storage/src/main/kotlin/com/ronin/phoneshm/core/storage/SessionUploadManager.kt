package com.ronin.phoneshm.core.storage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SessionUploadManager {

    fun enqueueUpload(
        context: Context,
        sessionId: String,
        binFilePath: String,
        qualityGatePassed: Boolean = true,
        qualityGateFailureReason: String? = null
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(UploadSessionWorker.KEY_SESSION_ID, sessionId)
            .putString(UploadSessionWorker.KEY_BIN_FILE_PATH, binFilePath)
            .putBoolean(UploadSessionWorker.KEY_QUALITY_GATE_PASSED, qualityGatePassed)
            .putString(UploadSessionWorker.KEY_QUALITY_GATE_FAILURE_REASON, qualityGateFailureReason)
            .build()

        val uploadWorkRequest = OneTimeWorkRequestBuilder<UploadSessionWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueue(uploadWorkRequest)
    }
}
