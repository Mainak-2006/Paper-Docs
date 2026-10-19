package com.company.paperdocs

import com.company.paperdocs.data.Document
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.ShareRole
import org.junit.Assert.*
import org.junit.Test

class SharingTest {
    @Test fun shareRolesParseLenientlyAndDefaultToViewer() {
        assertEquals(ShareRole.EDITOR, ShareRole.parse("EDITOR"))
        assertEquals(ShareRole.VIEWER, ShareRole.parse("VIEWER"))
        // Unknown or missing values must never grant edit access.
        assertEquals(ShareRole.VIEWER, ShareRole.parse("ADMIN"))
        assertEquals(ShareRole.VIEWER, ShareRole.parse(null))
        assertEquals(ShareRole.VIEWER, ShareRole.parse(""))
    }

    @Test fun roleCapabilitiesMatchTheLabel() {
        assertTrue(ShareRole.EDITOR.canEdit)
        assertFalse(ShareRole.VIEWER.canEdit)
        assertEquals("Viewer", ShareRole.VIEWER.label)
    }

    @Test fun onlyDocumentsOwnedBySomeoneElseCanBeReadOnly() {
        val mine = Document()
        assertFalse(mine.readOnly)
        assertFalse(mine.copy(role = ShareRole.EDITOR).readOnly)
        // Owned by another account and shared as a viewer: edits must be refused.
        assertTrue(mine.copy(ownedBy = "someone", role = ShareRole.VIEWER).readOnly)
        // Owned by another account but granted editor rights.
        assertFalse(mine.copy(ownedBy = "someone", role = ShareRole.EDITOR).readOnly)
    }
}
