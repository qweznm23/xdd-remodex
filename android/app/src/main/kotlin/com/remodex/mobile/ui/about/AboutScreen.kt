package com.remodex.mobile.ui.about

import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.readRemodexAppVersionName
import com.remodex.mobile.ui.theme.RemodexPageHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateBack)
    val context = LocalContext.current
    val versionName = remember { readRemodexAppVersionName(context) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
        topBar = {
            RemodexPageHeader(stringResource(R.string.about_remodex_title), onNavigateBack)
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
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.remodex_icon), null,
                    Modifier.size(48.dp))
                Column {
                    Text(stringResource(R.string.onboarding_app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.settings_about_version, versionName), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.about_remodex_description), style = MaterialTheme.typography.bodyMedium)
            RemodexGroup {
                LocalInfoSection(
                    title = stringResource(R.string.about_remodex_local_first_title),
                    body = stringResource(R.string.about_remodex_local_first_body),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LocalInfoSection(
                    title = stringResource(R.string.about_remodex_pairing_title),
                    body = stringResource(R.string.about_remodex_pairing_body),
                )
            }
        }
    }
}
@Composable
internal fun LocalInfoSection(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
