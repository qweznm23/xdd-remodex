package com.remodex.mobile.core.config

import com.remodex.mobile.BuildConfig

object FeatureFlags {
    // This Android build provides chat and files without repository management.
    const val gitIntegrationEnabled: Boolean = false

    val designModeEnabled: Boolean
        get() = BuildConfig.DEBUG

    val betaEngagementEnabled: Boolean
        get() = BuildConfig.BETA_ENABLED && BuildConfig.BETA_API_BASE_URL.isNotBlank()

}
