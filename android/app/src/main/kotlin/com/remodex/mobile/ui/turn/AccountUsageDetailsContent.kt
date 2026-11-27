package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.remodex.mobile.core.model.AccountResetCredit
import com.remodex.mobile.services.ResetCardReply
import com.remodex.mobile.ui.theme.RemodexAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
internal fun AccountUsageDetailsContent(details: AccountUsageDetails, enabled: Boolean,
    request: suspend (String, String?) -> ResetCardReply, refresh: suspend () -> Unit) {
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
    val summary = details.resetCredits
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.usage_reset_cards_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        summary?.let { Text(stringResource(R.string.usage_reset_cards_count, it.availableCount), style = MaterialTheme.typography.labelLarge) }
    }
    val cards = summary?.credits.orEmpty()
    if (summary == null) Text(unavailable, style = MaterialTheme.typography.bodySmall)
    else if (cards.isEmpty() && summary.availableCount > 0)
        Text(stringResource(R.string.usage_reset_cards_no_details), style = MaterialTheme.typography.bodySmall)
    ResetCardList(cards, enabled, request, refresh)
    if (cards.isNotEmpty() && summary != null && cards.count { it.status == "available" } < summary.availableCount)
        Text(stringResource(R.string.usage_reset_cards_partial, cards.size, summary.availableCount), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ResetCardList(cards: List<AccountResetCredit>, enabled: Boolean,
    request: suspend (String, String?) -> ResetCardReply, refresh: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var cardId by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf<ResetCardReply?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun step(id: String, ticket: String?) {
        if (busy || !enabled) return
        busy = true
        cardId = id
        error = null
        reply = null
        scope.launch {
            try {
                val result = request(id, ticket)
                reply = result
                if (result.status in setOf("reset", "nothingToReset", "noCredit", "alreadyRedeemed")) refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("RemodexResetCard", "stage=" + if (ticket == null) "check" else "confirm", failure)
                error = failure.localizedMessage ?: failure.javaClass.simpleName
            } finally { busy = false }
        }
    }
    for (card in cards.sortedBy { it.status != "available" }) key(card.id) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.usage_reset_card_default),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (card.status == "available" && card.expiresAt?.isAfter(Instant.now()) != false) {
                        TextButton(onClick = { step(card.id, null) }, enabled = enabled && !busy) {
                            Text(stringResource(R.string.reset_use))
                        }
                    } else {
                        Text(stringResource(when (card.status) {
                            "available" -> R.string.reset_expired
                            "redeemed" -> R.string.usage_reset_card_redeemed
                            "redeeming" -> R.string.usage_reset_card_redeeming
                            else -> R.string.usage_reset_card_unknown
                        }), style = MaterialTheme.typography.labelMedium)
                    }
                }
                card.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val expiry = when {
                    !card.expiryProvided -> stringResource(R.string.usage_details_unavailable)
                    card.expiresAt == null -> stringResource(R.string.usage_reset_card_no_expiry)
                    else -> formatAccountUsageTime(card.expiresAt)
                }
                Text(stringResource(R.string.usage_reset_card_expiry, expiry), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(stringResource(R.string.reset_error, it), color = MaterialTheme.colorScheme.error) }
    reply?.let { result ->
        val id = cardId ?: return@let
        val ticket = result.ticket
        if (ticket != null && enabled) {
            val again = result.status == "confirmAgain"
            RemodexAlertDialog(onDismissRequest = { reply = null },
                title = { Text(stringResource(if (again) R.string.reset_second_title else R.string.reset_confirm_title)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.account)
                    if (result.account != result.accountId) Text(result.accountId, style = MaterialTheme.typography.bodySmall)
                    Text(result.title.ifBlank { stringResource(R.string.usage_reset_card_default) })
                    Text(stringResource(R.string.reset_remaining, result.remaining.toString().removeSuffix(".0")))
                    Text(stringResource(if (again) R.string.reset_second_warning else R.string.reset_warning))
                } },
                confirmButton = { TextButton(onClick = { step(id, ticket) }) {
                    Text(stringResource(if (!again && result.remaining > 0) R.string.reset_continue else R.string.reset_confirm_use))
                } },
                dismissButton = { TextButton(onClick = { reply = null }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
        } else if (ticket == null) Text(when (result.status) {
            "blocked" -> stringResource(R.string.reset_blocked, result.remaining.toString().removeSuffix(".0"))
            "reset" -> stringResource(R.string.reset_success)
            "nothingToReset" -> stringResource(R.string.reset_nothing)
            "noCredit" -> stringResource(R.string.reset_none)
            "alreadyRedeemed" -> stringResource(R.string.usage_reset_card_redeemed)
            else -> stringResource(R.string.reset_error, result.status)
        }, style = MaterialTheme.typography.bodyMedium)
    }
}
