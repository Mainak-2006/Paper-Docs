package com.company.paperdocs

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.company.paperdocs.data.*
import com.company.paperdocs.editor.*
import com.company.paperdocs.io.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun formattingSurvivesSerializationAndPartialRemoval() {
        val rich = RichContent("hello world", listOf(TextMark("bold", 0, 11), TextMark("italic", 6, 11), TextMark("color", 0, 5, "-65536")))
        val spans = RichTextCodec.toSpannable(RichTextCodec.fromJson(RichTextCodec.toJson(rich)))
        RichTextCodec.clearRange(spans, 3, 8, "bold")
        val restored = RichTextCodec.fromSpanned(spans)
        assertEquals("hello world", restored.text)
        assertEquals(listOf(TextMark("bold", 0, 3), TextMark("bold", 8, 11)), restored.marks.filter { it.kind == "bold" })
        assertTrue(restored.marks.contains(TextMark("italic", 6, 11)))
        assertTrue(restored.marks.contains(TextMark("color", 0, 5, "-65536")))
    }

    @Test fun editorUndoRedoAndReplacePreserveRichText() {
        instrumentation.runOnMainSync {
            val editor = DocumentEditText(context)
            val controller = RichEditorController(RichContent("one two one")) {}
            controller.attach(editor)
            editor.setSelection(0, 3)
            controller.format("bold")
            assertTrue(controller.content.marks.any { it.kind == "bold" })
            assertEquals(2, controller.replace("one", "three", true))
            assertEquals("three two three", controller.content.text)
            controller.undo()
            assertEquals("one two one", controller.content.text)
            assertTrue(controller.content.marks.any { it.kind == "bold" && it.end == 3 })
            controller.redo()
            assertEquals("three two three", controller.content.text)
            controller.detach()
        }
    }

    @Test fun caretFormattingAndListContinuationWork() {
        instrumentation.runOnMainSync {
            val editor = DocumentEditText(context)
            val controller = RichEditorController(RichContent()) {}
            controller.attach(editor)
            controller.format("bold")
            assertTrue(controller.active("bold"))
            editor.text.insert(0, "Hello")
            assertTrue(controller.content.marks.any { it.kind == "bold" && it.start == 0 && it.end == 5 })
            controller.restore(RichContent("• First"))
            editor.setSelection(editor.length())
            editor.text.append("\n")
            assertEquals("• First\n• ", controller.content.text)
            editor.text.append("\n")
            assertEquals("• First\n\n", controller.content.text)
            controller.detach()
        }
    }

    @Test fun databasePersistsOrderedEditsFlagsNotesAndVersions() = runBlocking {
        val name = "integration-${UUID.randomUUID()}.db"
        var store = DocumentStore(context, name)
        try {
            store.initialize().await()
            val original = store.create(Document(title = "Research", content = RichContent("first"))).await()
            store.checkpoint(original.id).await()
            val formatted = RichContent("latest edit", listOf(TextMark("bold", 0, 6)))
            store.save(original.copy(content = RichContent("intermediate")))
            store.setStarred(original.id, true)
            store.save(original.copy(content = formatted)).await()
            store.putNote(Note(documentId = original.id, quote = "latest", body = "Keep this" )).await()
            store.close().await()
            store = DocumentStore(context, name)
            store.initialize().await()
            val loaded = store.documents.value.single { it.id == original.id }
            assertEquals(formatted, loaded.content)
            assertTrue(loaded.starred)
            assertEquals("Keep this", store.notes(original.id).await().single().body)
            assertEquals("first", store.revisions(original.id).await().first().content.text)
            store.setTrashed(original.id, true).await()
            store.setTrashed(original.id, false).await()
            assertFalse(store.documents.value.single { it.id == original.id }.trashed)
            store.setTrashed(original.id, true).await()
            store.delete(original.id).await()
            assertTrue(store.notes(original.id).await().isEmpty())
            assertTrue(store.revisions(original.id).await().isEmpty())
        } finally { store.close().await(); context.deleteDatabase(name) }
    }

    @Test fun docxRoundTripPreservesTextUnicodeAndStyles() {
        val content = RichContent("A & B <notes>\nCafé 😀\n\nLast line\n", listOf(TextMark("bold", 0, 5), TextMark("underline", 6, 13), TextMark("size", 0, 13, "28"), TextMark("color", 0, 5, "-65536"), TextMark("align", 0, 13, "center")))
        val bytes = ByteArrayOutputStream().also { DocumentFiles.write(Document(content = content), ExportFormat.DOCX, it) }.toByteArray()
        val imported = DocxCodec.read(ByteArrayInputStream(bytes))
        assertEquals(content.text, imported.text)
        val text = RichTextCodec.toSpannable(imported)
        assertTrue(text.getSpans(0, 5, StyleSpan::class.java).isNotEmpty())
        assertTrue(imported.marks.any { it.kind == "color" && it.value == "-65536" })
        assertTrue(imported.marks.any { it.kind == "align" && it.value == "center" })
    }

    @Test fun pdfExportPaginatesAndCanBeOpened() {
        val file = File(context.cacheDir, "pdf-test-${UUID.randomUUID()}.pdf")
        try {
            val content = RichContent((1..160).joinToString("\n") { "Paragraph $it — a line of exported text." })
            file.outputStream().use { DocumentFiles.write(Document(content = content), ExportFormat.PDF, it) }
            val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            try {
                assertTrue(renderer.pageCount >= 4)
                val page = renderer.openPage(0)
                assertEquals(595, page.width)
                assertEquals(842, page.height)
                page.close()
            } finally { renderer.close() }
        } finally { file.delete() }
    }

    @Test fun docxRejectsDoctypeAndHtmlEscapesMarkup() {
        val stream = ByteArrayOutputStream()
        ZipOutputStream(stream).use {
            it.putNextEntry(ZipEntry("word/document.xml"))
            it.write("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>".toByteArray())
            it.closeEntry()
        }
        assertThrows(Exception::class.java) { DocxCodec.read(ByteArrayInputStream(stream.toByteArray())) }
        val html = DocumentFiles.html(Document(title = "<title>", content = RichContent("<script>alert('x')</script>")))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&lt;title&gt;"))
    }

    @Test fun aConflictedDocumentIsResolvableFromTheLibrary() = runBlocking {
        val name = "conflict-${UUID.randomUUID()}.db"
        val store = DocumentStore(context, name)
        try {
            store.initialize().await()
            val local = store.create(Document(title = "Joint", content = RichContent("mine"))).await()

            // Local edit, then a cloud update that is newer than the last synced copy: this is the
            // same shape the realtime listener produces when two devices edit at once.
            store.save(local.copy(content = RichContent("mine, edited"), updatedAt = 2_000L)).await()
            val conflict = store.applyRemote(local.id, "remote-1", "Joint", RichContent("theirs"), 1_500L).await()
            assertEquals(SyncState.CONFLICT, conflict)

            val flagged = store.documents.value.single { it.id == local.id }
            assertEquals(SyncState.CONFLICT, flagged.syncState)
            // The local text is still intact and recoverable while the conflict is open.
            assertEquals("mine, edited", flagged.content.text)
            assertTrue(store.revisions(local.id).await().any { it.content.text == "mine" })

            // Taking the cloud side swaps the content in and clears the conflict.
            store.resolveConflict(local.id, acceptLocal = false, "Joint", RichContent("theirs"), 1_500L).await()
            val resolved = store.documents.value.single { it.id == local.id }
            assertEquals("theirs", resolved.content.text)
            assertEquals(SyncState.SYNCED, resolved.syncState)
        } finally { store.close().await(); context.deleteDatabase(name) }
    }

    @Test fun docxRoundTripKeepsHeadingsListsAndLinks() {
        val content = RichContent(
            "Report title\nA short summary\n• First point\n• Second point\n1. Step one\n2. Step two\n",
            listOf(
                TextMark("size", 0, 12, "28"),
                TextMark("link", 17, 31, "https://example.com/report"),
            ),
        )
        val bytes = ByteArrayOutputStream().also { DocxCodec.write(content, it) }.toByteArray()
        val imported = DocxCodec.read(ByteArrayInputStream(bytes))

        assertEquals(content.text, imported.text)
        // The title keeps its heading size, and Word numbering returns as markers.
        assertTrue("heading size kept", imported.marks.any { it.kind == "size" && it.start == 0 && it.end == 12 && it.value == "28" })
        assertTrue("bullets restored", imported.text.contains("• First point"))
        assertTrue("numbers restored", imported.text.contains("1. Step one") && imported.text.contains("2. Step two"))
        // The hyperlink survives as a link mark with its target.
        val link = imported.marks.firstOrNull { it.kind == "link" }
        assertNotNull("hyperlink kept", link)
        assertEquals("https://example.com/report", link!!.value)
    }

    @Test fun docxWriterEmitsNumberingAndHyperlinkParts() {
        val content = RichContent(
            "• Item\nSee docs\n",
            listOf(TextMark("link", 4, 8, "https://example.org/docs")),
        )
        val bytes = ByteArrayOutputStream().also { DocxCodec.write(content, it) }.toByteArray()
        val names = mutableSetOf<String>()
        var document = ""
        var relationships = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                val text = zip.readBytes().toString(Charsets.UTF_8)
                if (entry.name == "word/document.xml") document = text
                if (entry.name == "word/_rels/document.xml.rels") relationships = text
                entry = zip.nextEntry
            }
        }
        assertTrue(names.contains("word/numbering.xml"))
        assertTrue(names.contains("word/styles.xml"))
        assertTrue("uses real numbering", document.contains("<w:numPr>"))
        assertTrue("writes a hyperlink", document.contains("<w:hyperlink"))
        assertTrue("declares external target", relationships.contains("TargetMode=\"External\""))
    }

    @Test fun clearingCloudStateKeepsDocumentsButDetachesThem() = runBlocking {
        val name = "cloudclear-${UUID.randomUUID()}.db"
        val store = DocumentStore(context, name)
        try {
            store.initialize().await()
            val document = store.create(Document(title = "Keep me", content = RichContent("still here"))).await()
            store.markRemote(document.id, "remote-9", SyncState.SYNCED).await()
            assertEquals(SyncState.SYNCED, store.documents.value.single { it.id == document.id }.syncState)

            // Deleting cloud data must not delete the reader's local work.
            store.clearCloudState().await()
            val after = store.documents.value.single { it.id == document.id }
            assertEquals("Keep me", after.title)
            assertEquals("still here", after.content.text)
            assertEquals("", after.remoteId)
            assertEquals(SyncState.LOCAL, after.syncState)
        } finally { store.close().await(); context.deleteDatabase(name) }
    }

    @Test fun wipingLocalDataRemovesDocumentsNotesAndVersions() = runBlocking {
        val name = "wipe-${UUID.randomUUID()}.db"
        val store = DocumentStore(context, name)
        try {
            store.initialize().await()
            val document = store.create(Document(title = "Private", content = RichContent("secret text"))).await()
            store.putNote(Note(id = UUID.randomUUID().toString(), documentId = document.id, quote = "secret", body = "keep out", resolved = false, createdAt = 1)).await()
            store.checkpoint(document.id).await()
            store.save(document.copy(title = "Private v2", content = RichContent("more secrets"))).await()
            assertEquals(1, store.documents.value.count { it.id == document.id })
            assertEquals("keep out", store.notes(document.id).await().single().body)
            assertTrue(store.revisions(document.id).await().isNotEmpty())

            // "Delete my account and data" clears the device. Notes and versions must go with the
            // document they belong to, or their text would survive the account being deleted.
            store.wipeLocalData().await()

            assertTrue(store.documents.value.none { it.id == document.id })
            assertTrue(store.notes(document.id).await().isEmpty())
            assertTrue(store.revisions(document.id).await().isEmpty())
        } finally { store.close().await(); context.deleteDatabase(name) }
    }

    @Test fun remoteChangeWinsWhenItIsNewerAndFlagsRealConflicts() = runBlocking {
        val name = "sync-${UUID.randomUUID()}.db"
        val store = DocumentStore(context, name)
        try {
            store.initialize().await()
            val local = store.create(Document(title = "Shared", content = RichContent("local"))).await()

            // A newer cloud copy is adopted automatically.
            val remoteNewer = System.currentTimeMillis() + 5_000
            val adopted = store.applyRemote(local.id, "remote-1", "Shared", RichContent("from cloud"), remoteNewer).await()
            assertEquals(SyncState.SYNCED, adopted)
            val reloaded = store.documents.value.single { it.id == local.id }
            assertEquals("from cloud", reloaded.content.text)
            assertEquals("remote-1", reloaded.remoteId)

            // Editing locally, then receiving an older remote update, is a real conflict.
            store.save(reloaded.copy(content = RichContent("local edit"), updatedAt = remoteNewer + 5_000)).await()
            val conflicted = store.applyRemote(local.id, "remote-1", "Shared", RichContent("stale cloud"), remoteNewer).await()
            assertEquals(SyncState.CONFLICT, conflicted)
            assertEquals(SyncState.CONFLICT, store.documents.value.single { it.id == local.id }.syncState)
            // Both versions remain recoverable.
            assertTrue(store.revisions(local.id).await().any { it.content.text == "from cloud" })

            // Accepting the local side clears the conflict and marks it for upload.
            store.resolveConflict(local.id, acceptLocal = true, "", RichContent(), 0L).await()
            val resolved = store.documents.value.single { it.id == local.id }
            assertEquals(SyncState.SYNCING, resolved.syncState)
            assertEquals("local edit", resolved.content.text)

            // Accepting the cloud side replaces the local copy outright.
            store.resolveConflict(local.id, acceptLocal = false, "Cloud title", RichContent("cloud wins"), 9_000L).await()
            val replaced = store.documents.value.single { it.id == local.id }
            assertEquals("cloud wins", replaced.content.text)
            assertEquals(SyncState.SYNCED, replaced.syncState)
        } finally { store.close().await(); context.deleteDatabase(name) }
    }
}
