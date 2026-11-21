package com.remodex.mobile.ui.agent

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR

/**
 * Git-actions menu affordance: hollow circle with horizontal connectors (timeline / node glyph),
 * matching the in-app reference for repo git controls.
 */
@Composable
fun GitNodeConnectorIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(painterResource(LucideR.drawable.lucide_ic_git_commit_horizontal), null, modifier, tint = tint)
}
