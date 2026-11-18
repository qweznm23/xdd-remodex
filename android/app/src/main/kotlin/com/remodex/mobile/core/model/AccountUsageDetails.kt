package com.remodex.mobile.core.model

import java.time.Instant

data class AccountCreditBalance(val limitId: String, val label: String, val hasCredits: Boolean, val unlimited: Boolean, val balance: String?)
data class AccountResetCredit(
    val id: String, val title: String?, val description: String?, val status: String,
    val expiresAt: Instant?, val expiryProvided: Boolean,
)
data class AccountResetCredits(val availableCount: Long, val credits: List<AccountResetCredit>?)
data class AccountUsageDetails(
    val lastSuccessfulReadAt: Instant? = null,
    val balances: List<AccountCreditBalance> = emptyList(),
    val resetCredits: AccountResetCredits? = null,
)
