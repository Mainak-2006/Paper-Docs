package com.company.paperdocs.cloud

import android.util.Log
import com.company.paperdocs.data.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A file discovered in the user's Drive. */
data class DriveFile(val id: String, val name: String, val modifiedTime: String, val mimeType: String, val size: Long)

/**
 * Google Drive sync for exported copies of documents, over the Drive REST v3 API using an OAuth
 * access token from Firebase Auth. Writes carry `appProperties.paperDocsLocalId` so a Drive copy
 * can always be traced back to the local document it came from.
 */
class DriveSync(private val token: suspend () -> String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Lists Paper Docs related files, newest first. */
    suspend fun listFiles(limit: Int = 50): List<DriveFile> = withContext(Dispatchers.IO) {
        val mimeTypes = listOf(
            "application/vnd.google-apps.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain", "text/html", "application/pdf",
        ).joinToString(",") { "'$it'" }
        val query = "trashed = false and (mimeType in ($mimeTypes) or appProperties has { key='paperDocsLocalId' and value='*' })"
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?q=${query.urlEncoded()}" +
            "&orderBy=${"modifiedTime desc".urlEncoded()}" +
            "&pageSize=$limit" +
            "&fields=${"files(id,name,modifiedTime,mimeType,size,appProperties)".urlEncoded()}" +
            "&supportsAllDrives=true&includeItemsFromAllDrives=true"
        call(Request.Builder().url(url).get().build())?.let { body ->
            runCatching {
                val files = JSONObject(body).optJSONArray("files") ?: return@runCatching emptyList<DriveFile>()
                (0 until files.length()).map { index ->
                    val item = files.getJSONObject(index)
                    DriveFile(
                        id = item.getString("id"),
                        name = item.optString("name"),
                        modifiedTime = item.optString("modifiedTime"),
                        mimeType = item.optString("mimeType"),
                        size = item.optString("size").toLongOrNull() ?: 0L,
                    )
                }
            }.onFailure { Log.w(TAG, "Drive list parse failed", it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** Creates or updates the Drive copy of [document]; returns the Drive file id. */
    suspend fun saveCopy(
        document: Document,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        existingId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("name", fileName)
            .put("mimeType", mimeType)
            .put("appProperties", JSONObject().put("paperDocsLocalId", document.id))

        val request = if (existingId != null) {
            // Drive updates content and metadata together through the multipart upload endpoint.
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=multipart&fields=id,name,modifiedTime&supportsAllDrives=true")
                .header("Authorization", bearer() ?: return@withContext null)
                .post(buildMultipart(metadata, bytes, mimeType).toRequestBody("multipart/related; boundary=$BOUNDARY".toMediaType()))
        } else {
            val multipart = buildMultipart(metadata, bytes, mimeType)
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,modifiedTime,mimeType,size&supportsAllDrives=true")
                .header("Authorization", bearer() ?: return@withContext null)
                .post(multipart.toRequestBody("multipart/related; boundary=$BOUNDARY".toMediaType()))
        }
        call(request.build())?.let { runCatching { JSONObject(it).optString("id").takeIf(String::isNotEmpty) }.getOrNull() }
    }

    /** Downloads a file's bytes. */
    suspend fun download(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
            .header("Authorization", bearer() ?: return@withContext null)
            .get().build()
        runCatching { client.newCall(request).execute() }.getOrNull()?.use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }

    /** Moves a file to the Drive trash. */
    suspend fun trash(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?supportsAllDrives=true")
            .header("Authorization", bearer() ?: return@withContext false)
            .delete().build()
        runCatching { client.newCall(request).execute() }.getOrNull()?.use { it.isSuccessful } ?: false
    }

    /** Renames a Drive file. */
    suspend fun rename(fileId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", newName).toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?supportsAllDrives=true")
            .header("Authorization", bearer() ?: return@withContext false)
            .patch(body).build()
        runCatching { client.newCall(request).execute() }.getOrNull()?.use { it.isSuccessful } ?: false
    }

    private fun buildMultipart(metadata: JSONObject, bytes: ByteArray, mimeType: String?): ByteArray {
        val head = buildString {
            append("--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--$BOUNDARY\r\n")
            append("Content-Type: ${mimeType ?: "application/octet-stream"}\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$BOUNDARY--\r\n".toByteArray(Charsets.UTF_8)
        return head + bytes + tail
    }

    private suspend fun bearer(): String? = token()

    private suspend fun call(request: Request): String? =
        runCatching { client.newCall(request).execute() }.getOrNull()?.use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) text
            else {
                Log.w(TAG, "Drive request failed: ${response.code} $text")
                null
            }
        }

    /** Percent-encodes a query value. URLEncoder is form encoding and would emit '+' for spaces. */
    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private companion object {
        const val TAG = "DriveSync"
        const val BOUNDARY = "paperdocs_boundary"
    }
}