package com.company.paperdocs

import com.company.paperdocs.cloud.SyncError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pull to refresh is only useful if it tells the truth about what it did.
 *
 * A pull that reaches nothing must not read as a success, and a pull that changed nothing must
 * not claim documents were updated, so the wording is pinned here.
 */
class SyncSummaryTest {
    @Test fun aFailedPullIsReportedAsTheFailureAndNotAsSuccess() {
        listOf(SyncError.OFFLINE, SyncError.DENIED, SyncError.FAILED, SyncError.SIGNED_OUT, SyncError.NO_DATABASE).forEach { error ->
            assertEquals(error.message, syncSummary(updated = 3, conflicts = 0, error = error))
        }
    }

    @Test fun aFailedPullWinsOverAConflictBecauseNothingLanded() {
        assertEquals(
            SyncError.OFFLINE.message,
            syncSummary(updated = 0, conflicts = 2, error = SyncError.OFFLINE),
        )
    }

    @Test fun aPullThatChangedNothingSaysSo() {
        assertEquals("Already up to date", syncSummary(updated = 0, conflicts = 0, error = null))
    }

    @Test fun pulledDocumentsAreCountedAndPluralised() {
        assertEquals("Updated 1 document from the cloud", syncSummary(updated = 1, conflicts = 0, error = null))
        assertEquals("Updated 4 documents from the cloud", syncSummary(updated = 4, conflicts = 0, error = null))
    }

    @Test fun conflictsAreSurfacedEvenWhenNothingWasAdopted() {
        // A conflict flags the document without replacing it, so `updated` is legitimately zero.
        assertEquals(
            "1 document changed in two places. Choose which version to keep.",
            syncSummary(updated = 0, conflicts = 1, error = null),
        )
    }

    @Test fun conflictsTakePrecedenceOverAnUpdateCount() {
        assertEquals(
            "2 documents changed in two places. Choose which version to keep.",
            syncSummary(updated = 7, conflicts = 2, error = null),
        )
    }
}