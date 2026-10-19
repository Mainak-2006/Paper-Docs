package com.company.paperdocs.data

import java.util.UUID

data class TextMark(val kind: String, val start: Int, val end: Int, val value: String = "")

data class RichContent(val text: String = "", val marks: List<TextMark> = emptyList()) {
    val wordCount: Int get() = Regex("[\\p{L}\\p{N}]+(?:['’_-][\\p{L}\\p{N}]+)*").findAll(text).count()
    fun validated(): RichContent = copy(marks = marks.filter {
        it.start >= 0 && it.end > it.start && it.end <= text.length && it.kind in markKinds
    })

    companion object {
        val markKinds = setOf("bold", "italic", "underline", "strike", "size", "font", "color", "highlight", "align", "link", "table")
    }
}

/** Whether a document is only on this device, mirrored to the cloud, or in conflict. */
enum class SyncState(val label: String) { LOCAL("On this device"), SYNCED("Synced"), SYNCING("Syncing…"), FAILED("Not synced"), CONFLICT("Conflicting changes") }

/** What a collaborator may do with a shared document. */
enum class ShareRole(val label: String, val canEdit: Boolean) {
    VIEWER("Viewer", false),
    EDITOR("Editor", true);

    companion object {
        fun parse(value: String?): ShareRole = entries.firstOrNull { it.name == value } ?: VIEWER
    }
}

/** Someone a document has been shared with, as recorded in Firestore. */
data class Collaborator(val uid: String, val role: ShareRole, val email: String = "", val name: String = "")

/** A document someone else has shared with the signed-in user. */
data class SharedDocument(val ownerUid: String, val remoteId: String, val title: String, val role: ShareRole)

data class Document(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Untitled document",
    val content: RichContent = RichContent(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val starred: Boolean = false,
    val trashed: Boolean = false,
    /** Firestore document id once the document has been pushed, empty while local-only. */
    val remoteId: String = "",
    val syncState: SyncState = SyncState.LOCAL,
    /** Collaborator uid to role. Preserved on push so uploading never drops someone's access. */
    val sharedWith: Map<String, ShareRole> = emptyMap(),
    /** Empty when the signed-in user owns the cloud copy, otherwise the owner's Firebase uid. */
    val ownedBy: String = "",
    /** The access the signed-in user has. Owners are always editors. */
    val role: ShareRole = ShareRole.EDITOR,
) {
    /** True when the cloud copy sits in someone else's collection and may not be edited. */
    val readOnly: Boolean get() = ownedBy.isNotEmpty() && !role.canEdit
}

data class Revision(val id: Long, val documentId: String, val title: String, val content: RichContent, val createdAt: Long)
data class Note(val id: String = UUID.randomUUID().toString(), val documentId: String, val quote: String, val body: String, val resolved: Boolean = false, val createdAt: Long = System.currentTimeMillis())

enum class LibrarySection(val label: String) { ALL("All documents"), STARRED("Starred"), OFFLINE("On this device"), TRASH("Trash") }
enum class SortOrder(val label: String) { MODIFIED("Last modified"), NAME("Name A–Z"), CREATED("Date created") }

data class DocumentTemplate(val name: String, val description: String, val content: RichContent)

object Templates {
    private fun styled(title: String, body: String): RichContent = RichContent("$title\n$body", listOf(
        TextMark("size", 0, title.length, "28"), TextMark("bold", 0, title.length),
        TextMark("color", 0, title.length, "-15043608"),
    ))
    val all = listOf(
        DocumentTemplate("Blank document", "A fresh start", RichContent()),
        DocumentTemplate("Meeting notes", "Make every conversation count", styled("Meeting notes", "\nDate: \nAttendees: \n\nAgenda\n• \n\nDiscussion\n\n\nAction items\n☐ \n\nNext meeting\n")),
        DocumentTemplate("Project brief", "Turn an idea into a plan", styled("Project brief", "\nProject name\nDescribe your project in one sentence.\n\nThe opportunity\nWhat problem are we solving?\n\nGoals\n1. \n2. \n3. \n\nDeliverables\n• \n\nTimeline\nMilestone 1 — \nMilestone 2 — \n\nWhat success looks like\n")),
        DocumentTemplate("Weekly planner", "A little space for your week", styled("A week with intention", "\nWeek of: \n\nThis week’s focus\n\n\nMonday\n☐ \n\nTuesday\n☐ \n\nWednesday\n☐ \n\nThursday\n☐ \n\nFriday\n☐ \n\nSmall wins\n")),
        DocumentTemplate("Letter", "Something worth sending", styled("Your name", "\nYour address\nCity, postal code\n\nDate\n\nDear recipient,\n\nWrite your message here.\n\nKind regards,\nYour name\n")),
    )
    val welcome = Document(
        title = "Welcome to Paper Docs",
        starred = true,
        content = styled("A little space for big ideas.", "\nWelcome to Paper Docs — your thoughts, beautifully organized.\n\nMake it yours\nStart a blank document or choose a template. Everything you write is saved on this device, automatically.\n\nWrite with a little style\nSelect some text, then use the toolbar to add bold, italics, headings, colors, and more. Undo is always one tap away.\n\nTake your words with you\nExport a PDF, Word document, HTML, or plain-text file. Share a copy with any app on your phone.\n\nYour next great idea starts here.\n"),
    )
}
