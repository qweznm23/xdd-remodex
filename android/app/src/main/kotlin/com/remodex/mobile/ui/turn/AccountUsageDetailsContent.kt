package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.AccountUsageDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatAccountUsageTime(time: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).withZone(zone).format(time)

@Composable
internal fun AccountUsageDetailsContent(details: AccountUsageDetails) {
    val unavailable = stringResource(if (details.lastSuccessfulReadAt == null) R.string.usage_details_not_read else R.string.usage_details_unavailable)
    Text(stringResource(R.string.usage_credit_balance_title), style = MaterialTheme.typography.labelLarge)
    if (details.balances.isEmpty()) {
        Text(unavailable, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else details.balances.forEach { credit ->
        val balance = when {
            credit.unlimited -> stringResource(R.string.usage_credit_unlimited)
            credit.balance != null -> credit.balance
            !credit.hasCredits -> stringResource(R.string.usage_credit_none)
            else -> unavailable
        }
        Text(stringResource(R.string.usage_credit_balance_value, credit.label, balance), style = MaterialTheme.typography.bodyMedium)
    }
    Text(stringResource(R.string.usage_reset_cards_title), style = MaterialTheme.typography.labelLarge)
    val summary = details.resetCredits
    if (summary == null) {
        Text(unavailable, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.usage_reset_cards_count, summary.availableCount), style = MaterialTheme.typography.bodyMedium)
        if (summary.credits == null) {
            Text(stringResource(R.string.usage_reset_cards_no_details), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            summary.credits.forEach { card ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(card.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.usage_reset_card_default),
                            style = MaterialTheme.typography.titleSmall)
                        val status = stringResource(when (card.status) {
                            "available" -> R.string.usage_reset_card_available
                            "redeeming" -> R.string.usage_reset_card_redeeming
                            "redeemed" -> R.string.usage_reset_card_redeemed
                            else -> R.string.usage_reset_card_unknown
                        })
                        Text(status, style = MaterialTheme.typography.labelMedium)
                        card.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val expiry = when {
                            !card.expiryProvided -> stringResource(R.string.usage_details_unavailable)
                            card.expiresAt == null -> stringResource(R.string.usage_reset_card_no_expiry)
                            else -> formatAccountUsageTime(card.expiresAt)
                        }
                        Text(stringResource(R.string.usage_reset_card_expiry, expiry), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (summary.credits.size.toLong() < summary.availableCount) {
                Text(stringResource(R.string.usage_reset_cards_partial, summary.credits.size, summary.availableCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
