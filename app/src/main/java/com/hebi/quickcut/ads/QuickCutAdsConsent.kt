package com.hebi.quickcut.ads

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hebi.quickcut.R
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * UMP is refreshed once per Activity launch before any SDK initialization or ad request.
 * Denied/unavailable consent is never interpreted as permission to request ads.
 */
internal class QuickCutAdsConsent(private val activity: Activity) {
    private val consentInfo by lazy { UserMessagingPlatform.getConsentInformation(activity) }
    val adsConfigured: Boolean = activity.resources.getBoolean(R.bool.quickcut_ads_enabled)

    var canRequestAds by mutableStateOf(false)
        private set
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    private var updateStarted = false

    fun requestOnLaunch() {
        if (!adsConfigured || updateStarted) return
        updateStarted = true
        val params = ConsentRequestParameters.Builder().build()
        consentInfo.requestConsentInfoUpdate(
            activity,
            params,
            {
                updatePrivacyOptions()
                canRequestAds = consentInfo.canRequestAds()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    canRequestAds = consentInfo.canRequestAds()
                    updatePrivacyOptions()
                }
            },
            {
                canRequestAds = consentInfo.canRequestAds()
                updatePrivacyOptions()
            },
        )
    }

    fun openPrivacyOptions() {
        if (!privacyOptionsRequired) return
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            canRequestAds = consentInfo.canRequestAds()
            updatePrivacyOptions()
        }
    }

    private fun updatePrivacyOptions() {
        privacyOptionsRequired = consentInfo.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
}
