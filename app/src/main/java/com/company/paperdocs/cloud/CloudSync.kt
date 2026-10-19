package com.company.paperdocs.cloud

import android.util.Log
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import com.company.paperdocs.data.Collaborator
import com.company.paperdocs.data.Document
import com.company.paperdocs.data.ShareRole
import com.company.paperdocs.data.SharedDocument
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.TextMark

/** A collaborator currently viewing a document. */
data class Presence(val uid: String, val name: String, val photoUrl: String?, val viewingAt: Long)

/** An incoming remote change to a document. */
data class RemoteChange(val documentId: String, val title: String, val content: RichContent, val updatedAt: Long, val origin: String?)

/**
 * A document changed both here and in the cloud since the last sync. Both sides are kept so the
 * reader can choose; neither is discarded silently.
 */
data class SyncConflict(
    val documentId: String,
    val local: Document,
    val remote: RemoteChange,
    val detectedAt: Long = System.currentTimeMillis(),
)

/** Why a cloud operation could not complete. */
enum class SyncError(val message: String, val retryable: Boolean) {
    SIGNED_OUT("Sign in to sync your documents.", false),
    NOT_CONFIGURED("Cloud sync is not configured for this build.", false),
    NO_DATABASE("This Firebase project has no Firestore database yet. Create one in the console.", false),
    OFFLINE("No connection. Your changes are saved here and will sync automatically.", true),
    DENIED("The cloud rejected this request. Check that Firestore is enabled and the rules allow it.", false),
    FAILED("Sync failed. Tap retry to try again.", true),
}

/** The result of a push: either the document is in the cloud, or it is not. */
sealed interface PushOutcome {
    data class Uploaded(val remoteId: String) : PushOutcome
    data class Rejected(val error: SyncError, val detail: String = "") : PushOutcome
}

/**
 * Turns a cloud exception into a [SyncError] the UI can explain.
 *
 * Kept separate and free of Android types so it can be unit tested directly.
 */
object SyncErrors {
    fun classify(message: String?, cause: Throwable?): SyncError {
        val text = buildString {
            cause?.message?.let { append(it).append(' ') }
            message?.let { append(it) }
        }.lowercase()
        return when {
            // The project has no database instance yet, which is a setup step, not a fault.
            text.contains("does not exist for project") || text.contains("add a cloud datastore") ||
                text.contains("code=not_found") && text.contains("database") -> SyncError.NO_DATABASE

            // Rejected by the service or the rules: retrying will not help until it is fixed.
            text.contains("permission_denied") || text.contains("permission denied") ||
                text.contains("missing or insufficient permissions") ||
                text.contains("has not been used in project") || text.contains("api has not been enabled") ||
                text.contains("not authorized to access") || text.contains("unimplemented") ||
                text.contains("insufficient permission") -> SyncError.DENIED

            text.contains("unauthenticated") || text.contains("unauthorized") ||
                text.contains("token has expired") || text.contains("expired id token") ||
                text.contains("credential") || text.contains("no current user") ||
                text.contains("401") || text.contains("403") -> SyncError.SIGNED_OUT

            // Transport level problems: worth retrying once the network recovers.
            text.contains("unavailable") || text.contains("network") || text.contains("timed out") ||
                text.contains("timeout") || text.contains("deadline") || text.contains("connect") ||
                text.contains("eai_") || text.contains("socket") || text.contains("resolve") ||
                text.contains("host") || text.contains("offline") || text.contains("503") -> SyncError.OFFLINE

            else -> SyncError.FAILED
        }
    }
}

/**
 * Firestore-backed document sync.
 *
 * Local documents keep their own UUID. Each document carries a `remoteId` that links it to a
 * Firestore document under `users/{uid}/documents/{remoteId}`, so the local database stays the
 * source of truth on-device and the cloud is a mirror plus realtime presence.
 *
 * Every operation reports success or a classified [SyncError]. Nothing here reports success for a
 * write that did not land.
 */
