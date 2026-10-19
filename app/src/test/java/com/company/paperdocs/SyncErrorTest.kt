package com.company.paperdocs

import com.company.paperdocs.cloud.SyncError
import com.company.paperdocs.cloud.SyncErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync error classifier decides what the user is told when the cloud misbehaves. Getting this
 * wrong is how a real outage gets reported as success, so the cases are pinned here.
 */
class SyncErrorTest {
    @Test fun disabledFirestoreApiIsReportedAsDenied() {
        val detail = "PERMISSION_DENIED: Cloud Firestore API has not been used in project " +
            "paper-docs-006 before or it is disabled."
        assertEquals(SyncError.DENIED, SyncErrors.classify(detail, null))
        assertFalse(SyncError.DENIED.retryable)
    }

    @Test fun rulesRejectingWritesAreReportedAsDenied() {
        assertEquals(SyncError.DENIED, SyncErrors.classify("PERMISSION_DENIED: Missing or insufficient permissions.", null))
        assertEquals(SyncError.DENIED, SyncErrors.classify("Missing or insufficient permissions.", null))
    }

    @Test fun aMissingDatabaseInstanceIsCalledOutAsSetupWork() {
        val detail = "NOT_FOUND: The database (default) does not exist for project paper-docs-006. " +
            "Please visit https://console.cloud.google.com/datastore/setup to add a Cloud Datastore or Cloud Firestore database."
        assertEquals(SyncError.NO_DATABASE, SyncErrors.classify(detail, null))
        assertFalse(SyncError.NO_DATABASE.retryable)
    }

    @Test fun connectivityProblemsAreRetryable() {
        listOf(
            "The service is currently unavailable.",
            "Failed to connect to firestore.googleapis.com",
            "DEADLINE_EXCEEDED",
            "Unable to resolve host firestore.googleapis.com",
            "android_getaddrinfo failed: EAI_NODATA",
        ).forEach { detail ->
            assertEquals("expected OFFLINE for: $detail", SyncError.OFFLINE, SyncErrors.classify(detail, null))
        }
        assertTrue(SyncError.OFFLINE.retryable)
    }

    @Test fun expiredCredentialsAreTreatedAsSignedOut() {
        assertEquals(SyncError.SIGNED_OUT, SyncErrors.classify("UNAUTHENTICATED", null))
        assertEquals(SyncError.SIGNED_OUT, SyncErrors.classify("Token has expired", null))
    }

    @Test fun unknownFailuresStillReportSomething() {
        assertEquals(SyncError.FAILED, SyncErrors.classify("something unexpected happened", null))
        assertEquals(SyncError.FAILED, SyncErrors.classify(null, null))
        assertEquals(SyncError.FAILED, SyncErrors.classify("", null))
        assertTrue(SyncError.FAILED.retryable)
    }

    @Test fun theCauseIsConsideredWhenTheTopLevelMessageIsUnhelpful() {
        val cause = java.io.IOException("Failed to connect to /192.168.1.1:443")
        assertEquals(SyncError.OFFLINE, SyncErrors.classify("Internal error", cause))
    }
}