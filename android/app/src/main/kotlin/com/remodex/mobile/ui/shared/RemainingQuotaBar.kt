package com.remodex.mobile.ui.shared

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

@Composable
internal fun RemainingQuotaBar(percent: Int?, modifier: Modifier = Modifier) {
    val bar = modifier.fillMaxWidth().height(4.dp)
    if (percent == null) {
        Spacer(bar)
        return
    }
    LinearProgressIndicator(
        progress = { percent.coerceIn(0, 100) / 100f },
        modifier = bar.clip(CircleShape),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
