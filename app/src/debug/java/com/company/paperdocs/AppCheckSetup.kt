package com.company.paperdocs

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * App Check provider for debug builds.
 *
 * The debug provider accepts a token printed to logcat, which is what lets a local run or the
 * instrumentation suite pass App Check before the app is registered in the console. Register that
 * token under **Firebase console → App Check → Apps → your app → Add debug token**.
 *
 * This lives in the debug source set rather than behind a BuildConfig branch because the debug
 * provider is a debugImplementation dependency and is not on the release classpath at all.
 */
internal object AppCheckSetup {
    const val PROVIDER = "debug"

    fun install() {
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        // Tokens are short-lived, so refresh them in the background rather than once at startup.
        FirebaseAppCheck.getInstance().setTokenAutoRefreshEnabled(true)
    }
}