package com.company.paperdocs.cloud

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/**
 * Obtains the OAuth 2 access token that the Drive REST API requires.
 *
 * Firebase returns an ID token whose audience is the Firebase project, which Google APIs reject
 * with `401 CREDENTIALS_MISSING`. Drive therefore needs its own consent grant, obtained through
 * [AuthorizationClient]. The scopes are the minimum that lets Paper Docs list, upload, download
 * and trash the files it creates.
 *
 * Consent happens once; afterwards [authorize] resolves straight to a cached token.
 */
class DriveAuth(private val activity: Activity) {
    private val client: AuthorizationClient by lazy { Identity.getAuthorizationClient(activity) }

    private val scopes = listOf(Scope(DRIVE_FILE_SCOPE), Scope(DRIVE_FILE_READONLY_SCOPE))

    /** Resumes when the activity delivers the consent result. */
    private var pending: kotlin.coroutines.Continuation<String?>? = null

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.Builder().setRequestedScopes(scopes).build()

    /**
     * Returns a Drive access token, running the consent flow when it has not been granted yet.
     *
     * [launcher] must be started from an activity that is currently resumed; the app passes the
     * ActivityResultLauncher that receives the consent result.
     */
    suspend fun authorize(launchConsent: (android.app.PendingIntent) -> Unit): String? {
        val result = runCatching { client.authorize(request()).await() }
            .onFailure { Log.w(TAG, "Drive authorization failed", it) }
            .getOrNull()
        result?.accessToken?.takeIf { it.isNotBlank() }?.let { return it }

        val consent = result?.pendingIntent
        if (consent == null) {
            Log.w(TAG, "Drive authorization returned neither a token nor a consent request")
            return null
        }
        return awaitConsent(consent, launchConsent)
    }

    /** Returns an already-granted token without ever showing UI. */
    suspend fun cachedToken(): String? =
        runCatching { client.authorize(request()).await().accessToken }
            .onFailure { Log.w(TAG, "Cached token lookup failed", it) }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun awaitConsent(
        consent: android.app.PendingIntent,
        launchConsent: (android.app.PendingIntent) -> Unit,
    ): String? = suspendCancellableCoroutine { continuation ->
        if (pending != null) {
            // A consent request is already in flight; ignore the second tap.
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        pending = continuation
        runCatching { launchConsent(consent) }.onFailure {
            Log.w(TAG, "Could not start the Drive consent screen", it)
            pending = null
            continuation.resume(null)
        }
    }

    /**
     * Called with the activity result of the consent flow. Parses it into an access token.
     */
    fun onConsentResult(data: Intent?): String? {
        val continuation = pending ?: return null
        pending = null
        val token = runCatching { client.getAuthorizationResultFromIntent(data).accessToken }
            .onFailure { Log.w(TAG, "Could not read the Drive consent result", it) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        continuation.resume(token)
        return token
    }

    /** Drops any in-flight consent so a fresh one can be requested. */
    fun signOut() {
        pending = null
    }

    companion object {
        /** Read/write access limited to files this app creates. */
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        /** Read access, requested alongside the above so existing Paper Docs files are listed. */
        const val DRIVE_FILE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

        private const val TAG = "DriveAuth"
    }
}