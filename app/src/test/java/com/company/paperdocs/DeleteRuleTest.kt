package com.company.paperdocs

import com.company.paperdocs.data.Document
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.ShareRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a document must not be blocked by a cloud copy the user cannot reach.
 *
 * A document that was synced keeps its remote id in the local database even after signing out, so
 * a signed-out user must still be able to delete it from the trash.
 */
class DeleteRuleTest {
    private fun synced() = Document(title = "Report", content = RichContent("body"), remoteId = "remote-1")

    @Test fun aSyncedDocumentIsRemovedFromTheCloudWhileSignedIn() {
        assertTrue(shouldDeleteFromCloud(synced(), signedIn = true))
    }

    @Test fun aSignedOutUserCanStillDeleteARemotelySyncedDocument() {
        // The regression: this returned true once, which blocked the local delete entirely.
        assertFalse(shouldDeleteFromCloud(synced(), signedIn = false))
    }

    @Test fun aDocumentThatNeverSyncedHasNoCloudCopyToRemove() {
        assertFalse(shouldDeleteFromCloud(Document(title = "Local only"), signedIn = true))
    }

    @Test fun aSharedCopyIsRemovedLocallyWithoutDeletingTheOwnersDocument() {
        val viewer = Document(title = "Shared", remoteId = "remote-2", ownedBy = "other", role = ShareRole.VIEWER)
        // Even an editor must not delete the owner's document from the cloud.
        assertFalse(shouldDeleteFromCloud(viewer, signedIn = true))
        assertFalse(shouldDeleteFromCloud(viewer.copy(role = ShareRole.EDITOR), signedIn = true))
    }
}