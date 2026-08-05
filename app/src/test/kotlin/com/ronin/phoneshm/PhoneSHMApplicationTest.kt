package com.ronin.phoneshm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PhoneSHMApplication::class)
class PhoneSHMApplicationTest {

    private lateinit var app: PhoneSHMApplication
    private lateinit var rawSessionsDir: File

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        rawSessionsDir = File(app.filesDir, "raw_sessions")
        rawSessionsDir.mkdirs()
    }

    @Test
    fun testCleanupDeletesAllTmpFilesOnStartup() = runBlocking {
        // Create a recent .tmp file (simulating a crash that happened 2 seconds ago)
        val recentFile = File(rawSessionsDir, "recent_session.bin.tmp")
        recentFile.writeText("test data")
        recentFile.setLastModified(System.currentTimeMillis() - 2000)

        // Create an old .tmp file
        val oldFile = File(rawSessionsDir, "old_session.bin.tmp")
        oldFile.writeText("test data")
        oldFile.setLastModified(System.currentTimeMillis() - 25 * 60 * 1000L) // 25 minutes ago

        // Run cleanup
        app.cleanupOrphanedTmpFiles()
        
        // Wait for coroutine to finish
        delay(500)

        // Verify ALL .tmp files are deleted because at onCreate(), they are guaranteed orphans
        assertFalse("Recent .tmp file should be deleted", recentFile.exists())
        assertFalse("Old .tmp file should be deleted by cleanup", oldFile.exists())
    }
}
