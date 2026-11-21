package com.remodex.mobile.ui.about

import com.remodex.mobile.ui.theme.conversationColors
import com.remodex.mobile.ui.theme.RemodexGroup
import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.RemodexPageHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateBack)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
        topBar = {
            RemodexPageHeader(stringResource(R.string.whats_new_title), onNavigateBack)
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.whats_new_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RemodexGroup {
                LocalInfoSection(
                    title = stringResource(R.string.whats_new_added_title),
                    body = stringResource(R.string.whats_new_added_body),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LocalInfoSection(
                    title = stringResource(R.string.whats_new_improved_title),
                    body = stringResource(R.string.whats_new_improved_body),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LocalInfoSection(
                    title = stringResource(R.string.whats_new_fixed_title),
                    body = stringResource(R.string.whats_new_fixed_body),
                )
            }
        }
    }
}
