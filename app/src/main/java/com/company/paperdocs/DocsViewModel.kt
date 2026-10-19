package com.company.paperdocs

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.company.paperdocs.cloud.AccountManager
import com.company.paperdocs.cloud.CloudSync
import com.company.paperdocs.cloud.DriveAuth
import com.company.paperdocs.cloud.DriveSync
import com.company.paperdocs.cloud.DriveFile
import com.company.paperdocs.cloud.PushOutcome
import com.company.paperdocs.cloud.RemoteChange
import com.company.paperdocs.cloud.SyncConflict
import com.company.paperdocs.cloud.SyncError
import com.company.paperdocs.data.*
import com.company.paperdocs.io.DocumentFiles
import com.company.paperdocs.io.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class DocsViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {
    private val store = (application as DocsApplication).documents
    private val accounts = AccountManager(application)
    private val cloud = CloudSync(
        account = { accounts.account.value },
        databaseId = application.resources.getString(com.company.paperdocs.R.string.firestore_database_id).trim(),
    )
    /** Cached OAuth access token for Drive; null until the user grants Drive access. */
    private val driveToken = MutableStateFlow<String?>(null)
    val needsDriveConsent = MutableStateFlow(false)
    val driveReady = MutableStateFlow(false)
    private val drive = DriveSync { driveToken.value }
    private fun driveAuth(activity: Activity) = DriveAuth(activity)

    val documents = store.documents
    val account = accounts.account
    val accountBusy = accounts.busy
    val accountRestoring = accounts.restoring

    /** False until a Firebase project is configured; the UI explains what to add. */
    val cloudConfigured: Boolean get() = accounts.configured

    /** Why the cloud is not syncing, or null when everything is fine. */
    val syncError = cloud.error
    val presence = cloud.presence

    var active by mutableStateOf<Document?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var saveStatus by mutableStateOf("Saved on this device")
        private set
    var syncing by mutableStateOf(false)
        private set
    /**
     * True while a pull-to-refresh is running. Separate from [syncing] so the refresh indicator
     * tracks the gesture the reader started, not an unrelated Drive consent or bulk delete.
     */
    var refreshing by mutableStateOf(false)
        private set
    private var generation = 0L
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    private val conflicts = MutableStateFlow<Map<String, SyncConflict>>(emptyMap())
    val conflictList: StateFlow<Map<String, SyncConflict>> = conflicts

    /** A conflict the reader has not dealt with yet, for prompting them. */
    private val pendingConflict = MutableStateFlow<SyncConflict?>(null)
    val conflictToResolve: StateFlow<SyncConflict?> = pendingConflict

    fun dismissConflictPrompt() { pendingConflict.value = null }

    /**
     * Account errors are shown inside the account sheet rather than as a snackbar, because a
     * modal sheet covers the scaffold's snackbar host and the message would be invisible.
     */
    val accountMessage: StateFlow<String?> = accounts.message
    fun clearAccountMessage() { accounts.dismissMessage() }

    /** Re-opens the prompt for a conflicted document from the library. */
    fun promptConflict(documentId: String) { pendingConflict.value = conflicts.value[documentId] }

    /** Share sheet feedback, shown inline because a modal sheet hides the snackbar host. */
    private val shareNoticeState = MutableStateFlow<String?>(null)
    val shareNotice: StateFlow<String?> = shareNoticeState
    fun clearShareNotice() { shareNoticeState.value = null }

    private val collaboratorsState = MutableStateFlow<Map<String, List<Collaborator>>>(emptyMap())
    val collaborators: StateFlow<Map<String, List<Collaborator>>> = collaboratorsState

    private val sharesInboxState = MutableStateFlow<List<SharedDocument>>(emptyList())
    val sharesInbox: StateFlow<List<SharedDocument>> = sharesInboxState

    init {
        viewModelScope.launch {
            try {
                store.initialize().await()
                active = documents.value.firstOrNull { it.id == state.get<String>("document") && !it.trashed }
            } catch (error: Exception) { message("Could not open documents: ${error.message}") }
            finally { loading = false }
        }
        // Must live outside the init block above: collecting a StateFlow suspends forever, which
        // would stop the `finally` from ever clearing the loading state.
        viewModelScope.launch {
            accounts.message.collect { text -> text?.let(::message) }
        }
        // Keep the share directory populated even when the session was already restored at
        // launch, which is the common case and previously left the entry missing.
        viewModelScope.launch {
            accounts.account.filterNotNull().collectLatest { user ->
                if (user.email.isNotEmpty()) cloud.publishDirectoryEntry(user.email)
            }
        }
        // Live collaboration: apply remote edits to the open document as they arrive.
        viewModelScope.launch {
            cloud.changes.collect { change ->
                val open = active ?: return@collect
                if (change.documentId != open.id || change.origin == account.value?.uid) return@collect
                val outcome = store.applyRemote(open.id, open.remoteId, change.title, change.content, change.updatedAt).await()
                if (outcome == SyncState.CONFLICT) {
                    recordConflict(SyncConflict(open.id, currentLocal(open.id, change), change))
                }
            }
        }
    }

    /** Remembers a conflict and asks the reader to resolve it. */
    private fun recordConflict(conflict: SyncConflict) {
        rememberConflict(conflict)
        message(CONFLICT_NOTICE)
    }

    /** Records a conflict without announcing it, so one batch can speak once at the end. */
    private fun rememberConflict(conflict: SyncConflict) {
        conflicts.value = conflicts.value + (conflict.documentId to conflict)
        pendingConflict.value = conflict
    }

    /**
     * Applies a batch of pulled remote changes, flagging any that arrived as a genuine conflict.
     * Returns how many documents need the reader to choose a version.
     */
    private suspend fun applyRemoteBatch(remote: List<Pair<String, RemoteChange>>): Int {
        var found = 0
        remote.forEach { (remoteId, change) ->
            val outcome = store.applyRemote(change.documentId, remoteId, change.title, change.content, change.updatedAt).await()
            if (outcome == SyncState.CONFLICT) {
                found++
                rememberConflict(SyncConflict(change.documentId, currentLocal(change.documentId, change), change))
            }
        }
        return found
    }

    private fun currentLocal(documentId: String, remote: RemoteChange) =
        documents.value.firstOrNull { it.id == documentId }
            ?: Document(id = documentId, title = remote.title, content = remote.content)

    private fun notice(text: String) {
        shareNoticeState.value = text
        message(text)
    }

    // ------------------------------------------------------------------ accounts

    fun signInWithGoogle(activity: Activity) = action { accounts.signInWithGoogle(activity); onAccountChanged() }
    fun signOut() {
        accounts.signOut()
        cloud.signOut()
        conflicts.value = emptyMap()
        pendingConflict.value = null
        driveToken.value = null
        driveReady.value = false
        message("Signed out. Documents stay on this device.")
    }

    private suspend fun onAccountChanged() {
        val user = accounts.account.value
        if (user == null) return
        // Make this account findable so others can share with it by email address.
        if (user.email.isNotEmpty()) cloud.publishDirectoryEntry(user.email)
        syncing = true
        try {
            val conflicts = applyRemoteBatch(cloud.pullAll())
            // Push anything created while offline.
            documents.value.filter { it.syncState != SyncState.SYNCED && !it.trashed }.forEach { push(it) }
            message("Signed in as ${user.email.ifBlank { user.name }}")
            if (conflicts > 0) message(CONFLICT_NOTICE)
        } finally {
            syncing = false
        }
    }

    fun message(text: String) { messages.trySend(text) }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (error: Exception) {
            android.util.Log.w("DocsViewModel", "action failed", error)
            message(error.message ?: "That action could not be completed. Please try again.")
        }
    }

    /**
     * Obtains a Drive access token, running Google's consent flow the first time. The app passes
     * a launcher so the consent activity can be started from a resumed activity.
     */
    fun connectDrive(activity: Activity, launcher: (android.app.PendingIntent) -> Unit) = action {
        if (account.value == null) { message("Sign in to use Google Drive."); return@action }
        syncing = true
        try {
            val token = driveAuth(activity).authorize(launcher)
            driveToken.value = token
            driveReady.value = token != null
            needsDriveConsent.value = token == null
            message(if (token != null) "Google Drive connected" else "Drive access was not granted.")
        } finally {
            syncing = false
        }
    }

    /** Completes the consent flow once the activity returns its result. */
    fun completeDriveConsent(activity: Activity, data: Intent?) {
        val token = driveAuth(activity).onConsentResult(data)
        driveToken.value = token
        driveReady.value = token != null
        if (token == null) message("Drive access was not granted.")
    }

    /** Uses an already-granted token without prompting, for example on a later launch. */
    fun primeDriveToken(activity: Activity) = action {
        if (account.value == null) return@action
        val token = driveAuth(activity).cachedToken()
        driveToken.value = token
        driveReady.value = token != null
    }

    // ------------------------------------------------------------------ documents

    fun open(document: Document) {
        active = document
        state["document"] = document.id
        saveStatus = "Saved on this device"
        action { store.checkpoint(document.id).await() }
        action { startCloudSession(document) }
    }

    fun close() {
        active?.let { document ->
            action { store.checkpoint(document.id).await() }
            action { push(document) }
        }
        active = null
        state["document"] = null
    }

    private suspend fun startCloudSession(document: Document) {
        val user = account.value ?: return
        push(document)
        cloud.listen(document.id, document.remoteId, document.ownedBy)
        cloud.startPresence(document.id, document.remoteId, user, document.ownedBy)
    }

    /** Uploads the document when signed in; keeps it local otherwise. */
    private suspend fun push(document: Document) {
        if (document.trashed || document.readOnly) return
        when (val outcome = cloud.push(document, document.remoteId.ifEmpty { null })) {
            is PushOutcome.Uploaded -> {
                // The server acknowledged the write, so the document is genuinely synced even
                // when this is the first upload and the remote id was only just established.
                store.markRemote(document.id, outcome.remoteId, SyncState.SYNCED).await()
                retryScheduled = false
            }
            is PushOutcome.Rejected -> {
                // Signed out or unconfigured is a normal offline state, not a failure to report.
                if (outcome.error == SyncError.SIGNED_OUT || outcome.error == SyncError.NOT_CONFIGURED) {
                    store.markRemote(document.id, document.remoteId, SyncState.LOCAL).await()
                } else {
                    store.markRemote(document.id, document.remoteId, SyncState.FAILED).await()
                    scheduleRetry()
                }
            }
        }
    }

    // ------------------------------------------------------------------ retry

    private var retryScheduled = false

    /** Retries pending uploads with a backoff, so a flaky connection is not hammered. */
    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        viewModelScope.launch {
            var delay = RETRY_STEP_MS
            while (retryScheduled && account.value != null) {
                delay(RETRY_STEP_MS)
                retryScheduled = false
                val pending = documents.value.filter { it.syncState == SyncState.FAILED || it.syncState == SyncState.SYNCING }
                if (pending.isEmpty()) break
                var stillFailing = false
                pending.forEach { document ->
                    push(document)
                    if (documents.value.firstOrNull { it.id == document.id }?.syncState == SyncState.FAILED) stillFailing = true
                }
                if (!stillFailing) break
                retryScheduled = true
                delay = (delay * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    /** User-facing retry from the account sheet. */
    fun retrySync() = action {
        cloud.clearError()
        documents.value.filter { it.syncState == SyncState.FAILED || it.syncState == SyncState.LOCAL || it.syncState == SyncState.SYNCING }
            .forEach { push(it) }
        message("Sync retried")
    }

    /**
     * Pull to refresh. Fetches every cloud document, applies what changed, then pushes anything
     * still pending, so the gesture is a full sync rather than a one-way fetch.
     *
     * Realtime listeners only cover the document that is currently open, so this is the only way
     * the library picks up edits made on another device. It reports honestly: a signed-out,
     * unconfigured, or unreachable cloud is named rather than shown as success.
     */
    fun syncNow() = action {
        // Ignore a second gesture while one is already running, so pulls cannot interleave writes.
        if (refreshing) return@action
        if (account.value == null) { message(SyncError.SIGNED_OUT.message); return@action }
        if (!cloudConfigured) { message(SyncError.NOT_CONFIGURED.message); return@action }
        refreshing = true
        syncing = true
        // A stale error from an earlier operation must not be blamed on this pull.
        cloud.clearError()
        try {
            val before = documents.value.associate { it.id to (it.content to it.updatedAt) }
            val remote = cloud.pullAll()
            val pullFailure = cloud.error.value
            if (pullFailure != null) { message(pullFailure.message); return@action }
            val conflicts = applyRemoteBatch(remote)
            val updated = documents.value.count { doc ->
                val previous = before[doc.id]
                previous == null || previous.first != doc.content || previous.second != doc.updatedAt
            }
            documents.value.filter { it.syncState != SyncState.SYNCED && !it.trashed }.forEach { push(it) }
            // The outcome of the push loop is what the reader needs to hear, so it is captured
            // before the share lookup below can overwrite the cloud's last error.
            val pushFailure = cloud.error.value
            // A pull is also when newly shared documents should arrive; the inbox was otherwise
            // only ever read at launch and at sign-in.
            sharesInboxState.value = cloud.sharedWithMe()
            message(syncSummary(updated, conflicts, pushFailure))
        } finally {
            syncing = false
            refreshing = false
        }
    }

    fun create(template: DocumentTemplate) = action {
        val title = if (template.content.text.isEmpty()) "Untitled document" else template.name
        open(store.create(Document(title = title, content = template.content)).await())
    }
    fun importDocument(title: String, content: RichContent) = action { open(store.create(Document(title = title, content = content)).await()) }
    fun edit(content: RichContent) {
        val open = active ?: return
        if (open.readOnly) { message("This document is shared as read-only."); return }
        save(open.copy(content = content, updatedAt = System.currentTimeMillis()))
    }
    fun rename(document: Document, title: String) {
        val next = document.copy(title = title.trim().ifEmpty { "Untitled document" }, updatedAt = System.currentTimeMillis())
        if (active?.id == next.id) save(next) else action { store.save(next).await(); push(next) }
    }
    private fun save(document: Document) {
        active = document
        saveStatus = "Saving…"
        val version = ++generation
        // Enqueue synchronously before another library operation can overtake this edit.
        val saved = store.save(document)
        viewModelScope.launch {
            try {
                saved.await()
                if (generation == version && active?.id == document.id) saveStatus = "Saved on this device"
                push(document)
            } catch (error: Exception) {
                if (generation == version) saveStatus = "Not saved — tap to retry"
                message("Could not save: ${error.message}")
            }
        }
    }
    fun retrySave() { active?.let(::save) }
    fun star(document: Document) = action {
        store.setStarred(document.id, !document.starred).await()
        if (active?.id == document.id) active = active?.copy(starred = !document.starred)
    }
    fun trash(document: Document) = action {
        store.setTrashed(document.id, true).await()
        if (active?.id == document.id) close()
        message("Moved to Trash")
    }
    fun restore(document: Document) = action { store.setTrashed(document.id, false).await(); message("Document restored") }
    /**
     * Deletes a document everywhere: the Firestore copy (with its presence and share index),
     * then the local row. The cloud copy is removed first so a failed cloud delete cannot leave
     * an orphaned remote document that would reappear on the next sync.
     */
    fun delete(document: Document) = action {
        // Only reach for the cloud when there is an account to reach it with. A document synced
        // earlier keeps its remote id after signing out, so without this check a signed-out user
        // would wait on an unreachable cloud copy and the local delete would never run.
        if (shouldDeleteFromCloud(document, signedIn = account.value != null)) {
            if (!cloud.deleteCloudDocument(document.remoteId, document.ownedBy)) {
                message("Could not delete from the cloud. Check your connection, then try again.")
                return@action
            }
        }
        store.delete(document.id).await()
        message("Document deleted")
    }

    /** Removes every document and identifier belonging to this account from the cloud. */
    fun deleteAllCloudData() = action {
        val user = account.value ?: return@action
        syncing = true
        try {
            val removed = cloud.deleteAllCloudData()
            if (removed == null) {
                // The cloud copy is still there, so detaching the local copy would re-upload it on
                // the next sign-in. Leave the library untouched and let the person retry.
                message("Could not reach the cloud. Check your connection, then try again.")
                return@action
            }
            store.clearCloudState().await()
            conflicts.value = emptyMap()
            pendingConflict.value = null
            collaboratorsState.value = emptyMap()
            sharesInboxState.value = emptyList()
            cloud.signOut()
            message("Removed $removed cloud ${if (removed == 1) "document" else "documents"} and your account entries.")
        } finally {
            syncing = false
        }
    }

    /**
     * Permanently removes the account and everything associated with it, on this device and in
     * the cloud.
     *
     * This is the counterpart to signing in, and the path Google Play requires for an app that
     * lets someone create an account: the cloud data goes first, then the local documents, then
     * the Firebase Auth account. The order matters. If the cloud cannot be reached nothing local
     * is removed, because wiping the device copy while a remote copy survives would report a
     * deletion that never fully happened.
     */
    fun deleteAccount() = action {
        val user = account.value ?: return@action
        syncing = true
        try {
            val removed = cloud.deleteAllCloudData()
            if (removed == null) {
                message("Could not reach the cloud. Check your connection, then try again.")
                return@action
            }
            store.wipeLocalData().await()
            conflicts.value = emptyMap()
            pendingConflict.value = null
            collaboratorsState.value = emptyMap()
            sharesInboxState.value = emptyList()
            val accountGone = accounts.deleteAccount()
            active = null
            message(
                if (accountGone) {
                    "Account deleted. Everything Paper Docs held for ${user.email.ifBlank { user.name }} is gone."
                } else {
                    "Your documents and cloud data are deleted. Google could not remove the account " +
                        "itself, so sign in once more and try again."
                }
            )
        } finally {
            syncing = false
        }
    }
    fun duplicate(document: Document) = action {
        val now = System.currentTimeMillis()
        val copy = document.copy(id = UUID.randomUUID().toString(), title = "Copy of ${document.title}", createdAt = now, updatedAt = now, trashed = false, remoteId = "")
        store.create(copy).await()
        message("Copy created")
    }
    suspend fun revisions(id: String) = store.revisions(id).await()
    suspend fun notes(id: String) = store.notes(id).await()
    suspend fun putNote(note: Note) = store.putNote(note).await()
    fun checkpoint() = action { active?.let { store.checkpoint(it.id).await(); message("Version saved") } }

    /** Keeps the local or the cloud copy of a conflicted document. */
    fun resolveConflict(acceptLocal: Boolean) = action {
        val conflict = conflicts.value.values.firstOrNull() ?: return@action
        conflicts.value = conflicts.value - conflict.documentId
        pendingConflict.value = null
        if (acceptLocal) {
            store.resolveConflict(conflict.documentId, true, "", RichContent(), 0L).await()
        } else {
            store.resolveConflict(conflict.documentId, false, conflict.remote.title, conflict.remote.content, conflict.remote.updatedAt).await()
        }
        documents.value.firstOrNull { it.id == conflict.documentId }?.let { push(it) }
        message(if (acceptLocal) "Kept your version and will sync it." else "Switched to the cloud version.")
    }

    // ------------------------------------------------------------------ sharing

    fun loadCollaborators(document: Document) = action {
        if (document.remoteId.isEmpty()) return@action
        collaboratorsState.value = collaboratorsState.value + (document.id to cloud.collaborators(document.remoteId))
    }

    /** Grants a collaborator access by email. */
    fun shareWith(document: Document, email: String, role: ShareRole) = action {
        if (document.remoteId.isEmpty()) { notice("This document has not finished syncing yet."); return@action }
        val uid = cloud.uidForEmail(email)
        if (uid == null) {
            notice("No Paper Docs account is registered for $email. They need to sign in once.")
            return@action
        }
        if (uid == account.value?.uid) {
            notice("That is your own address — you already own this document.")
            return@action
        }
        if (cloud.share(document.remoteId, uid, role)) {
            notice("Shared with $email as a ${role.label.lowercase()}")
            loadCollaborators(document)
        } else {
            notice("Could not share. Your Firestore rules may not allow sharedWith yet.")
        }
    }

    fun revoke(document: Document, collaborator: Collaborator) = action {
        if (cloud.revokeShare(document.remoteId, collaborator.uid)) {
            notice("Access removed")
            loadCollaborators(document)
        } else message("Could not remove that collaborator.")
    }

    fun refreshShares() = action { sharesInboxState.value = cloud.sharedWithMe() }

    /**
     * Opens a document somebody else shared, keeping a local copy that tracks the owner.
     *
     * The owner's copy is fetched before anything is written locally. Creating the local row first
     * stamps it with the current time, which is always newer than the owner's copy, so applyRemote
     * would read the brand new empty row as a conflicting local edit and leave the document blank
     * and flagged as a conflict. Seeding the copy from the fetched change avoids that entirely, and
     * leaves nothing behind when the fetch fails.
     */
    fun openShared(shared: SharedDocument) = action {
        val change = cloud.fetchOne(shared.ownerUid, shared.remoteId)
        if (change == null) { message("That shared document could not be opened."); return@action }
        open(store.create(sharedSeed(shared, change)).await())
    }

    fun dismissShared(shared: SharedDocument) = action { cloud.hideShared(shared.ownerUid, shared.remoteId); refreshShares() }

    // ------------------------------------------------------------------ Drive

    /** Uploads a rendered copy of [document] to Drive and links the two. */
    fun saveToDrive(document: Document, fileName: String, mimeType: String, bytes: ByteArray) = action {
        if (account.value == null) return@action message("Sign in to use Google Drive.")
        if (driveToken.value == null) return@action message("Connect Google Drive first.")
        syncing = true
        try {
            val existing = drive.listFiles(200).firstOrNull { it.name == fileName }
            val id = drive.saveCopy(document, fileName, mimeType, bytes, existing?.id)
            message(if (id == null) "Drive could not save that file." else "Saved a copy in Drive: $fileName")
        } finally { syncing = false }
    }

    suspend fun driveFiles(): List<DriveFile> = if (account.value == null) emptyList() else drive.listFiles()

    /**
     * Downloads a Drive file and stores a local copy. PDF and Google-native Docs formats are not
     * readable by the local importers, so they are rejected with a clear message instead of
     * producing a silently empty document.
     */
    fun importFromDrive(file: DriveFile) = action {
        if (account.value == null) { message("Sign in to use Google Drive."); return@action }
        val supported = setOf(ExportFormat.DOCX.mime, ExportFormat.TXT.mime, ExportFormat.HTML.mime)
        if (file.mimeType !in supported) {
            message("That file type cannot be imported. Try a .docx, .txt, or .html file.")
            return@action
        }
        val bytes = drive.download(file.id)
        if (bytes == null) { message("Could not download that file from Drive."); return@action }
        val format = when (file.mimeType) {
            ExportFormat.DOCX.mime -> ExportFormat.DOCX
            ExportFormat.HTML.mime -> ExportFormat.HTML
            else -> ExportFormat.TXT
        }
        val title = file.name.substringBeforeLast('.', file.name).ifBlank { "Imported document" }
        val content = withContext(Dispatchers.IO) { DocumentFiles.decode(bytes, file.name, file.mimeType) }
        open(store.create(Document(title = title, content = content)).await())
        message("Imported \"$title\" from Drive")
    }

    private companion object {
        const val RETRY_STEP_MS = 5_000L
        const val RETRY_MAX_MS = 120_000L
    }
}

/**
 * Whether deleting [document] must first remove its cloud copy.
 *
 * Kept as a pure function so the rule can be tested: a document that was synced earlier still
 * carries a remote id after signing out, and blocking on a cloud copy nobody can reach is what
 * strands documents in the trash.
 */
internal fun shouldDeleteFromCloud(document: Document, signedIn: Boolean): Boolean =
    signedIn && document.remoteId.isNotEmpty() && document.ownedBy.isEmpty()

private const val CONFLICT_NOTICE = "This document changed in two places. Choose which version to keep."

/**
 * What a manual sync tells the reader.
 *
 * Kept pure and separate from the view model so the wording can be pinned by tests, and so that
 * claiming "updated" is impossible unless documents genuinely changed. Reporting success after a
 * pull that did nothing is the failure mode this guards against.
 */
internal fun syncSummary(updated: Int, conflicts: Int, error: SyncError?): String {
    if (error != null) return error.message
    return when {
        // Conflicts are reported even when nothing was adopted, because choosing a version is
        // the only outstanding action, and [updated] is legitimately zero in that case.
        conflicts > 0 -> "${counted(conflicts, "document")} changed in two places. Choose which version to keep."
        updated > 0 -> "Updated ${counted(updated, "document")} from the cloud"
        else -> "Already up to date"
    }
}

private fun counted(value: Int, noun: String): String = "$value ${if (value == 1) noun else "${noun}s"}"

/**
 * The local row for a shared document, seeded from the owner's copy.
 *
 * [Document.updatedAt] must be the owner's timestamp. A newly created row is stamped with the
 * current time, which is newer than the copy it is meant to mirror, and applyRemote would then
 * treat it as a conflicting local edit: the document stays blank and is flagged as a conflict.
 * Mirroring the remote timestamp is what keeps a shared document readable the moment it opens.
 */
internal fun sharedSeed(shared: SharedDocument, remote: RemoteChange) = Document(
    title = remote.title.ifBlank { shared.title.ifBlank { "Shared document" } },
    content = remote.content,
    updatedAt = remote.updatedAt,
    remoteId = shared.remoteId,
    syncState = SyncState.SYNCED,
    ownedBy = shared.ownerUid,
    role = shared.role,
)
