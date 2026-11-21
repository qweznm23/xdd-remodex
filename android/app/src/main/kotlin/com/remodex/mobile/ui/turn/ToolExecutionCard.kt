package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.ExecutionStatus
import com.remodex.mobile.core.model.ToolExecutionUi

@Composable
internal fun ToolExecutionCard(
    ui: ToolExecutionUi,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent =
        when (ui.status) {
            ExecutionStatus.Failed -> colors.error
            ExecutionStatus.Running -> colors.onSecondaryContainer
            ExecutionStatus.Cancelled -> colors.onSurfaceVariant
            ExecutionStatus.Completed -> colors.onPrimaryContainer
        }
    val openDetails = if (ui.isExpandable) onClick else null
    val expandable = openDetails != null
    val rowChevronSize = 22.dp
    val cdBase = stringResource(R.string.turn_tool_execution_card_cd)
    val cd =
        if (expandable) {
            "$cdBase, ${stringResource(R.string.turn_tool_execution_card_cd_open_details)}"
        } else {
            cdBase
        }

    TimelineActionRow(
        onClick = openDetails,
        modifier = modifier.semantics { contentDescription = cd },
        leading = { Icon(statusIcon(ui.status), null, Modifier.size(20.dp), tint = accent) },
        trailing = if (expandable) {
            { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(rowChevronSize), tint = colors.onSurfaceVariant) }
        } else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = ui.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ui.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 18.sp,
                        ),
                    color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusIcon(status: ExecutionStatus): ImageVector =
    when (status) {
        ExecutionStatus.Running -> Icons.Outlined.Schedule
        ExecutionStatus.Completed -> Icons.Outlined.CheckCircle
        ExecutionStatus.Failed -> Icons.Outlined.Error
        ExecutionStatus.Cancelled -> Icons.Outlined.Cancel
    }
