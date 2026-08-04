package com.ronin.phoneshm.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class UploadSessionWorkerTest {

    @Test
    fun testPayloadStructure() {
        val sessionId = "test-session-12345"
        val payload = hashMapOf<String, Any?>(
            "sessionId" to sessionId,
            "measurementProfileId" to "ambient_baseline_continuous",
            "qualityGatePassed" to false,
            "qualityGateFailureReason" to "sampling_gaps",
            "firebaseAuthUid" to "test_uid_9999"
        )

        assertEquals("test-session-12345", payload["sessionId"])
        assertEquals(false, payload["qualityGatePassed"])
        assertEquals("sampling_gaps", payload["qualityGateFailureReason"])
        assertNotNull(payload["firebaseAuthUid"])
    }
}
