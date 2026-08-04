package com.ronin.phoneshm.core.storage

import android.content.Context
import android.content.Intent
import android.os.Build

object SessionUploadManager {

    fun enqueueUpload(
        context: Context,
        sessionId: String,
        binFilePath: String,
        qualityGatePassed: Boolean = true,
        qualityGateFailureReason: String? = null
    ) {
        val serviceIntent = Intent(context, UploadService::class.java).apply {
            putExtra(UploadService.KEY_SESSION_ID, sessionId)
            putExtra(UploadService.KEY_BIN_FILE_PATH, binFilePath)
            putExtra(UploadService.KEY_QUALITY_GATE_PASSED, qualityGatePassed)
            putExtra(UploadService.KEY_QUALITY_GATE_FAILURE_REASON, qualityGateFailureReason)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
