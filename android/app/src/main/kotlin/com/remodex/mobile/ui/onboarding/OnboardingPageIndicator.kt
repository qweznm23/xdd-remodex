package com.remodex.mobile.ui.onboarding

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun OnboardingPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { (currentPage + 1f) / pageCount },
        modifier = modifier.fillMaxWidth(),
    )
}
