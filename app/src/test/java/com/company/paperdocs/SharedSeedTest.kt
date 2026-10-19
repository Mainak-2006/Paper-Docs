package com.company.paperdocs

import com.company.paperdocs.cloud.RemoteChange
import com.company.paperdocs.data.Document
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.ShareRole
import com.company.paperdocs.data.SharedDocument
import com.company.paperdocs.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a shared document must show the owner's text straight away.
 *
 * The regression: the local copy was created first, so it carried the current timestamp. That is
 * newer than the owner's copy, so applyRemote judged it a conflicting local edit and left the
 * document blank and flagged as a conflict.
 */
class SharedSeedTest {
    private val owner = "owner-uid"
    private val editedAt = 1_700_000_000_000L

    private fun remote(title: String = "Design notes", text: String = "The owner's text.") =
        RemoteChange(
            documentId = "remote-id",
            title = title,
            content = RichContent(text),
            updatedAt = editedAt,
            origin = owner,
        )

    private fun shared(role: ShareRole = ShareRole.VIEWER, title: String = "Design notes") =
        SharedDocument(ownerUid = owner, remoteId = "remote-id", title = title, role = role)

    @Test fun theCopyCarriesTheOwnersText() {
        assertEquals(RichContent("The owner's text."), sharedSeed(shared(), remote()).content)
    }

    @Test fun theCopyIsStampedWithTheOwnersTimestampNotNow() {
        // The exact cause of the blank, conflicted document.
        val seed = sharedSeed(shared(), remote())
        assertEquals(editedAt, seed.updatedAt)
        assertTrue("seed must not look newer than the copy it mirrors", seed.updatedAt <= editedAt)
    }

    @Test fun theCopyStartsSyncedBecauseNothingIsPendingLocally() {
        assertEquals(SyncState.SYNCED, sharedSeed(shared(), remote()).syncState)
    }

    @Test fun theCopyTracksTheOwnerSoAViewerCannotEdit() {
        val viewer = sharedSeed(shared(ShareRole.VIEWER), remote())
        assertEquals(owner, viewer.ownedBy)
        assertTrue("a viewer must not be able to change the owner's text", viewer.readOnly)

        val editor = sharedSeed(shared(ShareRole.EDITOR), remote())
        assertFalse(editor.readOnly)
    }

    @Test fun theOwnersTitleWinsAndStillHasAFallback() {
        // The owner renamed it after sharing, so the owner's title is what the reader must see.
        assertEquals("Renamed by owner", sharedSeed(shared(), remote(title = "Renamed by owner")).title)
        assertEquals(
            "Design notes",
            sharedSeed(shared(title = "Design notes"), remote(title = "")).title,
        )
        assertEquals("Shared document", sharedSeed(shared(title = ""), remote(title = "")).title)
    }

    @Test fun theSeededDocumentIsIdenticalToWhatApplyRemoteWouldAdopt() {
        // applyRemote compares title and content before the timestamp, so a seeded copy that
        // matches the remote must be recognised as the same document rather than a new edit.
        val change = remote()
        val seed: Document = sharedSeed(shared(), change)
        assertEquals(change.title, seed.title)
        assertEquals(change.content, seed.content)
        assertEquals(change.updatedAt, seed.updatedAt)
        assertEquals("remote-id", seed.remoteId)
    }
}