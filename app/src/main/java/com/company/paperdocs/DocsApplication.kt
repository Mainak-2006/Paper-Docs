package com.company.paperdocs

import android.app.Application
import android.util.Log
import com.company.paperdocs.data.DocumentStore
import com.google.firebase.FirebaseApp

class DocsApplication : Application() {
    val documents by lazy { DocumentStore(this) }

    override fun onCreate() {
        super.onCreate()
        installAppCheck()
    }

    /**
     * Registers App Check so Firestore can require a proof that this really is the Paper Docs app.
     *
     * Without it the security rules are the only boundary, and any signed-in Google account can
     * script against Firestore directly. App Check does not add a rule of its own: it makes the
     * caller prove app integrity before the rules are consulted, and the rules already refuse
     * anything unauthenticated.
     *
     * ## This is only the client half
     *
     * Attaching a provider is not enough on its own. Until the app is registered in
     * **Firebase console → App Check** and enforcement is switched on for Firestore, this call
     * does nothing observable. That ordering matters in the other direction too: **switch
     * enforcement on before a build containing this ships and every Firestore call in the app
     * starts failing** -- as sync failures with no crash, since Paper Docs ships no crash
     * reporting and simply shows documents as not synced. Register first, then enable enforcement
     * only once a build with this code is in the Play rollout.
     *
     * Debug builds use the debug provider so instrumentation and local runs can register the
     * printed token in the console. Release builds use Play Integrity, which requires the app to be
     * installed from Play; a sideloaded release APK will fail integrity, which is expected.
     */
    private fun installAppCheck() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            // No Firebase project, so no cloud features and nothing for App Check to attest.
            return
        }
        // The provider differs per build type, so it lives in the debug and release source sets
        // rather than behind a BuildConfig branch: the debug provider is a debugImplementation
        // dependency and is genuinely absent from a release classpath.
        AppCheckSetup.install()
        Log.i(TAG, "App Check installed with ${AppCheckSetup.PROVIDER} provider")
    }

    private companion object { const val TAG = "DocsApplication" }
}
