package com.hebi.quickcut.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDisplayPolicyTest {
    private fun show(
        adsConfigured: Boolean = true,
        consentAllowsAds: Boolean = true,
        sdkInitialized: Boolean = true,
        isHomeVisible: Boolean = true,
        hasVerifiedAdFreeEntitlement: Boolean = false,
    ) = AdDisplayPolicy.showHomeBanner(
        adsConfigured, consentAllowsAds, sdkInitialized, isHomeVisible, hasVerifiedAdFreeEntitlement,
    )

    @Test fun verifiedAdFreeUsersNeverSeeTheHomeBanner() {
        assertFalse(show(hasVerifiedAdFreeEntitlement = true))
        assertTrue(show())
    }

    @Test fun consentMissingOrDisabledAdsNeverRequestABanner() {
        assertFalse(show(consentAllowsAds = false))
        assertFalse(show(adsConfigured = false))
        assertFalse(show(sdkInitialized = false))
    }

    @Test fun bannerIsRestrictedToHomeAndNeverEditorOrExport() {
        assertFalse(show(isHomeVisible = false))
        assertTrue(show(isHomeVisible = true))
    }
}
