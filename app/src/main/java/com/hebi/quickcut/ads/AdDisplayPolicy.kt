package com.hebi.quickcut.ads

/** Verified ad-free purchase state must be supplied by Play Billing when implemented. */
internal object AdDisplayPolicy {
    fun showHomeBanner(
        adsConfigured: Boolean,
        consentAllowsAds: Boolean,
        sdkInitialized: Boolean,
        isHomeVisible: Boolean,
        hasVerifiedAdFreeEntitlement: Boolean,
    ): Boolean = adsConfigured && consentAllowsAds && sdkInitialized && isHomeVisible &&
        !hasVerifiedAdFreeEntitlement
}