class CloudSync(
    private val account: () -> Account?,
    /** Firestore database id; empty means the project's "(default)" database. */
    private val databaseId: String = "",
) {
    private val _presence = MutableStateFlow<Map<String, List<Presence>>>(emptyMap())
    val presence: StateFlow<Map<String, List<Presence>>> = _presence.asStateFlow()

    private val _changes = MutableSharedFlow<RemoteChange>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<RemoteChange> = _changes.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** The most recent failure, so the UI can explain why something is not synced. */
    private val _error = MutableStateFlow<SyncError?>(null)
    val error: StateFlow<SyncError?> = _error.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val presenceJobs = ConcurrentHashMap<String, ListenerRegistration>()

    private fun db(): FirebaseFirestore? {
        if (account() == null) return null
        return runCatching {
            if (databaseId.isBlank()) FirebaseFirestore.getInstance()
            else FirebaseFirestore.getInstance(databaseId)
        }.getOrNull()
    }

    private fun collection() = collectionFor(account()?.uid.orEmpty())

    /**
     * Documents belonging to [ownerUid]; a shared document lives in its owner's collection.
     * An empty id means the signed-in user's own collection. Returns null rather than building an
     * invalid document reference when there is no account at all.
     */
    private fun collectionFor(ownerUid: String): CollectionReference? {
        val uid = ownerUid.ifBlank { account()?.uid.orEmpty() }
        if (uid.isEmpty()) return null
        return runCatching { db()?.collection("users")?.document(uid)?.collection("documents") }.getOrNull()
    }

    /** Reads one document from an owner's collection, for opening a shared document. */
    suspend fun fetchOne(ownerUid: String, remoteId: String): RemoteChange? {
        val target = collectionFor(ownerUid) ?: return null
        return runCatching {
            val doc = target.document(remoteId).get().await()
            if (doc.exists()) doc.toChange(remoteId) else null
        }.onFailure {
            _error.value = SyncErrors.classify(it.message, it.cause)
        }.getOrNull()
    }

    /** Uploads or updates one document, reporting honestly whether the write landed. */
    suspend fun push(document: Document, remoteId: String?): PushOutcome {
        if (document.readOnly) {
            _error.value = SyncError.FAILED
            return PushOutcome.Rejected(SyncError.FAILED, "This document is shared as read-only")
        }
        val target = collectionFor(document.ownedBy.ifBlank { account()?.uid.orEmpty() })
            ?: return PushOutcome.Rejected(if (account() == null) SyncError.SIGNED_OUT else SyncError.NOT_CONFIGURED)
        val existingId = remoteId?.takeIf { it.isNotBlank() }
        val id = existingId ?: document.id
        return try {
            // The access control list is written only when the document is first created. Sending it
            // on every save would let an editor's push overwrite the owner's sharing list with
            // whatever the local copy happens to hold, and firestore.rules rejects an editor who
            // tries to change sharedWith at all. Merge keeps any field this payload omits, so the
            // ACL is never touched again after creation.
            target.document(id).set(payload(document, includeAcl = existingId == null), SetOptions.merge()).await()
            _error.value = null
            PushOutcome.Uploaded(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = SyncErrors.classify(failure.message, failure.cause)
            Log.w(TAG, "Push failed: $error", failure)
            _error.value = error
            PushOutcome.Rejected(error, failure.message.orEmpty())
        }
    }

    private fun payload(document: Document, includeAcl: Boolean): Map<String, Any?> = buildMap {
        put("localId", document.id)
        put("title", document.title)
        put("content", document.content.text)
        put("marks", document.content.marks.map { mapOf("kind" to it.kind, "start" to it.start, "end" to it.end, "value" to it.value) })
        put("updatedAt", document.updatedAt)
        put("origin", account()?.uid)
        if (includeAcl) put("sharedWith", document.sharedWith)
    }

    /** Fetches all cloud documents for the signed-in user. */
    suspend fun pullAll(): List<Pair<String, RemoteChange>> {
        val target = collection() ?: return emptyList()
        return try {
            target.get().await().documents.map { doc -> doc.id to doc.toChange(doc.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = SyncErrors.classify(failure.message, failure.cause)
            Log.w(TAG, "Pull failed: $error", failure)
            _error.value = error
            emptyList()
        }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toChange(fallbackId: String): RemoteChange {
        val marks = (get("marks") as? List<Map<String, Any?>>).orEmpty().mapNotNull { m ->
            val kind = m["kind"] as? String ?: return@mapNotNull null
            val start = (m["start"] as? Number)?.toInt() ?: return@mapNotNull null
            val end = (m["end"] as? Number)?.toInt() ?: return@mapNotNull null
            TextMark(kind, start, end, m["value"] as? String ?: "")
        }
        return RemoteChange(
            documentId = getString("localId") ?: fallbackId,
            title = getString("title") ?: "Untitled document",
            content = RichContent(getString("content").orEmpty(), marks).validated(),
            updatedAt = (get("updatedAt") as? Number)?.toLong() ?: 0L,
            origin = getString("origin"),
        )
    }

    /** Subscribes to realtime updates for a single open document. */
    fun listen(documentId: String, remoteId: String?, ownerUid: String = "") {
        stopListening(documentId)
        val target = collectionFor(ownerUid.ifBlank { account()?.uid.orEmpty() }) ?: return
        val id = remoteId?.takeIf { it.isNotBlank() } ?: documentId
        val registration = target.document(id).addSnapshotListener { snapshot, error ->
            if (error != null) {
                val classified = SyncErrors.classify(error.message, error.cause)
                Log.w(TAG, "Listen failed: $classified", error)
                _error.value = classified
                return@addSnapshotListener
            }
            val doc = snapshot ?: return@addSnapshotListener
            if (!doc.exists()) return@addSnapshotListener
            _changes.tryEmit(doc.toChange(documentId))
            _connected.value = true
        }
        if (registration != null) listeners[documentId] = registration
    }

    fun stopListening(documentId: String) {
        listeners.remove(documentId)?.remove()
    }

    /** Publishes that this user is viewing a document, and subscribes to co-editors. */
    fun startPresence(documentId: String, remoteId: String?, user: Account, ownerUid: String = "") {
        stopPresence(documentId)
        val target = collectionFor(ownerUid.ifBlank { user.uid }) ?: return
        val id = remoteId?.takeIf { it.isNotBlank() } ?: documentId
        val session = target.document(id).collection("presence").document(user.uid)
        scope.launch {
            runCatching {
                session.set(mapOf("uid" to user.uid, "name" to user.name, "photoUrl" to user.photoUrl, "viewingAt" to System.currentTimeMillis())).await()
            }.onFailure { Log.w(TAG, "Presence write failed", it) }
        }
        val registration = target.document(id).collection("presence").addSnapshotListener { snapshot, error ->
            if (error != null) { Log.w(TAG, "Presence listen failed", error); return@addSnapshotListener }
            val others = snapshot?.documents.orEmpty().mapNotNull { doc ->
                val uid = doc.getString("uid") ?: return@mapNotNull null
                Presence(uid, doc.getString("name") ?: "Guest", doc.getString("photoUrl"), (doc.get("viewingAt") as? Number)?.toLong() ?: 0L)
            }.filter { it.uid != user.uid }
            _presence.value = _presence.value + (documentId to others)
        }
        if (registration != null) presenceJobs[documentId] = registration
    }

    fun stopPresence(documentId: String) {
        presenceJobs.remove(documentId)?.remove()
        _presence.value = _presence.value - documentId
    }

    // ---------------------------------------------------------------- sharing

    /**
     * Records this user's email so collaborators can be found by address.
     *
     * The lookup table is readable by any signed-in user, which means someone could enumerate
     * who uses the app. A Cloud Function that resolves email to uid server side removes that
     * exposure; see docs/CLOUD_SETUP.md.
     *
     * The stored [email] is written exactly as given, never normalised. firestore.rules requires
     * it to equal the caller's own verified ID-token email so that nobody can claim an address
     * they do not own, and [directoryKey] lowercases separately for a stable lookup key. Callers
     * pass FirebaseUser.email, which is the same value the token carries.
     */
    suspend fun publishDirectoryEntry(email: String): Boolean {
        val user = account() ?: return false
        val key = directoryKey(email)
        if (key.isEmpty()) return false
        return runCatching {
            db()?.collection("directory")?.document(key)
                ?.set(mapOf("uid" to user.uid, "email" to email))?.await()
            true
        }.onFailure { Log.w(TAG, "Directory publish failed", it) }.getOrDefault(false)
    }

    /** Resolves a collaborator email to a Firebase uid. */
    suspend fun uidForEmail(email: String): String? {
        val key = directoryKey(email)
        if (key.isEmpty()) return null
        return runCatching { db()?.collection("directory")?.document(key)?.get()?.await()?.getString("uid") }.getOrNull()
    }

    private fun directoryKey(email: String): String {
        val clean = email.trim().lowercase()
        if (clean.isEmpty() || !clean.contains('@')) return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(clean.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    /** Grants [targetUid] a role on a document owned by the signed-in user. */
    suspend fun share(remoteId: String, targetUid: String, role: ShareRole): Boolean {
        val store = db() ?: return false
        val user = account() ?: return false
        if (targetUid == user.uid) {
            _error.value = SyncError.FAILED
            return false
        }
        return runCatching {
            val doc = store.collection("users").document(user.uid).collection("documents").document(remoteId)
            val current = (doc.get().await().get("sharedWith") as? Map<String, Any?>).orEmpty().toMutableMap()
            current[targetUid] = role.name
            doc.update("sharedWith", current).await()
            // A per-person entry doubles as a searchable index for the recipient.
            doc.collection("sharedWith").document(targetUid)
                .set(mapOf("uid" to targetUid, "role" to role.name, "title" to (doc.get().await().getString("title") ?: "")))
                .await()
            true
        }.onFailure {
            Log.w(TAG, "Share failed", it)
            _error.value = SyncErrors.classify(it.message, it.cause)
        }.getOrDefault(false)
    }

    /** Removes a collaborator's access. */
    suspend fun revokeShare(remoteId: String, targetUid: String): Boolean {
        val store = db() ?: return false
        val user = account() ?: return false
        return runCatching {
            val doc = store.collection("users").document(user.uid).collection("documents").document(remoteId)
            val current = (doc.get().await().get("sharedWith") as? Map<String, Any?>).orEmpty().toMutableMap()
            current.remove(targetUid)
            doc.update("sharedWith", current).await()
            doc.collection("sharedWith").document(targetUid).delete().await()
            true
        }.onFailure { Log.w(TAG, "Revoke failed", it) }.getOrDefault(false)
    }

    /** Lists who a document is shared with. */
    suspend fun collaborators(remoteId: String): List<Collaborator> {
        val store = db() ?: return emptyList()
        val user = account() ?: return emptyList()
        return runCatching {
            collectionFor(user.uid)?.document(remoteId)?.get()?.await()?.get("sharedWith") as? Map<String, Any?>
        }.getOrNull()?.mapNotNull { (uid, value) ->
            if (value == null) null else Collaborator(uid, ShareRole.parse(value.toString()))
        } ?: emptyList()
    }

    /** Finds documents other people have shared with the signed-in user. */
    suspend fun sharedWithMe(): List<SharedDocument> {
        val store = db() ?: return emptyList()
        val user = account() ?: return emptyList()
        return runCatching {
            store.collectionGroup("sharedWith").whereEqualTo("uid", user.uid).get().await().documents.map { doc ->
                // /users/{owner}/documents/{docId}/sharedWith/{personUid}
                val path = doc.reference.path.split("/")
                SharedDocument(
                    ownerUid = path.getOrElse(1) { "" },
                    remoteId = path.getOrElse(3) { "" },
                    title = doc.getString("title").orEmpty(),
                    role = ShareRole.parse(doc.getString("role")),
                )
            }.filter { it.ownerUid.isNotEmpty() && it.remoteId.isNotEmpty() }
        }.onFailure {
            Log.w(TAG, "Shared lookup failed", it)
            _error.value = SyncErrors.classify(it.message, it.cause)
        }.getOrDefault(emptyList())
    }

    /** Removes a document from your list of documents that others shared. */
    suspend fun hideShared(ownerUid: String, remoteId: String): Boolean {
        val store = db() ?: return false
        val user = account() ?: return false
        return runCatching {
            store.collection("users").document(ownerUid).collection("documents").document(remoteId)
                .collection("sharedWith").document(user.uid).delete().await()
            true
        }.onFailure { Log.w(TAG, "Hide shared failed", it) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- deletion

    /**
     * Deletes a document and everything attached to it from the cloud.
     *
     * Subcollections are not removed by a parent delete, so presence and share index entries are
     * removed explicitly. This is what "delete document" means to someone deleting their data.
     */
    suspend fun deleteCloudDocument(remoteId: String, ownerUid: String = ""): Boolean {
        val doc = collectionFor(ownerUid)?.document(remoteId) ?: return false
        // Subcollection cleanup is best effort. Security rules do not cascade, so older rule sets
        // can deny these reads, and that must not stop the document itself from being deleted.
        runCatching { doc.collection("presence").get().await().documents.forEach { it.reference.delete().await() } }
            .onFailure { Log.w(TAG, "Presence cleanup skipped", it) }
        runCatching { doc.collection("sharedWith").get().await().documents.forEach { it.reference.delete().await() } }
            .onFailure { Log.w(TAG, "Share index cleanup skipped", it) }
        return runCatching {
            doc.delete().await()
            true
        }.onFailure {
            Log.w(TAG, "Cloud delete failed", it)
            _error.value = SyncErrors.classify(it.message, it.cause)
        }.getOrDefault(false)
    }

    /** Removes this account's directory lookup entry. */
    suspend fun deleteDirectoryEntry(email: String): Boolean {
        val key = directoryKey(email)
        if (key.isEmpty()) return false
        return runCatching { db()?.collection("directory")?.document(key)?.delete()?.await(); true }
            .onFailure { Log.w(TAG, "Directory delete failed", it) }
            .getOrDefault(false)
    }

    /**
     * Removes every trace of the signed-in account from the cloud: all documents under their
     * own uid, their directory entry, and any presence or share index entries pointing at them.
     *
     * Returns the number of documents removed, or null when the bulk delete did not run to
     * completion. Callers must not report success or drop the local copy on a null: the whole
     * operation is offline-capable of failing, and silently continuing would leave a cloud copy
     * of data the user believes they deleted.
     */
    suspend fun deleteAllCloudData(): Int? {
        val store = db() ?: return null
        val user = account() ?: return null
        var removed = 0
        val ok = runCatching {
            val documents = store.collection("users").document(user.uid).collection("documents").get().await().documents
            documents.forEach { snapshot ->
                val doc = snapshot.reference
                doc.collection("presence").get().await().documents.forEach { it.reference.delete().await() }
                doc.collection("sharedWith").get().await().documents.forEach { it.reference.delete().await() }
                doc.delete().await()
                removed++
            }
            user.email.takeIf { it.isNotEmpty() }?.let { deleteDirectoryEntry(it) }
            // Drop presence and share index entries this account holds in other people's documents.
            store.collectionGroup("presence").whereEqualTo("uid", user.uid).get().await().documents
                .forEach { it.reference.delete().await() }
            store.collectionGroup("sharedWith").whereEqualTo("uid", user.uid).get().await().documents
                .forEach { it.reference.delete().await() }
        }.onFailure {
            Log.w(TAG, "Bulk cloud delete failed", it)
            _error.value = SyncErrors.classify(it.message, it.cause)
        }.isSuccess
        return if (ok) removed else null
    }

    fun signOut() {
        listeners.values.forEach { runCatching { it.remove() } }
        listeners.clear()
        presenceJobs.values.forEach { runCatching { it.remove() } }
        presenceJobs.clear()
        _presence.value = emptyMap()
        _changes.resetReplayCache()
        _connected.value = false
        _error.value = null
    }

    fun clearError() { _error.value = null }

    private companion object { const val TAG = "CloudSync" }
}