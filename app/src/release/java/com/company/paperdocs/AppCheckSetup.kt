package com.company.paperdocs

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * App Check provider for release builds.
 *
 * Play Integrity only attests an app that Play itself installed and vouches for, so a sideloaded
 * release APK will fail integrity. That is the intended behaviour, not a defect: it is the property
 * that makes App Check worth enabling, since it cannot be satisfied by repackaging the APK.
 */
internal object AppCheckSetup {
    const val PROVIDER = "Play Integrity"

    fun install() {
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        FirebaseAppCheck.getInstance().setTokenAutoRefreshEnabled(true)
    }
}