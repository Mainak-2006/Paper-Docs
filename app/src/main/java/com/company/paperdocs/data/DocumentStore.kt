package com.company.paperdocs.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.company.paperdocs.editor.RichTextCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single ordered writer keeps typing, renames, and library actions from racing. */
class DocumentStore(context: Context, name: String = "paper-docs.db") {
    private val helper = Database(context, name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    val documents = _documents.asStateFlow()

    init { scope.launch { for (command in commands) command() } }

    private fun <T> execute(action: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        check(commands.trySend {
            try { result.complete(action()) } catch (failure: Exception) { result.completeExceptionally(failure) }
        }.isSuccess)
        return result
    }

    fun initialize(): Deferred<Unit> = execute {
        val db = helper.writableDatabase
        db.rawQuery("SELECT value FROM settings WHERE name='initialized'", null).use { cursor ->
            if (!cursor.moveToFirst()) {
                transaction(db) {
                    insert(db, Templates.welcome)
                    db.execSQL("INSERT INTO settings(name,value) VALUES('initialized','1')")
                }
            }
        }
        refresh()
    }

    fun create(document: Document): Deferred<Document> = execute {
        insert(helper.writableDatabase, document)
        refresh()
        document
    }

    fun save(document: Document): Deferred<Unit> = execute {
        val db = helper.writableDatabase
        transaction(db) {
            val old = get(db, document.id) ?: error("This document no longer exists")
            if (old.content != document.content || old.title != document.title) {
                val last = db.rawQuery("SELECT MAX(created_at) FROM revisions WHERE document_id=?", arrayOf(document.id)).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
                if (System.currentTimeMillis() - last > 60_000) snapshot(db, old)
                db.update("documents", ContentValues().apply {
                    put("title", document.title)
                    put("content", RichTextCodec.toJson(document.content))
                    put("updated_at", document.updatedAt)
                }, "id=?", arrayOf(document.id))
            }
            // A local edit to a synced document marks it for re-upload.
            if (old.content != document.content || old.title != document.title) {
                markSync(db, document.id, SyncState.SYNCING)
            }
        }
        refresh()
    }

    /** Records the Firestore id of a document once it has been pushed. */
    fun markRemote(id: String, remoteId: String, state: SyncState = SyncState.SYNCED): Deferred<Unit> = execute {
        helper.writableDatabase.update("documents", ContentValues().apply {
            put("remote_id", remoteId)
            put("sync_state", state.name)
        }, "id=?", arrayOf(id))
        refresh()
    }

    /** Applies a remote change, keeping the newer version and flagging a genuine conflict. */
    fun applyRemote(id: String, remoteId: String, title: String, content: RichContent, remoteUpdatedAt: Long): Deferred<SyncState> = execute {
        val db = helper.writableDatabase
        var outcome = SyncState.SYNCED
        transaction(db) {
            val local = get(db, id)
            when {
                local == null -> insert(db, Document(id = id, title = title, content = content, updatedAt = remoteUpdatedAt, remoteId = remoteId))
                local.content == content && local.title == title ->
                    db.update("documents", syncValues(remoteId, SyncState.SYNCED), "id=?", arrayOf(id))
                local.updatedAt <= remoteUpdatedAt -> {
                    // Remote is newer: take it.
                    db.update("documents", ContentValues().apply {
                        put("title", title)
                        put("content", RichTextCodec.toJson(content))
                        put("updated_at", remoteUpdatedAt)
                        put("remote_id", remoteId)
                        put("sync_state", SyncState.SYNCED.name)
                    }, "id=?", arrayOf(id))
                }
                else -> {
                    // Both changed since the last sync; keep both and let the writer choose.
                    snapshot(db, local)
                    outcome = SyncState.CONFLICT
                    db.update("documents", syncValues(remoteId, SyncState.CONFLICT), "id=?", arrayOf(id))
                }
            }
        }
        refresh()
        outcome
    }

    /** Resolves a conflict by accepting one side, either the cloud copy or the local one. */
    fun resolveConflict(id: String, acceptLocal: Boolean, remoteTitle: String, remoteContent: RichContent, remoteUpdatedAt: Long): Deferred<Unit> = execute {
        val db = helper.writableDatabase
        transaction(db) {
            val local = get(db, id)
            if (!acceptLocal && local != null) snapshot(db, local)
            val values = if (acceptLocal) syncValues(local?.remoteId.orEmpty(), SyncState.SYNCING)
            else ContentValues().apply {
                put("title", remoteTitle)
                put("content", RichTextCodec.toJson(remoteContent))
                put("updated_at", remoteUpdatedAt)
                put("sync_state", SyncState.SYNCED.name)
            }
            db.update("documents", values, "id=?", arrayOf(id))
        }
        refresh()
    }

    private fun syncValues(remoteId: String, state: SyncState) = ContentValues().apply {
        put("remote_id", remoteId)
        put("sync_state", state.name)
    }

    private fun markSync(db: SQLiteDatabase, id: String, state: SyncState) {
        db.update("documents", ContentValues().apply { put("sync_state", state.name) }, "id=?", arrayOf(id))
    }

    fun checkpoint(documentId: String): Deferred<Unit> = execute {
        val db = helper.writableDatabase
        transaction(db) { get(db, documentId)?.let { snapshot(db, it) } }
    }

    fun setStarred(id: String, starred: Boolean) = updateFlag(id, "starred", starred)
    fun setTrashed(id: String, trashed: Boolean) = updateFlag(id, "trashed", trashed)
    private fun updateFlag(id: String, column: String, value: Boolean): Deferred<Unit> = execute {
        helper.writableDatabase.update("documents", ContentValues().apply { put(column, if (value) 1 else 0) }, "id=?", arrayOf(id))
        refresh()
    }

    fun delete(id: String): Deferred<Unit> = execute {
        helper.writableDatabase.delete("documents", "id=? AND trashed=1", arrayOf(id))
        refresh()
    }

    /**
     * Detaches every local document from the cloud after the account's data has been deleted,
     * so the library stays intact but nothing is re-uploaded.
     */
    fun clearCloudState(): Deferred<Unit> = execute {
        val db = helper.writableDatabase
        transaction(db) {
            db.execSQL("UPDATE documents SET remote_id='', sync_state='LOCAL', owned_by=''")
        }
        refresh()
    }

    /**
     * Removes every document from the device, for "delete my account and data".
     *
     * Notes and revision snapshots are removed by the foreign keys declared on those tables, so
     * clearing the documents is enough to leave nothing of this account behind locally. This is
     * not reversible and is deliberately not exposed to the ordinary delete action, which is a
     * two-step trash-and-empty that a single tap cannot trigger.
     */
    fun wipeLocalData(): Deferred<Unit> = execute {
        helper.writableDatabase.delete("documents", null, null)
        refresh()
    }

    fun revisions(id: String): Deferred<List<Revision>> = execute {
        helper.readableDatabase.rawQuery("SELECT * FROM revisions WHERE document_id=? ORDER BY id DESC", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Revision(cursor.long("id"), id, cursor.string("title"), RichTextCodec.fromJson(cursor.string("content")), cursor.long("created_at"))) }
        }
    }

    fun notes(id: String): Deferred<List<Note>> = execute {
        helper.readableDatabase.rawQuery("SELECT * FROM notes WHERE document_id=? ORDER BY created_at DESC", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Note(cursor.string("id"), id, cursor.string("quote"), cursor.string("body"), cursor.long("resolved") != 0L, cursor.long("created_at"))) }
        }
    }

    fun putNote(note: Note): Deferred<Unit> = execute {
        helper.writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", note.id); put("document_id", note.documentId); put("quote", note.quote)
            put("body", note.body); put("resolved", if (note.resolved) 1 else 0); put("created_at", note.createdAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    /** A barrier used by lifecycle callers and integration tests. */
    fun flush(): Deferred<Unit> = execute { Unit }
    fun close(): Deferred<Unit> = execute { helper.close(); commands.close() }

    private fun refresh() {
        _documents.value = helper.readableDatabase.rawQuery("SELECT * FROM documents ORDER BY updated_at DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.document()) }
        }
    }
    private fun get(db: SQLiteDatabase, id: String): Document? = db.rawQuery("SELECT * FROM documents WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.document() else null }
    private fun insert(db: SQLiteDatabase, document: Document) {
        db.insertOrThrow("documents", null, ContentValues().apply {
            put("id", document.id); put("title", document.title); put("content", RichTextCodec.toJson(document.content))
            put("created_at", document.createdAt); put("updated_at", document.updatedAt)
            put("starred", if (document.starred) 1 else 0); put("trashed", if (document.trashed) 1 else 0)
            put("remote_id", document.remoteId); put("sync_state", document.syncState.name)
            put("shared_with", encodeShared(document.sharedWith)); put("owned_by", document.ownedBy); put("role", document.role.name)
        })
    }
    private fun snapshot(db: SQLiteDatabase, document: Document) {
        val encoded = RichTextCodec.toJson(document.content)
        val identical = db.rawQuery("SELECT title,content FROM revisions WHERE document_id=? ORDER BY id DESC LIMIT 1", arrayOf(document.id)).use {
            it.moveToFirst() && it.getString(0) == document.title && it.getString(1) == encoded
        }
        if (identical) return
        db.insertOrThrow("revisions", null, ContentValues().apply {
            put("document_id", document.id); put("title", document.title); put("content", encoded); put("created_at", System.currentTimeMillis())
        })
        db.execSQL("DELETE FROM revisions WHERE document_id=? AND id NOT IN (SELECT id FROM revisions WHERE document_id=? ORDER BY id DESC LIMIT 30)", arrayOf(document.id, document.id))
    }
    private inline fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.document() = Document(
            string("id"), string("title"), RichTextCodec.fromJson(string("content")),
            long("created_at"), long("updated_at"), long("starred") != 0L, long("trashed") != 0L,
            remoteId = stringOrEmpty("remote_id"),
            syncState = runCatching { SyncState.valueOf(stringOrEmpty("sync_state").ifEmpty { "LOCAL" }) }.getOrDefault(SyncState.LOCAL),
            sharedWith = decodeShared(stringOrEmpty("shared_with")),
            ownedBy = stringOrEmpty("owned_by"),
            role = ShareRole.parse(stringOrEmpty("role").ifEmpty { "EDITOR" }),
        )

    private fun encodeShared(shared: Map<String, ShareRole>): String =
        org.json.JSONObject().apply { shared.forEach { (uid, role) -> put(uid, role.name) } }.toString()

    private fun decodeShared(raw: String): Map<String, ShareRole> {
        if (raw.isEmpty()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            json.keys().asSequence().associateWith { uid -> ShareRole.parse(json.optString(uid)) }
        }.getOrDefault(emptyMap())
    }

    private fun Cursor.stringOrEmpty(name: String): String =
        runCatching { getString(getColumnIndexOrThrow(name)) }.getOrNull().orEmpty()

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 4) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, starred INTEGER NOT NULL DEFAULT 0, trashed INTEGER NOT NULL DEFAULT 0, remote_id TEXT NOT NULL DEFAULT '', sync_state TEXT NOT NULL DEFAULT 'LOCAL', shared_with TEXT NOT NULL DEFAULT '{}', owned_by TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT 'EDITOR')")
            db.execSQL("CREATE TABLE revisions(id INTEGER PRIMARY KEY AUTOINCREMENT, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, title TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX revisions_document ON revisions(document_id)")
            db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, quote TEXT NOT NULL, body TEXT NOT NULL, resolved INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE documents ADD COLUMN remote_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN sync_state TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("ALTER TABLE documents ADD COLUMN shared_with TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE documents ADD COLUMN owned_by TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN role TEXT NOT NULL DEFAULT 'EDITOR'")
            }
        }
    }
}
