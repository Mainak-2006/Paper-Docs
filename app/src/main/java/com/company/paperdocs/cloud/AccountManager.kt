package com.company.paperdocs.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A signed-in user, or null while the app works offline. */
data class Account(val uid: String, val name: String, val email: String, val photoUrl: String?) {
    val initial: String get() = name.trim().take(1).uppercase().ifEmpty { email.take(1).uppercase() }.ifEmpty { "?" }
}

/**
 * Google sign-in backed by Firebase Auth.
 *
 * Without a Firebase project the app still builds and runs: [configured] is false, sign-in
 * reports a clear setup message, and everything else keeps working against the local store.
 */
class AccountManager(private val context: Context) {
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * True until Firebase has reported the persisted session. The UI shows a brief restoring
     * state instead of flashing "sign in" at someone who is already signed in.
     */
    private val _restoring = MutableStateFlow(configuredLazy())
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    /** True when a Firebase project is present and usable. */
    val configured: Boolean = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    private val auth: FirebaseAuth? = if (configured) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    init {
        // Firebase Auth persists the session on-device by default (setPersistence was removed in
        // Auth 24), so the user stays signed in across restarts until they sign out explicitly.
        if (auth != null) {
            // AuthStateListener reports the FirebaseAuth instance; the current user comes from it.
            auth.addAuthStateListener { firebase ->
                _account.value = toAccount(firebase.currentUser)
                _restoring.value = false
            }
        } else {
            _restoring.value = false
            if (configured) Log.w(TAG, "Firebase is configured but unavailable on this device")
        }
    }

    private fun configuredLazy() = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /** Signs in with a Google ID token obtained through the Credential Manager. */
    suspend fun signInWithGoogle(activity: Activity) {
        _message.value = null
        if (!requireFirebase()) return
        val clientId = clientId()
        if (clientId.isNullOrEmpty()) {
            emit("Add a Web OAuth client id to app/google-services.json — see docs/CLOUD_SETUP.md.")
            return
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (error: Exception) {
            emit(describe(error))
            return
        }
        val googleCredential = credential.asGoogleIdToken()
        if (googleCredential == null) {
            emit("That sign-in could not be completed.")
            return
        }
        val idToken = googleCredential.idToken
        if (idToken.isNullOrEmpty()) {
            emit("Google did not return a sign-in token.")
            return
        }
        val firebase = auth ?: return
        run { firebase.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await() }
    }

    /**
     * Newer Play services return the credential as a generic [CustomCredential] rather than the
     * typed class, because googleid is built against an older credentials artifact. Rebuild the
     * typed credential through its public factory instead of failing the `is` check.
     */
    private fun Credential.asGoogleIdToken(): GoogleIdTokenCredential? = when (this) {
        is GoogleIdTokenCredential -> this
        is CustomCredential ->
            if (type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                runCatching { GoogleIdTokenCredential.createFrom(data) }.getOrNull()
            } else {
                null
            }
        else -> null
    }

    /** Clears the current message, for example when the account sheet closes. */
    fun dismissMessage() { _message.value = null }

    fun signOut() {
        auth?.signOut()
        _account.value = null
    }

    /**
     * Deletes the Firebase Auth account itself.
     *
     * This is deliberately separate from [signOut]. Signing out only drops the session and leaves
     * the account and its data in place, which is what someone who wants to come back later
     * wants. Google Play requires an app that lets someone create an account to also let them
     * delete it, so the destructive path calls this once the cloud and local copies are gone.
     *
     * Callers are responsible for removing the account's data first: deleting the account leaves
     * the Firestore documents and the device database untouched.
     *
     * Returns true when the account is gone. A false result usually means the user has to
     * re-authenticate first, which Firebase demands when the session has been idle too long.
     */
    suspend fun deleteAccount(): Boolean {
        val user = auth?.currentUser ?: return false
        return runCatching {
            user.delete().await()
            // The auth state listener already clears the account; signOut makes it immediate and
            // covers the case where that listener has not been dispatched yet.
            signOut()
            true
        }.onFailure { Log.w(TAG, "Account deletion failed", it) }.getOrDefault(false)
    }

    /** Returns a fresh OAuth access token for Google Drive, or null when unavailable. */
    suspend fun driveAccessToken(): String? {
        val user = auth?.currentUser ?: return null
        return runCatching { user.getIdToken(true).await().token }.getOrNull()
    }

    private fun requireFirebase(): Boolean {
        if (!configured) {
            emit("Add app/google-services.json to enable cloud features.")
            return false
        }
        return true
    }

    private fun clientId(): String? = runCatching {
        context.resources.getString(com.company.paperdocs.R.string.default_web_client_id)
    }.getOrNull()


    private fun emit(text: String) {
        _message.value = text
        _account.value = toAccount(auth?.currentUser)
    }

    private fun toAccount(user: FirebaseUser?): Account? = user?.let {
        Account(it.uid, it.displayName ?: "Paper Docs user", it.email.orEmpty(), it.photoUrl?.toString())
    }

    private suspend fun run(block: suspend () -> Unit) {
        _busy.value = true
        _message.value = null
        try {
            block()
            _account.value = toAccount(auth?.currentUser)
        } catch (error: Exception) {
            emit(describe(error))
        } finally {
            _busy.value = false
        }
    }

    /**
     * Turns a sign-in failure into something worth showing a person.
     *
     * Raw exception text is never shown. These exceptions carry package-qualified class names and
     * internal status codes that mean nothing to the reader, so the detail is logged and the
     * message returned here says what to do instead. Type checks come first because they are
     * reliable; the message-string checks are a fallback for errors that only Google identifies
     * by their text.
     */
    private fun describe(error: Exception): String {
        val message = error.message.orEmpty()
        Log.w(TAG, "Sign-in failed", error)
        return when {
            error is NoCredentialException ->
                "No Google account is available on this device. Add one in Settings → Accounts, then try again."
            error is GetCredentialProviderConfigurationException ->
                "Google Play services is missing or out of date. Update it, then try again."
            error is GetCredentialCancellationException || message.contains("cancel", true) ->
                "The Google sign-in window closed without picking an account. Check that a Google " +
                    "account is added in Settings → Accounts, then try again."
            error is GetCredentialInterruptedException ->
                "Sign-in was interrupted. Please try again."
            message.contains("network", true) || message.contains("unavailable", true) ->
                "No internet connection. Check your network and try again."
            message.contains("ApiException", true) && message.contains("125", true) ->
                "Google rejected this app. Register its SHA-1 fingerprint in the Firebase console — see docs/CLOUD_SETUP.md."
            message.contains("28444", true) || message.contains("Developer console is not set up correctly", true) ->
                "Firebase rejected this app's credentials. Add this build's SHA-1 fingerprint in the " +
                    "Firebase console, then re-download google-services.json — see docs/CLOUD_SETUP.md."
            message.contains("too many requests", true) ->
                "Too many attempts. Wait a minute and try again."
            else -> "Sign-in could not be completed. Please try again."
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("The request failed"))
        }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object { const val TAG = "AccountManager" }
}